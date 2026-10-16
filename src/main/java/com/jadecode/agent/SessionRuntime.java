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
 * 管理一次 CLI 会话：把收到的任务排好队，一次做完一项，再做下一项。
 *
 * <p>例如，用户要求“构建项目，再检查报告”。构建可以放到后台，模型趁这时读其它文件。
 * 但只要构建结果还没处理，这项任务就没有结束；后来的用户请求或定时任务要继续排队。
 * 本类负责决定什么时候请模型继续、什么时候等后台命令、什么时候才算做完。</p>
 *
 * <p>阅读顺序：{@link #submit} 把任务放进队列 → {@link #consume} 取出任务 →
 * {@link #execute} 准备这项任务需要的数据 → {@link #runTask} 请求模型并执行工具。
 * 所有模型回复和工具结果都由 {@link #apply} 写入 history；具体怎么请求模型、执行工具和
 * 压缩历史，则交给 {@link AgentLoop}。</p>
 *
 * <p>通常只有本类启动的一个线程修改 history。其它线程只往队列放任务，或告诉它
 * “有后台命令跑完了”。旧的 {@link AgentLoop#run(List)} 则直接用调用者线程。
 * 程序退出后队列和当前历史不会恢复；已经写入的文件不会还原，网络请求也不一定立即停止。</p>
 */
public final class SessionRuntime implements AutoCloseable {
    /**
     * 这次会话现在在做什么，不是 s10 任务文件里保存的任务状态。
     */
    public enum State {
        /** 现在没有任务，处理线程等待队列里的下一项请求。 */
        IDLE,
        /** 正在请求模型、执行同步工具、压缩消息或处理长期记忆。 */
        RUNNING,
        /** 当前任务在等后台命令跑完；不做下一项任务，也不反复问模型。 */
        WAITING_BACKGROUND,
        /** 用户已经退出，不再开始新操作，排队任务也不再执行。 */
        CLOSED
    }
    /** 实际请求模型、执行工具和压缩历史的对象；什么时候调用它，由本类决定。 */
    private final AgentLoop engine;
    /** 与父 bash 共用的后台命令管理器；没有后台功能时为 null。 */
    private final BackgroundManager background;
    /** 发给模型的消息列表，只由处理线程修改；压缩时换内容，不换这个列表对象。 */
    private final List<Message> history;
    /** 尚未开始的任务在这里排队；后台命令跑完后，也通过它叫醒等待线程。 */
    private final SessionInbox inbox = new SessionInbox();
    /** 检查定时任务是否到期的对象，REPL 会启动它，单次 run 不启动。 */
    private final CronProducer cron;
    /** 显示进展、等待提示和最终回答的函数，调用后应尽快正常返回。 */
    private final Consumer<String> output;
    /** 显示任务或定时检查错误的函数，不把错误提示当成最终回答。 */
    private final Consumer<String> errors;
    /** volatile 让终端线程看到处理线程更新的状态，但终端仍不能修改 history。 */
    private volatile State state = State.IDLE;
    /** 退出时设为 true，父子 Agent 都可以检查用户是否已经退出。 */
    private volatile boolean closed;
    /** 记录正在处理的任务，退出时用它通知调用者“这项任务没做完”。 */
    private volatile Active active;
    /** start 创建的任务处理线程；旧 runExisting 直接用调用者线程。 */
    private Thread consumer;

    /**
     * 创建一段空对话，记住要使用的执行器和输出函数。
     * 不会请求模型或启动线程；调用 start 才开始处理任务，退出时应调用 close。
     *
     * @param engine 这次会话使用的 AgentLoop，不可同时交给另一会话使用
     * @param output 显示进展和回答的函数，不可为 null
     * @param errors 显示错误的函数，不可为 null
     * @throws NullPointerException engine 为 null
     * @see #start(boolean)
     * @see #close()
     */
    public SessionRuntime(AgentLoop engine, Consumer<String> output, Consumer<String> errors) {
        this(engine, new ArrayList<>(), output, errors);
    }

    /**
     * 直接使用给定的消息列表创建会话，供旧的 run(messages) 调用。
     *
     * <p>不复制列表，所以追加消息或压缩后，调用者也看得见。使用期间，调用者不能从另一线程
     * 修改它。这里还设置“后台命令跑完时叫醒本会话”的函数，但不会取结果、启动线程或执行任务。</p>
     * @param engine 请求模型和执行工具的对象
     * @param history 已有的、可以修改的消息列表
     * @param output 显示正常结果的函数
     * @param errors 显示错误的函数
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
     * 启动一个专门处理任务的线程。没有任务时，它会等队列，不会不断询问模型。
     *
     * <p>REPL 传 true，还会启动定时检查；单次 run 传 false。重复调用不会再开线程，
     * 也不会改变第一次的定时设置。线程虽是 daemon，退出时仍须 close，才能停止所属命令。</p>
     * @param enableCron 是否检查并执行到期定时任务
     * @throws IllegalStateException 已退出的会话不能重新启动
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
     * 把用户的新请求放到队尾，立即返回，不等模型回答。
     *
     * <p>比如 A 还在等构建结果，用户又输入 B，B 只会排队，不会打断 A。
     * 程序会记录任务来自用户，并保存询问用户是否允许某项操作的函数。
     * 模型不能在 prompt 或工具参数里给自己增加这项权限。调用前应先 start。</p>
     * @param prompt 用户要做的事，不得为 null 或全是空白
     * @param approval 询问用户是否允许操作的函数；null 表示不能询问，需询问的操作会被拒绝
     * @return 以后取得最终回答的 CompletableFuture。成功排队不代表做完：
     *         空请求以 IllegalArgumentException 失败，队列满或会话退出以 RejectedExecutionException
     *         失败，执行出错则记录实际错误。取消此 future 不会自动停止已经开始的命令
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
     * 查看现在是在处理任务、等后台结果，还是已退出，不返回消息列表。
     * @return 此刻的状态；返回后，处理线程可能已经做到了下一步
     */
    public State state() { return state; }

    /**
     * 把到期的定时任务放进队列，和用户请求一样按先后顺序执行。
     * @param job 调度器刚选出的到期任务
     * @return true 只表示排队成功；false 时 CronProducer 会让调度器以后再取它。
     *         要等模型第一次正常回复，才记为任务已送达；不能在这里就从待发送记录中划掉它
     */
    private boolean publishCron(CronJob job) {
        ExecutionContext context = ExecutionContext.create(ExecutionContext.Source.SCHEDULED, null, () -> closed);
        return inbox.offer(new SessionInbox.Entry(new SessionEvent.Request(job.prompt(), context, job.id()),
                new CompletableFuture<>()));
    }

    /**
     * 从队首取一项任务，等 execute 处理完，再取下一项。
     *
     * <p>当前任务在等后台构建时，也不会去做下一项任务。普通执行错误由 execute 报告，
     * 不会丢掉后面排队的请求。会话退出时 take 返回 null；线程被中断时记住中断状态并退出。</p>
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
     * 准备并执行一项完整任务，用户请求、定时请求和旧 run(messages) 都调用这里。
     *
     * <p>先检查定时任务是否已被取消或需要稍后重试，再把请求加进 history。
     * 接着记住用户原本要做的事，并查一次长期记忆，最后调用 runTask。
     * 先记请求原文、再加后台通知，免得把最后一条 USER 的通知错当成新请求。
     * 后面执行工具、等后台或 Stop 要求继续，都不重新选择任务或再查一遍记忆。</p>
     *
     * <p>出错后调用 fail，通常保留已经执行的步骤。只有定时任务还没拿到第一次正常模型回复时，
     * 才恢复添加这项任务前的消息，以便稍后重试。恢复消息不会还原已写入的文件。</p>
     * @param entry 从队列取出的请求，以及通知调用者任务结果的 future
     * @param append true 表示加上新请求，来自用户时还调用 UserPromptSubmit hook；
     *               false 表示旧 run(messages) 已有这条请求，不要再加一次
     */
    private void execute(SessionInbox.Entry entry, boolean append) {
        Active task = new Active(entry, history);
        active = task;
        state = State.RUNNING;
        try {
            ensureOpen();
            if (task.cronId() != null) {
                // 排队后可能被取消，也可能暂时不允许重试；先不执行，不能把它记成已经送达。
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
            // 先记住用户请求，再加入后台通知；之后历史即使压缩，仍记着用户原本要做什么。
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
     * 反复请模型决定下一步，执行它要求的工具，直到这项任务真正做完。
     *
     * <p>每轮先把已完成的后台结果放进 history，再请求模型。有工具调用就执行整批工具，
     * 把每个调用的结果一起加进 history，然后继续问模型。</p>
     *
     * <p>没有工具调用也不能马上结束。例如模型说“构建还在跑”，而当前任务必须等构建结果：
     * 程序要等命令完成，不花模型请求反复问进度，也不做队列里的下一项任务。
     * 结果到了再放进 history，请模型继续。Stop hook 若说还有事，也继续当前任务。
     * 这些事都处理完，才整理长期记忆、显示最终回答，并完成任务的 future。</p>
     *
     * <p>等模型回答时，也可能刚好有后台命令跑完，所以想结束时再查一次。
     * 定时任务在第一次正常回复时记为送达，先记录再执行工具；
     * 这不代表业务成功，后续失败不能让整个定时任务从头重跑。</p>
     * @param task 当前任务的数据，等后台期间也保留
     * @throws LlmException 请求模型或压缩摘要失败，已经不能继续
     * @throws InterruptedException 等后台结果时线程被中断
     * @throws RuntimeException 执行出错或用户退出，交给 execute 处理
     */
    private void runTask(Active task) throws LlmException, InterruptedException {
        while (!closed) {
            // 1. 请模型继续前，先加入已完成的后台结果；不必等待的命令结果只供参考。
            deliverResults(task, true);
            SessionEvent.ModelReply reply = engine.request(history, task.goal, task.system, this::apply);
            apply(reply);
            // 模型已正常回答，定时任务就算送到了，不用等工具成功；查记忆或生成摘要不算此次回答。
            if (!task.received) {
                task.received = true;
                if (task.cronId() != null) cron.received(task.cronId());
            }
            // 2. 模型已收到刚才送出的结果通知，可以删掉后台记录；这不等于业务已经成功。
            if (background != null) background.acknowledge(task.receipts);
            task.receipts.clear();

            if (!reply.calls().isEmpty()) {
                // 3. 执行这一批工具，把每个调用的结果一起写入历史；出错也要配齐调用和结果的 ID。
                SessionEvent.ToolBatch batch = engine.executeTools(reply.calls(), task.context(), task.rounds);
                apply(batch);
                task.rounds = batch.roundsSinceTodo();
                if (batch.failure() != null) throw batch.failure();
                // 这一批 tool_use 都已有 tool_result，现在才能压缩，不会把调用与结果拆开。
                engine.compactAfterBatch(batch, history, task.goal, this::apply);
                continue;
            }

            // 等模型回答时也许又有命令跑完；加入结果后要再问一次，不能还没给它看就宣布结束。
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
            // 4. 该等的结果都已处理，Stop 也没要求继续，才保存长期记忆并给出最终回答。
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
     * 取出已经跑完的后台命令，把结果说明加入 history，不等仍在运行的命令。
     *
     * <p>取出的记录先保存在 task.receipts，等模型正常回复后再从后台管理器删除。
     * 这样，定时任务第一次回复前失败时，仍能把记录放回去，下次再交给模型，不必重跑命令。</p>
     * @param task 用它的 ID 找出所属结果，并记住这次取出的记录
     * @param includeDetached true 时也加入“不必等它”的命令结果作参考；
     *                        false 时只取当前任务必须处理的结果，不让无关结果打断等待
     * @return 是否向 history 加了新结果；没开后台功能或还没结果时为 false
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
     * 等当前任务的一条或多条后台命令跑完，不请求模型，也不取下一项任务。
     *
     * <p>先记下 inbox 的计数，再查结果；没查到才试着睡下去。如果命令恰好在“查完”和
     * “睡下去”之间跑完，计数已增加，awaitChange 就立即返回，避免有结果却一直睡着。
     * 被叫醒只说明要再查一次，结果正文仍在 BackgroundManager。
     * 新请求也会叫醒线程，但这里只查后台结果，不执行新请求。</p>
     * @param task 正在等结果的任务；不用等所有命令都结束，取到一批就可以继续
     * @throws InterruptedException 等待线程被中断
     * @throws IllegalStateException 会话已退出，不再继续任务
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
     * 根据事件种类修改 history；父会话的消息添加和替换都在这里完成。
     *
     * <p>Request 是新任务，ToolBatch 是工具结果，Continue 是 Stop 说“还没做完”。
     * 它们发给模型时都可能用 USER role，但不代表它们都是用户新发的请求。
     * 事件只是告诉这里要加什么消息，不要求另开线程或先排队，AgentLoop 可以直接调用它。</p>
     *
     * <p>压缩时只换 history 中的内容，不换列表对象，旧 run(messages) 的调用者才看得见变化。
     * 后台通知尽量加在最后一条 USER 内：若另加 USER，原工具结果不再是末条，
     * 现有压缩器可能漏掉对最新工具结果的保护。这是当前压缩器需要，不是模型协议硬性要求。
     * 合并会新建 content 和 Message，不修改旧消息里的块。</p>
     * @param event 程序生成的请求、模型回复、工具结果等；交给这里后，发送方不要再改其中的内容
     * @throws IllegalStateException 会话已退出，不能再把稍后返回的结果写进去
     */
    private void apply(SessionEvent event) {
        ensureOpen();
        switch (event) {
            case SessionEvent.Request request -> history.add(Message.userText(
                    request.context().source() == ExecutionContext.Source.SCHEDULED
                            ? "[Scheduled] " + request.prompt() : request.prompt()));
            case SessionEvent.HistoryPrepared prepared -> {
                // 事件里已经复制了列表，所以 clear(history) 不会把接下来要装入的消息也清掉。
                history.clear();
                history.addAll(prepared.messages());
            }
            case SessionEvent.ModelReply reply -> history.add(reply.message());
            case SessionEvent.ToolBatch batch -> history.add(Message.userBlocks(batch.results()));
            case SessionEvent.Continue continuation -> history.add(Message.userText(continuation.prompt()));
            case SessionEvent.Notifications notifications -> {
                // 最后一条 USER 可能是用户请求，也可能是工具结果；只加参考文字，不改变 task.goal。
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
     * 任务出错后通知调用者，并停掉它不再需要的后台命令。
     *
     * <p>定时任务在第一次正常模型回复前出错：恢复添加任务前的消息，把取出的后台结果放回去，
     * 并让 CronProducer 稍后再试。其它错误则保留实际发生过的消息和文件修改，不从头重跑。
     * 结果通知若仍在历史里，对应的后台记录就可以删掉，不再占名额。</p>
     *
     * <p>只停止这项任务必须等待的命令。明确设置“不必等待”的命令可以继续，到会话退出才停。
     * 命令返回非零退出码本身通常是交给模型处理的结果，不会直接调用本方法。</p>
     * @param task 出错的任务，包含是否定时任务、是否收到过模型回复等信息
     * @param failure 实际异常，原样保存到调用者的结果 future
     */
    private void fail(Active task, Exception failure) {
        if (task.cronId() != null && !task.received) {
            // 用户退出后就不再改历史；否则通过 apply 放回开始任务前的消息。
            if (!closed) apply(new SessionEvent.HistoryPrepared(task.snapshot));
            if (background != null) background.restore(task.receipts);
            if (!closed) cron.failed(task.cronId());
            else cron.release(task.cronId());
        } else if (background != null) {
            // 通知还留在历史中，可以删除后台记录；不要因此把任务从头执行一遍。
            background.acknowledge(task.receipts);
        }
        if (background != null) background.cancelRequired(task.context().id());
        task.entry.result().completeExceptionally(failure);
        if (!closed) errors.accept("[Task failed] " + task.context().id() + ": " + failure.getMessage());
    }

    /**
     * 继续处理或修改消息前，检查用户是否已退出；不能还原已经开始的文件写入。
     * @throws IllegalStateException 用户已退出会话
     */
    private void ensureOpen() { if (closed) throw new IllegalStateException("Session is closed"); }

    /**
     * 保存当前任务已经做到了哪一步。
     * 请求模型、执行工具和等后台时使用同一个对象，不因等待重新开始任务。
     * 只有处理线程修改字段；退出线程只读 entry，用它通知调用者任务未完成。
     */
    private static final class Active {
        /** 最初取出的请求及结果 future，不随历史压缩变化。 */
        final SessionInbox.Entry entry;
        /** 定时任务开始前的历史副本，第一次模型回复前失败时恢复它；普通任务不保存。 */
        final List<Message> snapshot;
        /** 已加进 history、但还没等到模型正常回复的后台结果。 */
        final List<Completion> receipts = new ArrayList<>();
        /** 用户最初要做的事，不能被工具结果、后台通知或 Stop 提示换掉。 */
        String goal;
        /** 基础 system 提示加上这项任务查到的记忆，后面继续用同一份。 */
        String system;
        /** 自上次 todo 更新或提醒以来的工具批次数，不是工具个数或模型请求数。 */
        int rounds;
        /** 模型是否正常回复过一次；定时任务在这之后不能再按“还没送到”重试。 */
        boolean received;
        /**
         * 记住当前任务，只有定时任务才额外复制开始前的消息列表。
         * 复制的是列表，不是其中每个 Message，后续不能直接改旧消息的内容。
         * @param entry 取出的请求及结果 future
         * @param history 还没加入这项新请求的历史
         */
        Active(SessionInbox.Entry entry, List<Message> history) {
            this.entry = entry;
            // 只有定时任务在第一次回复前失败才会重试；普通任务不用多存一份长历史。
            snapshot = entry.request().cronId() == null ? List.of() : List.copyOf(history);
        }
        /**
         * @return 程序记录的任务 ID、来源、询问用户的方法，以及会话是否退出的检查方法
         */
        ExecutionContext context() { return entry.request().context(); }
        /**
         * @return 定时任务定义的 ID；用户直接提交的任务为 null
         */
        String cronId() { return entry.request().cronId(); }
    }

    /**
     * 让旧 run(messages) 调用同一个 execute，不再单独写一套模型循环。
     *
     * <p>直接在调用者线程处理，不开处理线程或定时检查。messages 已有请求，不重复添加。
     * 处理时会修改原列表。返回时去掉本方法设置的后台通知函数，但不关闭 engine，
     * 调用者之后还能用它继续这段对话。</p>
     * @param engine 本次使用的 AgentLoop，不能同时在另一线程运行
     * @param messages 已含本次请求、可以修改的消息列表
     * @throws LlmException 模型或摘要失败，从 future 中取出原异常重新抛出
     * @throws RuntimeException 消息不合法、执行出错或执行器退出，仍抛原异常而不是 future 包装异常
     */
    static void runExisting(AgentLoop engine, List<Message> messages) throws LlmException {
        SessionRuntime runtime = new SessionRuntime(engine, messages, ignored -> { }, ignored -> { });
        ExecutionContext context = ExecutionContext.create(ExecutionContext.Source.USER, null, () -> runtime.closed);
        SessionInbox.Entry entry = new SessionInbox.Entry(new SessionEvent.Request("", context, null), new CompletableFuture<>());
        try {
            runtime.execute(entry, false);
            entry.result().join();
        } catch (CompletionException wrapped) {
            // join 把实际错误包在 CompletionException 中；取出原异常，让调用者仍按原类型处理。
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
     * 用户退出 CLI 时停止整个会话，不等当前任务自然做完，也不执行剩余排队任务。
     *
     * <p>先记下“已退出”，停止定时检查和所有 shell 命令，再通知排队任务和当前任务失败，
     * 最后中断处理线程，最多等它两秒。先停止执行，再完成 future，
     * 是因为完成 future 可能立即运行调用者自己的代码，不能让这些代码拖延停止工具。</p>
     *
     * <p>重复调用没关系。不保存队列，不还原已经写入的文件。
     * 网络客户端可能不理会线程中断，稍后才返回；父子 Agent 会在继续请求模型或执行工具前
     * 检查是否退出，但不能保证把正在进行的网络请求立刻停止。</p>
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            state = State.CLOSED;
        }
        cron.close();
        // 先停止执行器和命令，再通知 future 的调用者；对方可能立即运行自己的代码，不能拖延停止工具。
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
