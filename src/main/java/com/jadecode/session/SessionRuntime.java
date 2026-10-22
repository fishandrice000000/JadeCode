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
import com.jadecode.safety.approval.Approval;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.LlmException;
import com.jadecode.llm.LlmClient;
import com.jadecode.llm.ModelMetadata;
import com.jadecode.llm.ThinkingSettings;
import com.jadecode.memory.MemoryManager;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolExchangeProtocol;
import com.jadecode.process.BackgroundManager.Completion;
import com.jadecode.process.BackgroundManager;
import com.jadecode.session.history.JournalEntry;
import com.jadecode.session.history.RestoredSession;
import com.jadecode.session.history.SessionJournal;
import com.jadecode.todo.TodoManager;

/**
 * 管理一次 Agent 会话：每次只执行一项工作，在完整工具批次之间接收允许插入的补充。
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
 * 程序退出后队列不会恢复；REPL 通过日志恢复已采用历史，不恢复执行线程和已批准权限。
 * 已经写入的业务文件不会还原，网络请求也不一定立即停止。</p>
 */
public final class SessionRuntime implements AutoCloseable {
    private com.jadecode.safety.PermissionState permissions = com.jadecode.safety.PermissionState.defaults();
    /** 工厂装配一次，共享的是权限配置，不是队友的历史或工具表。 */
    public void permissions(com.jadecode.safety.PermissionState value) {
        if (consumer != null || journal != null || closed) throw new IllegalStateException("Session is already started or closed");
        permissions = java.util.Objects.requireNonNull(value);
    }
    /** 界面通过共享状态切换档位；不能在运行后换成与工具检查器不同的另一份状态。 */
    public com.jadecode.safety.PermissionState permissionState() { return permissions; }
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
        /** 会话日志写入失败，保留内存结果但不再发模型请求或执行工具。 */
        PERSISTENCE_FAILED,
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
    /** 用户、cron、邮箱与后台事件统一在这里排队，只有消费线程能采用到历史。 */
    private final SessionInbox inbox = new SessionInbox();
    /** 检查定时任务是否到期的对象，REPL 会启动它，单次 run 不启动。 */
    private final CronProducer cron;
    /** 显示回复、接收通知和本轮计数；回复结束不等于所有业务成功。 */
    private final Consumer<String> output;
    /** 显示任务或定时检查错误的函数，不把错误提示当成最终回答。 */
    private final Consumer<String> errors;
    /** volatile 让终端线程看到处理线程更新的状态，但终端仍不能修改 history。 */
    private volatile State state = State.IDLE;
    /** 退出时设为 true，父子 Agent 都可以检查用户是否已经退出。 */
    private volatile boolean closed;
    /** 记录正在处理的任务，退出时用它通知调用者“这项任务没做完”。 */
    private volatile Active active;
    /** 日志采用与 close 共用锁；关闭后迟到的模型回复不可能继续追加磁盘记录。 */
    private final Object historyMonitor = new Object();
    private SessionJournal journal;
    private TodoManager todos;
    private String model = "";
    /** 会话累计用量可由多个 Agent 提交；history 仍然只由本会话消费者采用。 */
    private SessionUsage usage;
    private boolean ownsUsage = true;
    private LlmClient profile;
    private java.util.concurrent.atomic.AtomicReference<ThinkingSettings> requestThinking =
            new java.util.concurrent.atomic.AtomicReference<>(ThinkingSettings.DEFAULT);
    private volatile ContextUsage contextUsage;
    /** 只读 MCP 服务快照；工厂在装配时绑定 McpManager.snapshot，UI 不直接依赖 mcp 包。 */
    private java.util.function.Supplier<List<com.jadecode.mcp.McpManager.ServerSnapshot>> mcpSnapshot = List::of;

    /** 界面读取的纯资料，不包含可修改的消息列表、日志写者、线程或审批授权。 */
    public record Display(String id, String title, String model, ModelMetadata metadata, ThinkingSettings thinking,
            String thinkingLabel, List<ThinkingSettings> thinkingChoices, SessionUsage.Snapshot usage, ContextUsage context) {
        public Display { thinkingChoices = List.copyOf(thinkingChoices); }
    }

