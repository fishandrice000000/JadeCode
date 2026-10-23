package com.jadecode.cli.backend;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.backend.protocol.PayloadTransfer;
import com.jadecode.cli.backend.protocol.ProtocolError;
import com.jadecode.cli.backend.protocol.ProtocolException;
import com.jadecode.cli.backend.protocol.ProtocolSession;
import com.jadecode.cli.backend.protocol.WireCodec;
import com.jadecode.cli.backend.protocol.WireMessage;
import com.jadecode.cli.backend.transport.LocalControlServer;
import com.jadecode.config.JadeConfigFile;
import com.jadecode.config.JadeSettings;
import com.jadecode.safety.PermissionProfile;
import com.jadecode.safety.PermissionState;
import com.jadecode.util.Json;

/**
 * 交互模式的后端进程主体：拥有 socket、协议会话、串行服务与配置写者。
 *
 * <p>生命周期（与 Spec 一致）：</p>
 * <ol>
 *   <li>调用方（{@link com.jadecode.cli.InteractiveLauncher}）创建私有 UDS 端点并启动 Node；</li>
 *   <li>本类接受唯一连接，完成 hello/快照/ready 握手；</li>
 *   <li>Node 安装快照后 ready，首轮 prompt（若有）入队再启动消费者；</li>
 *   <li>Node 退出/EOF 或 {@code client.shutdown} → 拒绝审批、保存设置、关闭会话资源、清理端点。</li>
 * </ol>
 *
 * <p>它不读终端、不渲染、不启动第二个 JVM；headless 不经过这里，因此没有 Node 依赖。</p>
 */
public final class BackendApplication implements AutoCloseable {
    /** 一次交互启动的输入。 */
    public record Options(Path configPath, PermissionProfile permissionOverride,
            BackendSessionService.Startup startup) { }

    private static final long ACCEPT_TIMEOUT_MILLIS = 30_000;

    private final Options options;
    private final LocalControlServer server;
    private final String credential;
    private final String buildId;
    private final String connectionId = "conn-" + UUID.randomUUID().toString().substring(0, 8);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final PreLicense preLicense;

    /** 握手前就需要读取的配置/设置快照；避免 hello 期间再读盘失败。 */
    private record PreLicense(PreferenceService preferences, JadeSettings settings, PermissionState permissions,
            RuntimeFactory factory, String themeDirectory, String projectVersion) { }

    /**
     * @param server     已绑定、尚未接受连接的私有端点
     * @param credential 本次启动的唯一握手凭据（由启动器通过子进程环境交给 Node）
     * @param buildId    后端配对构建身份；与前端 manifest 不一致时握手失败
     */
    public BackendApplication(Options options, LocalControlServer server, String credential, String buildId)
            throws IOException {
        this.options = options;
        this.server = server;
        this.credential = credential;
        this.buildId = buildId;
        PreferenceService preferences = new PreferenceService(options.configPath());
        JadeSettings settings = preferences.state().settings();
        PermissionState permissions = settings.permissions().state(new JadeConfigFile(options.configPath()));
        if (options.permissionOverride() != null) permissions.select(options.permissionOverride());
        Path themeDirectory = options.configPath().toAbsolutePath().getParent().resolve("themes");
        this.preLicense = new PreLicense(preferences, settings, permissions,
                new RuntimeFactory(settings, permissions), themeDirectory.toString(),
                projectVersion());
    }

    /** @return 由构建过滤资源提供的真实项目版本；缺失时使用开发版本号。 */
    static String projectVersion() {
        try (InputStream stream = BackendApplication.class.getResourceAsStream("/META-INF/jade-build.properties")) {
            if (stream == null) return "0.1.0-SNAPSHOT";
            java.util.Properties properties = new java.util.Properties();
            properties.load(stream);
            return properties.getProperty("version", "0.1.0-SNAPSHOT");
        } catch (IOException ignored) {
            return "0.1.0-SNAPSHOT";
        }
    }

