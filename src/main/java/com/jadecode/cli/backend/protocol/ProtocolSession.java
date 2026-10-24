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
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 一条控制连接的协议状态机：握手 → 快照同步 → ready → 收口。
 *
 * <p>它只做“连接层”的判断：凭据/版本/构建身份、连接唯一性、方向内序号、命令去重、
 * ready 屏障和序号分配。它不执行任何会话操作，也不读终端；业务命令交给
 * {@link CommandSink}，由后端串行服务再校验会话代次。</p>
 *
 * <p>握手只允许在 {@code ACCEPTED} 状态发生一次。{@code session.ready} 只有在 Node 完整安装
 * 了最近一次快照后才被接受，因此首轮任务、cron 和正文事件不会抢在历史清屏之前出现。</p>
 */
public final class ProtocolSession implements AutoCloseable {
    /** 连接状态；只有 READY 接受会话命令。 */
    public enum State { ACCEPTED, HELLO, SYNC, READY, CLOSING, CLOSED }

    /** 握手结果：服务端 hello 与首份 bootstrap 资料。 */
    public record Greeting(ObjectNode helloPayload, ObjectNode bootstrapPayload) { }

    /** 握手校验器：由后端提供真实构建身份、协议版本与启动资料。 */
    @FunctionalInterface
    public interface Greeter {
        Greeting greet(WireMessage hello) throws ProtocolException;
    }

    /** 业务命令接收器；在协议层校验之后调用。 */
    @FunctionalInterface
    public interface CommandSink {
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
    /** 快照 begin 的来源 seq；ready 必须回传同一值（null 表示旧调用未提供）。 */
    private Long snapshotSourceSeq;
    /** 是否在等待一次新的 ready；切换会话/新打开会话都会重新置位。 */
    private boolean awaitingReady;
    private final Map<String, CachedResult> cachedResults = new LinkedHashMap<>();
    private final List<String> seenCommandIds = new ArrayList<>();
    private int handshakeTicks;
    private boolean closed;
    /** 最近一次 ready 的客户端序号，仅用于诊断；ready 不做结果缓存。 */
    private long localStorageReadySeq;

    private record CachedResult(String type, ObjectNode payload, String sessionId, Long generation) { }

    /**
     * @param out          连接输出流；写线程与序号分配由本类串行化
     * @param credential   本次启动唯一的一次性凭据（不写日志、不进 argv）
     * @param connectionId Java 分配的连接身份
     */
    public ProtocolSession(OutputStream out, String credential, String connectionId,
            Greeter greeter, CommandSink commands, Runnable onClosed) {
        this(out, credential, connectionId, greeter, commands, onClosed, () -> { });
    }

    /**
     * 允许会话切换时再次经过 ready 屏障：每次 {@link #expectReady} 之后，Node 都必须先安装
     * 完整快照再发 ready，Java 才会启动该代次的消费者/首轮任务。
     */
    public ProtocolSession(OutputStream out, String credential, String connectionId,
            Greeter greeter, CommandSink commands, Runnable onClosed, Runnable onReady) {
        this(out, credential, connectionId, greeter, commands, onClosed, onReady, () -> { });
    }

    /**
     * @param onHello server.hello/server.bootstrap 已入队后调用；后端在这里才打开会话并发送快照，
     *                从而保证 Node 先完成 bootstrap 初始化再安装快照。
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
        this.outbox = new EventOutbox(this::writeFrame, failure -> closeQuietly());
        // 手 shake 期限用“活跃 tick”累计：进程被 Ctrl+Z 暂停时 tick 也暂停，正常暂停不算失联。
        handshakeTimer.scheduleAtFixedRate(this::handshakeTick, 1, 1, TimeUnit.SECONDS);
    }

    /** @return 传输层/outbox 的第一次致命失败；null 表示没有。 */
    public String failureReason() {
        RuntimeException failure = outbox.failure();
        return failure == null ? null : failure.getMessage();
    }

    /** @return 本连接的唯一事件出口；大资料分块器与观察器共用一个有界队列。 */
    public EventOutbox outbox() { return outbox; }

    /** @return 当前连接状态；测试与诊断使用。 */
    public synchronized State state() { return state; }

