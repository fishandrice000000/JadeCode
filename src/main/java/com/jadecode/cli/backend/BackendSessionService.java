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
import com.jadecode.llm.ResponseStream;
import com.jadecode.llm.ThinkingSettings;
import com.jadecode.safety.PermissionProfile;
import com.jadecode.session.SessionRuntime;
import com.jadecode.session.history.RestoredSession;
import com.jadecode.session.history.SessionMetadata;
import com.jadecode.util.Json;

/**
 * 后端会话服务：把协议命令串行地变成 Host/Runtime/权限/设置的调用。
 *
 * <p>为什么单独一层：{@link SessionHost} 的 {@code current()} 只是“现在打开的是谁”，不是
 * 已经校验过的提交 API。每条命令都必须先核对“我看到的是哪一代会话”，再采用结果；切换会话
 * 期间、旧代次回包、未来会话改名都返回真实错误，而不是改到另一个会话上。</p>
 *
 * <p>并发模型：所有命令在单线程 {@code jade-backend-commands} 上执行；不会在协议 reader
 * 线程里等待模型 Future，也不在持锁回调里等 socket。任务 Future 完成后的回调也只把结果
 * 投递给 outbox，不反向调用 Node。</p>
 *
 * <p>它不持有终端、不渲染 Markdown、不写前端配置；设置写入交给唯一的
 * {@link PreferenceService}。</p>
 */
public final class BackendSessionService implements AutoCloseable {
    /** 快照出口：完整快照（含历史）与只读状态刷新分开，避免每帧重发历史。 */
    public interface SnapshotSink {
        void full(String snapshotId, long generation, List<BackendHistoryProjector.Entry> entries, BackendSnapshot snapshot);
        void state(BackendSnapshot snapshot);
    }

    /** 一次启动的意图，进入 server.bootstrap 供 Node 决定首轮/选择器行为。 */
    public record Startup(String mode, String prompt, String resumeTarget, boolean autoSubmit) { }

    /**
     * full-access 二次确认的一次性票据。
     *
     * @param intent runtime=只切换本次运行；default=同时保存启动默认。一次票据只服务一种意图。
     */
    private record Ticket(String id, long revision, String intent, boolean presented, String frameId,
            long terminalEpoch, long rawWatermark, long decodedWatermark,
            List<BridgeApproval.Rect> rects, int columns, int rows) { }

    /** 一个会话代次的全部前端协作对象；切换会话时整体替换。 */
    private static final class Generation {
        final long generation;
        final DisplayClock clock;
        final BackendObserver observer;
        final BackendMessages messages;
        final BridgeApproval approval;
        volatile String sessionId = "";