    /**
     * 阻塞运行直到连接结束；返回 0 表示有序退出，1 表示协议/IO/清理失败。
     */
    public int run() {
        ProtocolSession session = null;
        BackendDiagnostics diagnostics = null;
        try {
            SocketChannel channel;
            try {
                channel = server.acceptOne(ACCEPT_TIMEOUT_MILLIS);
            } catch (SocketTimeoutException timeout) {
                throw new IOException("The Node frontend did not connect within "
                        + (ACCEPT_TIMEOUT_MILLIS / 1000) + " seconds", timeout);
            }
            InputStream in = Channels.newInputStream(channel);
            OutputStream out = Channels.newOutputStream(channel);
            PayloadTransfer.Assembler assembler = new PayloadTransfer.Assembler();
            AtomicReference<BackendSessionService> serviceRef = new AtomicReference<>();
            AtomicReference<WireContext> wireRef = new AtomicReference<>();
            AtomicReference<ProtocolSession> sessionRef = new AtomicReference<>();

            ProtocolSession created = new ProtocolSession(out, credential, connectionId,
                    hello -> greeting(hello, wireRef),
                    command -> dispatchInbound(command, assembler, serviceRef, wireRef),
                    () -> finish(serviceRef, "frontend-disconnected"),
                    () -> ready(serviceRef));
            session = created;
            sessionRef.set(created);
            PayloadTransfer payloads = new PayloadTransfer(created.outbox());
            WireContext wire = new WireContext(created, payloads);
            wireRef.set(wire);
            diagnostics = BackendDiagnostics.install(wire);
            BackendSessionService service = new BackendSessionService(wire, preLicense.preferences(),
                    preLicense.factory(), snapshotSink(wire), message -> { }, options.startup());
            serviceRef.set(service);
            service.openInitial();

            // reader 线程：分帧、协议校验、分发；EOF 与致命协议错误都收口。
            Reader loop = new Reader(in, session, serviceRef);
            Thread reader = new Thread(loop, "jade-wire-reader");
            reader.setDaemon(true);
            reader.start();
            finished.await();
            reader.join(2000);
            return failure.get() == null ? 0 : 1;
        } catch (IOException startupFailure) {
            failure.set(startupFailure.getMessage());
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 1;
        } finally {
            if (session != null) session.close();
            if (diagnostics != null) diagnostics.close();
            server.close();
        }
    }

    /** 读取循环：协议错误按连接级处理，EOF 表示 Node 已退出。 */
    private final class Reader implements Runnable {
        private final InputStream in;
        private final ProtocolSession session;
        private final AtomicReference<BackendSessionService> serviceRef;

        Reader(InputStream in, ProtocolSession session, AtomicReference<BackendSessionService> serviceRef) {
            this.in = in;
            this.session = session;
            this.serviceRef = serviceRef;
        }

        @Override public void run() {
            try {
                WireMessage message;
                while ((message = WireCodec.readFrame(in)) != null) {
                    try {
                        session.receive(message);
                    } catch (ProtocolException rejected) {
                        session.sendError(message.commandId(), rejected.error(), rejected.getMessage(),
                                message.sessionId(), message.generation());
                        if (!rejected.error().recoverable()) {
                            failure.set(rejected.getMessage());
                            session.close();
                            break;
                        }
                    }
                }
            } catch (ProtocolException malformed) {
                failure.set(malformed.getMessage());
            } catch (IOException disconnected) {
                // 连接断开是正常退出路径之一（Node 崩溃/用户关窗口）；真实失败由 Node 侧报告。
            } finally {
                finish(serviceRef, "frontend-eof");
            }
        }
    }

    private void ready(AtomicReference<BackendSessionService> serviceRef) {
        BackendSessionService service = serviceRef.get();
        if (service != null) service.ready();
    }

    private void dispatchInbound(WireMessage command, PayloadTransfer.Assembler assembler,
            AtomicReference<BackendSessionService> serviceRef, AtomicReference<WireContext> wireRef) {
        WireContext wire = wireRef.get();
        try {
            switch (command.type()) {
                case "payload.begin" -> assembler.begin(command.payload().path("id").asText(""),
                        command.payload().path("kind").asText(""), command.payload().path("totalBytes").asInt(-1),
                        command.payload().path("sha256").asText(""));
                case "payload.chunk" -> assembler.chunk(command.payload().path("id").asText(""),
                        command.payload().path("index").asInt(-1), command.payload().path("text").asText(""));
                case "payload.end" -> {
                    String id = command.payload().path("id").asText("");
                    String text = assembler.end(id, command.payload().path("count").asInt(-1),
                            command.payload().path("sha256").asText(""));
                    BackendSessionService service = serviceRef.get();
                    if (service != null) service.registerBlob(id, text);
                }
                default -> {
                    BackendSessionService service = serviceRef.get();
                    if (service == null) {
                        wire.session().sendError(command.commandId(), ProtocolError.NOT_READY,
                                "The backend session is not initialized yet", null, null);
                        return;
                    }
                    service.dispatch(command);
                }
            }
        } catch (ProtocolException rejected) {
            wire.session().sendError(command.commandId(), rejected.error(), rejected.getMessage(),
                    wire.sessionId(), wire.generation());
        }
    }

