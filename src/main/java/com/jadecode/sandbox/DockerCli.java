package com.jadecode.sandbox;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * 本机 Docker 的窄入口：只接受参数数组，不拼 Shell，不继承模型认证。
 * stdout/stderr 持续排空并有界保留；超时只表示客户端未返回，调用方仍须清理实际容器。
 */
public final class DockerCli {
    public record Reply(int exitCode, String output) { }
    private String endpoint;
    /** 固定本地 Unix socket；拒绝远端 daemon，避免将本机路径误当成远端宿主路径。 */
    public synchronized void initialize() throws IOException {
        if (endpoint != null) return;
        Reply context = raw(List.of("/usr/bin/docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"), "", 15, () -> false);
        String socket = context.output().strip();
        if (context.exitCode() != 0 || !socket.startsWith("unix:///") || socket.contains("\n"))
            throw new IOException("Sandbox requires an available local Docker Unix socket");
        endpoint = socket;
        Reply info = call(List.of("info", "--format", "{{.OSType}} {{.CgroupVersion}} {{.MemoryLimit}} {{.SwapLimit}} {{.CPUCfsQuota}} {{.PidsLimit}} {{json .SecurityOptions}}"), "", 15, () -> false);
        if (info.exitCode() != 0 || !info.output().startsWith("linux 2 true true true true ") || !info.output().contains("seccomp")) {
            endpoint = null;
            throw new IOException("Sandbox requires Linux Docker with seccomp, cgroup v2 and enforceable memory/swap/CPU/PID limits");
        }
    }
    /** 业务调用只走 initialize 核验过的 endpoint；不自动拉取镜像。 */
    public Reply call(List<String> args, String input, long timeoutSeconds, BooleanSupplier cancelled) throws IOException {
        return raw(command(args), input, timeoutSeconds, cancelled);
    }
    /** MCP stdio 需要直接接管管道，因此只复用可信 Docker argv，环境仍由调用方过滤。 */
    public List<String> command(List<String> args) throws IOException {
        String socket = endpoint;
        if (socket == null) throw new IOException("Docker sandbox is not initialized");
        var command = new ArrayList<>(List.of("/usr/bin/docker", "--host", socket)); command.addAll(args);
        return List.copyOf(command);
    }
    /** 安全探针和 Docker 命令共用 IO/超时实现；不将无限输出留在内存。 */
    private static Reply raw(List<String> command, String input, long timeoutSeconds, BooleanSupplier cancelled) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        var original = new HashMap<>(builder.environment()); builder.environment().clear();
        for (String key : List.of("HOME", "PATH", "LANG", "LC_ALL", "XDG_RUNTIME_DIR", "DOCKER_CONFIG", "DOCKER_CONTEXT"))
            if (original.containsKey(key)) builder.environment().put(key, original.get(key));
        // 选定本地 socket 后固定 endpoint，后续用户环境中的 context 不能改变连接目标。
        if (command.contains("--host")) builder.environment().remove("DOCKER_CONTEXT");
        Process process = builder.start();
        FutureTask<String> read = new FutureTask<>(() -> {
            try (var stream = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                var out = new StringBuilder(); char[] data = new char[4096]; int count;
                while ((count = stream.read(data)) >= 0) {
                    int keep = Math.min(count, 1_048_576 - out.length());
                    if (keep > 0) out.append(data, 0, keep);
                }
                return out.toString();
            }
        });
        Thread.ofVirtual().name("jade-docker-output").start(read);
        try {
            // 写入在独立线程完成，避免服务不读 stdin 时绕过总超时。
            FutureTask<Void> write = new FutureTask<>(() -> {
                try (var stream = process.getOutputStream()) { stream.write(input.getBytes(StandardCharsets.UTF_8)); }
                return null;
            });
            Thread.ofVirtual().name("jade-docker-input").start(write);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean() || System.nanoTime() >= until) {
                    process.destroyForcibly(); throw new IOException("Docker operation cancelled or timed out; container state requires cleanup");
                }
            }
            write.get(2, TimeUnit.SECONDS);
            return new Reply(process.exitValue(), read.get(2, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("Docker operation interrupted; container state requires cleanup", interrupted);
        } catch (ExecutionException | TimeoutException failed) {
            throw new IOException("Docker operation returned no complete result", failed);
        } finally { process.destroyForcibly(); read.cancel(true); }
    }
}