    /** @return 握手后已发送事件/答复的数量（下一个 seq 减一）。 */
    public synchronized long sentSeq() { return nextSeq - 1; }

    /** 登记最近一次快照；Node 的 {@code session.ready} 必须引用同一 snapshotId。 */
    public synchronized void expectReady(String id, Long generation) {
        expectReady(id, generation, null);
    }

    /** @param sourceSeq 快照 begin 事件携带的来源序号；ready 必须原样回传 */
    public synchronized void expectReady(String id, Long generation, Long sourceSeq) {
        snapshotId = id;
        snapshotGeneration = generation;
        snapshotSourceSeq = sourceSeq;
        awaitingReady = true;
        if (state == State.HELLO || state == State.READY) state = State.SYNC;
    }

    /** 发送一个事件（不合并）。 */
    public void sendEvent(String type, ObjectNode payload, String sessionId, Long generation) {
        outbox.offer(type, payload, sessionId, generation, null);
    }

    /** 发送一个可以与前一个相邻同键增量合并的正文/预览事件。 */
    public void sendStreamEvent(String type, ObjectNode payload, String sessionId, Long generation, String mergeKey) {
        outbox.offer(type, payload, sessionId, generation, mergeKey);
    }

    /** 发送命令答复，并请求尽快送出。 */
    public void sendResult(String commandId, String type, ObjectNode payload, String sessionId, Long generation) {
        cachedResults.put(commandId, new CachedResult(type, payload, sessionId, generation));
        while (cachedResults.size() > MAX_CACHED_RESULTS) {
            String oldest = cachedResults.keySet().iterator().next();
            cachedResults.remove(oldest);
        }
        outbox.offerResult(type, payload, commandId, sessionId, generation);
    }

    /** 发送可恢复的命令错误；不会执行业务。 */
    public void sendError(String commandId, ProtocolError error, String message, String sessionId, Long generation) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("status", "error");
        payload.put("code", error.name());
        payload.put("message", message == null ? "" : message);
        payload.put("retryable", error.recoverable());
        sendResult(commandId, "command.error", payload, sessionId, generation);
    }

    /** 发送真正的命令成功结果（dispatched/ok 等由 payload 决定）。 */
    public void sendOk(String commandId, ObjectNode payload, String sessionId, Long generation) {
        sendResult(commandId, "command.result", payload, sessionId, generation);
    }

