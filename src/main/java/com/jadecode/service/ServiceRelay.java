package com.jadecode.service;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import com.jadecode.config.JadePaths;
import com.jadecode.util.Json;

/**
 * SSH 远端的服务适配入口：只连接该机器的私有 UDS，必要时启动本机 daemon。
 * 不解析客户端命令、不启动 Node；stdin EOF 只解绑连接，不停止服务会话。
 */
public final class ServiceRelay {
    private ServiceRelay() {
    }

    /** 一条连接拥有自身 IO 和 socket；close 不发停止会话命令。 */
    private static final class Peer implements AutoCloseable {
        public final InputStream input;
        public final OutputStream output;
        private final AutoCloseable resource;

        Peer(InputStream input, OutputStream output, AutoCloseable resource) {
            this.input = input;
            this.output = output;
            this.resource = resource;
        }

        @Override
        public void close() throws IOException {
            try {
                resource.close();
            } catch (Exception failure) {
                throw new IOException("Service connection close failed", failure);
            }
        }
    }

    /** 私有发现文件只含 socket 与进程身份；不读取任何模型认证。 */
    private static Peer local(boolean launch) throws IOException {
        JadePaths paths = JadePaths.defaults();
        Path root = paths.root().resolve("service");
        paths.directory(root, true);
        try {
            Path endpoint = root.resolve("endpoint.json");
            if (!Files.isRegularFile(endpoint, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("No service endpoint");
            var value = Json.mapper().readTree(Files.readString(endpoint));
            long pid = value.path("pid").asLong(), start = value.path("started").asLong();
            var process = ProcessHandle.of(pid);
            if (process.isEmpty() || !process.get().isAlive() || process.get().info().startInstant()
                    .map(t -> t.toEpochMilli() != start).orElse(true))
                throw new IOException("Service endpoint is stale");
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                channel.connect(UnixDomainSocketAddress.of(value.path("socket").asText()));
                return new Peer(ChannelStreams.input(channel), ChannelStreams.output(channel), channel);
            } catch (IOException failure) {
                channel.close();
                throw failure;
            }
        } catch (IOException failure) {
            if (!launch)
                throw failure;
            start(root);
            long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < end) {
                try {
                    return local(false);
                } catch (IOException notReady) {
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                }
            }
            throw new IOException("Agent service did not start; see " + root.resolve("daemon.log"));
        }
    }

    /** 独立进程不继承终端输入；setsid 使终端关闭不会向 daemon 发送挂断信号。 */
    private static void start(Path root) throws IOException {
        Path log = root.resolve("daemon.log");
        if (Files.exists(log, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid service log path");
        if (!Files.exists(log))
            Files.createFile(log, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        var args = new ArrayList<>(
                List.of("/usr/bin/setsid", "--", Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-Duser.home=" + System.getProperty("user.home"), "-cp", absoluteClasspath(),
                        "com.jadecode.Main", "--service-daemon"));
        new ProcessBuilder(args).redirectInput(new File("/dev/null"))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
    }

    /** 将启动时的类路径固定为绝对路径，避免 daemon 的工作目录影响 JAR 定位。 */
    private static String absoluteClasspath() {
        return Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(part -> Path.of(part).toAbsolutePath().normalize().toString())
                .reduce((a, b) -> a + java.io.File.pathSeparator + b).orElseThrow();
    }

    /** SSH 远端固定入口；只转发字节，不渲染，不加载模型，EOF 不停止 daemon。 */
    public static int relay() {
        var inputEnded = new java.util.concurrent.atomic.AtomicBoolean();
        try (Peer peer = local(true)) {
            var writer = Thread.ofVirtual().start(() -> {
                try {
                    System.in.transferTo(peer.output);
                    inputEnded.set(true);
                } catch (IOException ignored) {
                } finally {
                    try {
                        peer.close();
                    } catch (IOException ignored) {
                    }
                }
            });
            peer.input.transferTo(System.out);
            System.out.flush();
            writer.interrupt();
            return 0;
        } catch (IOException failure) {
            if (inputEnded.get())
                return 0;
            System.err.println("Service relay failed: " + failure.getMessage());
            return 1;
        }
    }
}
