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
 * 将远端 SSH 的标准输入输出接到同机 Java 常驻服务。读取私有端点并核对进程身份，必要时启动 daemon，
 * 随后双向转发字节；服务会话生命周期由显式管理命令控制。
 */
public final class ServiceRelay {
    /**
     * 汇集 SSH 标准流与远端常驻服务之间的连接及转发入口。
     */
    private ServiceRelay() {
    }

    /**
     * 一条连接拥有自身 IO 和 socket；close 不发停止会话命令。
     */
    private static final class Peer implements AutoCloseable {
        public final InputStream input;
        public final OutputStream output;
        private final AutoCloseable resource;

        /**
         * 保存到远端常驻服务的输入、输出流及底层连接资源，供 relay 两个方向转发字节。
         *
         * @param input 从当前连接读取字节的输入流
         * @param output 命令有界输出或子代理最终结果正文
         * @param resource 随 relay 连接一起关闭的底层 socket 资源
         */
        Peer(InputStream input, OutputStream output, AutoCloseable resource) {
            this.input = input;
            this.output = output;
            this.resource = resource;
        }

        /**
         * 关闭 relay 持有的服务连接，资源关闭失败包装为 IOException 交给转发流程收尾。
         *
         * @throws IOException 底层服务连接关闭失败
         */
        @Override
        public void close() throws IOException {
            try {
                resource.close();
            } catch (Exception failure) {
                throw new IOException("Service connection close failed", failure);
            }
        }
    }

    /**
     * 读取私有端点并核对 PID、启动时间和 socket，建立到常驻服务的连接。launch 为 true 且连接失败时启动 daemon，
     * 十五秒内重试端点；失败或中断以 IOException 返回。
     *
     * @param launch 端点不可用时是否允许按需启动 daemon
     * @return 已连接本机服务并持有输入输出与 socket 资源的 Peer
     * @throws IOException 服务端点不可用、身份过期、启动超时或连接等待被中断
     */
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

    /**
     * 核对或创建私有 daemon 日志，用 setsid 启动当前 Java 类路径下的 Main --service-daemon，stdin 接 /dev/null，
     * stdout 和 stderr 追加到日志。
     *
     * @param root 执行机器用户数据根下的 service 目录
     * @throws IOException 服务日志类型不合法、日志创建或独立 Java 进程启动失败
     */
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

    /**
     * 把当前 JVM 类路径的每一项转为规范化绝对路径，再按平台分隔符拼接，供独立 daemon 定位同一套类与 JAR。
     *
     * @return 当前 JVM 各类路径转为绝对路径后重新拼接的字符串
     */
    private static String absoluteClasspath() {
        return Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(part -> Path.of(part).toAbsolutePath().normalize().toString())
                .reduce((a, b) -> a + java.io.File.pathSeparator + b).orElseThrow();
    }

    /**
     * 建立或启动本机服务连接，以独立转发任务将 SSH stdin 写到服务、将服务输出送到 stdout。
     * 任一方向结束时关闭本连接并完成 relay 收尾，返回相应退出状态。
     *
     * @return 双向转发正常结束时为 0，连接或转发失败时为 1
     */
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