    /**
     * 工厂为同一用户会话绑定共享账本和本 Agent 独立的设置，构造阶段调用，不启动请求。
     * @param value Leader、队友和辅助请求共用的账本，记录的是花费，不是共享历史
     * @param owner 是否由本运行时关闭账本；只有 Leader 为 true，关闭队友不能停掉全队记账
     * @param client 本 Agent 已装配的客户端，只读取限额/选项，不在这里请求网络
     * @param thinking 本 Agent 的请求和本地设置操作读取同一个引用，已有请求仍持有自己的快照
     * @throws IllegalStateException 消费线程已经启动或日志已经安装
     */
    public void telemetry(SessionUsage value, boolean owner, LlmClient client,
            java.util.concurrent.atomic.AtomicReference<ThinkingSettings> thinking) {
        if (consumer != null || journal != null) throw new IllegalStateException("Session is already started");
        usage = java.util.Objects.requireNonNull(value); ownsUsage = owner;
        profile = java.util.Objects.requireNonNull(client); requestThinking = java.util.Objects.requireNonNull(thinking);
    }

    /**
     * 装配只读服务快照端口；必须在会话启动前调用。供应商只能做纯内存复制，不能连接服务。
     */
    public void services(java.util.function.Supplier<List<com.jadecode.mcp.McpManager.ServerSnapshot>> supplier) {
        if (consumer != null || journal != null) throw new IllegalStateException("Session is already started");
        mcpSnapshot = supplier == null ? List::of : supplier;
    }

    /** @return 当前 MCP 服务只读快照；空列表表示没有配置或尚未装配。 */
    public List<com.jadecode.mcp.McpManager.ServerSnapshot> mcpServers() {
        try { return List.copyOf(mcpSnapshot.get()); }
        catch (RuntimeException failure) { return List.of(); }
    }

    /** 只读当前已发布的快照，不读盘、不请求模型；允许 TUI 在自己的线程采用。 */
    public Display display() {
        ThinkingSettings setting = requestThinking.get();
        var metadata = journal == null ? null : journal.metadata();
        return new Display(sessionId(), metadata == null ? "" : metadata.title(), model,
                profile == null ? new ModelMetadata(ModelMetadata.Limits.unknown(), "") : profile.metadata(), setting,
                profile == null ? "default" : profile.thinkingLabel(setting),
                profile == null ? List.of(ThinkingSettings.DEFAULT) : profile.thinkingChoices(), usage.snapshot(), contextUsage);
    }

    /** 只取当前设置，供新队友/一次性子任务在创建时继承；不会改已有队友。 */
    public ThinkingSettings thinking() { return requestThinking.get(); }

    /**
     * 用户的本地设置操作：先保存，再供下一次请求读取，不等待当前模型结束或改写其输入。
     * 不支持的选项明确拒绝；写盘失败和历史写失败一样停止本会话后续操作。
     */
    public void changeThinking(ThinkingSettings value) throws IOException {
        synchronized (historyMonitor) {
            ensureOpen();
            if (!display().thinkingChoices().contains(value)) throw new IllegalArgumentException("This endpoint or model does not support that thinking setting");
            try { if (journal != null) journal.thinking(value); }
            catch (IOException failure) { persistenceError(); throw failure; }
            requestThinking.set(value);
        }
    }

    /** 改名只保存元数据，不插入模型消息、不修改工作区路径；CLI 负责在工作线程调用。 */
    public void rename(String title) throws IOException {
        String valid = com.jadecode.session.history.SessionMetadata.validTitle(title);
        synchronized (historyMonitor) {
            ensureOpen();
            if (journal == null) throw new IOException("Persistent saving is not enabled for this session");
            try { journal.rename(valid); }
            catch (IOException failure) { persistenceError(); throw failure; }
        }
    }
    /** 恢复后的系统说明，不伪装成人类新输入；提醒模型旧运行资源并没有随聊天记录复活。 */
    private String resumeContext = "";
    private volatile boolean persistenceFailed, switching;
    /** 在 Inbox 锁下登记“取出但尚未设置 active”的阶段，补上空闲判断的竞态窗口。 */
    private boolean dispatching;
    private java.util.function.BooleanSupplier switchBlocked = () -> false;
    /** start 创建的任务处理线程；旧 runExisting 直接用调用者线程。 */
    private Thread consumer;
    /** 工厂组合 Team、MCP 等依赖清理；后登记的依赖先关闭，动作不能互相覆盖。 */
    private Runnable closeDependents = () -> { };

    /** 安装本会话拥有的额外资源清理，不启动它们；须在 start 前调用。 */
    public synchronized void onClose(Runnable cleanup) {
        java.util.Objects.requireNonNull(cleanup);
        Runnable previous = closeDependents;
        // 后装配的依赖先关闭；即使其中失败，也继续释放先前登记的资源，不能覆盖 Team/MCP 清理。
        closeDependents = () -> { try { cleanup.run(); } finally { previous.run(); } };
    }