    /** 构造 bootstrap：真实版本/设置/主题目录/启动意图；不含认证或完整业务配置。 */
    private ProtocolSession.Greeting greeting(WireMessage hello, AtomicReference<WireContext> wireRef) throws ProtocolException {
        if (wireRef.get() == null)
            throw new ProtocolException(ProtocolError.NOT_READY, "The connection is not initialized");
        ObjectNode helloPayload = Json.mapper().createObjectNode();
        helloPayload.put("connectionId", connectionId);
        helloPayload.put("protocolVersion", WireCodec.PROTOCOL_VERSION);
        helloPayload.put("buildId", buildId);
        ArrayNode capabilities = helloPayload.putArray("capabilities");
        capabilities.add("session.submit");
        capabilities.add("session.resume");
        capabilities.add("approval.presented");
        helloPayload.put("projectVersion", preLicense.projectVersion());

        BackendSessionService.Startup startup = options.startup();
        ObjectNode bootstrap = Json.mapper().createObjectNode();
        bootstrap.put("projectVersion", preLicense.projectVersion());
        bootstrap.put("buildId", buildId);
        bootstrap.put("protocolVersion", WireCodec.PROTOCOL_VERSION);
        bootstrap.put("workspace", Path.of("").toAbsolutePath().normalize().toString());
        bootstrap.put("themeDirectory", preLicense.themeDirectory());
        bootstrap.put("configRevision", Long.toString(preLicense.preferences().state().revision()));
        bootstrap.set("tui", tuiJson(preLicense.settings()));
        ObjectNode intent = bootstrap.putObject("startup");
        intent.put("mode", startup.mode());
        intent.put("autoSubmit", startup.autoSubmit());
        intent.put("resumeTarget", startup.resumeTarget() == null ? "" : startup.resumeTarget());
        return new ProtocolSession.Greeting(helloPayload, bootstrap);
    }

    private ObjectNode tuiJson(JadeSettings settings) {
        return (ObjectNode) BackendSnapshot.capture(0, null, null, settings, 0, List.of(), Map.of(),
                "", "", Map.of(), Map.of()).toJson().get("tui");
    }

    /** 完整快照：begin/chunk/end 三段，Node 校验 hash 后安装并发 ready。 */
    private BackendSessionService.SnapshotSink snapshotSink(WireContext wire) {
        return new BackendSessionService.SnapshotSink() {
            @Override public void full(String snapshotId, long generation, List<BackendHistoryProjector.Entry> entries,
                    BackendSnapshot snapshot) {
                ObjectNode begin = Json.mapper().createObjectNode();
                begin.put("snapshotId", snapshotId);
                begin.put("sessionId", wire.sessionId());
                begin.put("generation", Long.toString(generation));
                begin.put("entryCount", entries.size());
                begin.put("configRevision", Long.toString(preLicense.preferences().state().revision()));
                begin.put("sourceSeq", Long.toString(wire.session().sentSeq()));
                wire.session().sendEvent("session.snapshot.begin", begin, wire.sessionId(), generation);

                MessageDigest digest = sha256();
                int chunkIndex = 0;
                int offset = 0;
                while (offset < entries.size()) {
                    int end = offset;
                    ArrayNode array = Json.mapper().createArrayNode();
                    while (end < entries.size()) {
                        JsonNode node = Json.mapper().valueToTree(entries.get(end));
                        array.add(node);
                        end++;
                        // 单块目标 128KiB：既控制帧大小，也保证长历史不会一次性进内存两份。
                        if (array.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 128 * 1024) break;
                    }
                    String encoded = array.toString();
                    digest.update(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                    ObjectNode chunk = Json.mapper().createObjectNode();
                    chunk.put("snapshotId", snapshotId);
                    chunk.put("chunkIndex", chunkIndex++);
                    chunk.put("encodedEntries", encoded);
                    wire.session().sendEvent("session.snapshot.chunk", chunk, wire.sessionId(), generation);
                    offset = end;
                }
                ObjectNode end = Json.mapper().createObjectNode();
                end.put("snapshotId", snapshotId);
                end.put("totalChunks", chunkIndex);
                end.put("hash", HexFormat.of().formatHex(digest.digest()));
                end.set("snapshot", snapshot.toJson());
                wire.session().sendEvent("session.snapshot.end", end, wire.sessionId(), generation);
            }

            @Override public void state(BackendSnapshot snapshot) {
                ObjectNode payload = Json.mapper().createObjectNode();
                payload.set("snapshot", snapshot.toJson());
                wire.session().sendEvent("state.snapshot", payload, wire.sessionId(), wire.generation());
            }
        };
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }

    /** Node 断开/关闭：拒绝审批、关闭会话与依赖、恢复诊断流，然后结束等待。 */
    private void finish(AtomicReference<BackendSessionService> serviceRef, String reason) {
        if (!closed.compareAndSet(false, true)) return;
        BackendSessionService service = serviceRef.get();
        if (service != null) {
            try { service.shutdown(reason); } catch (RuntimeException failure) { /* 记录后继续清理。 */ }
        }
        finished.countDown();
    }

    @Override public void close() {
        finish(new AtomicReference<>(), "application-close");
        server.close();
    }

    /** 供测试：连接完成后等待的剩余时间。 */
    boolean awaitFinished(long millis) throws InterruptedException { return finished.await(millis, TimeUnit.MILLISECONDS); }
}
