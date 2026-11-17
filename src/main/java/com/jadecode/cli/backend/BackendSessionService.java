package com.jadecode.cli.backend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.SessionHost;
import com.jadecode.cli.backend.protocol.ProtocolError;
import com.jadecode.cli.backend.protocol.ProtocolException;
import com.jadecode.cli.backend.protocol.WireMessage;
import com.jadecode.config.JadeSettings;
import com.jadecode.config.WorkspacePaths;
import com.jadecode.execution.ExecutionObserver;
import com.jadecode.llm.ThinkingSettings;
import com.jadecode.safety.PermissionProfile;
import com.jadecode.session.SessionRuntime;
import com.jadecode.session.history.RestoredSession;
import com.jadecode.session.history.SessionMetadata;
import com.jadecode.util.Json;

/**
 * 将客户端命令串行转换为会话、权限和设置操作。
 *
 * <p>命令线程先核对会话 ID 与代次，再调用 SessionHost 和 SessionRuntime；用户请求返回提交回执，
 * 完整任务的 Future 完成后再回到命令线程发送 task.result 或 task.failed。配置写入由 PreferenceService 串行保存，
 * 模型和工具的运行事件通过当前代次的观察器、流与审批通道返回前端。</p>
 *
 * @see com.jadecode.cli.SessionHost
 * @see com.jadecode.session.SessionRuntime
 */
public final class BackendSessionService implements AutoCloseable {
    /**
     * 快照出口：完整快照（含历史）与只读状态刷新分开，避免每帧重发历史。
     */
    public interface SnapshotSink {
        /**
         * 发送指定代次的完整历史条目和运行状态，使用 snapshotId 标识这次同步，供客户端安装后确认 ready。
         *
         * @param snapshotId 本次完整历史快照的身份，供客户端安装后确认
         * @param generation 客户端看到的会话代次，切换会话后随新代次更新
         * @param entries 按展示顺序排列的历史条目
         * @param snapshot 本次读取或发送的完整状态快照
         */
        void full(String snapshotId, long generation,
                List<BackendHistoryProjector.Entry> entries, BackendSnapshot snapshot);

        /**
         * 发送当前运行状态快照，供前端更新权限、设置、任务和用量显示。
         *
         * @param snapshot 本次读取或发送的完整状态快照
         */
        void state(BackendSnapshot snapshot);
    }

    /**
     * 一次启动的意图，进入 server.bootstrap 供 Node 决定首轮/选择器行为。
     *
     * @param mode 本次启动意图的模式，如 new、resume 或 prompt
     * @param prompt 用户输入的任务正文
     * @param resumeTarget 启动时要求恢复的持久会话 ID
     * @param autoSubmit 客户端通过 ready 后是否自动登记首轮任务
     */
    public record Startup(String mode, String prompt, String resumeTarget, boolean autoSubmit) {
    }

    /**
     * full-access 二次确认的一次性票据。
     *
     * @param id 本次 Full Access 一次性确认票据 ID
     * @param revision 本次操作依据的版本号，用于读取后修改时核对状态
     * @param intent runtime=只切换本次运行；default=同时保存启动默认。一次票据只服务一种意图。
     * @param presented 该确认票据是否已有可核验的页面呈现
     * @param frameId 已提交到终端的呈现帧身份
     * @param terminalEpoch 终端布局代次，重建布局后更新
     * @param rawWatermark 呈现帧提交时已接收原始输入的水位
     * @param decodedWatermark 呈现帧提交时已解码输入的水位
     * @param rects 当前帧可操作按钮的坐标与选项列表
     * @param columns 呈现帧的终端列数
     * @param rows 呈现帧的终端行数
     */
    private record Ticket(String id, long revision, String intent, boolean presented, String frameId,
            long terminalEpoch, long rawWatermark, long decodedWatermark,
            List<BridgeApproval.Rect> rects, int columns, int rows) {
    }

    /**
     * 一个会话代次的全部前端协作对象；切换会话时整体替换。
     */
    private static final class Generation {
        final long generation;
        final DisplayClock clock;
        final BackendObserver observer;
        final BackendMessages messages;
        final WireContext wire;
        final BridgeApproval approval;
        volatile String sessionId = "";

        /**
         * 为新会话代次创建独立的协议身份、显示时钟、执行观察器、消息出口和人工审批通道。时钟变化经 owner 发送，
         * 状态变化向 owner 请求快照刷新。
         *
         * @param owner 负责接收该代次时钟和状态刷新通知的后端服务
         * @param generation 客户端看到的会话代次，切换会话后随新代次更新
         * @param wire 当前会话代次的事件出口与身份上下文
         */
        Generation(BackendSessionService owner, long generation, WireContext wire) {
            this.generation = generation;
            this.wire = new WireContext(wire.session());
            this.wire.update("", generation);
            wire = this.wire;
            WireContext generationWire = wire;
            this.clock = new DisplayClock((actor, tick) -> owner.sendClock(generationWire, actor, tick));
            this.observer = new BackendObserver(wire, clock, ignored -> owner.requestStateSnapshot());
            this.messages = new BackendMessages(wire);
            this.approval = new BridgeApproval(wire);
        }
    }

    private final WireContext wire;
    private final PreferenceService preferences;
    private final RuntimeFactory factory;
    private final SnapshotSink snapshots;
    private final Consumer<String> diagnostics;
    private final SessionHost host;
    private final ExecutorService serial = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jade-backend-commands");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Ticket> tickets = new HashMap<>();
    private final Map<String, String> blobs = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Startup startup;
    private final boolean persistent;
    private final Map<String, String> submissions = new LinkedHashMap<>();
    private volatile Generation current;
    private Generation pending;
    private RestoredSession restored = null;
    private long snapshotCounter;
    private volatile boolean startupRegistered;
    private boolean headless;

    /**
     * 装配阶段选择无人工通道；连接前设置，运行后不从客户端临时改变。
     *
     * @param value 是否使用没有人工审批通道的 headless 接线
     * @throws IllegalStateException 首轮 ready 已登记，当前接线已固定
     */
    public void headless(boolean value) {
        if (startupRegistered)
            throw new IllegalStateException("Session already started");
        headless = value;
    }

    /** 当前终端代次；resize/模式切换/暂停/编辑器交接后 Node 会递增，旧呈现证据随之作废。 */
    private long terminalEpoch;
    /** 收口阶段的真实失败；非 null 时进程退出码必须是 1。 */
    private volatile String shutdownFailure;
    private ObjectNode stoppedPayload;

    /**
     * 装配单个客户端使用的会话后端，绑定配置、运行时工厂、快照出口和启动意图。
     * 会话创建与消费线程启动分别由 openInitial 和 ready 完成。
     *
     * @param wire 当前会话代次的事件出口与身份上下文
     * @param preferences 负责串行保存白名单设置的配置服务
     * @param factory 根据会话代次创建完整运行时的工厂
     * @param snapshots 完整历史与运行状态快照的发送出口
     * @param diagnostics 接收容器、连接或会话诊断信息的出口
     * @param startup 服务打开会话时固定的启动意图
     */
    public BackendSessionService(WireContext wire, PreferenceService preferences, RuntimeFactory factory,
            SnapshotSink snapshots, Consumer<String> diagnostics, Startup startup) {
        this(wire, preferences, factory, snapshots, diagnostics, startup, false);
    }