    /** 工厂登记真实 Todo 和模型名，不开始存盘；旧兼容调用仍可使用没有日志的运行时。 */
    public void historyState(TodoManager manager, String modelName, java.util.function.BooleanSupplier blocked) {
        if (consumer != null || journal != null || closed) throw new IllegalStateException("Session is already started or closed");
        todos = java.util.Objects.requireNonNull(manager);
        model = java.util.Objects.requireNonNull(modelName); switchBlocked = java.util.Objects.requireNonNull(blocked);
    }

    public String model() { return model; }
    public String sessionId() { return journal == null ? "" : journal.metadata().id(); }

    /** 关闭后消费者确实退出才返回 true；不把“已发中断”冒充“线程已停止”。 */
    public boolean isStopped() { return closed && (consumer == null || !consumer.isAlive()); }

    /**
     * 启动前采用恢复结果并接管写者所有权。只安装历史和计划，不提交旧请求或执行旧工具。
     * @throws IllegalStateException 运行时已经启动、已关闭或已经安装日志
     */
    public synchronized void restore(SessionJournal writer, RestoredSession restored) {
        if (consumer != null || closed || journal != null) throw new IllegalStateException("Cannot install history into a running session");
        if (todos != null) todos.restore(restored.todos());
        history = new ArrayList<>(restored.history());
        if (!restored.entries().isEmpty()) resumeContext = "\nRestored conversation: previous background processes, teammates, inbox and approval grants were not restored."
                + " Workspace files and persistent tasks, memory and cron reflect current disk state, not a rollback."
                + " Do not replay old tool calls to reconstruct history. An unrecorded result does not prove the operation did not execute."
                + " Internal data moved under .jadecode; read_file supports legacy artifact paths, but shell commands must use current paths.";
        journal = java.util.Objects.requireNonNull(writer);
        usage.restore(writer, restored.entries());
        ThinkingSettings saved = restored.metadata().thinking();
        if (saved != null) {
            if (display().thinkingChoices().contains(saved)) requestThinking.set(saved);
            else errors.accept("[Warning] The current model does not support the restored session's thinking setting; using the current configuration default.");
        }
    }

    /**
     * 原子冻结空闲会话，之后拒绝新入队，供 CLI 安全切换。失败不改变当前会话。
     * Team/后台检查放在 Inbox 锁外，避免与生产者持有的资源锁形成反向等待。
     */
    public boolean freezeForSwitch() {
        if (switchBlocked.getAsBoolean() || background != null && background.hasPending()) return false;
        synchronized (inbox) {
            if (closed || switching || active != null || dispatching || !inbox.isEmpty()) return false;
            switching = true;
            return true;
        }
    }

    /** 目标加载/装配失败时撤销冻结，让原会话继续接收请求；不会重放任何任务。 */
    public void unfreeze() { synchronized (inbox) { switching = false; inbox.wake(); } }

    /** 是否正处理大轮次；等待后台也算忙，不能把新派工直接当当前工作。 */
    public boolean isBusy() { return active != null; }

    /** 给提交结果工具检查当前轮次是否仍欠必等后台结果，不把“启动成功”当业务完成。 */
    public boolean hasRequiredBackground() {
        Active task = active;
        return task != null && background != null && background.hasRequired(task.context().id());
    }

    /**
     * 真实终端使用：忙时合并用户输入，空闲时保持普通请求提交。
     * 只改 Inbox，不在 UI 线程改 history。返回的 future 属于整批输入。
     */
    public CompletableFuture<String> submitUser(String prompt, Approval approval) {
        if (!isBusy()) return submit(prompt, approval);
        if (prompt == null || prompt.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("Empty prompt"));
        var context = ExecutionContext.create(ExecutionContext.Source.USER, approval, () -> closed);
        CompletableFuture<String> result;
        synchronized (inbox) {
            result = closed || switching || persistenceFailed ? null : inbox.buffer(prompt, context);
            publishBuffer();
        }
        return result == null ? CompletableFuture.failedFuture(new RejectedExecutionException("Session inbox is full or closed")) : result;
    }

    /** 空输入 Enter 只升级待处理原批次，没缓冲则不做事。 */
    public boolean promoteUserBuffer() {
        synchronized (inbox) {
            boolean changed = !closed && !switching && !persistenceFailed && inbox.promote();
            publishBuffer(); return changed;
        }
    }

    /** 复制现有业务缓冲的显示资料，TUI 不保存或消费另一份任务队列。 */
    private void publishBuffer() {
        var view = inbox.bufferedView();
        engine.notice(ExecutionNotice.event(ExecutionNotice.Kind.BUFFER, view == null ? "" : view.id(), "",
                view == null ? "" : view.summary(), view != null && view.allowInsertion() ? "INSERTION" : "QUEUED"));
    }

