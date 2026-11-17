package com.jadecode.sandbox;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * 以参数数组调用本地 Docker，并统一处理进程环境、输入输出、取消和超时。初始化固定 Unix socket 与运行条件；
 * 每次调用持续读取合并输出，最多保留 1048576 个字符，执行结果交给 DockerRuntime 判断和清理。
 */
public final class DockerCli {
    /**
     * 保存一次 Docker CLI 调用的退出码和有界合并输出，供运行时判断容器创建、启动、执行或删除结果。
     *
     * @param exitCode 实际进程退出码；null 表示未取得正常退出状态
     * @param output 命令有界输出或子代理最终结果正文
     */
    public record Reply(int exitCode, String output) {
    }

    private String endpoint;

    /**
     * 读取当前 Docker context 的本地 Unix socket，查询 daemon 的 Linux、cgroup v2、资源限制和 seccomp 能力。
     * 通过后固定 endpoint；条件不符清空 endpoint 并抛出 IOException。
     *
     * @throws IOException 当前 context 不是可用本地 Unix socket，或 daemon 缺少要求的隔离能力
     */
    public synchronized void initialize() throws IOException {
        if (endpoint != null)
            return;
        Reply context = raw(List.of("/usr/bin/docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"),
                "", 15, () -> false);
        String socket = context.output().strip();
        if (context.exitCode() != 0 || !socket.startsWith("unix:///") || socket.contains("\n"))
            throw new IOException("Sandbox requires an available local Docker Unix socket");
        endpoint = socket;
        Reply info = call(List.of("info", "--format",
                "{{.OSType}} {{.CgroupVersion}} {{.MemoryLimit}} {{.SwapLimit}} {{.CPUCfsQuota}} {{.PidsLimit}} {{json .SecurityOptions}}"),
                "", 15, () -> false);
        if (info.exitCode() != 0 || !info.output().startsWith("linux 2 true true true true ")
                || !info.output().contains("seccomp")) {
            endpoint = null;
            throw new IOException(
                    "Sandbox requires Linux Docker with seccomp, cgroup v2 and enforceable memory/swap/CPU/PID limits");
        }
    }

    /**
     * 为当前操作生成固定 endpoint 的 Docker 参数，再用统一 IO 与超时流程执行，返回退出码和有界合并输出。
     *
     * @param args Docker 子命令及其独立参数，按列表传给 ProcessBuilder
     * @param input 需要写入 Docker CLI 标准输入的完整 UTF-8 请求文本
     * @param timeoutSeconds 每次命令或容器操作的正数等待上限，单位秒
     * @param cancelled 执行过程中读取的取消检查函数
     * @return 当前 Docker 操作的实际退出码与有界合并输出
     * @throws IOException 尚未初始化本地 endpoint，或 CLI 启动、执行与结果采集失败
     */
    public Reply call(List<String> args, String input, long timeoutSeconds, BooleanSupplier cancelled)
            throws IOException {
        return raw(command(args), input, timeoutSeconds, cancelled);
    }

    /**
     * 将参数附到 /usr/bin/docker --host 和已固定 socket 后，返回不可变 argv 列表，供普通调用与 MCP stdio 启动使用；
     * 尚未初始化时抛出 IOException。
     *
     * @param args Docker 子命令及其独立参数，按列表传给 ProcessBuilder
     * @return 包含固定本地 endpoint 的完整不可变 Docker argv
     * @throws IOException Docker endpoint 尚未初始化
     */
    public List<String> command(List<String> args) throws IOException {
        String socket = endpoint;
        if (socket == null)
            throw new IOException("Docker sandbox is not initialized");
        var command = new ArrayList<>(List.of("/usr/bin/docker", "--host", socket));
        command.addAll(args);
        return List.copyOf(command);
    }

    /**
     * 用保留的少量环境变量启动命令，分别用虚拟线程写入 UTF-8 请求和持续排空合并输出。主线程观察退出、取消和总超时，
     * 正常结束取得退出码与输出；超时、中断或 IO 未完成抛出 IOException，最终结束 CLI 进程并取消读取。
     *
     * @param command 已经固定可执行程序和各参数的 argv 列表
     * @param input 需要写入 Docker CLI 标准输入的完整 UTF-8 请求文本
     * @param timeoutSeconds 每次命令或容器操作的正数等待上限，单位秒
     * @param cancelled 执行过程中读取的取消检查函数
     * @return CLI 进程正常结束后的退出码与有界输出
     * @throws IOException CLI 进程启动失败、超时、取消、中断或输入输出无法完整完成
     */
    private static Reply raw(List<String> command, String input, long timeoutSeconds, BooleanSupplier cancelled)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        var original = new HashMap<>(builder.environment());
        builder.environment().clear();
        for (String key : List.of("HOME", "PATH", "LANG", "LC_ALL", "XDG_RUNTIME_DIR", "DOCKER_CONFIG",
                "DOCKER_CONTEXT"))
            if (original.containsKey(key))
                builder.environment().put(key, original.get(key));
        // 选定本地 socket 后固定 endpoint，后续用户环境中的 context 不能改变连接目标。
        if (command.contains("--host"))
            builder.environment().remove("DOCKER_CONTEXT");
        Process process = builder.start();
        FutureTask<String> read = new FutureTask<>(() -> {
            try (var stream = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                var out = new StringBuilder();
                char[] data = new char[4096];
                int count;
                while ((count = stream.read(data)) >= 0) {
                    int keep = Math.min(count, 1_048_576 - out.length());
                    if (keep > 0)
                        out.append(data, 0, keep);
                }
                return out.toString();
            }
        });
        Thread.ofVirtual().name("jade-docker-output").start(read);
        try {
            // 写入在独立线程完成，避免服务不读 stdin 时绕过总超时。
            FutureTask<Void> write = new FutureTask<>(() -> {
                try (var stream = process.getOutputStream()) {
                    stream.write(input.getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
            Thread.ofVirtual().name("jade-docker-input").start(write);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean() || System.nanoTime() >= until) {
                    process.destroyForcibly();
                    throw new IOException("Docker operation cancelled or timed out; container state requires cleanup");
                }
            }
            write.get(2, TimeUnit.SECONDS);
            return new Reply(process.exitValue(), read.get(2, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Docker operation interrupted; container state requires cleanup", interrupted);
        } catch (ExecutionException | TimeoutException failed) {
            throw new IOException("Docker operation returned no complete result", failed);
        } finally {
            process.destroyForcibly();
            read.cancel(true);
        }
    }
}
