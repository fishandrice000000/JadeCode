package com.jadecode.session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import com.jadecode.agent.AgentExecutor.Continue;
import com.jadecode.agent.AgentExecutor.ModelReply;
import com.jadecode.agent.AgentExecutor.ToolBatch;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.context.ContextCompactor;
import com.jadecode.cron.CronJob;
import com.jadecode.cron.CronProducer;
import com.jadecode.cron.CronScheduler;
import com.jadecode.execution.Approval;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.LlmException;
import com.jadecode.memory.MemoryManager;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolExchangeProtocol;
import com.jadecode.process.BackgroundManager.Completion;
import com.jadecode.process.BackgroundManager;

/**
 * 管理一次 CLI 会话：把收到的任务排好队，一次做完一项，再做下一项。
 *
 * <p>例如，用户要求“构建项目，再检查报告”。构建可以放到后台，模型趁这时读其它文件。
 * 但只要构建结果还没处理，这项任务就没有结束；后来的用户请求或定时任务要继续排队。
 * 本类负责决定什么时候请模型继续、什么时候等后台命令、什么时候才算做完。</p>
 *
 * <p>阅读顺序：{@link #submit} 把任务放进队列 → {@link #consume} 取出任务 →
 * {@link #execute} 准备这项任务需要的数据 → {@link #runTask} 请求模型并执行工具。
 * 所有模型回复和工具结果都由 apply 的各个重载写入 history；具体怎么请求模型、执行工具和
 * 压缩历史，则交给 {@link AgentExecutor}。</p>
 *
 * <p>通常只有本类启动的一个线程修改 history。其它线程只往队列放任务，或告诉它
 * “有后台命令跑完了”。旧的 {@link #run(List)} 则直接用调用者线程。
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
    private final AgentExecutor engine;
    /** 长期记忆的算法留在 memory；本类决定任务入口与完成后的调用时机。 */
    private final MemoryManager memory;
    /** 与父 bash 共用的后台命令管理器；没有后台功能时为 null。 */
    private final BackgroundManager background;
    /** 发给模型的消息列表；压缩只换内容。同步 run 临时借用调用者列表，结束后恢复本会话列表。 */
    private List<Message> history;
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
     * 创建不带记忆、后台和定时服务的会话，不启动线程或模型请求。
     * @param engine 本会话独占使用的执行器，关闭会话时一并标记关闭
     * @param output 正常进展与结果输出，应尽快返回
     * @param errors 错误输出，应尽快返回
     */
    public SessionRuntime(AgentExecutor engine, Consumer<String> output, Consumer<String> errors) {
        this(engine, null, null, null, output, errors);
    }

    /**
     * 接收工厂装配的执行器和服务，取得它们的会话级关闭责任。
     * 记忆在任务入口召回、真正完成后维护；cron 仅在 start(true) 后轮询。
     * @param engine 本会话独占使用的执行器，不可同时交给其它会话
     * @param memory 记忆服务，可为 null；不在构造时请求辅助模型
     * @param background 与父 bash 共用的进程服务，可为 null；close 停全部命令
     * @param scheduler 与定时工具共用的调度器，可为 null；本类包装并拥有 CronProducer
     * @param output 正常结果输出
     * @param errors 错误输出
     */
    public SessionRuntime(AgentExecutor engine, MemoryManager memory, BackgroundManager background,
            CronScheduler scheduler, Consumer<String> output, Consumer<String> errors) {
        this.engine = java.util.Objects.requireNonNull(engine);
        this.memory = memory;
        this.background = background;
        this.history = new ArrayList<>();
        this.output = output;
        this.errors = errors;
        this.cron = new CronProducer(scheduler, this::publishCron, errors);
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
    public CompletableFuture<String> submit(String prompt, Approval approval) {
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
            task.goal = activeRequest(history);
            task.system = taskSystem(task.goal);
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
     * 开始处理任务时，检查消息中的工具调用和结果是否配对，然后记住用户请求。
     *
     * <p>用户请求只取这一次。以后 history 末条可能变成工具结果、后台通知或 Stop 提示，
     * 不能再把那些文字当成用户想做的新任务。压缩和长期记忆都没启用时，不需要请求原文，
     * 返回 null，但仍检查工具调用配对。</p>
     * @param messages 已含本次请求的历史；只读取，不修改，也不调用模型或读写文件
     * @return 本次请求原文；压缩和记忆都未启用时为 null
     * @throws IllegalArgumentException 调用和结果不配对，或需要请求原文却找不到合法请求
     * @throws IllegalStateException 会话或执行器已退出
     */
    private String activeRequest(List<Message> messages) {
        ensureOpen();
        // 先确认执行器仍可用，再检查协议；入口目标只在压缩或记忆需要时提取。
        boolean needsRequest = engine.usesCompaction() || memory != null;
        ToolExchangeProtocol.validateHistory(messages);
        return needsRequest ? ContextCompactor.extractActiveRequest(messages) : null;
    }

    /**
     * 在开始一项任务时查一次长期记忆，把查到的内容放进基础 system 提示词。
     *
     * <p>没有记忆功能就直接返回原 system。后面即使运行多批工具、等后台或 Stop 要求继续，
     * 也复用这次返回的字符串，不每次都查记忆。查记忆可能读文件和请求辅助模型，
     * 失败时怎么处理由 MemoryManager 负责；不把辅助模型的回答加入主对话。</p>
     * @param activeRequest 用户最初的请求，启用记忆时应已由 activeRequest 方法检查
     * @return 这项任务接下来一直使用的 system 提示词，不修改消息列表
     * @throws IllegalStateException 会话或执行器已退出
     */
    private String taskSystem(String activeRequest) {
        ensureOpen();
        String base = engine.system();
        return memory == null ? base : memory.prepareSystem(base, activeRequest);
    }

    /**
     * 在该等的后台结果都已处理、Stop 也允许结束后，保存并整理长期记忆。
     *
     * <p>没有记忆功能就直接返回；有的话可能读写文件或调用辅助模型。
     * 具体失败处理由 MemoryManager 负责，不能把辅助回复当成主任务回答。
     * 工具运行到一半或仍在等后台时不能调用，否则可能把没完成的结论当成长期知识。</p>
     * @param messages 当前已完成任务的历史，只读，不由本方法追加消息
     * @throws IllegalStateException 会话或执行器已退出
     */
    private void finish(List<Message> messages) {
        ensureOpen();
        if (memory != null) memory.finishTask(messages);
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
            ModelReply reply = engine.request(history, task.goal, task.system, this::adoptHistory);
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
                ToolBatch batch = engine.executeTools(reply.calls(), task.context(), task.rounds);
                apply(batch);
                task.rounds = batch.roundsSinceTodo();
                if (batch.failure() != null) throw batch.failure();
                // 这一批 tool_use 都已有 tool_result，现在才能压缩，不会把调用与结果拆开。
                engine.compactAfterBatch(batch, history, task.goal, this::adoptHistory);
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
            Continue continuation = engine.stop(history);
            if (continuation != null) {
                apply(continuation);
                continue;
            }
            // 4. 该等的结果都已处理，Stop 也没要求继续，才保存长期记忆并给出最终回答。
            finish(history);
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
        apply(new SessionEvent.Notifications(results.stream().map(SessionRuntime::notification).toList()));
        return true;
    }

    /** 通知最多保留 500 个 Java 字符，不是 token 或字节。 */
    private static final int SUMMARY_CHARS = 500;

    /**
     * 把结果写成准备发给模型的说明文字，不执行命令、不写文件，也不删除记录。
     *
     * <p>输出只留前 500 个 Java 字符，并注明原任务、命令、状态和退出码。
     * 原 bash 调用已经用“已启动”回答过，不能拿同一 tool_use_id 再回一个 tool_result，
     * 所以这次把文字作为后台完成通知加入消息。</p>
     * @param result process 提交的完成事实，只读，不执行命令
     * @return 带 task_notification 标签的文字；截断时说明没有完整输出文件。
     *         标签中的特殊字符会转义，但不保证命令输出里一定没有误导模型的内容
     */
    private static String notification(Completion result) {
        String summary = result.output().length() > SUMMARY_CHARS ? result.output().substring(0, SUMMARY_CHARS) : result.output();
        if (result.output().length() > SUMMARY_CHARS || result.truncated()) {
            summary += "\n[Output truncated; no full output file is retained.]";
        }
        return "<task_notification>\n<task_id>" + result.id() + "</task_id>\n<source_run>" + escape(result.owner())
                + "</source_run>\n<status>" + result.status() + "</status>\n<exit_code>" + result.exitCode()
                + "</exit_code>\n<command>" + escape(result.command()) + "</command>\n<summary>" + escape(summary)
                + "</summary>\n</task_notification>";
    }

    /**
     * 把文字中的特殊字符写成实体，避免正文里的尖括号被误认成通知标签。
     * 先替换 {@code &}，免得把后面新生成的实体再替换一次。
     * @param value 要放进标签的非 null 文字
     * @return 转义后的文字，只处理标签格式，不判断文字是否可信
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
     * 立即采用压缩器给出的列表；先复制再 clear，候选即使就是原列表也不会被清空。
     * 调用发生在正式请求之前，失败时已采用的历史按原规则保留。
     * @param messages 新历史，只复制列表，不修改旧 Message 或其 JSON
     */
    private void adoptHistory(List<Message> messages) {
        apply(new SessionEvent.HistoryPrepared(messages));
    }

    /** 采用执行器已验证的模型回复，写入历史，不在这里决定任务是否完成。 */
    private void apply(ModelReply reply) {
        ensureOpen();
        history.add(reply.message());
    }

    /** 整批采用工具结果，意外失败也先补齐 tool_result，再由执行流程抛出异常。 */
    private void apply(ToolBatch batch) {
        ensureOpen();
        history.add(Message.userBlocks(batch.results()));
    }

    /** 把 Stop 续跑文字留在当前任务历史，不排成新目标。 */
    private void apply(Continue continuation) {
        ensureOpen();
        history.add(Message.userText(continuation.prompt()));
    }

    /**
     * 采用会话自身的请求、历史替换和通知事件；执行步骤结果由其它 apply 重载采用。
     *
     * <p>Request 是新任务，HistoryPrepared 是整份候选历史，Notifications 是后台事实的文字说明。
     * 它们发给模型时都可能用 USER role，但不代表它们都是用户新发的请求。
     * 事件只告诉这里要采用什么内容；执行器的 ModelReply/ToolBatch/Continue 由其它 apply 重载采用。</p>
     *
     * <p>压缩时只换 history 中的内容，不换列表对象，旧 run(messages) 的调用者才看得见变化。
     * 后台通知尽量加在最后一条 USER 内：若另加 USER，原工具结果不再是末条，
     * 现有压缩器可能漏掉对最新工具结果的保护。这是当前压缩器需要，不是模型协议硬性要求。
     * 合并会新建 content 和 Message，不修改旧消息里的块。</p>
     * @param event 程序生成的新请求、整份历史或通知；交出后发送方不要再修改其中内容
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
     * 在调用者线程同步处理已有请求，复用相同的 execute，不启动处理线程或定时轮询。
     *
     * <p>临时借用 messages 作为当前历史，因此压缩和失败前的采用结果对调用者立即可见。
     * finally 恢复本会话原列表，不复制或还原 messages；执行器和服务保持可用，须由所有者关闭。
     * 此入口仅供尚未 start 的会话使用，调用者不得与其它 run 并发执行。</p>
     * @param messages 已含本次请求的可修改消息列表，期间不可由其它线程修改
     * @throws LlmException 模型或摘要失败，取出原异常重新抛出
     * @throws RuntimeException 非法历史、执行失败、会话已启动或已关闭
     */
    public void run(List<Message> messages) throws LlmException {
        ensureOpen();
        if (consumer != null) throw new IllegalStateException("Session consumer is already started");
        // 只在这个同步调用内借用列表；正式会话和兼容调用都由同一 active/closed 状态管理。
        List<Message> ownedHistory = history;
        history = messages;
        ExecutionContext context = ExecutionContext.create(ExecutionContext.Source.USER, null, () -> closed);
        SessionInbox.Entry entry = new SessionInbox.Entry(new SessionEvent.Request("", context, null), new CompletableFuture<>());
        try {
            execute(entry, false);
            entry.result().join();
        } catch (CompletionException wrapped) {
            // join 包装实际异常；保留原类型，旧调用方不需要改错误处理方式。
            if (wrapped.getCause() instanceof LlmException failure) throw failure;
            if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException(wrapped.getCause());
        } finally {
            history = ownedHistory;
        }
    }

    /**
     * 原先直接调用执行器 run 的无服务调用方使用此同步入口。
     * @param engine 借用的执行器，返回时不关闭，不可并发执行
     * @param messages 已含请求的可修改历史；失败前已采用的消息仍保留
     * @throws LlmException 模型或压缩失败，原样抛出
     * @throws RuntimeException 非法历史、执行失败或执行器已关闭
     */
    public static void runExisting(AgentExecutor engine, List<Message> messages) throws LlmException {
        new SessionRuntime(engine, ignored -> { }, ignored -> { }).run(messages);
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
        if (background != null) background.close();
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