    /**
     * 常驻服务拥有 Host，客户端断开只关闭出口；配置写者由服务统一关闭。
     *
     * @param wire 当前会话代次的事件出口与身份上下文
     * @param preferences 负责串行保存白名单设置的配置服务
     * @param factory 根据会话代次创建完整运行时的工厂
     * @param snapshots 完整历史与运行状态快照的发送出口
     * @param diagnostics 接收容器、连接或会话诊断信息的出口
     * @param startup 服务打开会话时固定的启动意图
     * @param persistent 是否由常驻服务持有会话与配置生命周期
     */
    public BackendSessionService(WireContext wire, PreferenceService preferences, RuntimeFactory factory,
            SnapshotSink snapshots, Consumer<String> diagnostics, Startup startup, boolean persistent) {
        this.persistent = persistent;
        this.wire = wire;
        this.preferences = preferences;
        this.factory = factory;
        this.snapshots = snapshots;
        this.diagnostics = diagnostics == null ? ignored -> {
        } : diagnostics;
        this.startup = startup;
        Path workspace = factory.workspace();
        this.host = new SessionHost(new WorkspacePaths(workspace, factory.paths()), this::createRuntime,
                warning -> diagnostics(warning));
    }

    /**
     * 读取配置服务最近成功保存并发布的版本，供 bootstrap、快照和补丁校验共用。
     *
     * @return 当前配置版本；Node 用它拒绝陈旧 patch。
     */
    public long configRevision() {
        return preferences.state().revision();
    }

    /**
     * 读取当前已保存的应用设置快照，供首屏与状态刷新采集。
     *
     * @return 当前已保存设置；bootstrap 与快照使用同一份。
     */
    public JadeSettings settings() {
        return preferences.state().settings();
    }

    /**
     * 由 SessionHost 在切换或新开会话时调用。建立该代次的观察器、审批和流出口，保存为待采用代次，再由工厂装配运行时。
     * 返回的会话等待宿主恢复历史和 ready 启动。
     *
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     * @return 已绑定新代次显示出口、等待宿主恢复与启动的运行时
     */
    private SessionRuntime createRuntime(long generation) {
        Generation candidate = new Generation(this, generation, wire);
        pending = candidate;
        ExecutionObserver observer = candidate.observer;
        return factory.create(candidate.messages::emit, () -> new BackendStream(candidate.wire, "leader"), observer);
    }

    /**
     * 打开首个会话（新会话或显式恢复目标）并输出完整快照。
     *
     * @throws IOException 目标会话不能恢复或装配失败
     */
    public void openInitial() throws IOException {
        String target = startup.mode().equals("resume") && !startup.resumeTarget().isBlank()
                ? startup.resumeTarget()
                : null;
        open(target);
    }

    /**
     * 打开 targetId 对应的历史会话；targetId 为 null 时创建新会话。采用工厂为新代次建立的观察器和审批通道，
     * 更新会话身份，清空提交去重表，并发送完整历史快照。打开失败以 IOException 交给调用方处理。
     *
     * @param targetId 待恢复的持久会话 ID；null 表示创建新会话
     * @throws IOException 宿主打开目标失败或工厂未提供对应代次
     */
    private void open(String targetId) throws IOException {
        SessionHost.Opened opened = host.open(targetId);
        Generation generation = pending;
        pending = null;
        if (generation == null || generation.generation != opened.generation())
            throw new IOException("The backend session factory did not produce the requested generation");
        generation.sessionId = host.current().sessionId();
        generation.wire.update(generation.sessionId, generation.generation);
        current = generation;
        submissions.clear();
        restored = opened.restored();
        wire.update(generation.sessionId, generation.generation);
        sendFullSnapshot();
    }

    /**
     * Node 已完整安装快照并确认 ready：登记首轮任务（若有）再启动消费者。
     *
     * <p>重复 ready 由协议层过滤；这里再加一道 {@link #startupRegistered} 幂等保护，
     * 保证重绘/fg 不会重放用户首轮 prompt。</p>
     */
    public void ready() {
        serial.execute(() -> {
            if (closed.get())
                return;
            Generation generation = current;
            if (generation == null) {
                sendDiagnostic("Node reported ready before a session was opened");
                return;
            }
            // 首轮 prompt 已由 service.open 保存到启动意图；ready 只确认快照安装完成。
            String prompt = startup.prompt() == null ? "" : startup.prompt();
            try {
                if (startup.autoSubmit() && !startupRegistered) {
                    startupRegistered = true;
                    String submissionId = "startup-" + generation.generation;
                    ObjectNode submitted = taskSubmitted(submissionId, prompt);
                    wire.session().sendEvent("task.submitted", submitted, wire.sessionId(), wire.generation());
                    CompletableFuture<String> result = host.startWithInitial(generation.generation, prompt,
                            headless ? null : generation.approval);
                    attachResult(result, submissionId);
                } else {
                    if (!startupRegistered) {
                        if (headless)
                            host.current().start();
                        else
                            host.start(generation.generation);
                    }
                    startupRegistered = true;
                }
                sendStateSnapshot();
            } catch (RuntimeException failure) {
                sendDiagnostic("Could not start the session: " + failure.getMessage());
            }
        });
    }

    /**
     * 处理一条已通过协议层校验的业务命令。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     */
    public void dispatch(WireMessage command) {
        serial.execute(() -> {
            if (closed.get())
                return;
            try {
                handle(command);
            } catch (ProtocolException rejected) {
                if (command.commandId() == null) {
                    sendDiagnostic("Task submit rejected: " + rejected.getMessage());
                    return;
                }
                wire.session().sendError(command.commandId(), rejected.error(), rejected.getMessage(),
                        wire.sessionId(), wire.generation());
            } catch (IOException | RuntimeException failure) {
                if (command.commandId() == null) {
                    sendDiagnostic("Task submit failed: " + failure.getMessage());
                    return;
                }
                wire.session().sendError(command.commandId(), ProtocolError.PERSISTENCE_FAILED,
                        failure.getMessage() == null ? failure.toString() : failure.getMessage(),
                        wire.sessionId(), wire.generation());
                sendDiagnostic("Command failed: " + failure.getMessage());
            }
        });
    }

    /**
     * 只读报告入口绑定当前会话和派工编号；正文走现有有界分块协议，前端不能传文件路径。
     * 不发模型请求，不改变报告核对状态。IO 失败沿用命令错误通道。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 会话身份或派工 ID 字段无效
     * @throws IOException 派工报告不存在或正文读取失败
     */
    private void teamReport(WireMessage command) throws ProtocolException, IOException {
        requireSession(command);
        String id = requiredText(command, "assignmentId");
        String body = host.current().teamReport(id);
        ObjectNode result = Json.mapper().createObjectNode();
        result.put("assignmentId", id);
        result.put("text", body);
        wire.session().sendOk(command.commandId(), result, wire.sessionId(), wire.generation());
    }