    /**
     * 处理一条已经通过编解码校验的入站消息。
     *
     * @throws ProtocolException 连接级错误（调用方应回错误并关闭）；可恢复错误在内部转成答复
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
        if (isDuplicate(message)) return;
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

    private void hello(WireMessage message) throws ProtocolException {
        synchronized (this) {
            if (state != State.ACCEPTED)
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "The handshake already happened");
        }
        JsonNode payload = message.payload();
        int version = payload.path("protocolVersion").asInt(-1);
        if (version != WireCodec.PROTOCOL_VERSION)
            throw new ProtocolException(ProtocolError.VERSION_MISMATCH, "Frontend protocol version " + version + " is not supported");
        String presented = payload.path("credential").asText("");
        if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), credential.getBytes(StandardCharsets.UTF_8)))
            throw new ProtocolException(ProtocolError.UNAUTHORIZED, "The one-time connection credential is invalid");
        String buildId = payload.path("buildId").asText("");
        Greeting greeting = greeter.greet(message);
        // 构建身份必须在发出任何启动资料之前核对；错误前端不会先创建会话或执行首轮。
        if (!buildId.equals(greeting.helloPayload().path("buildId").asText("")))
            throw new ProtocolException(ProtocolError.BUILD_MISMATCH, "Frontend and backend builds do not match");
        if (greeting.helloPayload().path("protocolVersion").asInt(-1) != WireCodec.PROTOCOL_VERSION)
            throw new ProtocolException(ProtocolError.VERSION_MISMATCH, "The backend greeting has a different protocol version");
        synchronized (this) { state = State.HELLO; }
        sendEvent("server.hello", greeting.helloPayload(), null, null);
        sendEvent("server.bootstrap", greeting.bootstrapPayload(), null, null);
        // 只有 hello/bootstrap 已入队后，后端才打开会话并发送快照。
        onHello.run();
    }

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
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "session.ready must reference the current snapshot");
        if (snapshotGeneration == null || generation != snapshotGeneration)
            throw new ProtocolException(ProtocolError.STALE_GENERATION, "session.ready referenced a stale session generation");
        // lastSnapshotSeq 是 Node 已安装快照的来源序号；帧仍在 outbox 中时 sentSeq 可能滞后，
        // 因此这里只做十进制/非负校验。真正的屏障由 snapshotId + generation 保证。
        long lastSeq = Long.parseLong(seqNode.asText());
        if (lastSeq < 0)
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "session.ready references an invalid snapshot sequence");
        if (snapshotSourceSeq != null && lastSeq != snapshotSourceSeq)
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE,
                    "session.ready must echo the snapshot begin sourceSeq");
        synchronized (this) { state = State.READY; awaitingReady = false; }
        handshakeTimer.shutdownNow();
        onReady.run();
    }

    /** 序号/命令 ID 去重：重复的已答复命令回缓存结果；否则回 DUPLICATE 且不执行。 */
    private boolean isDuplicate(WireMessage message) {
        synchronized (this) {
            // ready 是屏障命令，不是可缓存的业务命令；重复 ready 由 ready() 自身幂等处理。
            if (message.type().equals("session.ready")) {
                if (message.seq() <= lastClientSeq) localStorageReadySeq = message.seq();
                lastClientSeq = Math.max(lastClientSeq, message.seq());
                return false;
            }
            boolean oldSeq = message.seq() <= lastClientSeq;
            boolean seenId = message.commandId() != null && seenCommandIds.contains(message.commandId());
            if (!oldSeq && !seenId) {
                lastClientSeq = Math.max(lastClientSeq, message.seq());
                if (message.commandId() != null) {
                    seenCommandIds.add(message.commandId());
                    if (seenCommandIds.size() > 256) seenCommandIds.removeFirst();
                }
                return false;
            }
            CachedResult cached = message.commandId() == null ? null : cachedResults.get(message.commandId());
            if (cached != null) {
                outbox.offerResult(cached.type(), cached.payload(), message.commandId(), cached.sessionId(), cached.generation());
            } else {
                sendError(message.commandId(), ProtocolError.DUPLICATE,
                        "This command was already processed; it was not executed again",
                        message.sessionId(), message.generation());
            }
            return true;
        }
    }

    private void requireReady() throws ProtocolException {
        synchronized (this) {
            if (state != State.READY)
                throw new ProtocolException(ProtocolError.NOT_READY, "The frontend has not completed its snapshot handshake");
        }
    }

    /** 真实 socket 写入；序号在合并完成后、实际发帧时分配，与入队顺序分开记账。 */
    private void writeFrame(String type, ObjectNode payload, String sessionId, Long generation, String commandId)
            throws IOException {
        long seq;
        synchronized (this) {
            if (closed) throw new IOException("The connection is closed");
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

    private void handshakeTick() {
        synchronized (this) {
            if (state == State.READY || state == State.SYNC || closed) { handshakeTimer.shutdownNow(); return; }
            handshakeTicks++;
            if (handshakeTicks < 10) return;
        }
        // 10 个活跃 tick 仍未完成握手：不创建模型任务，直接收口连接。
        closeQuietly();
    }

    /** 先把已入队答复/事件送出，再走正常收口；用于 server.stopped 之后的最终关闭。 */
    public void flushAndClose() {
        outbox.flushNow();
        close();
    }

    /** 请求有序收口：拒绝新事件、排空已入队答复，最后关闭与回调。 */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            state = State.CLOSING;
        }
        handshakeTimer.shutdownNow();
        // outbox.close() 会先排空已入队事件（有界等待），再停止接收新事件。
        outbox.close();
        synchronized (this) {
            if (closed) return;
            closed = true;
            state = State.CLOSED;
        }
        try { onClosed.run(); } catch (RuntimeException ignored) { /* 关闭回调失败不能掩盖原状态。 */ }
    }

    private void closeQuietly() {
        try { close(); } catch (RuntimeException ignored) { /* 已关闭或底层流已断开。 */ }
    }
}
