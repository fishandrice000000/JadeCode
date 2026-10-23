package com.jadecode.cli.backend.transport;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 本次启动独占的私有 Unix domain socket 端点。
 *
 * <p>为什么用 UDS 而不是 TCP/HTTP：连接只服务同一台机器上由 Java 自己启动的那一个 Node
 * 进程，不需要端口、认证服务、远程调用或守护进程。目录 0700、socket 0600，只接受一个
 * 控制连接；第二个连接被立即关闭，不提供多客户端或重连语义。</p>
 *
 * <p>路径放在系统临时目录下的短名称私有目录里，避免 Unix socket 路径长度上限；退出时只
 * 清理本次创建并核验过的文件和目录，不碰其它进程的端点。</p>
 */
public final class LocalControlServer implements AutoCloseable {
    private final Path directory;
    private final Path socketPath;
    private final ServerSocketChannel server;
    private final AtomicBoolean closed = new AtomicBoolean();

    private LocalControlServer(Path directory, Path socketPath, ServerSocketChannel server) {
        this.directory = directory;
        this.socketPath = socketPath;
        this.server = server;
    }

    /**
     * 创建私有目录并绑定一个全新的 socket。
     *
     * @return 已就绪、等待 Node 连接的端点
     */
    public static LocalControlServer open() throws IOException {
        Path directory = Files.createTempDirectory("jade-" + UUID.randomUUID().toString().substring(0, 8) + "-");
        setPermissions(directory, "rwx------");
        Path socketPath = directory.resolve("ctl.sock");
        ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.bind(UnixDomainSocketAddress.of(socketPath));
            setPermissions(socketPath, "rw-------");
        } catch (IOException failure) {
            closeQuietly(channel);
            deleteOwned(directory);
            throw failure;
        }
        return new LocalControlServer(directory, socketPath, channel);
    }

    /** @return 实际 socket 路径；通过子进程环境传给 Node，不写日志。 */
    public Path socketPath() { return socketPath; }

    /**
     * 接受唯一的控制连接，随后关闭监听：之后到来的第二个连接会被操作系统直接拒绝。
     *
     * @param timeoutMillis 最多等待多久；0 表示一直等
     * @return 已连接的 socket
     */
    public SocketChannel acceptOne(long timeoutMillis) throws IOException {
        if (closed.get()) throw new IOException("The control endpoint is closed");
        server.configureBlocking(true);
        if (timeoutMillis > 0) {
            // 用非阻塞轮询实现有界等待：accept 本身没有超时参数。
            server.configureBlocking(false);
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            try {
                while (System.nanoTime() < deadline) {
                    SocketChannel accepted = server.accept();
                    if (accepted != null) return finishAccept(accepted);
                    try { Thread.sleep(10); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting for the frontend connection", interrupted);
                    }
                }
            } finally {
                if (!closed.get()) server.configureBlocking(true);
            }
            throw new java.net.SocketTimeoutException("The frontend did not connect to the control endpoint");
        }
        return finishAccept(server.accept());
    }

    private SocketChannel finishAccept(SocketChannel accepted) throws IOException {
        if (accepted == null) throw new IOException("The control endpoint closed before a connection arrived");
        // 只服务一个连接：立刻关闭监听通道，第二个连接不会排队。
        closeQuietly(server);
        closed.set(true);
        accepted.configureBlocking(true);
        return accepted;
    }

    /** 关闭端点并清理本次拥有的 socket/目录；重复调用无副作用。 */
    @Override
    public void close() {
        closed.set(true);
        closeQuietly(server);
        deleteOwned(socketPath);
        deleteOwned(directory);
    }

    private static void closeQuietly(Channel channel) {
        try { channel.close(); } catch (IOException ignored) { /* 关闭失败不影响后续清理。 */ }
    }

    /** 只删除本对象创建的普通文件/空目录；路径被替换时不递归删除别人的内容。 */
    private static void deleteOwned(Path path) {
        try {
            if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> entries = Files.list(path)) {
                    if (entries.findAny().isPresent()) return;
                }
                Files.deleteIfExists(path);
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) { /* 临时目录残留不会破坏会话正确性。 */ }
    }

    private static void setPermissions(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException ignored) {
            // 非 POSIX 文件系统没有权限模型；连接仍然只在本机临时目录内。
        }
    }

    /** 供测试核验目录内容（退出后应为空或被删除）。 */
    static long ownedEntryCount(Path directory) throws IOException {
        if (!Files.exists(directory)) return 0;
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.sorted(Comparator.naturalOrder()).count();
        }
    }
}