    /**
     * 按客户端命令类型选择对应的会话、设置、权限或审批处理分支。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>将提交、缓冲升级、列表查询、会话切换与元数据修改交给相应业务方法。</li>
     * <li>将设置补丁、权限档位、Full Access 呈现及审批输入交给各自的版本与证据检查入口。</li>
     * <li>停止操作取消当前轮次并清除待审批；关闭操作进入统一资源收尾。</li>
     * <li>常驻模式的 client.shutdown 发送分离事件并关闭控制连接；独立模式关闭整个后端。</li>
     * </ol>
     *
     * <p>输入已经完成连接级校验，各业务分支继续核对 sessionId、generation 与对应版本；未知类型抛出 UNSUPPORTED。</p>
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 命令类型、会话身份、输入字段或操作状态不符合对应契约
     * @throws IOException 会话清单、报告、补全或相关持久操作失败
     */
    private void handle(WireMessage command) throws ProtocolException, IOException {
        switch (command.type()) {
            case "session.submit" -> submit(command);
            case "session.promoteBuffer" -> promoteBuffer(command);
            case "session.new" -> switchSession(command, null);
            case "session.resume" -> switchSession(command, requiredText(command, "targetSessionId"));
            case "session.list" -> list(command);
            case "session.find" -> find(command);
            case "session.rename" -> rename(command);
            case "session.thinking" -> thinking(command);
            case "team.report" -> teamReport(command);
            case "workspace.complete" -> completePath(command);
            case "settings.patch" -> patchSettings(command);
            case "permission.select" -> selectPermission(command);
            case "permission.prepareFullAccess" -> prepareFullAccess(command);
            case "permission.presented" -> presentFullAccess(command);
            case "permission.cancelFullAccess" -> cancelFullAccess(command);
            case "permission.default" -> defaultPermission(command);
            case "approval.presented" -> presented(command);
            case "approval.answer" -> answer(command);
            case "terminal.suspend", "terminal.resume", "terminal.invalidate" -> terminalInvalidated(command);
            case "state.refresh" -> {
                sendStateSnapshot();
                wire.session().sendOk(command.commandId(), status("ok"), wire.sessionId(), wire.generation());
            }
            case "session.stop" -> {
                requireSession(command).cancelCurrent();
                current.approval.cancelPending();
                wire.session().sendOk(command.commandId(), status("stopping"), wire.sessionId(), wire.generation());
            }
            case "session.close" -> {
                requireSession(command);
                shutdown("session-closed");
            }
            case "client.shutdown" -> {
                if (persistent) {
                    wire.session().sendEvent("client.detached", status("detached"), wire.sessionId(),
                            wire.generation());
                    wire.session().flushAndClose();
                } else
                    shutdown(command.payload().path("reason").asText("user"));
            }
            default -> throw new ProtocolException(ProtocolError.UNSUPPORTED,
                    "Unsupported command type: " + command.type());
        }
    }

    /**
     * 把一条 session.submit 转换为当前会话的一次用户提交。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>校验命令的会话 ID 和代次，取出内联文本或消费已经组装好的 blob，并检查非空与服务文本大小。</li>
     * <li>按 clientSubmissionId 和 SHA-256 检查重复：相同 ID 与正文返回已有分派状态，正文冲突则报协议错误。</li>
     * <li>先发送 task.submitted 建立用户卡片，再调用 submitUser 取得本次独立请求或缓冲批次的结果 Future。</li>
     * <li>保存去重登记，为 Future 绑定最终结果回调，返回 dispatched 命令回执。</li>
     * </ol>
     *
     * <p>此方法在后端命令线程执行。模型工作由会话消费线程领取；Future 完成时才发送 task.result 或 task.failed。</p>
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 会话身份过期、文本为空或超限、提交 ID 冲突、去重表满或运行时拒绝接纳
     */
    private void submit(WireMessage command) throws ProtocolException {
        SessionRuntime runtime = requireSession(command);
        String text = command.payload().hasNonNull("textBlobRef")
                ? blob(command.payload().path("textBlobRef").asText())
                : command.payload().path("text").asText("");
        if (persistent && text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024)
            throw new ProtocolException(ProtocolError.PAYLOAD_TOO_LARGE, "Service prompt limit is 1 MiB");
        if (text.isBlank())
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Prompt must not be blank");
        String submissionId = command.payload().path("clientSubmissionId").asText("");
        if (submissionId.isBlank())
            submissionId = "sub-" + System.nanoTime();
        String fingerprint;
        try {
            fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        if (submissions.containsKey(submissionId)) {
            if (!submissions.get(submissionId).equals(fingerprint))
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Submission ID belongs to different text");
            if (command.commandId() != null)
                wire.session().sendOk(command.commandId(), status("already-dispatched"), wire.sessionId(),
                        wire.generation());
            return;
        }
        if (submissions.size() >= 4096)
            throw new ProtocolException(ProtocolError.OVERLOADED, "Submission limit reached; open a new session");
        ObjectNode submitted = taskSubmitted(submissionId, text);
        // 先登记提交意图，Node 才会在 TURN/正文事件之前看到用户卡；这不承诺已入队。
        wire.session().sendEvent("task.submitted", submitted, wire.sessionId(), wire.generation());
        CompletableFuture<String> result;
        try {
            result = runtime.submitUser(text, headless ? null : current.approval);
        } catch (RuntimeException rejected) {
            wire.session().sendEvent("task.failed", taskFailed(submissionId, rejected.getMessage()),
                    wire.sessionId(), wire.generation());
            throw new ProtocolException(ProtocolError.BUSY,
                    "The session did not accept the prompt: " + rejected.getMessage());
        }
        submissions.put(submissionId, fingerprint);
        attachResult(result, submissionId);
        if (command.commandId() != null)
            wire.session().sendOk(command.commandId(), status("dispatched"), wire.sessionId(), wire.generation());
    }

    /**
     * 终端补全在执行机器枚举工作区文件名；不读正文，不跟随链接到外部或敏感目录。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 命令所属会话已经改变
     * @throws IOException 补全前缀过长或目标目录解析、枚举失败
     */
    private void completePath(WireMessage command) throws ProtocolException, IOException {
        requireSession(command);
        String prefix = command.payload().path("prefix").asText();
        if (prefix.length() > 4096)
            throw new IOException("Completion prefix is too long");
        boolean at = prefix.startsWith("@");
        String query = at ? prefix.substring(1) : prefix;
        Path workspace = factory.workspace().toRealPath();
        Path full = workspace.resolve(query.isEmpty() ? "." : query).normalize();
        String leaf = query.endsWith("/") || query.isEmpty() ? "" : full.getFileName().toString();
        Path parent = (leaf.isEmpty() ? full : full.getParent()).toRealPath();
        var result = Json.mapper().createObjectNode().put("prefix", prefix);
        var items = result.putArray("items");
        if (parent.startsWith(workspace) && !com.jadecode.sandbox.PathProtection.sensitive(parent)) {
            try (var children = java.nio.file.Files.list(parent)) {
                for (Path child : children.sorted().limit(1000).toList()) {
                    String name = child.getFileName().toString();
                    if (!name.startsWith(leaf) || java.nio.file.Files.isSymbolicLink(child)
                            || com.jadecode.sandbox.PathProtection.sensitive(child))
                        continue;
                    int slash = query.lastIndexOf('/');
                    String value = (at ? "@" : "") + (slash < 0 ? "" : query.substring(0, slash + 1)) + name
                            + (java.nio.file.Files.isDirectory(child) ? "/" : "");
                    items.addObject().put("value", value).put("label", name);
                    if (items.size() >= 100)
                        break;
                }
            }
        }
        wire.session().sendOk(command.commandId(), result, wire.sessionId(), wire.generation());
    }

    /**
     * 将当前会话缓冲的用户消息升级为本轮补充，使消费线程在下一个安全边界采用它。
     * 回执中的 changed 表示本次是否改变了缓冲状态。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 命令所属会话或代次不匹配
     */
    private void promoteBuffer(WireMessage command) throws ProtocolException {
        SessionRuntime runtime = requireSession(command);
        boolean changed = runtime.promoteUserBuffer();
        ObjectNode payload = status("ok");
        payload.put("changed", changed);
        wire.session().sendOk(command.commandId(), payload, wire.sessionId(), wire.generation());
    }

    /**
     * 切换到指定历史会话，targetId 为 null 时新建会话。成功后关闭上一代审批通道并返回 switching；
     * 打开失败时清除候选代次，保留原会话并返回 BUSY。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @param targetId 待恢复的持久会话 ID；null 表示创建新会话
     * @throws ProtocolException 当前会话不匹配或目标会话无法打开
     */
    private void switchSession(WireMessage command, String targetId) throws ProtocolException {
        requireSession(command);
        Generation previous = current;
        try {
            open(targetId);
        } catch (IOException failure) {
            // 失败时保留旧会话与旧界面；旧代次的审批也不会被这次失败取消。
            pending = null;
            throw new ProtocolException(ProtocolError.BUSY, "Could not switch session: " + failure.getMessage(),
                    failure);
        }
        // 刚切换成功：上一代若还有等待中的审批，必须明确拒绝，不能让它挂到新会话上。
        previous.approval.close();
        wire.session().sendOk(command.commandId(), status("switching"), wire.sessionId(), wire.generation());
    }

    /**
     * 读取当前工作区中可切换的会话清单，将会话元数据编码为 sessions 数组并返回命令回执。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 命令所属会话或代次不匹配
     * @throws IOException 当前工作区会话清单读取失败
     */
    private void list(WireMessage command) throws ProtocolException, IOException {
        requireSession(command);
        ArrayNode items = Json.mapper().createArrayNode();
        for (SessionMetadata metadata : host.list())
            items.add(metadata(metadata));
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.set("sessions", items);
        wire.session().sendOk(command.commandId(), payload, wire.sessionId(), wire.generation());
    }

    /**
     * 按用户输入的会话 ID 或名称查询历史候选，将匹配的元数据作为 sessions 数组返回。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 会话身份或查询名称字段无效
     * @throws IOException 会话清单读取或名称查询失败
     */
    private void find(WireMessage command) throws ProtocolException, IOException {
        requireSession(command);
        String name = requiredText(command, "name");
        ArrayNode items = Json.mapper().createArrayNode();
        for (SessionMetadata metadata : host.find(name))
            items.add(metadata(metadata));
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.set("sessions", items);
        wire.session().sendOk(command.commandId(), payload, wire.sessionId(), wire.generation());
    }

    /**
     * 校验当前会话后保存新标题，刷新状态快照并返回成功回执。写入失败转换为 PERSISTENCE_FAILED 协议错误。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 会话身份、标题字段不合法或持久保存失败
     */
    private void rename(WireMessage command) throws ProtocolException {
        SessionRuntime runtime = requireSession(command);
        String title = requiredText(command, "title");
        try {
            host.rename(runtime.sessionId(), title);
        } catch (IOException failure) {
            throw new ProtocolException(ProtocolError.PERSISTENCE_FAILED, failure.getMessage(), failure);
        }
        sendStateSnapshot();
        wire.session().sendOk(command.commandId(), status("ok"), wire.sessionId(), wire.generation());
    }

    /**
     * 解析用户选择的思考档位并保存到当前会话，刷新快照后返回实际档位。非法档位返回 INVALID_MESSAGE，
     * 保存失败返回 PERSISTENCE_FAILED；新设置由后续模型请求读取。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 档位名称不合法、会话改变或保存失败
     */
    private void thinking(WireMessage command) throws ProtocolException {
        SessionRuntime runtime = requireSession(command);
        String value = requiredText(command, "value");
        ThinkingSettings next;
        try {
            next = ThinkingSettings.parse(value);
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, invalid.getMessage(), invalid);
        }
        try {
            host.thinking(runtime.sessionId(), next);
        } catch (IOException failure) {
            throw new ProtocolException(ProtocolError.PERSISTENCE_FAILED, failure.getMessage(), failure);
        }
        sendStateSnapshot();
        ObjectNode payload = status("ok");
        payload.put("thinking", next.value());
        wire.session().sendOk(command.commandId(), payload, wire.sessionId(), wire.generation());
    }

