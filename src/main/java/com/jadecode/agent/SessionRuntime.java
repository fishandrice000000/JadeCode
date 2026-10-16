package com.jadecode.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import com.jadecode.cron.CronJob;
import com.jadecode.llm.LlmException;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.safety.Hooks;
import com.jadecode.safety.PermissionGate;
import com.jadecode.tools.BackgroundManager;
import com.jadecode.tools.BackgroundManager.Completion;

/**
 * 一个 CLI 会话的运行器，也是会话历史的唯一写入者。
 *
 * <p><b>为什么需要这一层：</b>用户输入、定时任务和后台命令都可能在不同时间产生消息。
 * 如果这些组件直接调用模型或修改历史，就很难判断“当前究竟在做谁的任务”。本类把新目标
 * 放入 FIFO 收件箱，一次只推进一个目标；工具结果、Stop 续跑和该目标必等的后台结果则继续
 * 当前任务，而不是重新排队。等待后台结果时不轮询模型，也不领取下一个目标。</p>
 *
 * <p><b>阅读主线：</b>{@link #submit} 提交 → {@link #consume} 领取 → {@link #execute}
 * 建立任务上下文 → {@link #runTask} 推进模型/工具/等待 → 完成结果 future。
 * 所有历史变更集中在 {@link #apply}；模型、工具、压缩和记忆的具体工作委托给
 * {@link AgentLoop}，定时投递状态委托给 {@link CronProducer}。</p>
 *
 * <p>生产者只入队或发唤醒信号，不接触历史。正常模式由唯一消费者线程执行；旧
 * {@link AgentLoop#run(List)} 入口则在调用线程复用同一过程。会话和收件箱不做跨进程恢复。
 * 关闭会终止所属命令，但不能撤销此前的文件修改，也不保证立即取消在途 HTTP。</p>
 */
public final class SessionRuntime implements AutoCloseable {
    /**
     * 会话的可观察阶段，不是 s10 持久任务条目的状态。
     * IDLE 表示可领取新目标；RUNNING 表示正在推进当前目标；WAITING_BACKGROUND
     * 表示当前目标仍占据执行位置；CLOSED 表示整个会话已退出，不再接受正常执行。
     */
    public enum State {
        /** 没有当前任务，消费者可阻塞领取下一项请求。 */
        IDLE,
        /** 正推进当前目标，包括模型请求、同步工具、压缩或记忆维护。 */
        RUNNING,
        /** 当前任务尚未结束，只等必等后台结果，不领取队首新请求或轮询模型。 */
        WAITING_BACKGROUND,
        /** 整个会话已关闭，不再启动新执行步骤，也不排空队列。 */
        CLOSED
    }
    /** 单步执行能力；不拥有本类的历史或请求队列。 */
    private final AgentLoop engine;
    /** 与父 bash 共用的命令管理器；旧装配可为 null。 */
    private final BackgroundManager background;
    /** 消费者独占写入的可变列表；压缩也保留此列表对象身份，只替换其内容。 */
    private final List<Message> history;
    /** 仅排队新目标，同时提供后台结果到达时的变化信号。 */
    private final SessionInbox inbox = new SessionInbox();
    /** 到期任务的生产适配器；是否启动由 CLI 模式决定。 */
    private final CronProducer cron;
    /** 进度、等待和最终答复输出；回调不应抛异常或长时间阻塞。 */
    private final Consumer<String> output;
    /** 任务失败及调度诊断输出，与业务答复分开。 */
    private final Consumer<String> errors;
    /** volatile 让 UI 可以观察阶段；它并不赋予 UI 修改历史的权限。 */
    private volatile State state = State.IDLE;
    /** 同时供提交者、消费者和子代理的只读关闭视图观察。 */
    private volatile boolean closed;
    /** 向关闭线程发布当前任务，以便退出时让等待结果的调用者及时收到失败。 */
    private volatile Active active;
    /** start 创建的唯一消费者；兼容 runExisting 模式不创建线程。 */
    private Thread consumer;

    /**
     * 创建空历史会话，绑定依赖和后台唤醒入口，但不启动线程、计时器或模型请求。
     *
     * @param engine 专属于此会话的执行器，不能同时交给另一运行器使用
     * @param output 接收进度和最终答复的非 null 回调
     * @param errors 接收诊断信息的非 null 回调
     * @throws NullPointerException engine 为 null
     * @see #start(boolean)
     * @see #close()
     */
    public SessionRuntime(AgentLoop engine, Consumer<String> output, Consumer<String> errors) {
        this(engine, new ArrayList<>(), output, errors);
    }