        Generation(BackendSessionService owner, long generation, WireContext wire) {
            this.generation = generation;
            this.clock = new DisplayClock((actor, tick) -> owner.sendClock(wire, actor, tick));
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
    private volatile Generation current;
    private Generation pending;
    private RestoredSession restored = null;
    private long snapshotCounter;
    private boolean startupRegistered;
    /** 当前终端代次；resize/模式切换/暂停/编辑器交接后 Node 会递增，旧呈现证据随之作废。 */
    private long terminalEpoch;
    /** 收口阶段的真实失败；非 null 时进程退出码必须是 1。 */
    private volatile String shutdownFailure;

    public BackendSessionService(WireContext wire, PreferenceService preferences, RuntimeFactory factory,
            SnapshotSink snapshots, Consumer<String> diagnostics, Startup startup) {
        this.wire = wire;
        this.preferences = preferences;
        this.factory = factory;
        this.snapshots = snapshots;
        this.diagnostics = diagnostics == null ? ignored -> { } : diagnostics;
        this.startup = startup;
        Path workspace = Path.of("").toAbsolutePath().normalize();
        this.host = new SessionHost(new WorkspacePaths(workspace), this::createRuntime,
                warning -> diagnostics(warning));
    }

    /** @return 当前配置版本；Node 用它拒绝陈旧 patch。 */
    public long configRevision() { return preferences.state().revision(); }

    /** @return 当前已保存设置；bootstrap 与快照使用同一份。 */
    public JadeSettings settings() { return preferences.state().settings(); }

    /**
     * 由 SessionHost 在切换/新开时调用：为这一代建立观察器/审批/流出口。
     * 只构造后端对象，不启动模型、不读终端。
     */
    private SessionRuntime createRuntime(long generation) {
        Generation candidate = new Generation(this, generation, wire);
        pending = candidate;
        Consumer<String> output = text -> candidate.messages.message(text, false);
        Consumer<String> errors = text -> candidate.messages.message(text, true);
        ExecutionObserver observer = candidate.observer;
        return factory.create(output, errors, () -> new BackendStream(wire, "leader"), observer);
    }

    /**
     * 打开首个会话（新会话或显式恢复目标）并输出完整快照。
     *
     * @throws IOException 目标会话不能恢复或装配失败
     */
    public void openInitial() throws IOException {
        String target = startup.mode().equals("resume") && !startup.resumeTarget().isBlank()
                ? startup.resumeTarget() : null;
        open(target);
    }

    private void open(String targetId) throws IOException {
        SessionHost.Opened opened = host.open(targetId);
        Generation generation = pending;
        pending = null;
        if (generation == null || generation.generation != opened.generation())
            throw new IOException("The backend session factory did not produce the requested generation");
        generation.sessionId = host.current().sessionId();
        current = generation;
        restored = opened.restored();
        wire.update(generation.sessionId, generation.generation);
        sendFullSnapshot();
    }

    /**
     * Node 已完整安装快照并确认 ready：登记首轮任务（若有）再启动消费者/cron。
     *
     * <p>重复 ready 由协议层过滤；这里再加一道 {@link #startupRegistered} 幂等保护，
     * 保证重绘/fg 不会重放用户首轮 prompt。</p>
     */
    public void ready() {
        serial.execute(() -> {
            if (closed.get()) return;
            Generation generation = current;
            if (generation == null) {
                sendDiagnostic("Node reported ready before a session was opened");
                return;
            }
            // 首轮 prompt 来自 Java 启动参数，不经过 Node 回传，避免“重绘重放”或前端改写任务。
            String prompt = startup.prompt() == null ? "" : startup.prompt();
            try {
                if (startup.autoSubmit() && !startupRegistered) {
                    startupRegistered = true;
                    String submissionId = "startup-" + generation.generation;
                    ObjectNode submitted = taskSubmitted(submissionId, prompt);
                    wire.session().sendEvent("task.submitted", submitted, wire.sessionId(), wire.generation());
                    CompletableFuture<String> result = host.startWithInitial(generation.generation, prompt,
                            generation.approval, true);
                    attachResult(result, submissionId);
                } else {
                    host.start(generation.generation);
                    startupRegistered = true;
                }
                sendStateSnapshot();
            } catch (RuntimeException failure) {
                sendDiagnostic("Could not start the session: " + failure.getMessage());
            }
        });
    }

    /** 处理一条已通过协议层校验的业务命令。 */
    public void dispatch(WireMessage command) {
        serial.execute(() -> {
            if (closed.get()) return;
            try {
                handle(command);
            } catch (ProtocolException rejected) {
                wire.session().sendError(command.commandId(), rejected.error(), rejected.getMessage(),
                        wire.sessionId(), wire.generation());
            } catch (IOException | RuntimeException failure) {
                wire.session().sendError(command.commandId(), ProtocolError.PERSISTENCE_FAILED,
                        failure.getMessage() == null ? failure.toString() : failure.getMessage(),
                        wire.sessionId(), wire.generation());
                sendDiagnostic("Command failed: " + failure.getMessage());
            }
        });
    }

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
            case "client.shutdown" -> shutdown(command.payload().path("reason").asText("user"));
            default -> throw new ProtocolException(ProtocolError.UNSUPPORTED,
                    "Unsupported command type: " + command.type());
        }
    }