    /**
     * 读取期望配置版本，将 JSON 补丁转换为 Java 值后交给配置服务异步保存。先返回 accepted，
     * 再由保存回调发送 settings.saved 或 settings.failed，成功时刷新界面快照。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 会话身份不匹配或 patch 不是对象
     */
    private void patchSettings(WireMessage command) throws ProtocolException {
        requireSession(command);
        long expected = longValue(command.payload(), "expectedConfigRevision", -1);
        Map<String, Object> patch = new LinkedHashMap<>();
        JsonNode node = command.payload().path("patch");
        if (!node.isObject())
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "settings.patch needs an object patch");
        node.properties().forEach(entry -> patch.put(entry.getKey(), plain(entry.getValue())));
        wire.session().sendOk(command.commandId(), status("accepted"), wire.sessionId(), wire.generation());
        preferences.apply(patch, expected, new PreferenceService.Callback() {
            /**
             * 配置保存成功后发送 settings.saved，携带新配置版本、TUI 设置与界面状态，并请求刷新运行状态快照。
             *
             * @param settings 本次装配或保存的应用设置快照
             * @param revision 本次操作依据的版本号，用于读取后修改时核对状态
             * @param applied 本次交给配置保存服务的字段与值
             */
            @Override
            public void saved(JadeSettings settings, long revision, Map<String, Object> applied) {
                ObjectNode payload = Json.mapper().createObjectNode();
                payload.put("configRevision", Long.toString(revision));
                payload.set("tui", tuiJson(settings));
                payload.set("tuiSnapshot", snapshot().toJson());
                wire.session().sendEvent("settings.saved", payload, wire.sessionId(), wire.generation());
                requestStateSnapshot();
            }

            /**
             * 配置保存失败后发送 settings.failed，携带失败原因、当前配置版本和可重试标记，供前端保留并重试用户修改。
             *
             * @param failure 已经发生的 IO、模型或执行异常，供原任务收尾与回执使用
             * @param applied 本次交给配置保存服务的字段与值
             */
            @Override
            public void failed(IOException failure, Map<String, Object> applied) {
                ObjectNode payload = Json.mapper().createObjectNode();
                payload.put("message", failure.getMessage() == null ? failure.toString() : failure.getMessage());
                payload.put("configRevision", Long.toString(configRevision()));
                payload.put("retryable", true);
                wire.session().sendEvent("settings.failed", payload, wire.sessionId(), wire.generation());
            }
        });
    }

    /**
     * 解析权限档位并核对当前权限版本；选择 Full Access 时校验确认票据。更新本次运行的权限状态，回传新档位和版本，
     * 并刷新快照。受管工作区选择 Full Access 时返回协议错误。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 档位名称、权限版本、工作区模式或 Full Access 确认证据无效
     */
    private void selectPermission(WireMessage command) throws ProtocolException {
        SessionRuntime runtime = requireSession(command);
        String value = requiredText(command, "profile");
        PermissionProfile profile;
        try {
            profile = PermissionProfile.parse(value);
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, invalid.getMessage(), invalid);
        }
        var state = runtime.permissionState();
        long expected = longValue(command.payload(), "expectedPermissionRevision", -1);
        if (expected != state.snapshot().revision())
            throw new ProtocolException(ProtocolError.STALE_REVISION, "The permission revision changed");
        if (profile == PermissionProfile.FULL_ACCESS && factory.managed() != null)
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE,
                    "Managed workspaces require the Docker sandbox; Full Access is unavailable");
        if (profile == PermissionProfile.FULL_ACCESS)
            requireFullAccessEvidence(command, "runtime");
        state.select(profile);
        ObjectNode payload = status("ok");
        payload.put("profile", profile.value());
        payload.put("permissionRevision", Long.toString(state.snapshot().revision()));
        wire.session().sendOk(command.commandId(), payload, wire.sessionId(), wire.generation());
        sendStateSnapshot();
    }

    /**
     * 为当前权限版本创建一次 Full Access 确认票据，将票据 ID 和版本返回给权限页面。
     * 调用时的期望版本过期则返回 STALE_REVISION。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 会话不匹配或权限版本已经改变
     */
    private void prepareFullAccess(WireMessage command) throws ProtocolException {
        SessionRuntime runtime = requireSession(command);
        long expected = longValue(command.payload(), "expectedPermissionRevision", -1);
        long revision = runtime.permissionState().snapshot().revision();
        if (expected != revision)
            throw new ProtocolException(ProtocolError.STALE_REVISION, "The permission revision changed");
        String id = "fa-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        tickets.put(id, new Ticket(id, revision, "", false, "", -1, 0, 0, List.of(), 0, 0));
        ObjectNode payload = status("ok");
        payload.put("confirmationId", id);
        payload.put("permissionRevision", Long.toString(revision));
        wire.session().sendOk(command.commandId(), payload, wire.sessionId(), wire.generation());
    }

    /**
     * 登记 Full Access 确认页面的呈现结果：核对票据、权限版本和终端代次，检查按钮位于可见区域，
     * 并保存帧 ID、输入水位及本次确认用途，供后续作答校验。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 票据、用途、帧、终端代次或按钮可见区域不符合确认要求
     */
    private void presentFullAccess(WireMessage command) throws ProtocolException {
        requireSession(command);
        String id = requiredText(command, "confirmationId");
        Ticket ticket = tickets.get(id);
        if (ticket == null)
            throw new ProtocolException(ProtocolError.STALE_REVISION, "This confirmation is no longer valid");
        long revision = longValue(command.payload(), "revision", -1);
        if (revision != ticket.revision())
            throw new ProtocolException(ProtocolError.STALE_REVISION, "The permission revision changed");
        String intent = command.payload().path("intent").asText("");
        if (!intent.equals("runtime") && !intent.equals("default"))
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Full Access intent must be runtime or default");
        long epoch = longValue(command.payload(), "terminalEpoch", -1);
        if (epoch != terminalEpoch)
            throw new ProtocolException(ProtocolError.STALE_REVISION, "The terminal epoch changed");
        String frameId = command.payload().path("frameId").asText("");
        if (frameId.isBlank())
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Full Access needs a committed frame");
        List<BridgeApproval.Rect> rects = rects(command.payload().path("actionRects"));
        int columns = command.payload().path("columns").asInt(0);
        int rows = command.payload().path("rows").asInt(0);
        if (columns < 1 || rows < 1)
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "The viewport size is invalid");
        boolean confirmButton = false;
        for (BridgeApproval.Rect rect : rects) {
            if (rect.x() < 0 || rect.y() < 0 || rect.x() + rect.width() > columns || rect.y() + rect.height() > rows)
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE,
                        "A confirmation button is outside the viewport");
            if (rect.choice() == 1)
                confirmButton = true;
        }
        if (!confirmButton)
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE,
                    "The confirmation frame has no enabled confirm button");
        tickets.put(id, new Ticket(id, revision, intent, true, frameId, epoch,
                longValue(command.payload(), "rawReceiptWatermark", 0),
                longValue(command.payload(), "decodedWatermark", 0), rects, columns, rows));
        wire.session().sendOk(command.commandId(), status("ok"), wire.sessionId(), wire.generation());
    }

    /**
     * 按确认 ID 移除 Full Access 票据，并返回 cancelled 回执。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     */
    private void cancelFullAccess(WireMessage command) {
        String id = command.payload().path("confirmationId").asText("");
        tickets.remove(id);
        wire.session().sendOk(command.commandId(), status("cancelled"), wire.sessionId(), wire.generation());
    }

    /**
     * 解析并保存下次启动使用的默认权限档位；Full Access 先消费对应用途的确认票据。
     * 保存成功回传配置版本和默认档位并刷新快照，保存失败返回 PERSISTENCE_FAILED。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 会话身份、档位名称或默认权限用途的确认票据无效
     */
    private void defaultPermission(WireMessage command) throws ProtocolException {
        requireSession(command);
        String value = requiredText(command, "profile");
        PermissionProfile profile;
        try {
            profile = PermissionProfile.parse(value);
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, invalid.getMessage(), invalid);
        }
        long expected = longValue(command.payload(), "expectedConfigRevision", -1);
        if (profile == PermissionProfile.FULL_ACCESS)
            requireFullAccessEvidence(command, "default");
        preferences.saveDefaultPermission(profile, expected, new PreferenceService.Callback() {
            /**
             * 默认权限保存成功后，将新配置版本和默认档位作为原命令的结果返回，并请求状态快照刷新。
             *
             * @param settings 本次装配或保存的应用设置快照
             * @param revision 本次操作依据的版本号，用于读取后修改时核对状态
             * @param applied 本次交给配置保存服务的字段与值
             */
            @Override
            public void saved(JadeSettings settings, long revision, Map<String, Object> applied) {
                ObjectNode payload = status("saved");
                payload.put("configRevision", Long.toString(revision));
                payload.put("defaultProfile", settings.permissions().defaultProfile().value());
                wire.session().sendResult(command.commandId(), "command.result", payload,
                        wire.sessionId(), wire.generation());
                requestStateSnapshot();
            }

            /**
             * 默认权限保存失败时，将写入错误作为原命令的 PERSISTENCE_FAILED 回执返回。
             *
             * @param failure 已经发生的 IO、模型或执行异常，供原任务收尾与回执使用
             * @param applied 本次交给配置保存服务的字段与值
             */
            @Override
            public void failed(IOException failure, Map<String, Object> applied) {
                wire.session().sendError(command.commandId(), ProtocolError.PERSISTENCE_FAILED,
                        failure.getMessage(), wire.sessionId(), wire.generation());
            }
        });
    }

    /**
     * 核验并消费一次用于 Full Access 的明确确认。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>根据票据 ID 找到已呈现记录，检查用途与当前权限版本。</li>
     * <li>检查输入为新的 PRESS，且帧、终端代次、原始输入水位和解码序号属于这次呈现之后。</li>
     * <li>鼠标确认检查左键按下命中确认按钮；键盘确认检查 approval.confirm 动作。</li>
     * <li>核验全部通过后移除票据，使原确认只服务本次档位修改。</li>
     * </ol>
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @param requiredIntent 本次确认用途，runtime 表示临时切换，default 表示保存默认
     * @throws ProtocolException 确认用途、版本、帧或本次按键点击证据不匹配
     */
    private void requireFullAccessEvidence(WireMessage command, String requiredIntent) throws ProtocolException {
        SessionRuntime runtime = requireSession(command);
        String id = command.payload().path("fullAccessConfirmationId").asText("");
        Ticket ticket = tickets.get(id);
        if (ticket == null || !ticket.presented())
            throw new ProtocolException(ProtocolError.NOT_READY,
                    "Full Access must be confirmed on the permission page");
        if (!requiredIntent.equals(ticket.intent()))
            throw new ProtocolException(ProtocolError.STALE_REVISION,
                    "This confirmation was prepared for a different intent");
        if (ticket.revision() != runtime.permissionState().snapshot().revision())
            throw new ProtocolException(ProtocolError.STALE_REVISION, "The permission revision changed");
        JsonNode evidence = command.payload().path("evidence");
        if (!evidence.isObject())
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Full Access needs presentation evidence");
        if (!"PRESS".equals(evidence.path("phase").asText("")))
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Full Access needs a fresh key press");
        if (!ticket.frameId().equals(evidence.path("frameId").asText(""))
                || longValue(evidence, "terminalEpoch", -1) != ticket.terminalEpoch()
                || longValue(evidence, "rawStartReceipt", 0) <= ticket.rawWatermark()
                || longValue(evidence, "decodedSeq", 0) < ticket.decodedWatermark())
            throw new ProtocolException(ProtocolError.STALE_REVISION,
                    "That confirmation belongs to an older layout; confirm again");
        JsonNode mouse = evidence.path("mouse");
        if (mouse.isObject()) {
            int x = mouse.path("x").asInt(-1);
            int y = mouse.path("y").asInt(-1);
            boolean hit = "press".equals(mouse.path("type").asText())
                    && "left".equals(mouse.path("button").asText())
                    && ticket.rects().stream().anyMatch(rect -> rect.choice() == 1
                            && x >= rect.x() && x < rect.x() + rect.width()
                            && y >= rect.y() && y < rect.y() + rect.height());
            if (!hit)
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "The Full Access click missed its button");
        } else if (!"approval.confirm".equals(evidence.path("keyAction").asText())) {
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Full Access needs an explicit confirm action");
        }
        tickets.remove(id);
    }

    /**
     * 将前端提交的帧身份、按钮位置、输入水位和权限版本组装为审批呈现记录，交给当前审批通道保存。
     * 终端代次匹配后返回成功回执，供稍后的点击或按键核验使用。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 会话、终端代次或审批呈现记录不符合要求
     */
    private void presented(WireMessage command) throws ProtocolException {
        requireSession(command);
        String requestId = requiredText(command, "requestId");
        long epoch = longValue(command.payload(), "terminalEpoch", -1);
        if (epoch != terminalEpoch)
            throw new ProtocolException(ProtocolError.STALE_REVISION,
                    "The terminal epoch changed; present the approval again");
        BridgeApproval.Presentation presentation = new BridgeApproval.Presentation(
                command.payload().path("frameId").asText(""),
                epoch,
                command.payload().path("columns").asInt(0),
                command.payload().path("rows").asInt(0),
                rects(command.payload().path("actionRects")),
                command.payload().path("layoutDigest").asText(""),
                longValue(command.payload(), "rawReceiptWatermark", 0),
                longValue(command.payload(), "decodedWatermark", 0),
                longValue(command.payload(), "revision", -1),
                command.payload().path("runId").asText(""));
        current.approval.presented(requestId, presentation);
        wire.session().sendOk(command.commandId(), status("ok"), wire.sessionId(), wire.generation());
    }

    /**
     * 解析审批选项和输入证据，将帧、终端代次、输入序号及可选鼠标坐标交给当前审批通道核验。核验完成后返回命令回执，
     * 等待该审批的工具线程由审批通道唤醒。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 请求、选项或输入证据不符合当前审批记录
     */
    private void answer(WireMessage command) throws ProtocolException {
        requireSession(command);
        String requestId = requiredText(command, "requestId");
        int choice = command.payload().path("choice").asInt(-1);
        JsonNode evidence = command.payload().path("evidence");
        if (!evidence.isObject())
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Approval answers need input evidence");
        JsonNode mouseNode = evidence.path("mouse");
        BridgeApproval.MouseEvidence mouse = null;
        if (mouseNode.isObject()) {
            mouse = new BridgeApproval.MouseEvidence(mouseNode.path("type").asText(""),
                    mouseNode.path("button").asText(""), mouseNode.path("x").asInt(-1), mouseNode.path("y").asInt(-1));
        }
        BridgeApproval.InputEvidence input = new BridgeApproval.InputEvidence(
                evidence.path("phase").asText(""),
                longValue(evidence, "revision", -1),
                evidence.path("frameId").asText(""),
                longValue(evidence, "terminalEpoch", -1),
                longValue(evidence, "rawStartReceipt", -1),
                longValue(evidence, "rawEndReceipt", -1),
                longValue(evidence, "decodedSeq", -1),
                evidence.path("keyAction").asText(""),
                mouse);
        current.approval.answer(requestId, choice, input);
        wire.session().sendOk(command.commandId(), status("ok"), wire.sessionId(), wire.generation());
    }

    /**
     * resize/模式重建/暂停/编辑器交接：递增终端代次，作废审批呈现证据与 FullAccess 票据。
     *
     * <p>Node 上传的是它重建后的新 epoch；Java 只接受严格递增，旧帧/旧票据不能再用。审批申请
     * 本身仍 pending，下一帧重新呈现后才可确认。</p>
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @throws ProtocolException 会话身份无效或未提供新终端代次
     */
    private void terminalInvalidated(WireMessage command) throws ProtocolException {
        requireSession(command);
        long presentedEpoch = longValue(command.payload(), "terminalEpoch", -1);
        if (presentedEpoch < 0)
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "terminal.* needs the new terminalEpoch");
        terminalEpoch = Math.max(terminalEpoch, presentedEpoch);
        current.approval.invalidatePresented();
        tickets.clear();
        wire.session().sendOk(command.commandId(), status("ok"), wire.sessionId(), wire.generation());
    }

    /**
     * 当前终端代次；测试与诊断使用。
     *
     * @return 当前终端布局代次
     */
    public long terminalEpoch() {
        return terminalEpoch;
    }

    /**
     * 请求一次节流的状态刷新；观察器线程只投递请求，不等待渲染。
     */
    public void requestStateSnapshot() {
        if (closed.get())
            return;
        serial.execute(this::sendStateSnapshot);
    }

    /**
     * 会话已打开且后端运行中时，采集当前运行状态并发送状态快照；完整历史由 sendFullSnapshot 发送。
     */
    private void sendStateSnapshot() {
        if (closed.get() || current == null)
            return;
        snapshots.state(snapshot());
    }

    /**
     * 把恢复的会话记录投影成界面条目，为当前代次生成新的快照 ID，再连同运行状态发送完整快照。
     * 会话和恢复记录齐备后执行。
     */
    private void sendFullSnapshot() {
        Generation generation = current;
        if (generation == null || restored == null)
            return;
        List<BackendHistoryProjector.Entry> entries = BackendHistoryProjector.project(restored);
        String snapshotId = "snap-" + generation.generation + "-" + (++snapshotCounter);
        snapshots.full(snapshotId, generation.generation, entries, snapshot());
    }

    /**
     * 组装当前只读资料；历史条目不在其中，避免每帧重发。
     *
     * @return 采集当前运行事实与设置的状态快照
     */
    private BackendSnapshot snapshot() {
        Generation generation = current;
        JadeSettings settings = preferences.state().settings();
        SessionRuntime.Display display = host.hasCurrent() ? host.display() : null;
        return BackendSnapshot.capture(generation == null ? 0 : generation.generation, display,
                host.permissionSnapshot(), settings, preferences.state().revision(), host.mcpServers(),
                generation == null ? Map.of() : generation.observer.actors(),
                generation == null ? "" : generation.observer.bufferSummary(),
                generation == null ? "" : generation.observer.bufferState(),
                generation == null ? Map.of() : generation.observer.background(),
                generation == null ? Map.of() : generation.clock.all());
    }

    /**
     * 将指定 Agent 的轮次 ID、耗时、运行与暂停状态、展示停止状态和时钟版本编码为 display.clock 事件，
     * 经传入代次的出口发送。
     *
     * @param wire 当前会话代次的事件出口与身份上下文
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param tick 该 Agent 当前轮次的耗时、暂停及显示计时状态
     */
    private void sendClock(WireContext wire, String actor, DisplayClock.Tick tick) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("actor", actor);
        payload.put("runId", tick.runId());
        payload.put("elapsedMs", tick.elapsedMs());
        payload.put("running", tick.running());
        payload.put("paused", tick.paused());
        payload.put("displayStopped", tick.displayStopped());
        payload.put("clockRevision", Long.toString(tick.revision()));
        wire.session().sendEvent("display.clock", payload, wire.sessionId(), wire.generation());
    }

    /**
     * 构造 task.submitted 事件载荷，以提交 ID 派生用户卡片 ID，初始状态设为 SUBMITTING；null 文本转为空串。
     * 返回新 JSON 对象。
     *
     * @param submissionId 用户提交 ID，用于关联用户卡片和整轮结果
     * @param text 本次用户提交的完整文本；构造事件时 null 转为空串
     * @return 带 submissionId、用户卡片 ID、SUBMITTING 状态和文本的新载荷
     */
    private ObjectNode taskSubmitted(String submissionId, String text) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("submissionId", submissionId);
        payload.put("entryId", "task-" + submissionId);
        payload.put("state", "SUBMITTING");
        payload.put("text", text == null ? "" : text);
        return payload;
    }

    /**
     * 构造包含提交 ID 和失败说明的 task.failed 载荷；尚无轮次身份时 runId 使用空串，null 说明转为空串。
     * 返回新 JSON 对象。
     *
     * @param submissionId 用户提交 ID，用于关联用户卡片和整轮结果
     * @param message 需要返回给前端的失败或诊断说明；null 编码为空串
     * @return 带 submissionId 和失败说明的新载荷
     */
    private ObjectNode taskFailed(String submissionId, String message) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("submissionId", submissionId);
        payload.put("runId", "");
        payload.put("message", message == null ? "" : message);
        return payload;
    }

    /**
     * 任务 Future 完成只投递结果；共享 Future 的多次提交都会各自收到同一次最终回答。
     *
     * @param result 当前提交最终取得回答的 Future，回调转交命令串行线程发送结果
     * @param submissionId 用户提交 ID，用于关联用户卡片和整轮结果
     */
    private void attachResult(CompletableFuture<String> result, String submissionId) {
        result.whenComplete((text, failure) -> serial.execute(() -> {
            if (closed.get())
                return;
            ObjectNode payload = Json.mapper().createObjectNode();
            payload.put("submissionId", submissionId);
            payload.put("runId", "");
            if (failure != null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException
                        && failure.getCause() != null
                                ? failure.getCause()
                                : failure;
                payload.put("message", cause.getMessage() == null ? cause.toString() : cause.getMessage());
                wire.session().sendEvent("task.failed", payload, wire.sessionId(), wire.generation());
            } else {
                String value = text == null ? "" : text;
                payload.put("text", value);
                wire.session().sendEvent("task.result", payload, wire.sessionId(), wire.generation());
            }
            sendStateSnapshot();
        }));
    }

    /**
     * 核对命令携带的 sessionId 和 generation，返回当前可操作的 SessionRuntime。
     * 尚未打开、身份过期或会话已关闭分别转换成对应协议错误。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @return 与命令 sessionId、generation 匹配的可操作会话
     * @throws ProtocolException 尚无会话、命令身份或代次不匹配、宿主已关闭
     */
    private SessionRuntime requireSession(WireMessage command) throws ProtocolException {
        Generation generation = current;
        if (generation == null)
            throw new ProtocolException(ProtocolError.NOT_READY, "No session is open yet");
        // 会话命令必须带完整身份；缺失身份不能默认解释为“当前代次”。
        if (command.generation() == null || command.generation() != generation.generation)
            throw new ProtocolException(ProtocolError.STALE_GENERATION,
                    "This command belongs to an older session generation");
        if (command.sessionId() == null || !command.sessionId().equals(generation.sessionId))
            throw new ProtocolException(ProtocolError.STALE_GENERATION, "This command belongs to another session");
        try {
            return host.current();
        } catch (IllegalStateException closedSession) {
            throw new ProtocolException(ProtocolError.CLOSED, closedSession.getMessage(), closedSession);
        }
    }

    /**
     * 读取命令载荷中指定的非空白字符串字段并原样返回；字段缺失、类型错误或只有空白时返回 INVALID_MESSAGE 协议错误。
     *
     * @param command 已通过连接协议校验的业务命令，包含会话身份、代次及本次操作载荷
     * @param field 本次读取或写入的 JSON 字段名
     * @return 该字段的原始非空白字符串
     * @throws ProtocolException 指定字段缺失、类型不是字符串或内容为空白
     */
    private String requiredText(WireMessage command, String field) throws ProtocolException {
        JsonNode node = command.payload().path(field);
        if (!node.isTextual() || node.asText().isBlank())
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, field + " is required");
        return node.asText();
    }

    /**
     * 将指定 JSON 字段解析为 long，同时接受整数节点和十进制字符串；
     * 字段缺失、类型不匹配或字符串解析失败时返回 fallback。
     *
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param field 本次读取或写入的 JSON 字段名
     * @param fallback 字段缺失、类型不符或解析失败时返回的缺省值
     * @return 解析出的 long；缺失、类型不符或格式错误时为 fallback
     */
    private static long longValue(JsonNode payload, String field, long fallback) {
        JsonNode node = payload.path(field);
        if (node.isIntegralNumber())
            return node.asLong();
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * 创建仅包含 status 字段的命令状态载荷，供各处理分支添加业务字段后返回。
     *
     * @param value 命令回执中的业务状态，如 dispatched、ok 或 cancelled
     * @return 只含 status 字段的新 JSON 对象
     */
    private static ObjectNode status(String value) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("status", value);
        return payload;
    }

    /**
     * 将按钮区域数组转换为 Rect 列表，逐项读取位置、尺寸和选项编号；非数组输入返回空列表。
     * 区域有效性由具体呈现处理方法检查。
     *
     * @param node 当前读取或写入的 JSON 节点
     * @return 转换后的按钮矩形列表；非数组输入返回空列表
     */
    private static List<BridgeApproval.Rect> rects(JsonNode node) {
        if (!node.isArray())
            return List.of();
        List<BridgeApproval.Rect> rects = new ArrayList<>();
        for (JsonNode item : node) {
            rects.add(new BridgeApproval.Rect(item.path("x").asInt(0), item.path("y").asInt(0),
                    item.path("width").asInt(0), item.path("height").asInt(0), item.path("choice").asInt(-1)));
        }
        return rects;
    }

    /**
     * 将一条会话元数据转换为前端清单项，包含 ID、标题、更新时间、工作区和模型名。返回新 JSON 对象。
     *
     * @param metadata 当前会话或经验来源的持久元数据
     * @return 包含会话 ID、标题、更新时间、工作区与模型的清单项
     */
    private static ObjectNode metadata(SessionMetadata metadata) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("id", metadata.id());
        node.put("title", metadata.title());
        node.put("updatedAt", metadata.updatedAt());
        node.put("workspace", metadata.workspace());
        node.put("model", metadata.model());
        return node;
    }

    /**
     * 递归将 JSON 转成配置服务接收的 Java 值：字符串、布尔、整数、小数、列表或键值表；JSON null 和其他节点返回 null。
     * 容器值重新构建。
     *
     * @param node 当前读取或写入的 JSON 节点
     * @return 递归转换后的 Java 标量、列表或映射；JSON null 返回 null
     */
    private static Object plain(JsonNode node) {
        if (node.isTextual())
            return node.asText();
        if (node.isBoolean())
            return node.asBoolean();
        if (node.isIntegralNumber())
            return node.asLong();
        if (node.isFloatingPointNumber())
            return node.asDouble();
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(plain(item)));
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            node.properties().forEach(entry -> values.put(entry.getKey(), plain(entry.getValue())));
            return values;
        }
        return null;
    }

    /**
     * 供 settings.saved 事件携带完整 TUI 子快照，Node 无需再取整份配置。
     *
     * @param settings 本次装配或保存的应用设置快照
     * @return 当前设置的完整 TUI 子快照 JSON
     */
    private static ObjectNode tuiJson(JadeSettings settings) {
        return (ObjectNode) BackendSnapshot.capture(0, null, null, settings, 0, List.of(), Map.of(),
                "", "", Map.of(), Map.of()).toJson().get("tui");
    }

    /**
     * 已组装入站 blob 的解析；未知引用明确失败，不使用截断内容。
     *
     * @param id 已完成入站组装的 blob ID
     * @param text 本次用户提交的完整文本；构造事件时 null 转为空串
     */
    public void registerBlob(String id, String text) {
        serial.execute(() -> {
            while (blobs.size() >= 16)
                blobs.remove(blobs.keySet().iterator().next());
            blobs.put(id, text);
        });
    }

    /**
     * 按引用 ID 取出并移除已组装的文本；空引用返回空串，未知引用返回 INVALID_MESSAGE。成功读取后该引用即被消费。
     *
     * @param ref 已经完整组装的入站文本引用 ID
     * @return 已经完整组装的文本；空引用返回空串，成功取出后移除登记
     * @throws ProtocolException 非空引用在已组装文本登记中不存在
     */
    private String blob(String ref) throws ProtocolException {
        if (ref == null || ref.isBlank())
            return "";
        String text = blobs.remove(ref);
        if (text == null)
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Unknown blob reference: " + ref);
        return text;
    }

    /**
     * 将会话宿主产生的告警转交统一诊断出口，由 sendDiagnostic 同时通知前端和本地诊断接收者。
     *
     * @param warning 需要经诊断通道展示的告警文本
     */
    private void diagnostics(String warning) {
        sendDiagnostic(warning);
    }

    /**
     * 后端运行中时构造 warning 级 diagnostic 事件，并把相同文本交给诊断回调；null 文本转为空串，已关闭时结束处理。
     *
     * @param message 需要返回给前端的失败或诊断说明；null 编码为空串
     */
    private void sendDiagnostic(String message) {
        if (closed.get())
            return;
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("severity", "warning");
        payload.put("message", message == null ? "" : message);
        wire.session().sendEvent("diagnostic", payload, wire.sessionId(), wire.generation());
        diagnostics.accept(message == null ? "" : message);
    }

    /**
     * 结束后端会话，确认设置与所属资源的最终状态。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>原子进入关闭状态，关闭当前代次的审批通道。</li>
     * <li>关闭 SessionHost，并把未停止资源或关闭异常保存为 shutdownFailure。</li>
     * <li>等待已提交的配置保存，构造含 preferencesSaved、cleanupConfirmed 和原因的结束载荷。</li>
     * <li>独立模式发送 server.stopped 并排空连接；常驻模式保存载荷，等待外层服务确认审计与范围释放后发布。</li>
     * </ol>
     *
     * @param reason 当前事件来源或失败的业务原因
     */
    public void shutdown(String reason) {
        if (!closed.compareAndSet(false, true))
            return;
        Generation generation = current;
        if (generation != null)
            generation.approval.close();
        String lastSession = wire.sessionId();
        try {
            host.close();
            if (!host.isStopped())
                shutdownFailure = "Session resources have not stopped; workspace remains held";
        } catch (RuntimeException failure) {
            shutdownFailure = "Session close failed: " + failure.getMessage();
            sendDiagnostic(shutdownFailure);
        }
        boolean saved = true;
        try {
            preferences.awaitWrites();
        } catch (IOException failure) {
            saved = false;
            shutdownFailure = "Preferences were not saved: " + failure.getMessage();
            diagnostics.accept(shutdownFailure);
        }
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("reason", reason == null ? "" : reason);
        payload.put("sessionId", lastSession);
        payload.put("preferencesSaved", saved);
        payload.put("cleanupConfirmed", host.isStopped());
        payload.put("cleanupError", shutdownFailure == null ? "" : shutdownFailure);
        stoppedPayload = payload;
        // 常驻服务还拥有审计写者和目录占用，外层确认这些资源后才公布关闭回执。
        if (persistent)
            return;
        wire.session().sendEvent("server.stopped", payload, wire.sessionId(), wire.generation());
        wire.session().flushAndClose();
    }

    /**
     * 服务在释放自身资源后公布最终回执；未知清理结果必须让客户端看见失败。
     *
     * @param outerFailure 外层审计或范围清理失败的说明；正常时为 null
     */
    public void publishClosed(String outerFailure) {
        ObjectNode payload = stoppedPayload == null ? Json.mapper().createObjectNode() : stoppedPayload.deepCopy();
        if (outerFailure != null)
            payload.put("cleanupError", outerFailure).put("cleanupConfirmed", false);
        wire.session().sendEvent("server.stopped", payload, wire.sessionId(), wire.generation());
        wire.session().flushAndClose();
    }

    /**
     * 读取最近一次会话或设置收尾失败的说明，供外层服务组织最终关闭回执。
     *
     * @return 收口阶段的失败原因；null 表示正常。
     */
    public String shutdownFailure() {
        return shutdownFailure;
    }

    /**
     * 断连只使旧呈现和票据失效，审批账本仍由当前会话持有。
     */
    public void disconnected() {
        if (closed.get())
            return;
        serial.execute(() -> {
            if (current != null)
                current.approval.invalidatePresented();
            tickets.clear();
            terminalEpoch = 0;
        });
    }

    /**
     * 重连同步与命令串行；快照期间不允许事件插入其分块之间。
     *
     * @param synchronizedDisplay 完整快照排队后恢复实时增量的回调
     * @return 完整快照与审批同步结束后完成的 Future；同步失败时异常完成
     */
    public CompletableFuture<Void> reconnect(Runnable synchronizedDisplay) {
        var result = new CompletableFuture<Void>();
        serial.execute(() -> {
            try {
                if (closed.get())
                    throw new IllegalStateException("Session is closed");
                synchronized (wire.session()) {
                    sendFullSnapshot();
                    synchronizedDisplay.run();
                    current.approval.reconnect();
                }
                result.complete(null);
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /**
     * 管理连接可以取消当前轮次；真正的执行停止仍由 runtime 的取消链完成。
     *
     * @return 取消请求已交给当前轮次并清除待审批后完成的 Future
     */
    public CompletableFuture<Void> cancelCurrent() {
        var result = new CompletableFuture<Void>();
        serial.execute(() -> {
            try {
                host.current().cancelCurrent();
                current.approval.cancelPending();
                result.complete(null);
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /**
     * 返回宿主当前可操作的会话运行时；会话尚未打开或宿主已关闭时，由宿主抛出 IllegalStateException。
     *
     * @return 当前可操作的会话运行时
     */
    public SessionRuntime runtime() {
        return host.current();
    }

    /**
     * 返回后端已关闭且宿主资源已停止的状态，供服务决定何时释放工作区占用。
     *
     * @return 后端已关闭且宿主资源已停止时为 true
     */
    public boolean stopped() {
        return closed.get() && host.isStopped();
    }

    /**
     * 返回后端是否已经进入关闭流程。
     *
     * @return 后端已经进入关闭流程时为 true
     */
    public boolean closed() {
        return closed.get();
    }

    /**
     * 返回首次 ready 处理是否已登记，供重连流程选择首次启动或状态同步。
     *
     * @return 首次 ready 已登记时为 true
     */
    public boolean started() {
        return startupRegistered;
    }

    /**
     * 返回当前会话等待处理的审批请求快照；会话尚未打开时返回空列表。
     *
     * @return 当前待审批请求列表；会话未打开时为空列表
     */
    public List<com.jadecode.safety.approval.ApprovalRequest> pendingApprovals() {
        return current == null ? List.of() : current.approval.requests();
    }

    /**
     * 关闭会话后端，停止串行命令执行器并最多等待三秒；独立模式同时关闭配置服务，常驻模式由外层服务管理配置生命周期。
     * 等待被中断时恢复线程中断标记。
     */
    @Override
    public void close() {
        shutdown("closed");
        serial.shutdown();
        try {
            serial.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!persistent)
            preferences.close();
    }
}
