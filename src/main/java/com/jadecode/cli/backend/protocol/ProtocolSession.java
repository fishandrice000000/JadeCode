package com.jadecode.cli.backend.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 管理一条控制连接从握手、快照同步、ready 到关闭的协议状态。核对凭据、版本、构建身份、连接序号和重复命令，
 * 业务交给 CommandSink。出站事件统一进入 EventOutbox，写线程实际发送时分配序号；客户端确认同一快照后触发会话启动。
 *
 * @see EventOutbox
 * @see WireCodec
 */
public final class ProtocolSession implements AutoCloseable, EventEndpoint {
    /**
     * 连接状态；只有 READY 接受会话命令。
     */
    public enum State {
        ACCEPTED, HELLO, SYNC, READY, CLOSING, CLOSED
    }

    /**
     * 握手结果：服务端 hello 与首份 bootstrap 资料。
     *
     * @param helloPayload 服务端握手响应中的身份与构建资料
     * @param bootstrapPayload 握手后发送的启动模式和初始配置资料
     */
    public record Greeting(ObjectNode helloPayload, ObjectNode bootstrapPayload) {
    }

    /**
     * 握手校验器：由后端提供真实构建身份、协议版本与启动资料。
     */
    @FunctionalInterface
    public interface Greeter {
        /**
         * 依据 client.hello 准备服务身份和启动资料，返回握手响应；握手意图不合法时抛出协议错误。
         *
         * @param hello 客户端发送的 client.hello 握手帧
         * @return 用于 server.hello 和 server.bootstrap 的完整资料
         * @throws ProtocolException 握手意图或客户端声明不符合当前服务要求
         */
        Greeting greet(WireMessage hello) throws ProtocolException;
    }

    /**
     * 业务命令接收器；在协议层校验之后调用。
     */
    @FunctionalInterface
    public interface CommandSink {
        /**
         * 接收已经通过连接、序号和 ready 检查的业务命令，交给后端命令线程处理。
         *
         * @param command 已通过连接协议检查的业务命令，包含类型、会话代次及操作载荷
         */
        void accept(WireMessage command);
    }

    private static final int MAX_CACHED_RESULTS = 64;