    private void submit(WireMessage command) throws ProtocolException {
        SessionRuntime runtime = requireSession(command);
        String text = command.payload().hasNonNull("textBlobRef")
                ? blob(command.payload().path("textBlobRef").asText())
                : command.payload().path("text").asText("");
        if (text.isBlank()) throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Prompt must not be blank");
        String submissionId = command.payload().path("clientSubmissionId").asText("");
        if (submissionId.isBlank()) submissionId = "sub-" + System.nanoTime();
        ObjectNode submitted = taskSubmitted(submissionId, text);
        // 先登记提交意图，Node 才会在 TURN/正文事件之前看到用户卡；这不承诺已入队。
        wire.session().sendEvent("task.submitted", submitted, wire.sessionId(), wire.generation());
        CompletableFuture<String> result;
        try {
            result = runtime.submitUser(text, current.approval);
        } catch (RuntimeException rejected) {
            wire.session().sendEvent("task.failed", taskFailed(submissionId, rejected.getMessage()),
                    wire.sessionId(), wire.generation());
            throw new ProtocolException(ProtocolError.BUSY, "The session did not accept the prompt: " + rejected.getMessage());
        }
        attachResult(result, submissionId);
        wire.session().sendOk(command.commandId(), status("dispatched"), wire.sessionId(), wire.generation());
    }

    private void promoteBuffer(WireMessage command) throws ProtocolException {
        SessionRuntime runtime = requireSession(command);
        boolean changed = runtime.promoteUserBuffer();
        ObjectNode payload = status("ok");
        payload.put("changed", changed);
        wire.session().sendOk(command.commandId(), payload, wire.sessionId(), wire.generation());
    }

    private void switchSession(WireMessage command, String targetId) throws ProtocolException {
        requireSession(command);
        Generation previous = current;
        try {
            open(targetId);
        } catch (IOException failure) {
            // 失败时保留旧会话与旧界面；旧代次的审批也不会被这次失败取消。
            pending = null;
            throw new ProtocolException(ProtocolError.BUSY, "Could not switch session: " + failure.getMessage(), failure);
        }
        // 刚切换成功：上一代若还有等待中的审批，必须明确拒绝，不能让它挂到新会话上。
        previous.approval.close();
        wire.session().sendOk(command.commandId(), status("switching"), wire.sessionId(), wire.generation());
    }

    private void list(WireMessage command) throws ProtocolException, IOException {
        requireSession(command);
        ArrayNode items = Json.mapper().createArrayNode();
        for (SessionMetadata metadata : host.list()) items.add(metadata(metadata));
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.set("sessions", items);
        wire.session().sendOk(command.commandId(), payload, wire.sessionId(), wire.generation());
    }

    private void find(WireMessage command) throws ProtocolException, IOException {
        requireSession(command);
        String name = requiredText(command, "name");
        ArrayNode items = Json.mapper().createArrayNode();
        for (SessionMetadata metadata : host.find(name)) items.add(metadata(metadata));
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.set("sessions", items);
        wire.session().sendOk(command.commandId(), payload, wire.sessionId(), wire.generation());
    }

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