    /**
     * 绑定会话历史；兼容入口通过此构造器借用调用方的列表，而不是复制一份断开关联的历史。
     * 构造会替换后台管理器的完成监听器，但不消费已有结果，也不执行任务。
     *
     * @param engine 本会话的执行能力
     * @param history 可变历史，绑定期间调用方不得并发修改
     * @param output 正常输出通道
     * @param errors 诊断输出通道
     */
    private SessionRuntime(AgentLoop engine, List<Message> history, Consumer<String> output, Consumer<String> errors) {
        this.engine = java.util.Objects.requireNonNull(engine);
        this.background = engine.background();
        this.history = history;
        this.output = output;
        this.errors = errors;
        this.cron = new CronProducer(engine.cronScheduler(), this::publishCron, errors);
        if (background != null) background.onCompletion(inbox::wake);
    }

    /**
     * 启动唯一消费者；REPL 同时启动定时生产，单次 run 只消费显式提交的任务。
     *
     * <p>重复调用不再创建线程，也不改变首次选择的定时模式。消费者空闲时等待收件箱，
     * 不会因为单纯启动会话就调用模型。线程是 daemon，但调用方仍须显式 close 清理命令。</p>
     * @param enableCron true 表示允许生产到期任务；单次 run 传 false
     * @throws IllegalStateException 会话已关闭，不能重新启动
     */
    public synchronized void start(boolean enableCron) {
        if (closed) throw new IllegalStateException("Session is closed");
        if (consumer != null) return;
        consumer = new Thread(this::consume, "jade-session-consumer");
        consumer.setDaemon(true);
        consumer.start();
        if (enableCron) cron.start();
    }

    /**
     * 将一项人工目标放入收件箱，不等待模型、审批或后台命令完成。
     *
     * <p>本方法在入队前生成可信执行身份；模型不能通过 prompt 或工具 JSON 将定时任务
     * 伪装成人工任务。当前任务忙碌或等待后台时，新目标只排队，不抢占。调用方应先 start。
     * future 是观察结果的通道，取消该 future 不等于终止已经启动的工具。</p>
     * @param prompt 非空白的用户目标；原文将在领取时加入历史
     * @param approval 人工审批通道，可为 null；没有通道的需询问操作会被安全门拒绝
     * @return 真正结束后提供最终答复的 future；空输入以 IllegalArgumentException 完成失败，
     *         收件箱已关闭或已满以 RejectedExecutionException 完成失败，执行失败则保留其原因。
     *         排队成功、首个模型回复和命令启动都不代表该 future 已成功
     */
    public CompletableFuture<String> submit(String prompt, PermissionGate.Approval approval) {
        CompletableFuture<String> result = new CompletableFuture<>();
        if (prompt == null || prompt.isBlank()) {
            result.completeExceptionally(new IllegalArgumentException("Prompt must not be blank"));
            return result;
        }
        ExecutionContext context = ExecutionContext.create(ExecutionContext.Source.USER, approval, () -> closed);
        SessionEvent.Request request = new SessionEvent.Request(prompt, context, null);
        if (!inbox.offer(new SessionInbox.Entry(request, result))) {
            result.completeExceptionally(new RejectedExecutionException("Session inbox is full or closed"));
        }
        return result;
    }

    /**
     * 观察当前会话阶段，不返回可变历史，也不等待状态变化。
     * @return 读取瞬间的状态；返回后消费者可能已进入下一阶段
     */
    public State state() { return state; }

    /**
     * 将到期定义包装为可信的 SCHEDULED 请求，与人工请求按相同 FIFO 排队。
     *
     * @param job 调度器已领取临时投递资格的任务定义
     * @return 是否入队；false 时 CronProducer 归还 lease（临时投递资格），不确认交付。
     *         true 也只是入队，真正确认要等首个合法正式模型回复
     */
    private boolean publishCron(CronJob job) {
        ExecutionContext context = ExecutionContext.create(ExecutionContext.Source.SCHEDULED, null, () -> closed);
        return inbox.offer(new SessionInbox.Entry(new SessionEvent.Request(job.prompt(), context, job.id()),
                new CompletableFuture<>()));
    }