    private final OutputStream out;
    private final String credential;
    private final String connectionId;
    private final Greeter greeter;
    private final CommandSink commands;
    private final Runnable onClosed;
    private final Runnable onReady;
    /** 握手应答已发出后才创建会话/快照；避免快照抢在 bootstrap 之前。 */
    private final Runnable onHello;
    private final EventOutbox outbox;
    private final ScheduledExecutorService handshakeTimer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jade-wire-handshake");
        thread.setDaemon(true);
        return thread;
    });

    private State state = State.ACCEPTED;
    private long nextSeq = 1;
    private long lastClientSeq;
    private String snapshotId;
    private Long snapshotGeneration;
    /** 快照 begin 的来源 seq；ready 必须回传同一值。 */
    private long snapshotSourceSeq = -1;
    /** 是否在等待一次新的 ready；切换会话/新打开会话都会重新置位。 */
    private boolean awaitingReady;
    private final Map<String, CachedResult> cachedResults = new LinkedHashMap<>();
    private final List<String> seenCommandIds = new ArrayList<>();
    private int handshakeTicks;
    private boolean closed;
    /** 最近一次 ready 的客户端序号，仅用于诊断；ready 不做结果缓存。 */
    private long localStorageReadySeq;

    /**
     * 保存已完成命令的结果类型、载荷和会话代次，供同一命令重发时返回原结果。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 当前完整快照对应的会话代次，客户端 ready 必须回传相同值
     */
    private record CachedResult(String type, ObjectNode payload, String sessionId, Long generation) {
    }

    /**
     * 建立连接状态机，绑定输出、一次性凭据、连接身份、握手资料和业务命令出口，以及关闭回调。使用空 ready 回调，
     * 供无需启动屏障回调的调用方使用。
     *
     * @param out 当前连接的输出流
     * @param credential 本次连接握手需要的一次性凭据
     * @param connectionId 本次控制连接的唯一身份
     * @param greeter 为 client.hello 生成服务端身份和启动资料的回调
     * @param commands 通过协议检查后接收业务命令的出口
     * @param onClosed 协议连接最终关闭后的清理回调
     */
    public ProtocolSession(OutputStream out, String credential, String connectionId,
            Greeter greeter, CommandSink commands, Runnable onClosed) {
        this(out, credential, connectionId, greeter, commands, onClosed, () -> {
        });
    }

    /**
     * 建立带 ready 回调的连接状态机；客户端安装完整快照并确认后调用 onReady，首次 hello 使用空回调。
     *
     * @param out 当前连接的输出流
     * @param credential 本次连接握手需要的一次性凭据
     * @param connectionId 本次控制连接的唯一身份
     * @param greeter 为 client.hello 生成服务端身份和启动资料的回调
     * @param commands 通过协议检查后接收业务命令的出口
     * @param onClosed 协议连接最终关闭后的清理回调
     * @param onReady 客户端确认完整快照后调用的会话启动回调
     */
    public ProtocolSession(OutputStream out, String credential, String connectionId,
            Greeter greeter, CommandSink commands, Runnable onClosed, Runnable onReady) {
        this(out, credential, connectionId, greeter, commands, onClosed, onReady, () -> {
        });
    }

    /**
     * 保存连接身份和生命周期回调，创建有界事件出口与握手计时任务。hello 与 bootstrap 排队后调用 onHello，
     * 客户端通过快照屏障后调用 onReady，关闭时调用 onClosed。
     *
     * @param out 当前连接的输出流
     * @param credential 本次连接握手需要的一次性凭据
     * @param connectionId 本次控制连接的唯一身份
     * @param greeter 为 client.hello 生成服务端身份和启动资料的回调
     * @param commands 通过协议检查后接收业务命令的出口
     * @param onClosed 协议连接最终关闭后的清理回调
     * @param onReady 客户端确认完整快照后调用的会话启动回调
     * @param onHello hello 与 bootstrap 排队后调用的打开会话回调
     */
    public ProtocolSession(OutputStream out, String credential, String connectionId,
            Greeter greeter, CommandSink commands, Runnable onClosed, Runnable onReady, Runnable onHello) {
        this.out = out;
        this.credential = credential;
        this.connectionId = connectionId;
        this.greeter = greeter;
        this.commands = commands;
        this.onClosed = onClosed;
        this.onReady = onReady;
        this.onHello = onHello;
        // 过载通知可能来自领域投递线程，也可能来自 outbox 写线程。关闭会等待写线程排空，
        // 必须交给独立线程：否则领域线程会被慢 socket 拖住，写线程还会等待自己结束。
        this.outbox = new EventOutbox(this::writeFrame,
                failure -> Thread.ofVirtual().name("jade-wire-overload-close").start(this::closeQuietly));
        // 握手期限按实际执行的 tick 累计。fixed-delay 不会在进程 fg 后补跑暂停期间
        // 积压的 fixed-rate tick，避免一次正常作业暂停被误判为握手超时。
        handshakeTimer.scheduleWithFixedDelay(this::handshakeTick, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * 读取当前连接在传输或事件队列中记录的首次致命失败。
     *
     * @return 传输层/outbox 的第一次致命失败；null 表示没有。
     */
    public String failureReason() {
        RuntimeException failure = outbox.failure();
        return failure == null ? null : failure.getMessage();
    }

    /**
     * 取得此连接的有界事件出口，供大文本分块与普通事件保持统一发送顺序。
     *
     * @return 本连接的唯一事件出口；大资料分块器与观察器共用一个有界队列。
     */
    public EventOutbox outbox() {
        return outbox;
    }

    /**
     * 读取此连接当前的握手、同步、就绪或关闭阶段。
     *
     * @return 当前连接状态；测试与诊断使用。
     */
    public synchronized State state() {
        return state;
    }

    /**
     * 读取本连接已经实际写出的最后序号，供状态查询与快照同步定位发送进度。
     *
     * @return 握手后已发送事件/答复的数量（下一个 seq 减一）。
     */
    public synchronized long sentSeq() {
        return nextSeq - 1;
    }

    /**
     * 登记正在同步的快照 ID、会话代次和 begin 来源序号，设置等待 ready 标记，并把 HELLO 或 READY 连接切换到 SYNC。
     * 客户端随后原样回传这些资料完成屏障。
     *
     * @param id 刚刚发送的完整历史快照 ID
     * @param generation 当前完整快照对应的会话代次，客户端 ready 必须回传相同值
     * @param sourceSeq 完整快照 begin 固定的展示来源序号
     * @throws NullPointerException sourceSeq 为 null
     */
    public synchronized void expectReady(String id, Long generation, Long sourceSeq) {
        snapshotId = id;
        snapshotGeneration = generation;
        snapshotSourceSeq = java.util.Objects.requireNonNull(sourceSeq, "snapshot source sequence");
        awaitingReady = true;
        if (state == State.HELLO || state == State.READY)
            state = State.SYNC;
    }

    /**
     * 发送一个事件（不合并）。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 当前完整快照对应的会话代次，客户端 ready 必须回传相同值
     */
    public void sendEvent(String type, ObjectNode payload, String sessionId, Long generation) {
        outbox.offer(type, encode(payload, sessionId, generation), sessionId, generation, null);
    }

    /**
     * 复制出站载荷，对协议约定的长文本、工具参数、详情、历史与状态字段发送 payload 分块，再将字段改为 blob 引用。
     * 分块先于引用帧排入同一队列，返回当前连接使用的独立载荷。
     *
     * @param source 准备转换为本连接 blob 引用的完整业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 当前完整快照对应的会话代次，客户端 ready 必须回传相同值
     * @return 带当前连接 blob 引用的独立载荷，原始业务对象保留
     */
    private ObjectNode encode(ObjectNode source, String sessionId, Long generation) {
        ObjectNode result = source.deepCopy();
        var transfer = new PayloadTransfer(outbox);
        // 前端协议明确区分这些资料字段；只处理约定字段，不递归改写任意模型 JSON。
        for (String field : java.util.List.of("inputJson", "detail", "text", "encodedEntries", "snapshot")) {
            var value = result.get(field);
            if (value == null || value.isNull())
                continue;
            String text = value.isTextual() ? value.asText() : value.toString();
            boolean report = field.equals("text") && result.has("assignmentId");
            if (!report && text.length() <= (field.equals("inputJson") || field.equals("detail") ? 4096 : 32768))
                continue;
            String ref = switch (field) {
                case "inputJson" -> "inputBlobRef";
                case "encodedEntries" -> "entriesBlobRef";
                default -> field + "BlobRef";
            };
            result.remove(field);
            result.put(ref,
                    transfer.sendText(field, text, sessionId, generation, "wire-" + java.util.UUID.randomUUID()));
        }
        return result;
    }

    /**
     * 发送一个可以与前一个相邻同键增量合并的正文/预览事件。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 当前完整快照对应的会话代次，客户端 ready 必须回传相同值
     * @param mergeKey 增量合并键；null 表示结构事件单独发送
     */
    public void sendStreamEvent(String type, ObjectNode payload, String sessionId, Long generation, String mergeKey) {
        outbox.offer(type, payload, sessionId, generation, mergeKey);
    }

    /**
     * 按命令 ID 缓存完整结果，保留最近 64 项；为当前连接编码大载荷后入队并请求立即刷新。供重复命令返回既有结果。
     *
     * @param commandId 客户端命令 ID，供命令回执和重复请求查找使用
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 当前完整快照对应的会话代次，客户端 ready 必须回传相同值
     */
    public void sendResult(String commandId, String type, ObjectNode payload, String sessionId, Long generation) {
        cachedResults.put(commandId, new CachedResult(type, payload, sessionId, generation));
        while (cachedResults.size() > MAX_CACHED_RESULTS) {
            String oldest = cachedResults.keySet().iterator().next();
            cachedResults.remove(oldest);
        }
        outbox.offerResult(type, encode(payload, sessionId, generation), commandId, sessionId, generation);
    }

    /**
     * 把错误码、说明和可重试标记组成 command.error，交给统一命令结果通道缓存并发送。
     *
     * @param commandId 客户端命令 ID，供命令回执和重复请求查找使用
     * @param error 本次协议错误类别，决定错误码和可重试标记
     * @param message 需要返回给调用者的结果或错误说明
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 当前完整快照对应的会话代次，客户端 ready 必须回传相同值
     */
    public void sendError(String commandId, ProtocolError error, String message, String sessionId, Long generation) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("status", "error");
        payload.put("code", error.name());
        payload.put("message", message == null ? "" : message);
        payload.put("retryable", error.recoverable());
        sendResult(commandId, "command.error", payload, sessionId, generation);
    }

    /**
     * 把成功载荷作为 command.result 交给统一命令结果通道，业务状态由 payload 提供。
     *
     * @param commandId 客户端命令 ID，供命令回执和重复请求查找使用
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 当前完整快照对应的会话代次，客户端 ready 必须回传相同值
     */
    public void sendOk(String commandId, ObjectNode payload, String sessionId, Long generation) {
        sendResult(commandId, "command.result", payload, sessionId, generation);
    }

    /**
     * 处理完整入站帧：核对连接状态与身份，按序号和命令 ID 去重，分派 hello、ready 或已就绪业务命令。
     * 可恢复协议错误发送命令错误，连接级错误抛给外层结束连接。
     *
     * @param message 待编码、校验或投影的完整消息
     * @throws ProtocolException 当前连接身份、状态、握手或消息方向不符合协议
     */
    public void receive(WireMessage message) throws ProtocolException {
        if (message.type().equals("client.hello")) {
            hello(message);
            return;
        }
        // 握手之后所有帧都必须属于本连接；错误连接不能借用已认证的 reader。
        synchronized (this) {
            if (state == State.ACCEPTED)
                throw new ProtocolException(ProtocolError.UNAUTHORIZED, "The handshake must come first");
            if (!connectionId.equals(message.connectionId()))
                throw new ProtocolException(ProtocolError.UNAUTHORIZED, "This frame belongs to another connection");
        }
        if (isDuplicate(message))
            return;
        switch (message.type()) {
            case "session.ready" -> ready(message);
            case "client.shutdown" -> {
                requireReady();
                commands.accept(message);
            }
            default -> {
                requireReady();
                commands.accept(message);
            }
        }
    }

    /**
     * 处理 client.hello：核对当前握手阶段、协议版本、一次性凭据和构建身份。校验通过后进入 HELLO，
     * 依次发送 server.hello 与 server.bootstrap，再调用 onHello 打开会话并准备快照；校验失败抛出协议错误。
     *
     * @param message 待编码、校验或投影的完整消息
     * @throws ProtocolException 握手阶段、协议版本、凭据或构建身份不匹配
     */
    private void hello(WireMessage message) throws ProtocolException {
        synchronized (this) {
            if (state != State.ACCEPTED)
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "The handshake already happened");
        }
        JsonNode payload = message.payload();
        int version = payload.path("protocolVersion").asInt(-1);
        if (version != WireCodec.PROTOCOL_VERSION)
            throw new ProtocolException(ProtocolError.VERSION_MISMATCH,
                    "Frontend protocol version " + version + " is not supported");
        String presented = payload.path("credential").asText("");
        if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                credential.getBytes(StandardCharsets.UTF_8)))
            throw new ProtocolException(ProtocolError.UNAUTHORIZED, "The one-time connection credential is invalid");
        String buildId = payload.path("buildId").asText("");
        Greeting greeting = greeter.greet(message);
        // 构建身份必须在发出任何启动资料之前核对；错误前端不会先创建会话或执行首轮。
        if (!buildId.equals(greeting.helloPayload().path("buildId").asText("")))
            throw new ProtocolException(ProtocolError.BUILD_MISMATCH, "Frontend and backend builds do not match");
        if (greeting.helloPayload().path("protocolVersion").asInt(-1) != WireCodec.PROTOCOL_VERSION)
            throw new ProtocolException(ProtocolError.VERSION_MISMATCH,
                    "The backend greeting has a different protocol version");
        synchronized (this) {
            state = State.HELLO;
        }
        sendEvent("server.hello", greeting.helloPayload(), null, null);
        sendEvent("server.bootstrap", greeting.bootstrapPayload(), null, null);
        // 只有 hello/bootstrap 已入队后，后端才打开会话并发送快照。
        onHello.run();
    }

    /**
     * 采用客户端对本次完整快照的 ready 确认，并打开业务处理屏障。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>检查当前为 SYNC，固定正在等待的快照身份；已完成屏障的重复 ready 直接返回。</li>
     * <li>解析十进制 generation 与 lastSnapshotSeq，依次核对快照 ID、代次和 begin 固定的 sourceSeq。</li>
     * <li>发布 READY 并清除等待标记，停止握手定时器，再执行 onReady 启动当前会话。</li>
     * </ol>
     *
     * @param message 待编码、校验或投影的完整消息
     * @throws ProtocolException 尚未同步快照，或快照 ID、代次、来源序号不符合屏障
     */
    private void ready(WireMessage message) throws ProtocolException {
        String presentedSnapshot;
        synchronized (this) {
            if (!awaitingReady && state == State.READY) {
                // 同一快照的重复 ready（重绘/fg/外部编辑器返回）不得重放首轮任务。
                return;
            }
            if (state != State.SYNC)
                throw new ProtocolException(ProtocolError.NOT_READY, "No snapshot is waiting for a ready barrier");
            presentedSnapshot = snapshotId;
        }
        String id = message.payload().path("snapshotId").asText("");
        JsonNode generationNode = message.payload().path("generation");
        JsonNode seqNode = message.payload().path("lastSnapshotSeq");
        if (!generationNode.isTextual() || !generationNode.asText().matches("\\d+"))
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "session.ready needs a decimal generation");
        if (!seqNode.isTextual() || !seqNode.asText().matches("\\d+"))
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "session.ready needs lastSnapshotSeq");
        long generation = Long.parseLong(generationNode.asText());
        if (presentedSnapshot == null || !presentedSnapshot.equals(id))
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE,
                    "session.ready must reference the current snapshot");
        if (snapshotGeneration == null || generation != snapshotGeneration)
            throw new ProtocolException(ProtocolError.STALE_GENERATION,
                    "session.ready referenced a stale session generation");
        // lastSnapshotSeq 是 Node 已安装快照的来源序号；帧仍在 outbox 中时 sentSeq 可能滞后，
        // 因此这里核对 begin 固定的来源游标，而不拿发送线程当前序号作为上限。
        long lastSeq = Long.parseLong(seqNode.asText());
        if (lastSeq < 0)
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE,
                    "session.ready references an invalid snapshot sequence");
        if (lastSeq != snapshotSourceSeq)
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE,
                    "session.ready must echo the snapshot begin sourceSeq");
        synchronized (this) {
            state = State.READY;
            awaitingReady = false;
        }
        handshakeTimer.shutdownNow();
        onReady.run();
    }

    /**
     * 在连接锁内核对客户端序号和最近命令 ID。新命令更新有界去重记录并返回 false；重复命令优先回缓存结果，
     * 否则回 DUPLICATE。ready 使用自身幂等屏障逻辑，仅更新序号记录。
     *
     * @param message 待编码、校验或投影的完整消息
     * @return 该帧属于已处理序号或命令 ID 时为 true，此时已经发送去重回执
     */
    private boolean isDuplicate(WireMessage message) {
        synchronized (this) {
            // ready 是屏障命令，不是可缓存的业务命令；重复 ready 由 ready() 自身幂等处理。
            if (message.type().equals("session.ready")) {
                if (message.seq() <= lastClientSeq)
                    localStorageReadySeq = message.seq();
                lastClientSeq = Math.max(lastClientSeq, message.seq());
                return false;
            }
            boolean oldSeq = message.seq() <= lastClientSeq;
            boolean seenId = message.commandId() != null && seenCommandIds.contains(message.commandId());
            if (!oldSeq && !seenId) {
                lastClientSeq = Math.max(lastClientSeq, message.seq());
                if (message.commandId() != null) {
                    seenCommandIds.add(message.commandId());
                    if (seenCommandIds.size() > 256)
                        seenCommandIds.removeFirst();
                }
                return false;
            }
            CachedResult cached = message.commandId() == null ? null : cachedResults.get(message.commandId());
            if (cached != null) {
                outbox.offerResult(cached.type(), encode(cached.payload(), cached.sessionId(), cached.generation()),
                        message.commandId(), cached.sessionId(),
                        cached.generation());
            } else {
                sendError(message.commandId(), ProtocolError.DUPLICATE,
                        "This command was already processed; it was not executed again",
                        message.sessionId(), message.generation());
            }
            return true;
        }
    }

    /**
     * 检查连接已完成快照同步并进入 READY；尚未完成时抛出 NOT_READY，供业务命令入口返回协议错误。
     *
     * @throws ProtocolException 连接尚未完成最近一次快照的 ready 确认
     */
    private void requireReady() throws ProtocolException {
        synchronized (this) {
            if (state != State.READY)
                throw new ProtocolException(ProtocolError.NOT_READY,
                        "The frontend has not completed its snapshot handshake");
        }
    }

    /**
     * 在实际写出时分配下一协议序号，编码为完整 NDJSON 帧并写到连接输出，供队列单写线程依次发送；
     * IO 失败交给 outbox 收尾。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 当前完整快照对应的会话代次，客户端 ready 必须回传相同值
     * @param commandId 客户端命令 ID，供命令回执和重复请求查找使用
     * @throws IOException 向连接写出或刷新完整 NDJSON 帧失败
     */
    private void writeFrame(String type, ObjectNode payload, String sessionId, Long generation, String commandId)
            throws IOException {
        long seq;
        synchronized (this) {
            if (closed)
                throw new IOException("The connection is closed");
            seq = nextSeq++;
        }
        WireMessage message = commandId == null
                ? WireMessage.event(seq, connectionId, type, sessionId, generation, payload)
                : WireMessage.result(seq, connectionId, type, commandId, sessionId, generation, payload);
        byte[] frame;
        try {
            frame = WireCodec.encode(message);
        } catch (ProtocolException tooLarge) {
            // 出站帧超限是真实失败：不能截断后继续发送一条看似完整的消息。
            throw new IOException(tooLarge.getMessage(), tooLarge);
        }
        synchronized (out) {
            out.write(frame);
            out.flush();
        }
    }

    /**
     * 推进首次握手的等待计数；已经 ready、正在同步快照或连接已关闭时停止计时器，累计十个等待 tick 后关闭连接。
     */
    private void handshakeTick() {
        synchronized (this) {
            if (state == State.READY || state == State.SYNC || closed) {
                handshakeTimer.shutdownNow();
                return;
            }
            handshakeTicks++;
            if (handshakeTicks < 10)
                return;
        }
        // 10 个活跃 tick 仍未完成握手：不创建模型任务，直接收口连接。
        closeQuietly();
    }

    /**
     * 先把已入队答复/事件送出，再走正常收口；用于 server.stopped 之后的最终关闭。
     */
    public void flushAndClose() {
        outbox.flushNow();
        close();
    }

    /**
     * 请求有序收口：拒绝新事件、排空已入队答复，最后关闭与回调。
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed)
                return;
            state = State.CLOSING;
        }
        handshakeTimer.shutdownNow();
        // outbox.close() 会先排空已入队事件（有界等待），再停止接收新事件。
        outbox.close();
        synchronized (this) {
            if (closed)
                return;
            closed = true;
            state = State.CLOSED;
        }
        try {
            onClosed.run();
        } catch (RuntimeException ignored) {
            /* 关闭回调失败不能掩盖原状态。 */ }
    }

    /**
     * 调用连接关闭流程，吸收收尾时的运行时异常，供超时和传输失败回调结束当前连接。
     */
    private void closeQuietly() {
        try {
            close();
        } catch (RuntimeException ignored) {
            /* 已关闭或底层流已断开。 */ }
    }
}