    private void thinking(WireMessage command) throws ProtocolException {
        SessionRuntime runtime = requireSession(command);
        String value = requiredText(command, "value");
        ThinkingSettings next;
        try { next = ThinkingSettings.parse(value); }
        catch (IllegalArgumentException invalid) {
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
            @Override public void saved(JadeSettings settings, long revision, Map<String, Object> applied) {
                ObjectNode payload = Json.mapper().createObjectNode();
                payload.put("configRevision", Long.toString(revision));
                payload.set("tui", tuiJson(settings));
                payload.set("tuiSnapshot", snapshot().toJson());
                wire.session().sendEvent("settings.saved", payload, wire.sessionId(), wire.generation());
                requestStateSnapshot();
            }

            @Override public void failed(IOException failure, Map<String, Object> applied) {
                ObjectNode payload = Json.mapper().createObjectNode();
                payload.put("message", failure.getMessage() == null ? failure.toString() : failure.getMessage());
                payload.put("configRevision", Long.toString(configRevision()));
                payload.put("retryable", true);
                wire.session().sendEvent("settings.failed", payload, wire.sessionId(), wire.generation());
            }
        });
    }

    private void selectPermission(WireMessage command) throws ProtocolException {
        SessionRuntime runtime = requireSession(command);
        String value = requiredText(command, "profile");
        PermissionProfile profile;
        try { profile = PermissionProfile.parse(value); }
        catch (IllegalArgumentException invalid) {
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, invalid.getMessage(), invalid);
        }
        var state = runtime.permissionState();
        long expected = longValue(command.payload(), "expectedPermissionRevision", -1);
        if (expected != state.snapshot().revision())
            throw new ProtocolException(ProtocolError.STALE_REVISION, "The permission revision changed");
        if (profile == PermissionProfile.FULL_ACCESS) requireFullAccessEvidence(command, "runtime");
        state.select(profile);
        ObjectNode payload = status("ok");
        payload.put("profile", profile.value());
        payload.put("permissionRevision", Long.toString(state.snapshot().revision()));
        wire.session().sendOk(command.commandId(), payload, wire.sessionId(), wire.generation());
        sendStateSnapshot();
    }

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
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "A confirmation button is outside the viewport");
            if (rect.choice() == 1) confirmButton = true;
        }
        if (!confirmButton)
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "The confirmation frame has no enabled confirm button");
        tickets.put(id, new Ticket(id, revision, intent, true, frameId, epoch,
                longValue(command.payload(), "rawReceiptWatermark", 0),
                longValue(command.payload(), "decodedWatermark", 0), rects, columns, rows));
        wire.session().sendOk(command.commandId(), status("ok"), wire.sessionId(), wire.generation());
    }

    private void cancelFullAccess(WireMessage command) {
        String id = command.payload().path("confirmationId").asText("");
        tickets.remove(id);
        wire.session().sendOk(command.commandId(), status("cancelled"), wire.sessionId(), wire.generation());
    }

    private void defaultPermission(WireMessage command) throws ProtocolException {
        requireSession(command);
        String value = requiredText(command, "profile");
        PermissionProfile profile;
        try { profile = PermissionProfile.parse(value); }
        catch (IllegalArgumentException invalid) {
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, invalid.getMessage(), invalid);
        }
        long expected = longValue(command.payload(), "expectedConfigRevision", -1);
        if (profile == PermissionProfile.FULL_ACCESS) requireFullAccessEvidence(command, "default");
        preferences.saveDefaultPermission(profile, expected, new PreferenceService.Callback() {
            @Override public void saved(JadeSettings settings, long revision, Map<String, Object> applied) {
                ObjectNode payload = status("saved");
                payload.put("configRevision", Long.toString(revision));
                payload.put("defaultProfile", settings.permissions().defaultProfile().value());
                wire.session().sendResult(command.commandId(), "command.result", payload,
                        wire.sessionId(), wire.generation());
                requestStateSnapshot();
            }

            @Override public void failed(IOException failure, Map<String, Object> applied) {
                wire.session().sendError(command.commandId(), ProtocolError.PERSISTENCE_FAILED,
                        failure.getMessage(), wire.sessionId(), wire.generation());
            }
        });
    }

    /**
     * full-access 必须有：同一次意图的票据、已提交帧、当前 revision/epoch，且确认来自呈现之后的新输入。
     * 鼠标确认还必须落在该帧 confirm 按钮矩形内；键盘确认必须带显式 confirm 动作。
     */
    private void requireFullAccessEvidence(WireMessage command, String requiredIntent) throws ProtocolException {
        SessionRuntime runtime = requireSession(command);
        String id = command.payload().path("fullAccessConfirmationId").asText("");
        Ticket ticket = tickets.get(id);
        if (ticket == null || !ticket.presented())
            throw new ProtocolException(ProtocolError.NOT_READY, "Full Access must be confirmed on the permission page");
        if (!requiredIntent.equals(ticket.intent()))
            throw new ProtocolException(ProtocolError.STALE_REVISION, "This confirmation was prepared for a different intent");
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

    private void presented(WireMessage command) throws ProtocolException {
        requireSession(command);
        String requestId = requiredText(command, "requestId");
        long epoch = longValue(command.payload(), "terminalEpoch", -1);
        if (epoch != terminalEpoch)
            throw new ProtocolException(ProtocolError.STALE_REVISION, "The terminal epoch changed; present the approval again");
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

    /** 当前终端代次；测试与诊断使用。 */
    public long terminalEpoch() { return terminalEpoch; }

    /** 请求一次节流的状态刷新；观察器线程只投递请求，不等待渲染。 */
    public void requestStateSnapshot() {
        if (closed.get()) return;
        serial.execute(this::sendStateSnapshot);
    }

    private void sendStateSnapshot() {
        if (closed.get() || current == null) return;
        snapshots.state(snapshot());
    }

    private void sendFullSnapshot() {
        Generation generation = current;
        if (generation == null || restored == null) return;
        List<BackendHistoryProjector.Entry> entries = BackendHistoryProjector.project(restored);
        String snapshotId = "snap-" + generation.generation + "-" + (++snapshotCounter);
        wire.session().expectReady(snapshotId, generation.generation);
        snapshots.full(snapshotId, generation.generation, entries, snapshot());
    }

    /** 组装当前只读资料；历史条目不在其中，避免每帧重发。 */
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

    private ObjectNode taskSubmitted(String submissionId, String text) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("submissionId", submissionId);
        payload.put("entryId", "task-" + submissionId);
        payload.put("state", "SUBMITTING");
        if (text != null && text.length() > 32_768) {
            payload.put("text", "");
            payload.put("textBlobRef", wire.payloads().sendText("task-text", text, wire.sessionId(),
                    wire.generation(), "task"));
        } else {
            payload.put("text", text == null ? "" : text);
        }
        return payload;
    }

    private ObjectNode taskFailed(String submissionId, String message) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("submissionId", submissionId);
        payload.put("runId", "");
        payload.put("message", message == null ? "" : message);
        return payload;
    }

    /** 任务 Future 完成只投递结果；共享 Future 的多次提交都会各自收到同一次最终回答。 */
    private void attachResult(CompletableFuture<String> result, String submissionId) {
        result.whenComplete((text, failure) -> serial.execute(() -> {
            if (closed.get()) return;
            ObjectNode payload = Json.mapper().createObjectNode();
            payload.put("submissionId", submissionId);
            payload.put("runId", "");
            if (failure != null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                payload.put("message", cause.getMessage() == null ? cause.toString() : cause.getMessage());
                wire.session().sendEvent("task.failed", payload, wire.sessionId(), wire.generation());
            } else {
                String value = text == null ? "" : text;
                if (value.length() > 32_768) {
                    payload.put("text", "");
                    payload.put("textBlobRef", wire.payloads().sendText("task-reply", value,
                            wire.sessionId(), wire.generation(), "reply"));
                } else {
                    payload.put("text", value);
                }
                wire.session().sendEvent("task.result", payload, wire.sessionId(), wire.generation());
            }
            sendStateSnapshot();
        }));
    }

    private SessionRuntime requireSession(WireMessage command) throws ProtocolException {
        Generation generation = current;
        if (generation == null) throw new ProtocolException(ProtocolError.NOT_READY, "No session is open yet");
        // 会话命令必须带完整身份；缺失身份不能默认解释为“当前代次”。
        if (command.generation() == null || command.generation() != generation.generation)
            throw new ProtocolException(ProtocolError.STALE_GENERATION, "This command belongs to an older session generation");
        if (command.sessionId() == null || !command.sessionId().equals(generation.sessionId))
            throw new ProtocolException(ProtocolError.STALE_GENERATION, "This command belongs to another session");
        try {
            return host.current();
        } catch (IllegalStateException closedSession) {
            throw new ProtocolException(ProtocolError.CLOSED, closedSession.getMessage(), closedSession);
        }
    }

    private String requiredText(WireMessage command, String field) throws ProtocolException {
        JsonNode node = command.payload().path(field);
        if (!node.isTextual() || node.asText().isBlank())
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, field + " is required");
        return node.asText();
    }

    private static long longValue(JsonNode payload, String field, long fallback) {
        JsonNode node = payload.path(field);
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isTextual()) {
            try { return Long.parseLong(node.asText()); } catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    private static ObjectNode status(String value) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("status", value);
        return payload;
    }

    private static List<BridgeApproval.Rect> rects(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<BridgeApproval.Rect> rects = new ArrayList<>();
        for (JsonNode item : node) {
            rects.add(new BridgeApproval.Rect(item.path("x").asInt(0), item.path("y").asInt(0),
                    item.path("width").asInt(0), item.path("height").asInt(0), item.path("choice").asInt(-1)));
        }
        return rects;
    }

    private static ObjectNode metadata(SessionMetadata metadata) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("id", metadata.id());
        node.put("title", metadata.title());
        node.put("updatedAt", metadata.updatedAt());
        node.put("workspace", metadata.workspace());
        node.put("model", metadata.model());
        return node;
    }

    private static Object plain(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isFloatingPointNumber()) return node.asDouble();
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

    /** 供 settings.saved 事件携带完整 TUI 子快照，Node 无需再取整份配置。 */
    private static ObjectNode tuiJson(JadeSettings settings) {
        return (ObjectNode) BackendSnapshot.capture(0, null, null, settings, 0, List.of(), Map.of(),
                "", "", Map.of(), Map.of()).toJson().get("tui");
    }

    /** 已组装入站 blob 的解析；未知引用明确失败，不使用截断内容。 */
    public void registerBlob(String id, String text) {
        serial.execute(() -> {
            while (blobs.size() >= 16) blobs.remove(blobs.keySet().iterator().next());
            blobs.put(id, text);
        });
    }

    private String blob(String ref) throws ProtocolException {
        if (ref == null || ref.isBlank()) return "";
        String text = blobs.remove(ref);
        if (text == null) throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Unknown blob reference: " + ref);
        return text;
    }

    private void diagnostics(String warning) {
        sendDiagnostic(warning);
    }

    private void sendDiagnostic(String message) {
        if (closed.get()) return;
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("severity", "warning");
        payload.put("message", message == null ? "" : message);
        wire.session().sendEvent("diagnostic", payload, wire.sessionId(), wire.generation());
        diagnostics.accept(message == null ? "" : message);
    }

    /** 有序收口：拒绝审批、保存设置、关闭会话与依赖，最后通知前端。 */
    public void shutdown(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        Generation generation = current;
        if (generation != null) generation.approval.close();
        String lastSession = wire.sessionId();
        try {
            host.close();
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
        wire.session().sendEvent("server.stopped", payload, wire.sessionId(), wire.generation());
        wire.session().flushAndClose();
    }

    /** @return 收口阶段的失败原因；null 表示正常。 */
    public String shutdownFailure() { return shutdownFailure; }

    @Override public void close() {
        shutdown("closed");
        serial.shutdown();
        try {
            serial.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        preferences.close();
    }
}