    /** 状态变化来自真实执行路径；失败和关闭也通知，不只依靠正常完成的 TURN_END。 */
    private void changeState(State next) {
        state = next;
        engine.notice(ExecutionNotice.event(ExecutionNotice.Kind.SESSION, "", "", "", next.name()));
    }

    /**
     * Harness 投递邮箱消息。接收成功仅表示已放进内存 Inbox，不代表模型读过或业务完成。
     * 权限取自程序提供的 context，不从摘要正文里解析。调用方据返回值清理 pending 文件。
     */
    public CompletableFuture<String> receive(SessionEvent.Incoming incoming, ExecutionContext context) {
        var accepted = tryReceive(incoming, context);
        return accepted == null ? CompletableFuture.failedFuture(new RejectedExecutionException("Recipient inbox full or closed")) : accepted;
    }

    /** 返回 null 明确表示未入队，避免把“已接单但执行失败”误判成派工未成功。 */
    public synchronized CompletableFuture<String> tryReceive(SessionEvent.Incoming incoming, ExecutionContext context) {
        if (closed || switching || persistenceFailed) return null;
        var result = new CompletableFuture<String>();
        // 已入队的邮件属于接收方，发送者随后退出不能撤回它；仍保留原来源和审批范围。
        var scoped = new ExecutionContext("run_" + java.util.UUID.randomUUID(), context.source(),
                context.approval(), () -> closed);
        synchronized (inbox) {
            if (closed || switching || persistenceFailed || !inbox.offer(new SessionInbox.Entry(new SessionEvent.Request(incoming.render(), scoped, null, incoming), result)))
                return null;
        }
        return result;
    }

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
        this.usage = new SessionUsage(errors);
        this.cron = new CronProducer(scheduler, this::publishCron, errors);
        if (background != null) {
            background.onResult(this::backgroundCompleted);
            background.onCompletion(inbox::wake);
        }
    }

    /**
     * 后台线程只生产 Inbox 事件，不改 history、不请求模型。
     * 完成结果带原 run ID 与投递规则；预留后台槽避免普通请求满时丢通知。
     */
    private void backgroundCompleted(Completion result) {
        var origin = result.context();
        var context = new ExecutionContext("run_" + java.util.UUID.randomUUID(), origin.source(), origin.approval(), () -> closed);
        var incoming = new SessionEvent.Incoming(result.id(), "background", "session", result.owner(), null,
                "background", notification(result), null, result.allowInsertion(), result.wakeWhenIdle());
        var request = new SessionEvent.Request(incoming.render(), context, null, incoming);
        if (inbox.offerBackground(new SessionInbox.Entry(request, new CompletableFuture<>(), result))) background.queued(result);
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
        synchronized (inbox) {
            if (closed || switching || persistenceFailed || !inbox.offer(new SessionInbox.Entry(request, result)))
                result.completeExceptionally(new RejectedExecutionException("Session inbox is full, switching or unavailable"));
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
        synchronized (inbox) {
            return !switching && !closed && !persistenceFailed && inbox.offer(new SessionInbox.Entry(
                    new SessionEvent.Request(job.prompt(), context, job.id()), new CompletableFuture<>()));
        }
    }

    /**
     * 空闲时按优先级取可唤起的任务，等 execute 处理完，再取下一项。
     *
     * <p>当前任务在等后台构建时，也不会去做下一项任务。普通执行错误由 execute 报告，
     * 不会丢掉后面排队的请求。没有任务时等 revision 变化，不忙轮询；中断时记住中断状态并退出。</p>
     */
    private void consume() {
        try {
            while (!closed && !persistenceFailed) {
                long observed = inbox.revision();
                SessionInbox.Entry entry;
                synchronized (inbox) {
                    entry = switching ? null : inbox.pollIdle();
                    dispatching = entry != null;
                }
                if (entry != null) {
                    try { execute(entry, true); }
                    finally { synchronized (inbox) { dispatching = false; } }
                    continue;
                }
                inbox.awaitChange(observed);
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
        execute(entry, append, entry.background() == null ? List.of() : List.of(entry.background()));
    }

    /** 执行从后台唤起的轮次时，先登记已取出的回执；普通任务传空列表。 */
    private void execute(SessionInbox.Entry entry, boolean append, List<Completion> initialResults) {
        Active task = new Active(entry, history, engine::notice);
        task.receipts.addAll(initialResults);
        active = task;
        changeState(State.RUNNING);
        // 实时计数和最终耗时共用 Active 的同一起点，不累加工具或模型的局部耗时。
        engine.notice(ExecutionNotice.event(ExecutionNotice.Kind.TURN_START, task.context().id(), "", "", "RUNNING")
                .started(task.timer.startedNanos()));
        synchronized (inbox) { publishBuffer(); }
        try {
            ensureOpen();
            // 定时交付检查本身也可能失败，先保存轮次开始，失败回滚才能找回正确显示快照。
            record(JournalEntry.Kind.TURN_STARTED, List.of(), "turn");
            if (task.cronId() != null) {
                // 排队后可能被取消，也可能暂时不允许重试；先不执行，不能把它记成已经送达。
                if (!cron.canDeliver() || !cron.pending(task.cronId())) {
                    cron.release(task.cronId());
                    record(JournalEntry.Kind.TURN_FINISHED, List.of(), "delivery_deferred");
                    turnEnded(task, "SKIPPED", false);
                    entry.result().completeExceptionally(new IllegalStateException("Cron delivery deferred or cancelled"));
                    return;
                }
                output.accept("[Cron] Running " + task.cronId());
            }
            if (append) {
                if (task.context().source() == ExecutionContext.Source.USER
                        && (entry.request().incoming() == null || entry.request().incoming().kind().equals("user"))) {
                    engine.hooks().trigger(Hooks.USER_PROMPT_SUBMIT,
                            Map.of(Hooks.PARAM_QUERY, entry.request().prompt(), Hooks.PARAM_CONTEXT, task.context()));
                }
                apply(entry.request());
            }
            // 先记住用户请求，再加入后台通知；之后历史即使压缩，仍记着用户原本要做什么。
            task.goal = activeRequest(history);
            task.system = taskSystem(task.goal);
            if (entry.request().incoming() != null) {
                output.accept(entry.request().incoming().kind().equals("background")
                        ? "[Background] 1 command result received" : "[Incoming] " + entry.request().incoming().summary());
            }
            // 新轮次已经由用户/派工/允许唤起的通知启动，此时可顺带加入此前停放的参考消息。
            deliverIncoming(task, false);
            // 前一次若优先取了插入式消息，现在再给非插入式参考一次机会，仍不取新派工/新用户任务。
            deliverIncoming(task, false);
            runTask(task);
        } catch (LlmException | IOException | RuntimeException failure) {
            fail(task, failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(task, new IllegalStateException("Session execution interrupted", interrupted));
        } finally {
            active = null;
            if (!closed && !persistenceFailed) changeState(State.IDLE);
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
        String base = engine.system() + resumeContext;
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
            deliverIncoming(task, true);
            flushNotices(task);
            ModelReply reply = engine.request(history, task.goal, task.system, this::adoptHistory);
            if (reply.calls().isEmpty()) {
                // 回答先展示一次，再记录显示截止；Memory 还会照常收尾，不能提前宣布业务完成。
                // 流式时间来自生产端 complete，旧客户端在这里回退输出，不再在多个结束分支重放。
                if (!reply.textDisplayed() && !reply.message().extractText().isBlank())
                    output.accept("[Reply] " + reply.message().extractText());
                task.timer.answerCompleted(reply.textDisplayed() ? reply.displayCompletedNanos() : null);
            }
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
                ToolBatch batch = engine.executeTools(reply, task.context(), task.rounds);
                task.toolCalls += reply.calls().size();
                apply(batch);
                task.rounds = batch.roundsSinceTodo();
                if (batch.failure() != null) throw batch.failure();
                // 这一批 tool_use 都已有 tool_result，现在才能压缩，不会把调用与结果拆开。
                engine.compactAfterBatch(batch, history, task.goal, this::adoptHistory);
                continue;
            }

            // 等模型回答时也许又有命令跑完；加入结果后要再问一次，不能还没给它看就宣布结束。
            boolean incoming = deliverIncoming(task, true, true);
            if (incoming) {
                // 模型刚才的回答仍在 history 中，也先显示，不用下一条回答覆盖它。
                task.timer.resumeDisplay();
                flushNotices(task);
                continue;
            }
            if (background != null && background.hasRequired(task.context().id())) {
                task.timer.resumeDisplay();
                changeState(State.WAITING_BACKGROUND);
                awaitResults(task);
                changeState(State.RUNNING);
                continue;
            }
            Continue continuation = engine.stop(history);
            if (continuation != null) {
                task.timer.resumeDisplay();
                apply(continuation);
                continue;
            }
            // 4. 最后回答已输出；计时停在回答边界，Memory/日志及真实结束判定继续执行。
            task.timer.beginMaintenance();
            finish(history);
            ensureOpen();
            boolean pending;
            synchronized (inbox) {
                // Stop/记忆维护也可能耗时。结束判定必须再查一次，并与入队互斥。
                // 这之后才到达的消息属于下一轮；已有补充则继续当前轮，不漏掉用户空回车升级。
                pending = inbox.hasCurrentInput(task.context().id());
                if (!pending) { active = null; if (!closed) changeState(State.IDLE); }
            }
            if (pending) {
                task.timer.resumeDisplay();
                deliverIncoming(task, true, true);
                flushNotices(task);
                continue;
            }
            ensureOpen();
            String answer = reply.message().extractText();
            // 本次回答已在候选边界显示；这里只收口业务，不重复输出。
            engine.hooks().trigger(Hooks.TURN_END, Map.of(Hooks.PARAM_TOOL_COUNT, task.toolCalls));
            record(JournalEntry.Kind.TURN_FINISHED, List.of(), "turn");
            turnEnded(task, "DONE", !answer.isBlank());
            task.entry.result().complete(answer);
            task.incomingReceipts.forEach(receipt -> receipt.result().complete(answer));
            return;
        }
        throw new IllegalStateException("Session is closed");
    }

    /**
     * 在完整工具批次之后取外部消息，重建末条 USER，不破坏 tool_use/result 相邻关系。
     * 用户插入式补充更新本轮目标，避免稍后压缩只保留最初请求而遗漏用户新要求。
     * 邮箱消息只是带来源的资料，不会把发件人任务偷换成当前任务。
     */
    private boolean deliverIncoming(Active task, boolean insertionOnly) {
        return deliverIncoming(task, insertionOnly, false);
    }

    /** 结束/等待阶段可以接收本轮必等结果，不受禁止提前插入限制。 */
    private boolean deliverIncoming(Active task, boolean insertionOnly, boolean finishing) {
        List<SessionInbox.Entry> entries;
        synchronized (inbox) { entries = inbox.drain(insertionOnly, finishing, task.context().id()); publishBuffer(); }
        if (entries.isEmpty()) return false;
        // 先登记整批回执，哪怕退出发生在采用第一条消息时，也能通知所有发送者失败。
        task.incomingReceipts.addAll(entries);
        for (var entry : entries) {
            var incoming = entry.request().incoming();
            apply(new SessionEvent.Notifications(List.of(incoming.render())));
            if (entry.background() != null) task.receipts.add(entry.background());
            if (incoming.kind().equals("user")) {
                task.goal = (task.goal == null ? "" : task.goal + "\n\n") + incoming.summary();
                // 更新目标不改变原任务来源：插入用户文字不能把定时任务升级成人工授权任务。
            }
            task.notices.add(entry.background() == null ? "[Incoming] " + incoming.summary()
                    : "[Background] 1 command result received");
        }
        return true;
    }

    /**
     * 在开始下一次请求前显示通知；若刚有阶段回复，先显示回复再显示 Incoming。
     * history 的先后顺序不受展示缓冲影响，后台结果只显示数量，不打印内部 bg_id。
     */
    private void flushNotices(Active task) {
        task.notices.forEach(output);
        task.notices.clear();
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
     * 被叫醒只说明要再查一次，完成事件已经由后台生产者排进 Inbox。
     * 新请求也会叫醒线程，但这里只查后台结果，不执行新请求。</p>
     * @param task 正在等结果的任务；不用等所有命令都结束，取到一批就可以继续
     * @throws InterruptedException 等待线程被中断
     * @throws IllegalStateException 会话已退出，不再继续任务
     */
    private void awaitResults(Active task) throws InterruptedException {
        while (!closed) {
            long observed = inbox.revision();
            if (deliverIncoming(task, true, true)) return;
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
        engine.notice(ExecutionNotice.event(ExecutionNotice.Kind.COMPACT, "", "", "Model context compacted; UI history preserved", ""));
    }

    /** 采用执行器已验证的模型回复，写入历史，不在这里决定任务是否完成。 */
    private void apply(ModelReply reply) {
        appendMessage(reply.message(), "reply");
        contextUsage = ContextUsage.measured(reply.usage(), display().metadata());
    }

    /** 整批采用工具结果，意外失败也先补齐 tool_result，再由执行流程抛出异常。 */
    private void apply(ToolBatch batch) {
        appendMessage(Message.userBlocks(batch.results()), "tool_batch");
    }

    /** 把 Stop 续跑文字留在当前任务历史，不排成新目标。 */
    private void apply(Continue continuation) {
        appendMessage(Message.userText(continuation.prompt()), "stop");
    }

    /** 先保留已得到的完整消息，再同步存盘；失败时内存证据仍在，但不开始下一步。 */
    private void appendMessage(Message message, String reason) {
        synchronized (historyMonitor) {
            ensureOpen(); history.add(message);
            if (!reason.equals("reply")) contextUsage = null;
            record(JournalEntry.Kind.APPEND, List.of(message), reason);
        }
    }

    /**
     * 保存正式采用的事实。日志故障关闭执行器与 cron，防止继续累积无法恢复的副作用。
     * close 和本方法共用 historyMonitor；已有工具造成的文件变化不会因此回滚。
     */
    private void record(JournalEntry.Kind kind, List<Message> messages, String reason) {
        synchronized (historyMonitor) {
            if (journal == null || closed) return;
            try { journal.append(kind, messages, todos == null ? com.jadecode.util.Json.mapper().createArrayNode() : todos.snapshot(), reason); }
            catch (IOException failure) {
                persistenceError();
                throw new IllegalStateException(failure.getMessage(), failure);
            }
        }
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
        synchronized (historyMonitor) {
            ensureOpen();
            contextUsage = null;
            switch (event) {
                case SessionEvent.Request request -> appendMessage(Message.userText(
                        request.context().source() == ExecutionContext.Source.SCHEDULED
                                ? "[Scheduled] " + request.prompt() : request.prompt()), "request");
                case SessionEvent.HistoryPrepared prepared -> {
                    // 事件已经复制列表，clear 不会把候选一起清空；日志同样保存“替换”而非追加。
                    history.clear(); history.addAll(prepared.messages());
                    record(JournalEntry.Kind.REPLACE_HISTORY, history, "compaction");
                }
                case SessionEvent.Notifications notifications -> {
                    // 末条 USER 可能是请求或工具结果。新建 Message，旧日志记录和内容块不被原地修改。
                    List<ContentBlock> content = new ArrayList<>();
                    boolean merge = !history.isEmpty() && history.getLast().role() == Role.USER;
                    if (merge) content.addAll(history.getLast().content());
                    notifications.texts().forEach(text -> content.add(new TextBlock(text)));
                    Message message = Message.userBlocks(List.copyOf(content));
                    if (merge) history.set(history.size() - 1, message);
                    else history.add(message);
                    record(merge ? JournalEntry.Kind.REPLACE_LAST : JournalEntry.Kind.APPEND, List.of(message), "notification");
                }
                case SessionEvent.Incoming incoming -> throw new IllegalArgumentException("Incoming must be queued first; history cannot be mutated across threads");
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
        try {
            if (!persistenceFailed && task.cronId() != null && !task.received) {
                synchronized (historyMonitor) {
                    // 与 close 共用锁，在锁内再检查，不能在退出后恢复旧列表。
                    if (!closed) {
                        history.clear(); history.addAll(task.snapshot);
                        contextUsage = null;
                        record(JournalEntry.Kind.REPLACE_HISTORY, history, "rollback");
                    }
                }
                if (background != null) background.restore(task.receipts);
                if (!closed) cron.failed(task.cronId());
                else cron.release(task.cronId());
            } else if (background != null) {
                // 通知仍留在历史，可以确认接收；不要因此从头再执行工具。
                background.acknowledge(task.receipts);
            }
        } catch (RuntimeException recording) {
            // 报告错误的过程中也可能遇到磁盘故障，但仍须完成原 future，不能让调用者永远等待。
            failure.addSuppressed(recording);
        }
        if (background != null) background.cancelRequired(task.context().id());
        inbox.discardRequired(task.context().id());
        try { if (!persistenceFailed) record(JournalEntry.Kind.TURN_FAILED, List.of(), "turn"); }
        catch (RuntimeException recording) { failure.addSuppressed(recording); }
        if (persistenceFailed) {
            // 写失败也可能刚刚发生在失败记录本身；统一收尾，排队 future 不能永久等待。
            inbox.close();
            if (background != null) background.close();
        }
        task.entry.result().completeExceptionally(failure);
        turnEnded(task, "FAILED", false);
        task.incomingReceipts.forEach(receipt -> receipt.result().completeExceptionally(failure));
        if (!closed) errors.accept("[Error] " + task.context().id() + ": " + failure.getMessage());
    }

    /**
     * 继续处理或修改消息前，检查用户是否已退出；不能还原已经开始的文件写入。
     * @throws IllegalStateException 用户已退出会话
     */
    private void ensureOpen() {
        if (usage.failed() && !persistenceFailed && !closed) persistenceError();
        if (closed || persistenceFailed || switching) throw new IllegalStateException("Session is closed, switching or persistence failed");
    }

    /** 统一停止本会话的新动作；已发生的工具副作用不回滚，内存结果仍留作排查。 */
    private void persistenceError() {
        persistenceFailed = true; cron.close(); engine.close(); changeState(State.PERSISTENCE_FAILED);
    }

    /**
     * 最后一次回答与该轮日志已经处理完后，才报告整轮耗时；这不是“模型流结束”的同义词。
     * 工具、思考、必等后台计入工作量；人工审批及最后回答后的记忆维护不计入。
     * 只有最终确实有回复文字时才指向 Reply 卡，不把先前阶段回答冒充最终回答。
     */
    private void turnEnded(Active task, String outcome, boolean hasReply) {
        engine.notice(ExecutionNotice.event(ExecutionNotice.Kind.TURN_END, task.context().id(), hasReply ? "Reply" : "", "", outcome)
                .timed(task.timer.elapsedNanos()));
    }

    /**
     * 保存当前任务已经做到了哪一步。
     * 请求模型、执行工具和等后台时使用同一个对象，不因等待重新开始任务。
     * 只有处理线程修改字段；退出线程只读 entry，用它通知调用者任务未完成。
     */
    private static final class Active {
        /** 消费者已经取出任务才创建，故不包含 Inbox 中等待别人做完的排队时间。 */
        final com.jadecode.execution.TurnTimer timer;
        /** 审批已经绑定本轮计时；任务 ID、来源、退出检查不变。 */
        final ExecutionContext execution;
        /** 最初取出的请求及结果 future，不随历史压缩变化。 */
        final SessionInbox.Entry entry;
        /** 定时任务开始前的历史副本，第一次模型回复前失败时恢复它；普通任务不保存。 */
        final List<Message> snapshot;
        /** 已加进 history、但还没等到模型正常回复的后台结果。 */
        final List<Completion> receipts = new ArrayList<>();
        /** 已采用的插入消息，随当前大轮次完成其 future，不重复执行原业务。 */
        final List<SessionInbox.Entry> incomingReceipts = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** 展示通知暂存到合适位置，避免 Incoming 抢在刚生成的阶段回复前面显示。 */
        final List<String> notices = new ArrayList<>();
        /** 当前大轮次模型提出的工具调用个数，包含被拒绝调用，不数子代理内部工具。 */
        int toolCalls;
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
        Active(SessionInbox.Entry entry, List<Message> history, java.util.function.Consumer<ExecutionNotice> notices) {
            this.entry = entry;
            var origin = entry.request().context();
            timer = new com.jadecode.execution.TurnTimer(origin.id(), notices);
            execution = new ExecutionContext(origin.id(), origin.source(), timer.track(origin.approval()), origin.sessionClosed(), origin.goal());
            // 只有定时任务在第一次回复前失败才会重试；普通任务不用多存一份长历史。
            snapshot = entry.request().cronId() == null ? List.of() : List.copyOf(history);
        }
        /**
         * @return 原任务 ID、来源、审批和退出检查，再附上包含已接收补充的当前任务说明
         */
        ExecutionContext context() { return execution.withGoal(goal == null ? entry.request().prompt() : goal); }
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
        if (journal != null) throw new IllegalStateException("Persistent sessions do not borrow external history");
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
        synchronized (historyMonitor) {
            if (closed) return;
            closed = true;
            changeState(State.CLOSED);
        }
        cron.close();
        try { closeDependents.run(); }
        catch (RuntimeException failure) { errors.accept("[Error] Dependent session cleanup failed: " + failure.getMessage()); }
        // 先停止执行器和命令，再通知 future 的调用者；对方可能立即运行自己的代码，不能拖延停止工具。
        engine.close();
        if (background != null) background.close();
        inbox.close();
        Active running = active;
        if (running != null) {
            running.entry.result().completeExceptionally(new IllegalStateException("Session closed"));
            // 模型网络调用可能不响应中断；已采用的插入批次也不能一直留下 pending future。
            running.incomingReceipts.forEach(receipt -> receipt.result().completeExceptionally(new IllegalStateException("Session closed")));
        }
        if (consumer != null && consumer != Thread.currentThread()) {
            consumer.interrupt();
            try {
                consumer.join(2000);
                if (consumer.isAlive()) errors.accept("[Session] Model/IO still finishing; new tools are disabled.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (historyMonitor) {
            if (ownsUsage) usage.close();
            if (journal != null) {
                try { journal.close(); }
                catch (IOException failure) { errors.accept("[Error] Failed to close the session journal: " + failure.getMessage()); }
            }
        }
    }
}
