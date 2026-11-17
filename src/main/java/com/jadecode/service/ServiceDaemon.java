package com.jadecode.service;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.backend.protocol.*;
import com.jadecode.config.*;
import com.jadecode.util.Json;

/**
 * 为每个用户数据根运行一个常驻 Java 服务。持有启动文件锁，发布私有 Unix socket 端点，
 * 接纳客户端身份探测、管理与会话连接；会话由 AgentService 持有，连接结束时释放该连接的控制资源。
 */
public final class ServiceDaemon {
    /**
     * 汇集常驻服务启动、连接处理和端点回收入口。
     */
    private ServiceDaemon() {
    }

    /**
     * 取得数据根下 daemon 锁，核验旧端点并创建私有 Unix socket，发布进程和构建身份。接纳最多 32 个连接，
     * 以独立虚拟线程和首帧十秒期限处理请求；停止时关闭服务、执行器和端点文件。成功或已有服务持锁返回 0，
     * 启动或清理异常返回 1。
     *
     * @return 正常停止或已有服务持锁时为 0，启动或清理失败时为 1
     */
    public static int run() {
        JadePaths paths = JadePaths.defaults();
        Path root = paths.root().resolve("service");
        Path socketDirectory = null;
        try {
            paths.directory(root, true);
            // 服务目录在工作区之外；只有取得 OS 锁的进程能发布端点或修改会话清单。
            if (Files.exists(root.resolve("daemon.lock"), LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(root.resolve("daemon.lock"), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Invalid daemon lock path");
            try (FileChannel lockFile = FileChannel.open(root.resolve("daemon.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                    FileLock lock = lockFile.tryLock()) {
                if (lock == null)
                    return 0;
                cleanupOldEndpoint(root);
                String build = ServerBuild.load().buildId();
                socketDirectory = Files.createTempDirectory("jade-service-", PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
                Path socket = socketDirectory.resolve("agent.sock");
                try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                        var service = new AgentService(paths, build, null)) {
                    server.bind(UnixDomainSocketAddress.of(socket));
                    Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
                    JadePaths.atomicWrite(root.resolve("endpoint.json"), Json.mapper().createObjectNode()
                            .put("socket", socket.toString()).put("pid", ProcessHandle.current().pid())
                            .put("started", ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli())
                            .put("buildId", build).toString());
                    Thread shutdown = new Thread(() -> {
                        service.close();
                        try {
                            server.close();
                        } catch (IOException ignored) {
                        }
                    });
                    Runtime.getRuntime().addShutdownHook(shutdown);
                    // 有界连接名额和首帧期限防止未握手客户端无限持有 reader。
                    var readers = Executors.newVirtualThreadPerTaskExecutor();
                    var timer = Executors.newSingleThreadScheduledExecutor();
                    var slots = new Semaphore(32);
                    try {
                        while (server.isOpen()) {
                            SocketChannel peer = server.accept();
                            if (!slots.tryAcquire()) {
                                peer.close();
                                continue;
                            }
                            readers.submit(() -> {
                                var deadline = timer.schedule(() -> {
                                    try {
                                        peer.close();
                                    } catch (IOException ignored) {
                                    }
                                }, 10, TimeUnit.SECONDS);
                                try (peer) {
                                    serve(service, peer, () -> {
                                        try {
                                            server.close();
                                        } catch (IOException ignored) {
                                        }
                                    }, deadline);
                                } catch (Exception failure) {
                                    /* 连接错误不会终止其他会话；协议答复在 serve 内发送。 */ } finally {
                                    deadline.cancel(false);
                                    slots.release();
                                }
                            });
                        }
                    } catch (AsynchronousCloseException stopped) {
                        /* 明确 service.stop 唤醒 accept。 */ } finally {
                        service.close();
                        timer.shutdownNow();
                        readers.shutdownNow();
                        Runtime.getRuntime().removeShutdownHook(shutdown);
                        Files.deleteIfExists(root.resolve("endpoint.json"));
                    }
                }
            }
            return 0;
        } catch (Exception failure) {
            System.err.println("[Service] Startup/cleanup failed: " + failure.getMessage());
            return 1;
        } finally {
            if (socketDirectory != null)
                try {
                    Files.deleteIfExists(socketDirectory.resolve("agent.sock"));
                    Files.deleteIfExists(socketDirectory);
                } catch (IOException failure) {
                    System.err.println("[Service] Socket cleanup incomplete");
                }
        }
    }

    /**
     * 在一条 Unix socket 上处理服务前导，再接入管理、传输或会话协议。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>读取首帧，可先回应 service.identity，并继续在同一输入流读取后续命令。</li>
     * <li>工作区传输在该连接上逐块往返；list、cancel、close 和 stop 分派到服务管理入口。</li>
     * <li>service.open 选择运行组并生成随机一次性凭据，先回复打开结果，再把后续帧送到本连接 ProtocolSession。</li>
     * <li>接收帧直至 EOF；协议错误按 recoverable 决定继续或结束连接。外层服务操作错误尝试返回 service.result。</li>
     * </ol>
     *
     * <p>连接关闭后释放本控制连接资源，运行组仍由 AgentService 持有。</p>
     *
     * @param service 当前连接所属的常驻 Agent 服务
     * @param peer 本次接入的 Unix socket 客户端连接
     * @param stop 关闭监听 socket、唤醒服务 accept 的回调
     * @param deadline 首帧等待期限对应的调度任务，前导完成后取消
     * @throws Exception 服务前导、管理操作或会话协议连接无法完成，且错误答复也未成功写出
     */
    static void serve(AgentService service, SocketChannel peer, Runnable stop, ScheduledFuture<?> deadline)
            throws Exception {
        InputStream in = ChannelStreams.input(peer);
        OutputStream out = ChannelStreams.output(peer);
        WireMessage request = WireCodec.readFrame(in);
        if (request == null)
            return;
        try {
            // 身份探测不创建会话；后续前导和握手复用同一连接，不能丢失客户端已发的字节。
            if (request.type().equals("service.identity")) {
                var build = ServerBuild.load();
                reply(out, Json.mapper().createObjectNode().put("pid", ProcessHandle.current().pid())
                        .put("started", ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli())
                        .put("buildId", build.buildId()).put("protocolVersion", build.protocolVersion())
                        .put("paired", build.paired()));
                request = WireCodec.readFrame(in);
                if (request == null)
                    return;
            }
            // 文件按块往返同一 SSH/UDS 连接，避免每块重新启动 SSH 或把大 ZIP 放进单帧。
            if (request.type().equals("service.workspace")) {
                deadline.cancel(false);
                do {
                    if (!request.type().equals("service.workspace"))
                        throw new IOException("Expected workspace transfer command");
                    reply(out, service.workspaceCommand(request.payload()));
                } while ((request = WireCodec.readFrame(in)) != null);
                return;
            }
            switch (request.type()) {
                case "service.list" -> {
                    reply(out, service.list());
                    return;
                }
                case "service.cancel" -> {
                    service.stopSession(request.payload().path("id").asText());
                    reply(out, Json.mapper().createObjectNode().put("status", "stopping"));
                    return;
                }
                case "service.close" -> {
                    service.closeSession(request.payload().path("id").asText());
                    reply(out, Json.mapper().createObjectNode());
                    return;
                }
                case "service.stop" -> {
                    try {
                        service.close();
                        if (!service.list().path("sessions").isEmpty())
                            throw new IOException("Service cleanup is incomplete; original registry was preserved");
                        reply(out, Json.mapper().createObjectNode().put("status", "stopped"));
                    } finally {
                        stop.run();
                    }
                    return;
                }
                case "service.open" -> {
                }
                default -> throw new IOException("Expected service.open or management command");
            }
            // 创建会话只准备运行时；模型和 cron 要等客户端安装快照并通过 ready 屏障。
            AgentService.Managed managed = service.open(request.payload());
            String credential = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
            try (ProtocolSession protocol = managed.protocol(out, credential, () -> {
                try {
                    peer.close();
                } catch (IOException ignored) {
                }
            }, request.payload())) {
                reply(out, Json.mapper().createObjectNode().put("id", managed.id).put("credential", credential));
                deadline.cancel(false);
                WireMessage message;
                while ((message = WireCodec.readFrame(in)) != null)
                    try {
                        protocol.receive(message);
                    } catch (ProtocolException rejected) {
                        protocol.sendError(message.commandId(), rejected.error(), rejected.getMessage(),
                                message.sessionId(), message.generation());
                        if (!rejected.error().recoverable())
                            break;
                    }
            }
        } catch (Exception failure) {
            reply(out, Json.mapper().createObjectNode().put("error",
                    failure.getMessage() == null ? "Service operation failed" : failure.getMessage()));
        }
    }

    /**
     * 生成 32 字节安全随机数，供 service.open 创建本连接的一次性握手凭据。
     *
     * @return 用于生成本连接一次性凭据的 32 字节随机数
     */
    private static byte[] randomBytes() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /**
     * 读取旧端点并比较 PID 与启动时间，旧所有者存活时报错；确认已退出后，核对临时 socket 所在的固定目录布局，
     * 清理该 socket 与发现文件。
     *
     * @param root 当前用户数据根下的 service 目录
     * @throws IOException 旧端点格式或文件类型非法、旧所有者仍存活，或残留 socket 清理失败
     */
    private static void cleanupOldEndpoint(Path root) throws IOException {
        Path metadata = root.resolve("endpoint.json");
        if (!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS))
            return;
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid endpoint metadata");
        var previous = Json.mapper().readTree(Files.readString(metadata));
        var owner = ProcessHandle.of(previous.path("pid").asLong());
        if (owner.filter(ProcessHandle::isAlive).map(p -> p.info().startInstant()
                .map(t -> t.toEpochMilli() == previous.path("started").asLong()).orElse(true)).orElse(false))
            throw new IOException("Previous endpoint owner is still alive");
        Path socket = Path.of(previous.path("socket").asText()).toAbsolutePath().normalize();
        Path parent = socket.getParent(), temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (parent != null && parent.getParent().equals(temporary)
                && parent.getFileName().toString().startsWith("jade-service-")
                && socket.getFileName().toString().equals("agent.sock")
                && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(socket);
            Files.deleteIfExists(parent);
        }
        Files.delete(metadata);
    }

    /**
     * 将服务操作载荷编码为一条 service.result 帧，写入当前连接并立即刷新；编码或写入失败交给连接处理流程。
     *
     * @param out 当前连接的输出流
     * @param p 服务前导或管理操作的 JSON 结果载荷
     * @throws Exception service.result 序列化、写入或刷新失败
     */
    private static void reply(OutputStream out, ObjectNode p) throws Exception {
        out.write(WireCodec.encode(WireMessage.event(1, "", "service.result", null, null, p)));
        out.flush();
    }
}
