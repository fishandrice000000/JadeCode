package com.jadecode.session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import com.jadecode.agent.AgentExecutor.Continue;
import com.jadecode.agent.AgentExecutor.ModelReply;
import com.jadecode.agent.AgentExecutor.ToolBatch;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.context.ContextCompactor;
import com.jadecode.safety.approval.Approval;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.hooks.Hooks;
import com.jadecode.goal.GoalController;
import com.jadecode.goal.GoalEvaluator;
import com.jadecode.goal.GoalState;
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
 * 管理一个 Agent 会话的排队、执行、等待和结果采用。
 *
 * <p>submit 把独立请求放入 Inbox，consume 逐项领取，execute 建立本轮状态，
 * runTask 调用 AgentExecutor 驱动模型与工具循环。
 * 后台结果、Team 报告和用户补充在完整工具批次之间写入历史；本轮必等结果采用、报告验收、Stop 和 Goal 条件处理后，
 * 保存轮次终态并完成请求 Future。正式历史由会话消费线程同步记录到日志，关闭时统一释放会话资源。</p>
 *
 * @see com.jadecode.agent.AgentExecutor
 * @see SessionInbox
 * @see com.jadecode.session.history.SessionJournal
 */
public final class SessionRuntime implements AutoCloseable {
    private com.jadecode.safety.PermissionState permissions = com.jadecode.safety.PermissionState.defaults();

    /**
     * 绑定当前会话使用的共享权限状态，供工具检查和界面快照读取同一档位与版本。
     *
     * @param value 本会话与工具权限检查共用的权限状态
     * @throws IllegalStateException 运行时已启动、已绑定日志或已经关闭
     * @throws NullPointerException value 为 null
     */
    public void permissions(com.jadecode.safety.PermissionState value) {
        if (consumer != null || journal != null || closed)
            throw new IllegalStateException("Session is already started or closed");
        permissions = java.util.Objects.requireNonNull(value);
    }

    /**
     * 返回当前会话与工具检查器共用的权限状态，供界面读取和切换档位。
     *
     * @return 与当前工具检查器共用的权限状态
     */
    public com.jadecode.safety.PermissionState permissionState() {
        return permissions;
    }

    /**
     * 标识会话当前处于空闲、运行、等待后台、等待队友、持久化失败或关闭阶段，供控制操作与界面显示使用。
     */
    public enum State {
        /** 现在没有任务，处理线程等待队列里的下一项请求。 */
        IDLE,
        /** 正在请求模型、执行同步工具、压缩消息或处理长期记忆。 */
        RUNNING,
        /** 当前任务在等后台命令跑完；不做下一项任务，也不反复问模型。 */
        WAITING_BACKGROUND,
        /** 等本轮派工报告；界面显示 Waiting，不轮询模型。 */
        WAITING_TEAM,
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
    /** 用户、请求、邮箱与后台事件统一在这里排队，只有消费线程能采用到历史。 */
    private final SessionInbox inbox = new SessionInbox();
    /** 派工到原 Leader 执行的映射；在投递前登记，终态通知正式写入历史后删除。 */
    private final Map<String, String> pendingTeam = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.function.Supplier<List<Map<String, Object>>> teamSnapshot = List::of;

    /**
     * 装配只读 Team 视图；供应方只复制内存，不能在 UI 线程读取邮箱正文。
     *
     * @param supplier 为界面提供当前状态副本的函数
     * @throws NullPointerException supplier 为 null
     */
    public void teamView(java.util.function.Supplier<List<Map<String, Object>>> supplier) {
        teamSnapshot = java.util.Objects.requireNonNull(supplier);
    }

    /**
     * 只读报告端口只接受派工 ID；后台服务不接受模型或前端指定任意文件路径。
     */
    @FunctionalInterface
    public interface TeamReports {
        /**
         * 按派工 ID 读取队友提交的报告正文，供 Leader 界面查看；报告不可读时抛出 IOException。
         *
         * @param assignment 需要交付或查询报告的派工 ID
         * @return 对应派工已提交的完整报告正文
         * @throws IOException 报告不存在或完整正文读取失败
         */
        String read(String assignment) throws IOException;
    }

    private TeamReports teamReports = id -> {
        throw new IllegalArgumentException("No Team is active");
    };

    /**
     * 工厂安装当前 Team 的只读报告端口，不启动 IO。
     *
     * @param reader 显式详情查询时按派工 ID 读取报告正文的函数
     * @throws NullPointerException reader 为 null
     */
    public void teamReports(TeamReports reader) {
        teamReports = java.util.Objects.requireNonNull(reader);
    }

    /** Leader 验收属于当前轮的决策工作；在 Inbox 锁外检查，避免与 Team 投递形成锁反转。 */
    private java.util.function.Function<String, String> teamCompletion = run -> "";

    /**
     * 绑定 Team 完成检查函数，供主循环在候选结束时查询尚待交付或验收的工作。
     * 返回的检查文本由主循环采用为继续处理的提示。
     *
     * @param check 按轮次 ID 返回尚待 Leader 验收工作的检查函数
     * @throws NullPointerException check 为 null
     */
    public void teamCompletion(java.util.function.Function<String, String> check) {
        teamCompletion = java.util.Objects.requireNonNull(check);
    }

    /**
     * 后端详情页显式请求时才读取正文，普通快照不读磁盘。
     *
     * @param assignment 需要交付或查询报告的派工 ID
     * @return 指定派工的完整报告正文
     * @throws IOException 报告入口未绑定、报告身份无效或正文读取失败
     */
    public String teamReport(String assignment) throws IOException {
        return teamReports.read(assignment);
    }

    /**
     * 当前执行编号供只读 Team 统计使用，空闲时为空串。
     *
     * @return 正在运行的轮次 ID；空闲时为空串
     */
    public String currentExecutionId() {
        Active task = active;
        return task == null ? "" : task.context().id();
    }

    /**
     * 返回当前 Active 中的派工 ID；会话空闲或当前轮次为普通用户请求时返回空串。
     *
     * @return 当前派工 ID；用户轮次或空闲时为空串
     */
    public String currentAssignmentId() {
        Active current = active;
        return current == null || current.context().assignment() == null ? "" : current.context().assignment().id();
    }

    /**
     * 在派工投递前把该派工登记为当前轮次的必等结果，达到独立接收槽上限时报告失败。
     *
     * @param assignment 需要交付或查询报告的派工 ID
     * @param owner 必等派工所属的原轮次 ID；telemetry 中表示是否由本会话关闭共享账本
     * @throws IllegalStateException 必等派工数量已达到接收槽上限
     */
    public void expectTeamResult(String assignment, String owner) {
        synchronized (inbox) {
            ensureOpen();
            if (pendingTeam.size() >= 32)
                throw new IllegalStateException("At most 32 pending required assignments");
            pendingTeam.put(assignment, owner);
        }
    }

    /**
     * 撤销尚待交付的派工等待，供派工投递失败或所属轮次收尾使用；已执行操作按实际结果保留。
     *
     * @param assignment 需要交付或查询报告的派工 ID
     */
    public void forgetTeamResult(String assignment) {
        synchronized (inbox) {
            pendingTeam.remove(assignment);
            inbox.wake();
        }
    }

    /**
     * 按轮次 ID 查询会话内登记的必等派工，返回该轮是否仍有尚未交付报告。
     *
     * @param owner 必等派工所属的原轮次 ID；telemetry 中表示是否由本会话关闭共享账本
     * @return 指定原轮次仍有必等派工报告时为 true
     */
    private boolean hasPendingTeam(String owner) {
        return pendingTeam.containsValue(owner);
    }

    /** 显示回复、接收通知和本轮计数；回复结束不等于所有业务成功。 */
    private final Consumer<com.jadecode.execution.RuntimeMessage> messages;
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
    private java.util.concurrent.atomic.AtomicReference<ThinkingSettings> requestThinking = new java.util.concurrent.atomic.AtomicReference<>(
            ThinkingSettings.DEFAULT);
    private volatile ContextUsage contextUsage;
    /** 只读 MCP 服务快照；工厂在装配时绑定 McpManager.snapshot，UI 不直接依赖 mcp 包。 */
    private java.util.function.Supplier<List<com.jadecode.mcp.McpManager.ServerSnapshot>> mcpSnapshot = List::of;
    /** 装配时实际采用的资源名称；展示不再自行扫描文件或推测已加载的内容。 */
    private Map<String, List<String>> resources = Map.of();
    /** 显式 /goal 的条件与当前任务说明分离；只给 Leader 装配，状态变更共用 historyMonitor。 */
    private GoalController goals;
    /** 查看活跃目标时展示本进程自设置/恢复以来的会话用量差值，包含辅助/队友，不称为主模型费用。 */
    private SessionUsage.Totals goalUsageStart = SessionUsage.Totals.empty();

    /**
     * 会话启动前绑定 Goal 控制器和目标状态出口，供后续用户目标命令及候选完成检查使用。
     *
     * @param evaluator 依据当前历史证据判断 Goal 是否完成的评估器
     * @param limits 当前压缩流程使用的预算与保留限制
     * @throws IllegalStateException 目标入口在运行时启动后才尝试重新绑定
     */
    public void goals(GoalEvaluator evaluator, GoalController.Limits limits) {
        if (consumer != null || journal != null || closed || goals != null)
            throw new IllegalStateException("Cannot install goal evaluator now");
        goals = new GoalController(evaluator, this::saveGoal, limits);
    }

    /**
     * 返回当前 Goal 状态快照；未装配或尚未设置目标时返回 null。
     *
     * @return 当前不可变 Goal 状态；没有 Goal 时为 null
     */
    public GoalState goalState() {
        return goals == null ? null : goals.state();
    }