    /**
     * 消费者线程的最外层循环：空闲时阻塞领取队首，完成整项任务后才领取下一项。
     * execute 内部已处理普通任务失败，因此一项失败不会自动清空其后的请求。
     * 关闭使 take 返回 null；中断则恢复中断标记并退出。此处不重试任何业务副作用。
     */
    private void consume() {
        try {
            while (!closed) {
                SessionInbox.Entry entry = inbox.take();
                if (entry == null) return;
                execute(entry, true);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 建立并收尾一项任务，人工、定时和兼容入口共用此生命周期。
     *
     * <p>先复核定时项是否仍可交付，再采用入口消息，随后固定本次目标并做一次记忆召回。
     * 捕获目标必须早于后台通知注入，否则末条 USER 的通知可能被误当成用户真正要做的事。
     * 工具轮、Stop 和后台恢复都沿用这个目标与召回背景，不重新经过入口。</p>
     *
     * <p>失败通过 fail 完成结果通道；本方法不向消费循环抛出通常的模型/执行错误。
     * 只有定时项在首个正式回复前失败时恢复入口前历史，以配合投递重试；这不是 IO 事务回滚。</p>
     * @param entry 已领取的目标及其结果通道
     * @param append true 表示追加新请求并触发人工入口 hook；false 仅用于已有末条请求的
     *               run(messages)，避免重复追加和重复触发 hook
     */
    private void execute(SessionInbox.Entry entry, boolean append) {
        Active task = new Active(entry, history);
        active = task;
        state = State.RUNNING;
        try {
            ensureOpen();
            if (task.cronId() != null) {
                // 生产后、执行前可能发生取消或退避；未尝试的项归还而不是假确认。
                if (!cron.canDeliver() || !cron.pending(task.cronId())) {
                    cron.release(task.cronId());
                    entry.result().completeExceptionally(new IllegalStateException("Cron delivery deferred or cancelled"));
                    return;
                }
                output.accept("[Cron] Delivering " + task.cronId());
            }
            if (append) {
                if (task.context().source() == ExecutionContext.Source.USER) {
                    engine.hooks().trigger(Hooks.USER_PROMPT_SUBMIT,
                            Map.of(Hooks.PARAM_QUERY, entry.request().prompt(), Hooks.PARAM_CONTEXT, task.context()));
                }
                apply(entry.request());
            }
            // 捕获先于任何通知注入，之后即使换了历史，也始终是这一个目标。
            task.goal = engine.activeRequest(history);
            task.system = engine.taskSystem(task.goal);
            runTask(task);
        } catch (LlmException | IOException | RuntimeException failure) {
            fail(task, failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(task, new IllegalStateException("Session execution interrupted", interrupted));
        } finally {
            active = null;
            if (!closed) state = State.IDLE;
        }
    }

    /**
     * 推进当前目标的核心循环；阅读事件化 Agent 时应优先阅读本方法。
     *
     * <p>每轮依次为：收集可带入的后台结果 → 请求模型并采用回复 → 确认模型已收到的通知
     * → 执行工具批次（如有）→ 处理结束意图。没有 tool_use 只是模型想结束，不是任务已结束：
     * 若还有必等命令，应等待其结果并继续请求；若 Stop 追加要求，也继续当前任务。
     * 只有这些条件全部满足，才运行结束记忆维护、输出 Completed 并完成 future。</p>
     *
     * <p>在途模型请求期间也可能到达结果，因此无工具回复后还要再收一次必等结果。
     * 一旦采用了新结果，必须让模型看过再判断结束。首个合法正式回复在工具执行前确认
     * cron 投递；这只表示收到任务，不表示业务成功，后续失败不会重投整项业务。</p>
     * @param task 当前任务独占的运行状态，跨等待保留；不修改其中固定的入口目标
     * @throws LlmException 正式请求有限补救后仍失败，或压缩辅助调用失败
     * @throws InterruptedException 等待后台变化时被中断
     * @throws RuntimeException 执行链意外失败或会话已关闭；交由 execute 统一收尾
     */
    private void runTask(Active task) throws LlmException, InterruptedException {
        while (!closed) {
            // 1. 正常请求边界可带入当前必等结果及已完成的脱离任务通知；后者只是参考上下文。
            deliverResults(task, true);
            SessionEvent.ModelReply reply = engine.request(history, task.goal, task.system, this::apply);
            apply(reply);
            // 确认发生在工具执行之前。辅助召回/摘要的回复不会经过这里。
            if (!task.received) {
                task.received = true;
                if (task.cronId() != null) cron.received(task.cronId());
            }
            // 2. 有效回复证明上一轮通知已交给模型；此时才释放后台记录容量，不代表命令业务成功。
            if (background != null) background.acknowledge(task.receipts);
            task.receipts.clear();

            if (!reply.calls().isEmpty()) {
                // 3. 同步闭合整个工具批次。异常批次也先采用完整结果，防止遗留孤立 tool_use。
                SessionEvent.ToolBatch batch = engine.executeTools(reply.calls(), task.context(), task.rounds);
                apply(batch);
                task.rounds = batch.roundsSinceTodo();
                if (batch.failure() != null) throw batch.failure();
                // 到这里整批交换已经闭合；压缩只能在此之后采用。
                engine.compactAfterBatch(batch, history, task.goal, this::apply);
                continue;
            }

            // 请求在途期间可能有结果到达；采用后必须再请求一次，不能没让模型看就宣布完成。
            if (deliverResults(task, false)) continue;
            if (background != null && background.hasRequired(task.context().id())) {
                if (!reply.message().extractText().isBlank()) output.accept("[Progress] " + reply.message().extractText());
                output.accept("[Waiting] " + task.context().id() + " is waiting for required background results");
                state = State.WAITING_BACKGROUND;
                awaitResults(task);
                state = State.RUNNING;
                continue;
            }
            SessionEvent.Continue continuation = engine.stop(history);
            if (continuation != null) {
                apply(continuation);
                continue;
            }
            // 4. 无待处理依赖、Stop 不再续跑后才做任务结束维护；中间进度不能提前走到这里。
            engine.finish(history);
            ensureOpen();
            String answer = reply.message().extractText();
            output.accept("[Completed] " + answer);
            task.entry.result().complete(answer);
            return;
        }
        throw new IllegalStateException("Session is closed");
    }

    /**
     * 非阻塞地领取当前可采用的后台结果，并把通知作为历史事件统一回灌。
     *
     * <p>领取仅从 ready 队列移除记录，不立即释放后台容量。task.receipts 保存领取凭据，
     * 等合法正式回复后确认；定时首回复前失败时可以凭原记录归还，而不是重跑命令。</p>
     * @param task 决定必等结果所属的执行身份，并保存尚未确认的领取凭据
     * @param includeDetached true 用于正常请求边界，同时带入脱离任务通知；false 用于
     *                        等待/结束检查，不能让无关旧结果唤醒一次新的模型请求
     * @return 是否确实向历史采用了新通知；false 包括没有后台管理器或暂时没有可用结果
     */
    private boolean deliverResults(Active task, boolean includeDetached) {
        if (background == null) return false;
        List<Completion> results = background.collect(task.context().id(), includeDetached);
        if (results.isEmpty()) return false;
        task.receipts.addAll(results);
        apply(new SessionEvent.Notifications(results.stream().map(Completion::notification).toList()));
        return true;
    }

    /**
     * 等到当前任务至少一条必等结果可采用；不调用模型、不领取其它目标，也不等待所有命令。
     *
     * <p>必须先记变化版本，再扫描结果：若完成事件恰在扫描后、wait 前到达，版本已经变化，
     * awaitChange 就不会睡下去。新请求也可能发信号，但只会促使重新检查，不会被此处执行。
     * 信号本身不携带结果，真实结果仍由 BackgroundManager 保管。</p>
     * @param task 保持执行位置的当前任务
     * @throws InterruptedException 消费者等待被中断
     * @throws IllegalStateException 会话已关闭，不再恢复任务
     */
    private void awaitResults(Active task) throws InterruptedException {
        while (!closed) {
            long observed = inbox.revision();
            if (deliverResults(task, false)) return;
            inbox.awaitChange(observed);
        }
        throw new IllegalStateException("Session is closed");
    }

    /**
     * 将一种可信的执行事件翻译为模型协议消息，是会话历史的唯一写入入口。
     *
     * <p>事件表达含义，Message 表达发给模型的格式：新目标、工具结果和 Stop 输入都可能
     * 使用 USER role，但不能因此把它们当成同一种任务。事件不必异步入队；执行器在消费者
     * 线程同步回调此方法，也能保持单一写入口。</p>
     *
     * <p>压缩候选替换列表内容而不更换列表对象，使兼容调用方仍看到最新历史。后台通知
     * 合并末条 USER 是为了适配现有按位置识别最新工具交换的压缩保护，不是协议禁止独立通知。
     * 合并重建 content 和 Message，不原地改写已有消息块。</p>
     * @param event 内部组件提供的事件；其中列表已经固定外层结构，不得由生产者事后修改内容
     * @throws IllegalStateException 会话已关闭，晚到事件不得继续采用
     */
    private void apply(SessionEvent event) {
        ensureOpen();
        switch (event) {
            case SessionEvent.Request request -> history.add(Message.userText(
                    request.context().source() == ExecutionContext.Source.SCHEDULED
                            ? "[Scheduled] " + request.prompt() : request.prompt()));
            case SessionEvent.HistoryPrepared prepared -> {
                // HistoryPrepared 已复制外层：即便原候选关联 history，clear 也不会把候选一起清空。
                history.clear();
                history.addAll(prepared.messages());
            }
            case SessionEvent.ModelReply reply -> history.add(reply.message());
            case SessionEvent.ToolBatch batch -> history.add(Message.userBlocks(batch.results()));
            case SessionEvent.Continue continuation -> history.add(Message.userText(continuation.prompt()));
            case SessionEvent.Notifications notifications -> {
                // 末条可能是用户 prompt，也可能是工具结果 USER；只加参考文本，不改变原目标。
                List<ContentBlock> content = new ArrayList<>();
                boolean merge = !history.isEmpty() && history.getLast().role() == Role.USER;
                if (merge) content.addAll(history.getLast().content());
                notifications.texts().forEach(text -> content.add(new TextBlock(text)));
                Message message = Message.userBlocks(List.copyOf(content));
                if (merge) history.set(history.size() - 1, message);
                else history.add(message);
            }
        }
    }

    /**
     * 按“是否已经确认收到任务”处理失败，而不是把所有失败都当成可安全重试。
     *
     * <p>定时首回复前失败：恢复入口前历史、归还已领取通知，并让投递器退避。其余失败：
     * 保留真实历史和 IO 进度，不重投业务；已留在历史中的通知释放后台记录。两条路径都会
     * 清理此任务的必等命令、令结果 future 失败；脱离命令仍由会话拥有，直到会话关闭。</p>
     * @param task 失败任务，包含来源、首回复标记、历史快照及通知领取凭据
     * @param failure 保留到结果 future 的原始异常；不是成功工具结果或后台非零退出码本身
     */
    private void fail(Active task, Exception failure) {
        if (task.cronId() != null && !task.received) {
            // 关闭后不再采用任何新历史；未关闭时恢复也走统一的历史事件入口。
            if (!closed) apply(new SessionEvent.HistoryPrepared(task.snapshot));
            if (background != null) background.restore(task.receipts);
            if (!closed) cron.failed(task.cronId());
            else cron.release(task.cronId());
        } else if (background != null) {
            // 这些通知已保留在失败历史中，不再占据后台容量；当前任务不自动重投。
            background.acknowledge(task.receipts);
        }
        if (background != null) background.cancelRequired(task.context().id());
        task.entry.result().completeExceptionally(failure);
        if (!closed) errors.accept("[Task failed] " + task.context().id() + ": " + failure.getMessage());
    }

    /**
     * 在执行/采用边界复核关闭信号；不能撤销已开始的 IO，但能拒绝后续晚到事件。
     * @throws IllegalStateException 会话已关闭
     */
    private void ensureOpen() { if (closed) throw new IllegalStateException("Session is closed"); }

    /**
     * 一次任务跨模型轮、工具轮及后台等待保留的本地状态。
     * 只由消费者修改，不能通过工具 JSON 设置。close 只读取 entry 来完成结果通道，
     * 不接管历史或驱动另一套执行循环。
     */
    private static final class Active {
        /** 原始入口和任务完成通道；不随上下文压缩变化。 */
        final SessionInbox.Entry entry;
        /** 仅定时任务保存入口前的外层历史快照，供首回复前投递失败恢复。 */
        final List<Message> snapshot;
        /** 已注入历史但尚未得到合法正式回复确认的后台完成记录。 */
        final List<Completion> receipts = new ArrayList<>();
        /** 入口捕获的用户目标，通知、Stop 和摘要都不能替换它。 */
        String goal;
        /** 本任务入口召回后的 system，跨续跑固定使用。 */
        String system;
        /** 未更新 todo 的工具批次数，不是模型请求数或工具调用数。 */
        int rounds;
        /** 是否已有首个合法正式回复；决定 cron 失败还能否按未交付处理。 */
        boolean received;
        /**
         * 创建尚未执行的任务状态；只复制定时历史的外层，不深拷贝消息。
         * @param entry 已领取入口
         * @param history 采用入口消息之前的历史，随后只通过运行器重建/追加
         */
        Active(SessionInbox.Entry entry, List<Message> history) {
            this.entry = entry;
            // 只有定时首回复前的交付重试需要恢复，普通任务不额外复制一份长历史。
            snapshot = entry.request().cronId() == null ? List.of() : List.copyOf(history);
        }
        /** @return 排队前已经固定、子代理沿用的可信执行身份 */
        ExecutionContext context() { return entry.request().context(); }
        /** @return 定时定义 ID；普通人工任务为 null */
        String cronId() { return entry.request().cronId(); }
    }

    /**
     * 为旧 run(messages) API 提供同步适配，复用 execute，而不是保留第二套推理循环。
     *
     * <p>直接在调用线程推进，不启动消费者或 cron，不追加空的占位请求。调用方原列表会被
     * 更新；退出适配器只解绑其唤醒监听器，不关闭 engine，因此后续可以继续使用同一执行器。
     * 执行错误解包后保留原 LlmException/RuntimeException，不让 future 包装掩盖真实原因。</p>
     * @param engine 独占使用的旧入口执行器
     * @param messages 已含当前请求的可变历史，不得并发修改
     * @throws LlmException 模型或摘要调用失败
     * @throws RuntimeException 历史非法、工具执行链意外失败或执行器已关闭
     */
    static void runExisting(AgentLoop engine, List<Message> messages) throws LlmException {
        SessionRuntime runtime = new SessionRuntime(engine, messages, ignored -> { }, ignored -> { });
        ExecutionContext context = ExecutionContext.create(ExecutionContext.Source.USER, null, () -> runtime.closed);
        SessionInbox.Entry entry = new SessionInbox.Entry(new SessionEvent.Request("", context, null), new CompletableFuture<>());
        try {
            runtime.execute(entry, false);
            entry.result().join();
        } catch (CompletionException wrapped) {
            // 保留旧 API 的异常对象与类型，不能用 CompletionException 掩盖真实失败。
            if (wrapped.getCause() instanceof LlmException failure) throw failure;
            if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException(wrapped.getCause());
        } finally {
            runtime.closed = true;
            runtime.inbox.close();
            if (runtime.background != null) runtime.background.onCompletion(() -> { });
        }
    }

    /**
     * 关闭整个会话，而不是“等待当前任务完成后退出”。可重复调用。
     *
     * <p>先发布 CLOSED，再停 cron、关闭执行器及所属 shell，随后拒绝排队请求与当前结果
     * future，最后中断消费者并最多等待两秒。先关闭执行能力再完成 future，是为了避免
     * 调用者的同步完成回调拖延禁止新工具的时点。</p>
     *
     * <p>不排空请求、不保存进程内收件箱、不撤销文件副作用。HTTP 若不响应中断可能稍后
     * 才返回；执行器和子代理的关闭检查会拒绝后续工具步骤，而非保证底层网络瞬间停止。</p>
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            state = State.CLOSED;
        }
        cron.close();
        // 先禁用执行器并清理命令，再完成队列 future；用户回调不能延迟禁止新工具的时点。
        engine.close();
        inbox.close();
        Active running = active;
        if (running != null) running.entry.result().completeExceptionally(new IllegalStateException("Session closed"));
        if (consumer != null && consumer != Thread.currentThread()) {
            consumer.interrupt();
            try {
                consumer.join(2000);
                if (consumer.isAlive()) errors.accept("[Session] Model/IO still finishing; new tools are disabled.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