    /**
     * 先将目标状态保存到会话元数据，再通知界面更新；持久化失败通过 persistenceError 停止后续执行。
     *
     * @param next 待尝试合并的下一条增量
     * @throws IllegalStateException 目标事件持久写入失败，当前会话进入持久化故障
     */
    private void saveGoal(GoalState next) {
        ensureOpen();
        try {
            if (journal != null)
                journal.goal(next);
        } catch (IOException failure) {
            persistenceError();
            throw new IllegalStateException("Could not save goal state", failure);
        }
        engine.notice(ExecutionNotice.event(ExecutionNotice.Kind.GOAL, next.id(), "Goal",
                next.condition() + "\n" + next.reason(), next.status().name()));
    }

    /**
     * 检查用户输入是否为一条完整的 /goal 命令，供用户请求入口选择本地查询、清除或目标任务分支。
     *
     * @param prompt 用户输入的任务正文
     * @return 输入是本地识别的完整 /goal 命令时为 true
     */
    private static boolean goalCommand(String prompt) {
        if (prompt == null)
            return false;
        String text = prompt.strip();
        return text.startsWith("/goal") && (text.length() == 5 || Character.isWhitespace(text.charAt(5)));
    }

    /**
     * 就地处理 /goal 查看与清除，返回已完成结果；设置新条件返回 null 以继续按 FIFO 入队。条件非法时返回失败 Future。
     *
     * @param prompt 用户输入的任务正文
     * @return 本地处理结果 Future；需要入队执行或普通文本返回 null
     */
    private CompletableFuture<String> localGoalCommand(String prompt) {
        if (!goalCommand(prompt))
            return null;
        synchronized (historyMonitor) {
            try {
                ensureOpen();
                if (goals == null)
                    throw new IllegalStateException("Goal commands are not available in this session");
                String argument = prompt.strip().substring(5).strip();
                if (!argument.isEmpty()
                        && !java.util.Set.of("clear", "stop", "off", "reset", "none", "cancel").contains(argument)) {
                    GoalState.validCondition(argument);
                    return null;
                }
                if (!argument.isEmpty())
                    goals.clear();
                String answer = goals.describe();
                GoalState current = goals.state();
                if (current != null && current.active()) {
                    SessionUsage.Totals total = usage.snapshot().total();
                    answer += "\nReported session tokens since set/restore (all purposes/actors): "
                            + (total.inputTokens() - goalUsageStart.inputTokens() + total.outputTokens()
                                    - goalUsageStart.outputTokens())
                            + "\nMissing usage reports: "
                            + (total.missingRequests() - goalUsageStart.missingRequests());
                }
                engine.notice(
                        ExecutionNotice.event(ExecutionNotice.Kind.GOAL, current == null ? "" : current.id(), "Goal",
                                answer, current == null ? "NONE" : current.status().name()));
                return CompletableFuture.completedFuture(answer);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }

    /**
     * 会话启动前按组复制可展示资源名，供后续 Display 快照列出已装配能力。
     *
     * @param value 按组列出的可展示资源名称；方法逐组复制列表
     * @throws IllegalStateException 资源目录在运行时启动后才尝试修改
     */
    public void resources(Map<String, List<String>> value) {
        if (consumer != null || journal != null)
            throw new IllegalStateException("Session is already started");
        var copy = new java.util.LinkedHashMap<String, List<String>>();
        value.forEach((group, names) -> copy.put(group, List.copyOf(names)));
        resources = Map.copyOf(copy);
    }

    /**
     * 保存会话身份、状态、模型、用量、Todo、资源和队友信息的不可变展示资料，供后端生成状态快照。
     *
     * @param id 当前 Agent 或会话的稳定身份标识
     * @param title 用户选择的会话标题或当前界面条目的标题
     * @param model 用于请求与用量归属的模型标识
     * @param metadata 当前模型的窗口和输出限额资料
     * @param thinking 本 Agent 的思考设置或当前值来源
     * @param thinkingLabel 当前思考档位的界面名称
     * @param thinkingChoices 模型实际支持的思考档位列表
     * @param usage 模型报告或会话累计的 token 用量
     * @param context 上游已绑定来源、派工范围及取消条件的执行上下文
     * @param resources 按组保存的可展示资源名
     * @param team 共享的 Team 管理器，负责派工、报告和成员生命周期
     */
    public record Display(String id, String title, String model, ModelMetadata metadata, ThinkingSettings thinking,
            String thinkingLabel, List<ThinkingSettings> thinkingChoices, SessionUsage.Snapshot usage,
            ContextUsage context,
            Map<String, List<String>> resources, List<Map<String, Object>> team) {

        /**
         * 将队友列表、思考选项和各组资源名复制成不可变集合，形成可跨线程读取的会话展示快照。
         *
         * @param id 当前 Agent 或会话的稳定身份标识
         * @param title 用户选择的会话标题或当前界面条目的标题
         * @param model 用于请求与用量归属的模型标识
         * @param metadata 当前模型的窗口和输出限额资料
         * @param thinking 本 Agent 的思考设置或当前值来源
         * @param thinkingLabel 当前思考档位的界面名称
         * @param thinkingChoices 模型实际支持的思考档位列表
         * @param usage 模型报告或会话累计的 token 用量
         * @param context 上游已绑定来源、派工范围及取消条件的执行上下文
         * @param resources 按组保存的可展示资源名
         * @param team 共享的 Team 管理器，负责派工、报告和成员生命周期
         */
        public Display {
            team = List.copyOf(team);
            thinkingChoices = List.copyOf(thinkingChoices);
            var copy = new java.util.LinkedHashMap<String, List<String>>();
            resources.forEach((group, names) -> copy.put(group, List.copyOf(names)));
            resources = Map.copyOf(copy);
        }
    }

    /**
     * 在启动和安装日志前绑定运行组共享的用量账本、本 Agent 客户端及思考设置引用，登记当前运行时是否负责关闭账本。
     * 供正式、队友与辅助请求统一累计用量，并让各 Agent 独立选择思考设置。
     *
     * @param value 运行组共享的模型用量账本
     * @param owner 必等派工所属的原轮次 ID；telemetry 中表示是否由本会话关闭共享账本
     * @param client 用于请求模型的客户端，由装配方确定具体后端
     * @param thinking 本 Agent 的思考设置或当前值来源
     * @throws IllegalStateException 消费线程已经启动或日志已经安装
     * @throws NullPointerException value、client、thinking 为 null
     */
    public void telemetry(SessionUsage value, boolean owner, LlmClient client,
            java.util.concurrent.atomic.AtomicReference<ThinkingSettings> thinking) {
        if (consumer != null || journal != null)
            throw new IllegalStateException("Session is already started");
        usage = java.util.Objects.requireNonNull(value);
        ownsUsage = owner;
        profile = java.util.Objects.requireNonNull(client);
        requestThinking = java.util.Objects.requireNonNull(thinking);
    }

    /**
     * 会话启动前绑定 MCP 服务快照来源，供界面读取当前服务状态。
     *
     * @param supplier 为界面提供当前状态副本的函数
     * @throws IllegalStateException 运行时已经启动或关闭，服务快照入口已固定
     */
    public void services(java.util.function.Supplier<List<com.jadecode.mcp.McpManager.ServerSnapshot>> supplier) {
        if (consumer != null || journal != null)
            throw new IllegalStateException("Session is already started");
        mcpSnapshot = supplier == null ? List::of : supplier;
    }

    /**
     * 读取已绑定的 MCP 服务快照，尚未装配时返回空列表。
     *
     * @return 当前 MCP 服务快照；未装配或采集失败时为空列表
     */
    public List<com.jadecode.mcp.McpManager.ServerSnapshot> mcpServers() {
        try {
            return List.copyOf(mcpSnapshot.get());
        } catch (RuntimeException failure) {
            return List.of();
        }
    }

    /**
     * 读取当前模型、思考设置、会话元数据、用量、上下文、资源和队友状态，组装新的 Display 快照，
     * 供前端状态采集线程使用。
     *
     * @return 按当前已发布状态组装的不可变会话展示快照
     */
    public Display display() {
        ThinkingSettings setting = requestThinking.get();
        var metadata = journal == null ? null : journal.metadata();
        return new Display(sessionId(), metadata == null ? "" : metadata.title(), model,
                profile == null ? new ModelMetadata(ModelMetadata.Limits.unknown(), "") : profile.metadata(), setting,
                profile == null ? "default" : profile.thinkingLabel(setting),
                profile == null ? List.of(ThinkingSettings.DEFAULT) : profile.thinkingChoices(), usage.snapshot(),
                contextUsage, resources, teamSnapshot.get());
    }

    /**
     * 返回此 Agent 当前的思考设置，供新队友与一次性子任务创建时继承。
     *
     * @return 本 Agent 当前思考档位
     */
    public ThinkingSettings thinking() {
        return requestThinking.get();
    }

    /**
     * 检查模型支持所选思考档位，先保存会话元数据再发布新值，供后续模型请求读取。非法档位报错，
     * 保存失败进入会话持久化失败收尾。
     *
     * @param value 用户本次选择的模型思考档位
     * @throws IOException 当前会话没有持久日志或思考档位写入失败
     * @throws IllegalArgumentException 所选思考档位不在当前模型支持列表中
     */
    public void changeThinking(ThinkingSettings value) throws IOException {
        synchronized (historyMonitor) {
            ensureOpen();
            if (!display().thinkingChoices().contains(value))
                throw new IllegalArgumentException("This endpoint or model does not support that thinking setting");
            try {
                if (journal != null)
                    journal.thinking(value);
            } catch (IOException failure) {
                persistenceError();
                throw failure;
            }
            requestThinking.set(value);
        }
    }

    /**
     * 将新标题保存到当前会话元数据并更新展示快照，供会话清单与终端显示使用；保存失败按持久化故障处理。
     *
     * @param title 用户选择的会话标题或当前界面条目的标题
     * @throws IOException 当前会话没有持久日志或标题保存失败
     */
    public void rename(String title) throws IOException {
        String valid = com.jadecode.session.history.SessionMetadata.validTitle(title);
        synchronized (historyMonitor) {
            ensureOpen();
            if (journal == null)
                throw new IOException("Persistent saving is not enabled for this session");
            try {
                journal.rename(valid);
            } catch (IOException failure) {
                persistenceError();
                throw failure;
            }
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
    private Runnable closeDependents = () -> {
    };

    /**
     * 在启动前登记本会话额外拥有的资源清理回调，供 close 统一释放资源。
     *
     * @param cleanup 当前会话或轮次结束时调用的资源清理函数
     * @throws NullPointerException cleanup 为 null
     */
    public synchronized void onClose(Runnable cleanup) {
        java.util.Objects.requireNonNull(cleanup);
        Runnable previous = closeDependents;
        // 后装配的依赖先关闭；即使其中失败，也继续释放先前登记的资源，不能覆盖 Team/MCP 清理。
        closeDependents = () -> {
            try {
                cleanup.run();
            } finally {
                previous.run();
            }
        };
    }

    private java.util.function.Consumer<String> taskCleanup = ignored -> {
    };

    /**
     * 绑定轮次终态的资源清理回调，供 execute 的 finally 撤销该轮任务级沙箱资源；清理异常转成 Sandbox 告警。
     *
     * @param cleanup 当前会话或轮次结束时调用的资源清理函数
     */
    public void onTaskEnd(java.util.function.Consumer<String> cleanup) {
        var previous = taskCleanup;
        taskCleanup = run -> {
            try {
                previous.accept(run);
            } finally {
                cleanup.accept(run);
            }
        };
    }

    /**
     * 绑定真实 Todo 状态与模型名，供恢复日志和展示快照采集会话状态。
     *
     * @param manager 当前会话使用的 Todo 管理器
     * @param modelName 当前会话使用的模型名
     * @param blocked 判断 Team 等资源是否仍阻止会话切换的函数
     * @throws IllegalStateException 会话已启动、关闭或安装日志，历史状态接线已固定
     * @throws NullPointerException manager、modelName、blocked 为 null
     */
    public void historyState(TodoManager manager, String modelName, java.util.function.BooleanSupplier blocked) {
        if (consumer != null || journal != null || closed)
            throw new IllegalStateException("Session is already started or closed");
        todos = java.util.Objects.requireNonNull(manager);
        model = java.util.Objects.requireNonNull(modelName);
        switchBlocked = java.util.Objects.requireNonNull(blocked);
    }

    /**
     * 返回此会话绑定的模型名，供会话清单、历史创建和状态快照使用。
     *
     * @return 当前会话模型名
     */
    public String model() {
        return model;
    }

    /**
     * 返回当前持久会话日志的 ID；嵌入式运行尚未绑定日志时返回空串。
     *
     * @return 当前持久会话 ID；尚无日志时为空串
     */
    public String sessionId() {
        return journal == null ? "" : journal.metadata().id();
    }

    /** 路由包装只归本运行时，绝不保存认证；非持久化调用也已有稳定的临时身份。 */
    private com.jadecode.llm.SessionLlmClient modelRequests;
    private String backendIdentity = "";

    /**
     * 启动或恢复日志前绑定模型请求路由与后端恢复身份，供后续会话清单核对模型配置。
     *
     * @param client 用于请求模型的客户端，由装配方确定具体后端
     * @param identity 宿主工具目录提供的可信工具身份
     * @throws IllegalStateException 会话已经开始请求或恢复流程，模型路由接线已固定
     * @throws NullPointerException client、identity 为 null
     */
    public void modelRequests(com.jadecode.llm.SessionLlmClient client, String identity) {
        if (consumer != null || journal != null || closed || modelRequests != null)
            throw new IllegalStateException("Model requests are already bound or the session has started");
        modelRequests = java.util.Objects.requireNonNull(client);
        backendIdentity = java.util.Objects.requireNonNull(identity);
    }

    /**
     * 返回会话清单使用的后端身份，供历史恢复比较运行环境。
     *
     * @return 恢复检查使用的供应商、端点与模型身份文本
     */
    public String backendIdentity() {
        return backendIdentity;
    }

    /** 关闭依赖失败时保留失败事实，阻止服务提前释放工作区。 */
    private volatile boolean cleanupFailed;
    private long executionTokenBudget;

    /**
     * 在启动前设置每轮累计 token 软上限，正数表示限制，默认 0 表示不限；上限由共享用量账本在本轮执行中检查。
     *
     * @param tokens 每轮累计 token 用量软上限
     * @throws IllegalArgumentException 额度不是正数或消费线程已经启动
     */
    public void executionTokenBudget(long tokens) {
        if (consumer != null || tokens < 1)
            throw new IllegalArgumentException("Set a positive budget before starting");
        executionTokenBudget = tokens;
    }

    /**
     * 检查关闭状态、消费线程退出及资源清理结果，供服务确认可释放工作区占用。
     *
     * @return 会话已关闭、依赖清理完成且消费线程退出时为 true
     */
    public boolean isStopped() {
        return closed && !cleanupFailed && (consumer == null || !consumer.isAlive());
    }

    /**
     * 标记当前 Active 取消并唤醒处理线程，由工具、子代理和主循环观察同一取消信号，失败收尾后继续领取后续用户请求。
     */
    public void cancelCurrent() {
        Active task = active;
        if (task == null)
            return;
        task.cancelled.set(true);
        taskCancellation.accept(task.context().id());
        inbox.wake();
        if (consumer != null)
            consumer.interrupt();
    }

    private java.util.function.Consumer<String> taskCancellation = ignored -> {
    };

    /**
     * 绑定当前轮次取消时的派工撤销回调，供顶层工厂清理该轮所属的 Team 工作。
     *
     * @param value 取消当前轮次时按 run ID 撤销派工的回调
     */
    public void onTaskCancel(java.util.function.Consumer<String> value) {
        taskCancellation = value;
    }

    /**
     * 在消费者启动前接管持久日志并恢复可供下一轮使用的会话状态。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>核对运行时尚未启动、未关闭且没有旧日志；涉及 OpenCode Go 时核对原后端身份一致。</li>
     * <li>恢复稳定模型会话 ID 与 Todo，用独立列表采用恢复历史，清除旧上下文用量锚点。</li>
     * <li>接管日志写者，恢复用量账本与最后一份 Goal 元数据，并加入历史恢复说明。</li>
     * <li>检查历史思考档位是否被当前模型支持；支持则采用，否则保留当前配置并发出说明。</li>
     * </ol>
     *
     * <p>本方法完成恢复准备。新的工作由后续 submit 登记，真实副作用依据当前工作区状态继续处理。</p>
     *
     * @param writer 当前会话接管的日志写者
     * @param restored 由持久日志恢复的历史、元数据、Todo 和告警
     * @throws IllegalArgumentException Go 历史的供应商、Messages 端点与模型身份不匹配
     * @throws IllegalStateException 运行时已经启动、关闭或持有日志
     * @throws NullPointerException writer 为 null
     */
    public synchronized void restore(SessionJournal writer, RestoredSession restored) {
        if (consumer != null || closed || journal != null)
            throw new IllegalStateException("Cannot install history into a running session");
        String previousBackend = restored.metadata().backendIdentity();
        // Anthropic-compatible 允许同协议恢复；涉及 Go 时必须证明来源一致，不能把旧签名/工具历史交给另一个后端。
        // 先检查再改变内存历史或采用 writer，失败时宿主仍能关闭目标日志并保留旧会话。
        if ((backendIdentity.startsWith("opencode-go:") || previousBackend.startsWith("opencode-go:"))
                && !backendIdentity.equals(previousBackend))
            throw new IllegalArgumentException(
                    "OpenCode Go history requires the same provider, Messages endpoint and model; start a new session for a different backend");
        if (modelRequests != null)
            modelRequests.restoreIdentity(restored.metadata().id());
        if (todos != null)
            todos.restore(restored.todos());
        history = new ArrayList<>(restored.history());
        engine.invalidateContext();
        if (!restored.entries().isEmpty())
            resumeContext = "\nRestored conversation: previous background processes, teammates, inbox and approval grants were not restored."
                    + " Workspace files and persistent tasks and memory reflect current disk state, not a rollback."
                    + " Do not replay old tool calls to reconstruct history. An unrecorded result does not prove the operation did not execute."
                    + " Runtime artifacts are stored under the user data directory. Use current artifact paths; shell commands run in the project.";
        journal = java.util.Objects.requireNonNull(writer);
        usage.restore(writer, restored.entries());
        if (goals != null) {
            // 只恢复最后的目标元数据；不恢复旧审批/线程，不在恢复时自动 submit。
            GoalState saved = null;
            for (JournalEntry entry : restored.entries())
                if (entry.kind() == JournalEntry.Kind.GOAL)
                    saved = entry.goal();
            goals.restore(saved);
            goalUsageStart = usage.snapshot().total();
        }
        ThinkingSettings saved = restored.metadata().thinking();
        if (saved != null) {
            if (display().thinkingChoices().contains(saved))
                requestThinking.set(saved);
            else
                emit("Warning",
                        "The current model does not support the restored session's thinking setting; using the current configuration default.",
                        true);
        }
    }

    /**
     * 核对会话空闲、队列和后台与队友状态后，在 Inbox 锁内设置切换标记，使会话进入可更换状态。条件不满足返回 false，
     * 供宿主保留当前会话。
     *
     * @return 会话满足空闲条件并成功设置切换标记时为 true
     */
    public boolean freezeForSwitch() {
        if (switchBlocked.getAsBoolean() || background != null && background.hasPending())
            return false;
        synchronized (inbox) {
            if (closed || switching || active != null || dispatching || !inbox.isEmpty())
                return false;
            switching = true;
            return true;
        }
    }

    /**
     * 清除会话切换标记并唤醒队列等待，供目标会话加载失败后恢复原会话接收请求。
     */
    public void unfreeze() {
        synchronized (inbox) {
            switching = false;
            inbox.wake();
        }
    }

    /**
     * 返回会话是否持有正在执行的大轮次；等待后台或队友结果期间也属于忙碌状态。
     *
     * @return 当前存在 Active 轮次时为 true，等待后台和队友仍属于该轮次
     */
    public boolean isBusy() {
        return active != null;
    }

    /**
     * 结合当前轮次、队列、后台命令和队友状态判断会话是否可按闲置回收，供常驻服务维护使用。
     *
     * @return 当前轮次、队列、后台与协作资源满足闲置回收条件时为 true
     */
    public boolean idleForReaping() {
        if (state != State.IDLE || switchBlocked.getAsBoolean() || background != null && background.hasPending())
            return false;
        synchronized (inbox) {
            return !closed && active == null && !dispatching && inbox.isEmpty();
        }
    }

    /**
     * 检查当前轮次是否还有已接纳且尚待采用结果的后台命令，供任务完成检查使用。
     *
     * @return 当前轮次仍有必等后台结果时为 true
     */
    public boolean hasPendingBackground() {
        Active task = active;
        return task != null && background != null && background.hasPending(task.context().id());
    }

    /**
     * 接收真实用户输入：空闲时走独立请求提交，忙时合并到用户缓冲；/goal 使用专门命令分支。
     * 返回该批输入的最终结果 Future，队列和状态错误由失败 Future 表达。
     *
     * @param prompt 用户输入的任务正文
     * @param approval 本轮人工审批通道；null 表示本轮没有人工通道
     * @return 当前独立请求或缓冲批次的最终结果 Future
     */
    public CompletableFuture<String> submitUser(String prompt, Approval approval) {
        // 设置不走忙时用户缓冲：整条命令应在当前任务之后开始，避免被拼到旧条件里。
        if (goalCommand(prompt))
            return submit(prompt, approval);
        if (!isBusy())
            return submit(prompt, approval);
        if (prompt == null || prompt.isBlank())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Empty prompt"));
        var context = ExecutionContext.create(ExecutionContext.Source.USER, approval, () -> closed);
        CompletableFuture<String> result;
        synchronized (inbox) {
            result = closed || switching || persistenceFailed ? null : inbox.buffer(prompt, context);
            publishBuffer();
        }
        return result == null
                ? CompletableFuture.failedFuture(new RejectedExecutionException("Session inbox is full or closed"))
                : result;
    }

    /**
     * 将待处理用户缓冲改为本轮可插入消息，唤醒消费线程在安全边界采用；返回缓冲状态是否变化。
     *
     * @return 现有用户缓冲首次成功升级为插入消息时为 true
     */
    public boolean promoteUserBuffer() {
        synchronized (inbox) {
            boolean changed = !closed && !switching && !persistenceFailed && inbox.promote();
            publishBuffer();
            return changed;
        }
    }

    /**
     * 从 Inbox 读取当前用户缓冲的文字摘要与 QUEUED 或 INSERTION 状态，发送 BUFFER 执行事实供前端更新。
     */
    private void publishBuffer() {
        var view = inbox.bufferedView();
        engine.notice(ExecutionNotice.event(ExecutionNotice.Kind.BUFFER, view == null ? "" : view.id(), "",
                view == null ? "" : view.summary(), view != null && view.allowInsertion() ? "INSERTION" : "QUEUED"));
    }

    /**
     * 保存会话新状态并通知执行观察器和状态快照，使运行、等待、失败及关闭状态同步到界面。
     *
     * @param next 待尝试合并的下一条增量
     */
    private void changeState(State next) {
        state = next;
        engine.notice(ExecutionNotice.event(ExecutionNotice.Kind.SESSION, "", "", "", next.name()));
    }

    /**
     * 将后台、队友或用户补充作为 Incoming 投递到 Inbox，供运行轮次在安全边界采用；空闲时暂存。入队成功返回 true，
     * 关闭、切换或容量不足返回 false。
     *
     * @param incoming 后台、队友或用户补充投递的插入消息
     * @return 通知成功入队时为 true；关闭、切换或容量不足时为 false
     * @throws IllegalArgumentException 传入的是 assignment 消息，或消息未声明允许在安全边界插入
     */
    public boolean receive(SessionEvent.Incoming incoming) {
        if (incoming.kind().equals("assignment") || !incoming.allowInsertion())
            throw new IllegalArgumentException("Notifications must be insertions; assignments use receiveTask");
        synchronized (inbox) {
            return !closed && !switching && !persistenceFailed
                    && inbox.offer(new SessionInbox.Entry(incoming, null));
        }
    }

    /**
     * 接收显式 Team 派工，为新轮次绑定已核验的 AssignmentScope 并排入独立请求队列。接纳成功返回整轮结果 Future，
     * 未接纳返回 null。
     *
     * @param incoming 后台、队友或用户补充投递的插入消息
     * @param context 上游已绑定来源、派工范围及取消条件的执行上下文
     * @return 已接纳派工的整轮 Future；未接纳时为 null
     * @throws IllegalArgumentException 派工上下文的来源或范围快照不合法
     */
    public CompletableFuture<String> receiveTask(SessionEvent.Incoming incoming, ExecutionContext context) {
        if (!incoming.kind().equals("assignment") || context.assignment() == null
                || !context.assignment().id().equals(incoming.assignmentId()))
            throw new IllegalArgumentException("Explicit assignments require a matching Harness identity");
        var result = new CompletableFuture<String>();
        var scoped = new ExecutionContext("run_" + java.util.UUID.randomUUID(), context.source(), context.approval(),
                () -> closed, context.goal(), context.assignment(), null, "");
        synchronized (inbox) {
            if (closed || switching || persistenceFailed || !inbox.offer(new SessionInbox.Entry(
                    new SessionEvent.Request(incoming.render(), scoped), result)))
                return null;
        }
        return result;
    }

    /**
     * 接收本会话的执行器、可选记忆维护者、可选后台服务及消息出口，建立空历史与用量账本，并把后台完成回调接入 Inbox。
     * 会话取得这些组件的关闭责任，start 负责启动消费线程。
     *
     * @param engine 负责一次模型请求、工具批次和压缩的执行器
     * @param memory 当前会话的长期记忆入口，负责参考、候选笔记和提取作业
     * @param background 会话共用的后台命令管理器，负责编号、交付和停止
     * @param messages 本次读取或采用的模型消息列表
     * @throws NullPointerException engine、messages 为 null
     */
    public SessionRuntime(AgentExecutor engine, MemoryManager memory, BackgroundManager background,
            Consumer<com.jadecode.execution.RuntimeMessage> messages) {
        this.engine = java.util.Objects.requireNonNull(engine);
        this.memory = memory;
        this.background = background;
        this.history = new ArrayList<>();
        this.messages = java.util.Objects.requireNonNull(messages);
        this.usage = new SessionUsage(text -> emit("Usage", text, true));
        if (background != null)
            background.onResult(this::backgroundCompleted);
    }

    /**
     * 将程序产生的消息标签、正文和错误标记组成 RuntimeMessage，交给会话消息出口展示。
     *
     * @param label 供终端显示的人类可读名称
     * @param text 本次处理的完整文本，按该入口执行编码、脱敏、显示或组装
     * @param error 本次执行或条目是否失败
     */
    private void emit(String label, String text, boolean error) {
        messages.accept(new com.jadecode.execution.RuntimeMessage("", label, text, error));
    }

    /**
     * 将后台完成事实编码为 Incoming，放入后台结果专用槽；会话可用而入队失败时发送告警。正式结果由消费线程采用到历史。
     *
     * @param result 后台执行完成后交付的事实，含原轮次、退出码与有界输出
     */
    private void backgroundCompleted(Completion result) {
        var incoming = new SessionEvent.Incoming(result.id(), "background", "session", result.owner(), null,
                "background", notification(result), null, true);
        // 后台拥有独立预留槽；原 run 只用于记录来源，不限制采用轮次。
        if (!receive(incoming) && !closed && !persistenceFailed)
            emit("Background", "Completion could not be queued: " + result.id(), true);
    }

    /**
     * 首次调用先启动记忆维护，再创建唯一的会话消费线程。消费线程按 Inbox 请求执行任务，无独立请求时等待变化；
     * 重复调用复用已有线程，已关闭时抛出 IllegalStateException。
     *
     * @throws IllegalStateException 会话已经关闭
     */
    public synchronized void start() {
        if (closed)
            throw new IllegalStateException("Session is closed");
        if (consumer != null)
            return;
        if (memory != null)
            memory.start();
        consumer = new Thread(this::consume, "jade-session-consumer");
        consumer.setDaemon(true);
        consumer.start();
    }

    /**
     * 为用户新任务创建执行上下文和结果 Future，排入独立请求队列。
     *
     * <p>先处理可就地完成的 Goal 命令，普通请求校验非空文本，再创建 USER 来源上下文并保存人工审批通道。
     * 在 Inbox 锁内完成接纳检查与入队，消费线程按顺序执行；恢复完成后的准备态可先入队再由宿主启动线程。</p>
     *
     * @param prompt 用户任务原文
     * @param approval 人工审批通道，null 表示本次任务没有人工通道
     * @return 整轮最终回答的 Future；空文本、队列满或会话不可用时以异常完成。停止实际轮次使用 cancelCurrent
     */
    public CompletableFuture<String> submit(String prompt, Approval approval) {
        CompletableFuture<String> local = localGoalCommand(prompt);
        if (local != null)
            return local;
        CompletableFuture<String> result = new CompletableFuture<>();
        if (prompt == null || prompt.isBlank()) {
            result.completeExceptionally(new IllegalArgumentException("Prompt must not be blank"));
            return result;
        }
        ExecutionContext context = ExecutionContext.create(ExecutionContext.Source.USER, approval, () -> closed);
        SessionEvent.Request request = new SessionEvent.Request(prompt, context);
        synchronized (inbox) {
            if (closed || switching || persistenceFailed || !inbox.offer(new SessionInbox.Entry(request, result)))
                result.completeExceptionally(
                        new RejectedExecutionException("Session inbox is full, switching or unavailable"));
        }
        return result;
    }

    /**
     * 返回当前会话的运行、等待、失败或关闭状态快照。
     *
     * @return 此刻的会话状态
     */
    public State state() {
        return state;
    }

    /**
     * 按 FIFO 领取独立请求并同步执行完整轮次，结束后再取下一项。队列无请求或正在切换时等待 Inbox 版本变化；
     * 收到线程中断时恢复中断标记并退出。普通轮次错误由 execute 完成其失败 Future。
     */
    private void consume() {
        try {
            while (!closed && !persistenceFailed) {
                long observed = inbox.revision();
                SessionInbox.Entry entry;
                synchronized (inbox) {
                    entry = switching ? null : inbox.pollRequest();
                    dispatching = entry != null;
                }
                if (entry != null) {
                    try {
                        execute(entry, true);
                    } finally {
                        synchronized (inbox) {
                            dispatching = false;
                        }
                    }
                    continue;
                }
                inbox.awaitChange(observed);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 为 Inbox 领取的独立请求建立 Active，并推进一个完整轮次。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>固定日志与历史证据起点，建立取消和计时状态，发布 RUNNING 与 TURN_START。</li>
     * <li>保存 TURN_STARTED，触发用户提交 Hook，并按普通任务或 Goal 条件采用请求消息。</li>
     * <li>提取本轮目标，绑定记忆人类证据，准备 system 与参考，再采用停放通知并调用 runTask。</li>
     * <li>模型、执行或中断异常交给 fail；finally 执行任务资源清理，解除 Active，并在会话可继续时回到 IDLE。</li>
     * </ol>
     *
     * @param entry 消费线程已经领取的请求及其最终结果 Future
     * @param append 是否先把领取的请求追加到历史；false 表示已由调用方提供
     */
    private void execute(SessionInbox.Entry entry, boolean append) {
        Active task = new Active(entry, engine::notice);
        task.memoryStart = journal == null ? 0 : journal.sequence();
        task.memoryHistoryStart = append ? history.size() : Math.max(0, history.size() - 1);
        active = task;
        if (ownsUsage && executionTokenBudget > 0)
            usage.limitAdditionalTokens(executionTokenBudget);
        changeState(State.RUNNING);
        // 实时计数和最终耗时共用 Active 的同一起点，不累加工具或模型的局部耗时。
        engine.notice(ExecutionNotice.event(ExecutionNotice.Kind.TURN_START, task.context().id(), "", "", "RUNNING")
                .started(task.timer.startedNanos()));
        synchronized (inbox) {
            publishBuffer();
        }
        try {
            ensureOpen();
            // 在首次模型请求前保存轮次开始；后续失败保留已采用历史，不回滚或重跑业务。
            record(JournalEntry.Kind.TURN_STARTED, List.of(), "turn");
            if (append) {
                if (task.isUserRequest()) {
                    engine.hooks().trigger(Hooks.USER_PROMPT_SUBMIT,
                            Map.of(Hooks.PARAM_QUERY, entry.request().prompt(), Hooks.PARAM_CONTEXT, task.context()));
                }
                boolean userRequest = task.isUserRequest();
                if (userRequest && goalCommand(entry.request().prompt())) {
                    synchronized (historyMonitor) {
                        ensureOpen();
                        if (goals == null)
                            throw new IllegalStateException("Goal commands are not available in this session");
                        String condition = entry.request().prompt().strip().substring(5).strip();
                        goals.set(condition);
                        goalUsageStart = usage.snapshot().total();
                        // 条件作为本轮真正任务交给模型，不让它把 /goal 当成要执行的 Shell 命令。
                        appendMessage(Message.userText(condition), "request");
                    }
                } else
                    apply(entry.request());
            }
            // 先记住用户请求，再加入后台通知；之后历史即使压缩，仍记着用户原本要做什么。
            task.goal = activeRequest(history);
            if (task.isUserRequest() && task.goal != null && journal == null)
                task.memoryHumans.add(task.goal);
            if (memory != null)
                memory.beginTask(sessionId(), task.context().id(), task.isUserRequest() ? task.goal : "");
            task.system = prepareTaskSystem();
            // 新工作开始后可采用以前停放的通知；它们不改变本轮执行身份。
            deliverIncoming(task);
            runTask(task);
        } catch (LlmException | RuntimeException failure) {
            if (task.cancelled.get() && !closed)
                Thread.interrupted();
            fail(task, failure);
        } catch (InterruptedException interrupted) {
            if (!task.cancelled.get() || closed)
                Thread.currentThread().interrupt();
            fail(task, new IllegalStateException("Session execution interrupted", interrupted));
        } finally {
            try {
                taskCleanup.accept(task.context().id());
            } catch (RuntimeException failed) {
                emit("Sandbox", "Task resource cleanup did not finish", true);
            }
            active = null;
            // 轮次取消不能让消费者永久退出；关闭会话仍保留中断状态。
            if (task.cancelled.get() && !closed)
                Thread.interrupted();
            if (!closed && !persistenceFailed)
                changeState(State.IDLE);
        }
    }

    /**
     * 先校验历史中的工具调用与结果配对。启用压缩或记忆时从历史提取当前用户请求，固定为本轮目标；
     * 两者均未启用时返回 null。历史不完整或请求提取失败时抛出 IllegalArgumentException。
     *
     * @param messages 本次读取或采用的模型消息列表
     * @return 本轮用户原文；压缩和记忆均关闭时为 null
     */
    private String activeRequest(List<Message> messages) {
        ensureOpen();
        // 先确认执行器仍可用，再检查协议；入口目标只在压缩或记忆需要时提取。
        boolean needsRequest = engine.usesCompaction() || memory != null;
        ToolExchangeProtocol.validateHistory(messages);
        return needsRequest ? ContextCompactor.extractActiveRequest(messages) : null;
    }

    /**
     * 组合基础 system 与恢复上下文；记忆启用时追加稳定参考规则，
     * 再把当前全局和工作区参考作为独立记忆消息追加历史并落盘。返回本轮后续请求复用的 system，
     * 日志写入失败进入会话失败路径。
     *
     * @return 本轮循环复用的稳定 system，参考正文另以消息采用
     */
    private String prepareTaskSystem() {
        ensureOpen();
        String base = engine.system() + resumeContext;
        if (memory == null)
            return base;
        String system = memory.prepareSystem(base);
        String reference = memory.reference();
        // 来源存在内部字段，不靠正文前缀判断；只追加，不替换上一轮已发出的内容。
        if (!reference.isEmpty())
            appendMessage(Message.memoryReference(reference), "memory_reference");
        return system;
    }

    /**
     * 驱动一个用户或派工轮次中的模型、工具与等待循环，直到该轮满足完成条件。
     *
     * <p>每次请求前采用通知并刷新展示，准备 Goal 条件，再取得并采用完整模型回复。
     * 包含工具时执行整批、配齐并保存结果，更新 Todo 提醒和记忆读取反馈，在完整工具往返后处理压缩并继续请求。</p>
     * <p>无工具回复后检查新增补充、本轮后台和队友交付、队友报告验收、Stop Hook 与 Goal。
     * 待交付工作使用 Inbox 等待；需要继续的理由写回当前历史。最后在 Inbox 锁内复查新增工作，
     * 满足结束条件后触发轮次 Hook，记录 TURN_FINISHED，登记记忆作业，发送 TURN_END，并完成请求及补充输入的 Future。
     * </p>
     *
     * @param task 本轮共享状态，包含目标、执行身份、计时和等待结果
     * @throws LlmException 模型请求或上下文压缩失败
     * @throws InterruptedException 等待结果时收到线程中断
     * @throws IllegalStateException 轮次取消、执行意外失败、Goal 无法继续或会话关闭
     */
    private void runTask(Active task) throws LlmException, InterruptedException {
        while (!closed) {
            if (task.context().isCancelled())
                throw new IllegalStateException("Current execution was cancelled");
            // 1. 请模型继续前，批量采用插入通知；旧轮次结果也可以进入当前上下文。
            deliverIncoming(task);
            flushNotices(task);
            String system = goalSystem(task);
            ModelReply reply = engine.request(history, task.goal, system, this::adoptHistory);
            if (task.context().isCancelled())
                throw new IllegalStateException("Current execution was cancelled");
            if (reply.calls().isEmpty()) {
                // 回答先展示一次，再记录显示截止；Memory 还会照常收尾，不能提前宣布业务完成。
                // 流式时间来自生产端 complete，非流式响应在这里输出，不再在多个结束分支重放。
                if (!reply.textDisplayed() && !reply.message().extractText().isBlank())
                    emit("Reply", reply.message().extractText(), false);
                task.timer.answerCompleted(reply.textDisplayed() ? reply.displayCompletedNanos() : null);
            }
            apply(reply);
            if (memory != null)
                memory.observeReply(reply.message());
            if (!reply.calls().isEmpty()) {
                // 3. 执行这一批工具，把每个调用的结果一起写入历史；出错也要配齐调用和结果的 ID。
                ToolBatch batch = engine.executeTools(reply, task.context());
                task.toolCalls += reply.calls().size();
                // 取消只阻止后续行动，已开始的一批仍须配齐 tool_result 并落盘。
                if (task.cancelled.get() && !closed)
                    Thread.interrupted();
                // Todo 提醒属于父会话，不进入可供子代理复用的工具执行内核。
                task.rounds = batch.usedTodo() ? 0 : task.rounds + 1;
                if (batch.failure() == null && task.rounds >= 3) {
                    var results = new ArrayList<ContentBlock>(batch.results());
                    results.add(new com.jadecode.messages.TextBlock("<reminder>Update your todos.</reminder>"));
                    batch = new ToolBatch(batch.calls(), results, batch.usedTodo(), batch.compact(), batch.failure());
                    task.rounds = 0;
                }
                apply(batch);
                if (task.context().isCancelled())
                    throw new IllegalStateException("Current execution was cancelled");
                if (memory != null)
                    memory.observeTools(batch.calls(), Message.userBlocks(batch.results()));
                if (batch.failure() != null)
                    throw batch.failure();
                // 这一批 tool_use 都已有 tool_result，现在才能压缩，不会把调用与结果拆开。
                engine.compactAfterBatch(batch, history, task.goal, this::adoptHistory);
                continue;
            }

            // 等模型回答时也许又有命令跑完；加入结果后要再问一次，不能还没给它看就宣布结束。
            boolean incoming = deliverIncoming(task);
            if (incoming) {
                // 模型刚才的回答仍在 history 中，也先显示，不用下一条回答覆盖它。
                task.timer.resumeDisplay();
                flushNotices(task);
                continue;
            }
            if (background != null && background.hasPending(task.context().id())) {
                task.timer.resumeDisplay();
                changeState(State.WAITING_BACKGROUND);
                awaitResults(task);
                changeState(State.RUNNING);
                continue;
            }
            if (hasPendingTeam(task.context().id())) {
                task.timer.resumeDisplay();
                changeState(State.WAITING_TEAM);
                awaitResults(task);
                changeState(State.RUNNING);
                continue;
            }
            // 队友报告已交付后仍须 Leader 验收；继续原轮，不进入等待外部结果的休眠。
            String review = teamCompletion.apply(task.context().id());
            if (!review.isEmpty()) {
                task.timer.resumeDisplay();
                appendMessage(Message.userText("[Team acceptance required]\n" + review), "team_review_required");
                continue;
            }
            Continue continuation = engine.stop(history);
            if (continuation != null) {
                task.timer.resumeDisplay();
                apply(continuation);
                continue;
            }
            if (continueGoal(task))
                continue;
            // 4. 最后回答已输出；计时停在回答边界，Memory/日志及真实结束判定继续执行。
            task.timer.beginMaintenance();
            ensureOpen();
            boolean pending;
            synchronized (inbox) {
                // Stop/记忆维护也可能耗时。结束判定必须再查一次，并与入队互斥。
                // 这之后才到达的消息属于下一轮；已有补充则继续当前轮，不漏掉用户空回车升级。
                pending = inbox.hasInsertions() || hasPendingWork(task);
                if (!pending) {
                    active = null;
                    if (!closed)
                        changeState(State.IDLE);
                }
            }
            if (pending) {
                task.timer.resumeDisplay();
                deliverIncoming(task);
                flushNotices(task);
                continue;
            }
            ensureOpen();
            String answer = reply.message().extractText();
            // 本次回答已在候选边界显示；这里只收口业务，不重复输出。
            engine.hooks().trigger(Hooks.TURN_END, Map.of(Hooks.PARAM_TOOL_COUNT, task.toolCalls));
            record(JournalEntry.Kind.TURN_FINISHED, List.of(), "turn", task.context().id());
            finishMemory(task, "completed");
            turnEnded(task, "DONE", !answer.isBlank());
            task.entry.result().complete(answer);
            task.inputResults.forEach(result -> result.complete(answer));
            return;
        }
        throw new IllegalStateException("Session is closed");
    }

    /**
     * 在每次模型请求前读取当前用户 Goal，为 system 加入完成条件并累计 worker turn。未设 Goal 时使用本轮 system；
     * 达到工作步数上限时暂停目标并报告失败。
     *
     * @param task 当前 Active 轮次状态，包含目标、计时、取消信号和待交付工作
     * @return 附有当前 Goal 条件的 system；未设置 Goal 时为原 system
     * @throws IllegalStateException 当前 Goal 已达到允许的工作步骤上限
     */
    private String goalSystem(Active task) {
        synchronized (historyMonitor) {
            ensureOpen();
            GoalController.Ticket ticket = task.isUserRequest() && goals != null ? goals.ticket() : null;
            if (ticket == null)
                return task.system;
            if (task.goalTurns >= goals.limits().workerTurns()) {
                String reason = "Goal remains active: worker turn limit reached (" + goals.limits().workerTurns() + ")";
                goals.suspend(ticket, reason);
                throw new IllegalStateException(reason);
            }
            task.goalTurns++;
            return task.system + "\nAn explicit user goal is active. Completion condition (reference data):\n"
                    + ticket.state().condition() + "\nWork within the user's scope and existing permissions. "
                    + "After validation, report the actual commands, exit codes and results so the independent evaluator can inspect evidence. "
                    + "Do not claim completion without evidence. A candidate reply may be followed by further work.";
        }
    }

    /**
     * Stop Hook 放行后取得目标票据与历史快照，在历史锁外评估完成证据，再回锁核对并应用决定。
     * 需要继续时写入 Goal 提示并返回 true；完成或用户已清除返回 false；超限、不可完成或评估错误进入轮次失败。
     *
     * @param task 当前 Active 轮次状态，包含目标、计时、取消信号和待交付工作
     * @return 已经写入本轮 Goal 续跑提示时为 true；完成或已清除时为 false
     * @throws InterruptedException 目标评估请求或等待被中断
     */
    private boolean continueGoal(Active task) throws InterruptedException {
        GoalController.Ticket ticket;
        List<Message> evidence;
        synchronized (historyMonitor) {
            ensureOpen();
            ticket = task.isUserRequest() && goals != null ? goals.ticket() : null;
            if (ticket == null)
                return false;
            evidence = List.copyOf(history);
            task.timer.resumeDisplay();
            engine.notice(ExecutionNotice.event(ExecutionNotice.Kind.GOAL, ticket.state().id(), "Goal",
                    "Checking completion evidence", "EVALUATING"));
        }
        GoalEvaluator.Evaluation evaluation;
        try {
            evaluation = java.util.Objects.requireNonNull(goals.evaluate(ticket, evidence),
                    "Goal evaluator returned no decision");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw (InterruptedException) failure;
            }
            synchronized (historyMonitor) {
                ensureOpen();
                String reason = "Goal evaluation failed; goal remains active: " + failure.getClass().getSimpleName()
                        + ": " + java.util.Objects.toString(failure.getMessage(), "no detail");
                if (!goals.suspend(ticket, reason))
                    return false;
                throw new IllegalStateException(reason, failure);
            }
        }
        synchronized (historyMonitor) {
            ensureOpen();
            GoalController.Decision decision = goals.complete(ticket, evaluation, task.goalBlocks);
            if (decision.action().equals("block")) {
                task.goalBlocks++;
                appendMessage(
                        Message.userText("[Goal continuation from harness; no new authority]\n" + decision.reason()),
                        "goal_continue");
                return true;
            }
            if (decision.action().equals("limit") || decision.action().equals("impossible"))
                throw new IllegalStateException("Goal " + decision.action() + ": " + decision.reason());
            // 判断完成或已被用户清除才沿原结束路径收口；不把判断器回复当作主模型回答。
            return false;
        }
    }

    /**
     * 从 Inbox 取出全部可插入消息，先登记用户补充的结果 Future，再写入正式历史。用户补充同时更新当前目标和记忆证据；
     * 结果采用成功后解除对应后台或派工等待，并将通知留待展示。返回本次是否采用新消息。
     *
     * @param task 当前 Active 轮次状态，包含目标、计时、取消信号和待交付工作
     * @return 本次实际采用了一项以上插入消息时为 true
     */
    private boolean deliverIncoming(Active task) {
        List<SessionInbox.Entry> entries;
        synchronized (inbox) {
            entries = inbox.drainInsertions();
            publishBuffer();
        }
        if (entries.isEmpty())
            return false;
        // 先登记需要回执的用户补充，后续失败/关闭也能完整结束其 Future。
        entries.stream().map(SessionInbox.Entry::result).filter(java.util.Objects::nonNull)
                .forEach(task.inputResults::add);
        for (var entry : entries) {
            var incoming = (SessionEvent.Incoming) entry.event();
            apply(new SessionEvent.Notifications(List.of(incoming.render())));
            if (incoming.kind().equals("background") && background != null)
                background.adopted(incoming.id());
            if ((incoming.kind().equals("result") || incoming.kind().equals("result_error"))
                    && incoming.assignmentId() != null)
                forgetTeamResult(incoming.assignmentId());
            if (incoming.kind().equals("user")) {
                task.goal = (task.goal == null ? "" : task.goal + "\n\n") + incoming.summary();
                task.memoryHumans.add(incoming.summary());
                if (memory != null)
                    memory.addHumanInput(incoming.summary());
            }
            task.notices.add(new com.jadecode.execution.RuntimeMessage("",
                    incoming.kind().equals("background") ? "Background" : "Incoming",
                    incoming.kind().equals("background") ? "1 command result received" : incoming.summary(), false));
        }
        return true;
    }

    /**
     * 在下一次模型请求前依次展示暂存通知，已有阶段回答先展示；后台通知展示数量，
     * 完整的有界输出已由 deliverIncoming 写入模型历史。
     *
     * @param task 当前 Active 轮次状态，包含目标、计时、取消信号和待交付工作
     */
    private void flushNotices(Active task) {
        task.notices.forEach(messages);
        task.notices.clear();
    }

    /**
     * 将后台完成结果编码为 task_notification 文本，包含原轮次、命令、状态、退出码和有界输出；截断时附说明。
     * 标签属性使用 escape 转义，供后续安全边界作为通知采用。
     *
     * @param result 后台执行完成后交付的事实，含原轮次、退出码与有界输出
     * @return 带任务 ID、退出状态和转义输出的 task_notification 文本
     */
    private static String notification(Completion result) {
        String summary = result.output();
        if (result.truncated()) {
            summary += "\n[Output truncated; no full output file is retained.]";
        }
        return "<task_notification>\n<task_id>" + result.id() + "</task_id>\n<source_run>" + escape(result.owner())
                + "</source_run>\n<status>" + result.status() + "</status>\n<exit_code>" + result.exitCode()
                + "</exit_code>\n<command>" + escape(result.command()) + "</command>\n<summary>" + escape(summary)
                + "</summary>\n</task_notification>";
    }

    /**
     * 按顺序把 {@code &}、尖括号等标签特殊字符转成实体，返回可放入通知标签的文字；
     * 先处理 {@code &} 使后续生成实体保持原格式。
     *
     * @param value 需要放入通知标签的非 null 原文
     * @return 将 {@code &} 和尖括号转换为实体后的文本
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 按当前任务的工作记录检查后台与 Team 交付义务，返回本轮候选结束时是否仍有必等工作。
     *
     * @param task 当前 Active 轮次状态，包含目标、计时、取消信号和待交付工作
     * @return 当前轮次仍欠后台或派工交付时为 true
     */
    private boolean hasPendingWork(Active task) {
        String runId = task.context().id();
        return hasPendingTeam(runId) || background != null && background.hasPending(runId);
    }

    /**
     * 等待当前轮次结果或补充：先记录 Inbox 版本并尝试采用通知，再检查必等工作，仍需等待时调用 awaitChange。
     * 新版本使等待返回并重新检查，取消或关闭以中断结束等待。
     *
     * @param task 当前 Active 轮次状态，包含目标、计时、取消信号和待交付工作
     * @throws InterruptedException 当前轮次取消或会话关闭使等待被中断
     * @throws IllegalStateException 等待期间会话已关闭
     */
    private void awaitResults(Active task) throws InterruptedException {
        while (!closed) {
            long observed = inbox.revision();
            if (deliverIncoming(task) || !hasPendingWork(task))
                return;
            inbox.awaitChange(observed);
        }
        throw new IllegalStateException("Session is closed");
    }

    /**
     * 复制压缩器给出的消息列表，再将会话原列表内容替换为该副本并记录历史替换事件。
     * 先复制使候选与原列表相同时也能保留全部消息，调用方持有的 history 对象继续有效。
     *
     * @param messages 本次读取或采用的模型消息列表
     */
    private void adoptHistory(List<Message> messages) {
        apply(new SessionEvent.HistoryPrepared(messages));
        engine.notice(ExecutionNotice.event(ExecutionNotice.Kind.COMPACT, "", "",
                "Model context compacted; UI history preserved", ""));
    }

    /**
     * 采用完整模型回复：追加助手消息并同步落盘，再刷新上下文占用估算，供后续工具批执行或候选完成检查使用。
     *
     * @param reply 已取得的完整模型回复
     */
    private void apply(ModelReply reply) {
        appendMessage(reply.message(), "reply");
        refreshContextUsage();
    }

    /**
     * 将整批 tool_result 组成一个 USER 消息并采用到历史；批次携带意外异常时，调用方在保存完整配对结果后收尾失败。
     *
     * @param batch 本次按原顺序准备写出的事件批次
     */
    private void apply(ToolBatch batch) {
        appendMessage(Message.userBlocks(batch.results()), "tool_batch");
    }

    /**
     * 把 Stop Hook 的续跑提示追加到当前历史，供模型继续同一轮任务。
     *
     * @param continuation Stop Hook 给出的当前轮次续跑提示
     */
    private void apply(Continue continuation) {
        appendMessage(Message.userText(continuation.prompt()), "stop");
    }

    /**
     * 在历史锁内采用完整消息，除模型回复外刷新上下文占用，再同步记录 APPEND 事件。
     * 保存失败时保留已采用的内存消息并进入持久化故障处理。
     *
     * @param message 待编码、校验或投影的完整消息
     * @param reason 当前事件来源或失败的业务原因
     */
    private void appendMessage(Message message, String reason) {
        synchronized (historyMonitor) {
            ensureOpen();
            history.add(message);
            if (!reason.equals("reply"))
                refreshContextUsage();
            record(JournalEntry.Kind.APPEND, List.of(message), reason);
        }
    }

    /**
     * 结合执行器当前占用估算与模型窗口上限发布上下文用量；模型资料缺失时保留未知状态，供快照显示。
     */
    private void refreshContextUsage() {
        contextUsage = profile == null ? null
                : ContextUsage.projected(engine.contextEstimate(history), profile.metadata());
    }

    /**
     * 将当前采用事实连同 Active 轮次身份写入会话日志；无日志入口跳过文件写入，
     * 写入错误交给 persistenceError 停止后续执行。
     *
     * @param kind 要写入会话日志的正式事件种类
     * @param messages 本次读取或采用的模型消息列表
     * @param reason 当前事件来源或失败的业务原因
     */
    private void record(JournalEntry.Kind kind, List<Message> messages, String reason) {
        Active current = active;
        record(kind, messages, reason, current == null ? null : current.context().id());
    }

    /**
     * 按调用方明确传入的轮次 ID 保存正式事件，供 Active 已清除后的轮次终态仍关联原任务；
     * 写入失败进入统一持久化故障流程。
     *
     * @param kind 要写入会话日志的正式事件种类
     * @param messages 本次读取或采用的模型消息列表
     * @param reason 当前事件来源或失败的业务原因
     * @param runId 当前轮次 ID，用于关联取消、后台命令和任务收尾
     * @throws IllegalStateException 正式会话事件持久写入失败
     */
    private void record(JournalEntry.Kind kind, List<Message> messages, String reason, String runId) {
        synchronized (historyMonitor) {
            if (journal == null || closed)
                return;
            try {
                journal.append(kind, messages,
                        todos == null ? com.jadecode.util.Json.mapper().createArrayNode() : todos.snapshot(), reason,
                        runId);
            } catch (IOException failure) {
                persistenceError();
                throw new IllegalStateException(failure.getMessage(), failure);
            }
        }
    }

    /**
     * 按事件类型采用请求、历史替换和通知：Request 追加用户任务；HistoryPrepared 替换原 history 列表内容；
     * Notifications 优先与末尾普通 USER 消息合并，必要时新建消息。内容块和 Message 重新构建，并同步记录对应日志事件。
     *
     * @param event 入队的独立 Request 或插入消息 Incoming
     * @throws IllegalArgumentException 收到的会话事件类型不适用于此采用入口
     */
    private void apply(SessionEvent event) {
        synchronized (historyMonitor) {
            ensureOpen();
            contextUsage = null;
            switch (event) {
                case SessionEvent.Request request -> appendMessage(Message.userText(
                        request.context().source() == ExecutionContext.Source.SCHEDULED
                                ? "[Scheduled] " + request.prompt()
                                : request.prompt()),
                        "request");
                case SessionEvent.HistoryPrepared prepared -> {
                    // 事件已经复制列表，clear 不会把候选一起清空；日志同样保存“替换”而非追加。
                    history.clear();
                    history.addAll(prepared.messages());
                    // 真正摘要改变旧前缀后，tracker 比较投影会自动失效；只改新结果时保留已报告的锚点。
                    record(JournalEntry.Kind.REPLACE_HISTORY, history, "compaction");
                }
                case SessionEvent.Notifications notifications -> {
                    // 末条 USER 可能是请求或工具结果。新建 Message，旧日志记录和内容块不被原地修改。
                    List<ContentBlock> content = new ArrayList<>();
                    // 召回资料保持独立，避免通知合并后丢失来源或让提取误收旧记忆。
                    boolean merge = !history.isEmpty() && history.getLast().role() == Role.USER
                            && history.getLast().source() != Message.Source.MEMORY_REFERENCE;
                    if (merge)
                        content.addAll(history.getLast().content());
                    notifications.texts().forEach(text -> content.add(new TextBlock(text)));
                    Message message = Message.userBlocks(List.copyOf(content));
                    if (merge)
                        history.set(history.size() - 1, message);
                    else
                        history.add(message);
                    refreshContextUsage();
                    record(merge ? JournalEntry.Kind.REPLACE_LAST : JournalEntry.Kind.APPEND, List.of(message),
                            "notification");
                }
                case SessionEvent.Incoming incoming -> throw new IllegalArgumentException(
                        "Incoming must be queued first; history cannot be mutated across threads");
            }
        }
    }

    /**
     * 收尾失败轮次，撤销派工等待并记录 TURN_FAILED；符合条件时登记 failed 记忆作业，发送轮次失败通知，
     * 并使原请求和已采用用户补充的 Future 异常完成。持久化故障额外关闭执行资源。
     *
     * @param task 当前 Active 轮次状态，包含目标、计时、取消信号和待交付工作
     * @param failure 已经发生的 IO、模型或执行异常，供原任务收尾与回执使用
     */
    private void fail(Active task, Exception failure) {
        pendingTeam.entrySet().removeIf(entry -> entry.getValue().equals(task.context().id()));
        try {
            if (!persistenceFailed)
                record(JournalEntry.Kind.TURN_FAILED, List.of(), "turn", task.context().id());
        } catch (RuntimeException recording) {
            failure.addSuppressed(recording);
        }
        if (!closed && !persistenceFailed)
            finishMemory(task, "failed");
        if (persistenceFailed) {
            // 写失败也可能刚刚发生在失败记录本身；统一收尾，排队 future 不能永久等待。
            inbox.close();
            if (background != null)
                background.close();
        }
        task.entry.result().completeExceptionally(failure);
        turnEnded(task, "FAILED", false);
        task.inputResults.forEach(result -> result.completeExceptionally(failure));
        if (!closed)
            emit("Error", "" + task.context().id() + ": " + failure.getMessage(), true);
    }

    /**
     * 对用户轮次按 memoryRegistered 标记登记一次提取作业，使用开始至结束的日志事件区间及本轮用户补充；
     * 嵌入入口截取本轮历史快照。调用交给 MemoryManager 完成本地持久登记。
     *
     * @param task 当前 Active 轮次状态，包含目标、计时、取消信号和待交付工作
     * @param outcome 本轮 DONE、FAILED 等展示终态，或记忆作业的 completed、failed、interrupted 状态
     */
    private void finishMemory(Active task, String outcome) {
        if (memory == null || !task.isUserRequest() || task.memoryRegistered)
            return;
        synchronized (historyMonitor) {
            if (task.memoryRegistered)
                return;
            task.memoryRegistered = true;
            List<Message> evidence = journal == null
                    ? List.copyOf(history.subList(Math.min(task.memoryHistoryStart, history.size()), history.size()))
                    : List.of();
            memory.finishTask(sessionId(), task.context().id(), journal == null ? null : journal.eventsPath(),
                    task.memoryStart,
                    journal == null ? 0 : journal.sequence(), evidence, List.copyOf(task.memoryHumans), outcome);
        }
    }

    /**
     * 检查会话与执行器可用状态，已关闭或持久化故障时抛出 IllegalStateException，供当前步骤进入收尾。
     *
     * @throws IllegalStateException 会话、执行器或持久化状态已经不可继续
     */
    private void ensureOpen() {
        if (usage.failed() && !persistenceFailed && !closed)
            persistenceError();
        if (closed || persistenceFailed || switching)
            throw new IllegalStateException("Session is closed, switching or persistence failed");
    }

    /**
     * 记录持久化故障并停止执行器，拒绝后续新动作，保留已有内存历史供失败报告和排查。
     */
    private void persistenceError() {
        persistenceFailed = true;
        engine.close();
        changeState(State.PERSISTENCE_FAILED);
    }

    /**
     * 在轮次日志处理完成后发布最终耗时与终态，按是否有最终文字关联 Reply 卡片。计时包含模型、工具及必等工作，
     * 人工审批暂停与末尾维护由计时器单独处理。
     *
     * @param task 当前 Active 轮次状态，包含目标、计时、取消信号和待交付工作
     * @param outcome 本轮 DONE、FAILED 等展示终态，或记忆作业的 completed、failed、interrupted 状态
     * @param hasReply 本轮最终是否有可关联到 Reply 卡片的回答正文
     */
    private void turnEnded(Active task, String outcome, boolean hasReply) {
        engine.notice(ExecutionNotice
                .event(ExecutionNotice.Kind.TURN_END, task.context().id(), hasReply ? "Reply" : "", "", outcome)
                .timed(task.timer.elapsedNanos()));
    }

    /**
     * 保存同一轮次在模型请求、工具执行和等待期间共用的目标、上下文、计时、取消信号及交付状态，消费线程持续更新此对象。
     */
    private static final class Active {
        /** 外部线程发出本轮取消，执行上下文和主循环读取同一标记。 */
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        /** 消费者已经取出任务才创建，故不包含 Inbox 中等待别人做完的排队时间。 */
        final com.jadecode.execution.TurnTimer timer;
        /** 审批已经绑定本轮计时；任务 ID、来源、退出检查不变。 */
        final ExecutionContext execution;
        /** 最初取出的请求及结果 future，不随历史压缩变化。 */
        final SessionInbox.Entry entry;
        /** 已采用用户补充的结果句柄；通知无需再携带整份 Entry 或等待模型响应确认。 */
        final List<CompletableFuture<String>> inputResults = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** 展示通知暂存到合适位置，避免 Incoming 抢在刚生成的阶段回复前面显示。 */
        final List<com.jadecode.execution.RuntimeMessage> notices = new ArrayList<>();
        /** 当前大轮次模型提出的工具调用个数，包含被拒绝调用，不数子代理内部工具。 */
        int toolCalls;
        /** 记忆证据边界属于运行状态，不能由长期记忆反过来充当任务队列。 */
        long memoryStart;
        int memoryHistoryStart;
        volatile boolean memoryRegistered;
        final List<String> memoryHumans = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** 用户最初要做的事，不能被工具结果、后台通知或 Stop 提示换掉。 */
        String goal;
        /** 基础 system 与固定记忆边界；召回正文在历史里，续跑不重新拼接。 */
        String system;
        /** 自上次 todo 更新或提醒以来的工具批次数，不是工具个数或模型请求数。 */
        int rounds;
        /** 当前大轮次的循环额度计数，与历史总判断次数/Todo 提醒分别统计。 */
        int goalTurns, goalBlocks;

        /**
         * 沿用请求身份建立本轮计时器与取消信号，供执行器和主循环观察同一轮任务状态。
         *
         * @param entry 消费线程已经领取的请求及其最终结果 Future
         * @param notices 接收计时与轮次执行事实的观察出口
         */
        Active(SessionInbox.Entry entry, java.util.function.Consumer<ExecutionNotice> notices) {
            this.entry = entry;
            var origin = entry.request().context();
            timer = new com.jadecode.execution.TurnTimer(origin.id(), notices);
            execution = new ExecutionContext(origin.id(), origin.source(), timer.track(origin.approval()),
                    () -> origin.isCancelled() || cancelled.get(), origin.goal(), origin.assignment(), null, "");
        }

        /**
         * 沿用本轮 ID、来源、审批和取消条件，派生包含最新用户补充后任务目标的执行上下文。
         *
         * @return 沿用原执行身份、包含最新任务说明的上下文
         */
        ExecutionContext context() {
            return execution.withGoal(goal == null ? entry.request().prompt() : goal);
        }

        /**
         * 根据入口固定的来源和派工身份，判断此轮是否为真实用户任务，供 Goal 与记忆分支使用。
         *
         * @return 来源为 USER 且没有派工身份时为 true
         */
        boolean isUserRequest() {
            return execution.source() == ExecutionContext.Source.USER && execution.assignment() == null;
        }

    }

    /**
     * 关闭整个会话及其拥有的执行资源。
     *
     * <p>先设置关闭状态，当前轮次存在时登记 interrupted 记忆作业，再关闭维护者与附属资源。
     * 停止执行器和后台命令，关闭 Inbox，并以异常完成当前及已采用补充的 Future。
     * 最后中断消费线程并最多等待两秒，关闭自有用量账本和日志；重复关闭直接返回。</p>
     */
    @Override
    public void close() {
        synchronized (historyMonitor) {
            if (closed)
                return;
            closed = true;
            changeState(State.CLOSED);
        }
        Active interruptedTask = active;
        if (interruptedTask != null)
            finishMemory(interruptedTask, "interrupted");
        if (memory != null)
            memory.close();
        try {
            closeDependents.run();
        } catch (RuntimeException failure) {
            cleanupFailed = true;
            emit("Error", "Dependent session cleanup failed: " + failure.getMessage(), true);
        }
        // 先停止执行器和命令，再通知 future 的调用者；对方可能立即运行自己的代码，不能拖延停止工具。
        engine.close();
        if (background != null)
            background.close();
        inbox.close();
        Active running = active;
        if (running != null) {
            running.entry.result().completeExceptionally(new IllegalStateException("Session closed"));
            // 模型网络调用可能不响应中断；已采用的插入批次也不能一直留下 pending future。
            running.inputResults
                    .forEach(result -> result.completeExceptionally(new IllegalStateException("Session closed")));
        }
        if (consumer != null && consumer != Thread.currentThread()) {
            consumer.interrupt();
            try {
                consumer.join(2000);
                if (consumer.isAlive())
                    emit("Session", "Model/IO still finishing; new tools are disabled.", true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (historyMonitor) {
            if (ownsUsage)
                usage.close();
            if (journal != null) {
                try {
                    journal.close();
                } catch (IOException failure) {
                    emit("Error", "Failed to close the session journal: " + failure.getMessage(), true);
                }
            }
        }
    }
}
