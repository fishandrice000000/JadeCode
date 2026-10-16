package com.jadecode.tools;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Linux shell 执行器：同步 bash 与后台命令共用超时、输出和清理语义。
 *
 * <p>权限由调用方在启动前检查。setsid 为每条命令建立独立进程组，退出时清理组内进程；
 * 这不是沙箱，自行另开 session 的程序可能逃离该组。不写输出文件。
 * 实例拥有启动的进程，必须 close；JVM 正常关闭另有兜底，SIGKILL/断电无法兜底。</p>
 */
final class ShellCommand implements AutoCloseable {
    private static final int MAX_OUTPUT = 50_000;
    private final Path cwd;
    private final long timeoutSeconds;
    /** 同一锁保护启动登记与关闭，避免 close 扫描结束后又启动新进程。 */
    private final Set<Running> processes = new HashSet<>();
    private final Thread shutdownHook = new Thread(this::close, "jade-shell-cleanup");
    private boolean hookRegistered;
    private boolean closed;

    /**
     * 绑定工作区和超时，不创建线程/进程、不注册 hook。
     * @param cwd shell 初始目录；内部 cd 不改变其他命令目录
     * @param timeoutSeconds 正数，每条命令独立计时
     */
    ShellCommand(Path cwd, long timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        this.cwd = cwd.toAbsolutePath().normalize();
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 最终结果：exitCode=null 表示超时/IO/中断；output 有界，truncated 表示不完整。 */
    record Result(String output, Integer exitCode, boolean truncated) {
        /** 工具协议文本：非零退出明确失败，不能仅把错误命令的 stdout 当作成功结果。 */
        String text() {
            String body = output.isEmpty() ? "(no output)" : output;
            return exitCode != null && exitCode != 0
                    ? "Error: command exited with status " + exitCode + "\n" + body : body;
        }
    }

    /**
     * 阻塞执行已获准的非空命令，普通系统失败作为 Result 返回，不重试、不回滚副作用。
     *
     * <p>等待线程负责超时，读线程同时排空 stdout/stderr。截断只限制保留内容，不停止读取，
     * 否则子进程写满管道仍会卡住。最多保留 50,000 个 Java 字符。</p>
     * @param command 已校验的 shell 文本
     * @return 只有 exitCode=0 才算成功，失败保留原因
     */
    Result run(String command) {
        Running running;
        try {
            synchronized (processes) {
                if (closed) {
                    return new Result("Error: Shell session is closed", null, false);
                }
                // 使用绝对系统路径；缺 setsid 时明确失败，不静默退化成无法清理进程组的模式。
                if (!hookRegistered) {
                    Runtime.getRuntime().addShutdownHook(shutdownHook);
                    hookRegistered = true;
                }
                Process process = new ProcessBuilder("/usr/bin/setsid", "/bin/sh", "-c", command)
                        .directory(cwd.toFile()).redirectErrorStream(true).start();
                running = new Running(process);
                processes.add(running);
            }
        } catch (IOException | IllegalStateException | SecurityException failure) {
            return new Result("Error: " + failure.getMessage(), null, false);
        }

        // 虚拟线程只负责 IO，不触碰历史、模型或权限交互。
        Process process = running.process;
        FutureTask<Captured> output = new FutureTask<>(() -> readOutput(process));
        Thread.ofVirtual().name("jade-shell-output").start(output);
        try {
            // 不向子进程开放 REPL 输入，防止命令抢走下一条用户输入。
            process.getOutputStream().close();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                return new Result("Error: Timeout (" + timeoutSeconds + "s)", null, false);
            }
            int exitCode = process.exitValue();
            // shell 已结束仍可能有后代持有 stdout，先清理原组，再收尾读取。
            running.stop();
            Captured captured = output.get(1, TimeUnit.SECONDS);
            return new Result(captured.text().strip(), exitCode, captured.truncated());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Result("Error: Interrupted", null, false);
        } catch (IOException | ExecutionException | TimeoutException failure) {
            return new Result("Error: Unable to collect command output: " + failure.getMessage(), null, false);
        } finally {
            // 与并发 close 共用一次性 stop，避免进程结束后再次按旧 PID 发送组信号。
            running.stop();
            output.cancel(true);
            synchronized (processes) {
                processes.remove(running);
            }
        }
    }

    /** 有界保留、持续排空；大量输出不会持续扩大 StringBuilder。 */
    private static Captured readOutput(Process process) throws IOException {
        try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[4096];
            boolean truncated = false;
            int count;
            while ((count = reader.read(buffer)) != -1) {
                int kept = Math.min(count, MAX_OUTPUT - text.length());
                text.append(buffer, 0, kept);
                truncated |= kept < count;
            }
            return new Captured(text.toString(), truncated);
        }
    }

    /** 读线程自己的不可变结果，不向其他线程暴露可变 buffer。 */
    private record Captured(String text, boolean truncated) { }

    /**
     * 一条正在运行的命令。正常收尾、finally 和关闭流程都可能调用 stop，
     * 这里保证只发一次停止信号，不向可能已被系统回收的旧进程号重复发。
     * 用每条命令自己的锁，不在整个 processes 锁里等 kill，其他命令还能继续登记或收尾。
     */
    private static final class Running {
        private final Process process;
        private boolean stopped;

        /** 只封装新创建的进程，不启动额外线程。 */
        Running(Process process) {
            this.process = process;
        }

        /** 首次调用清理原进程组，之后立即返回，不向可能已复用的旧 PID 重复发信号。 */
        synchronized void stop() {
            if (!stopped) {
                stopped = true;
                stopGroup(process);
            }
        }
    }

    /**
     * 仅终止本执行器创建的原进程组，再兜底终止直接进程。
     * 负 ID 由 Java Process.pid 产生，模型不能注入 kill 参数；不是文件系统沙箱。
     */
    private static void stopGroup(Process process) {
        try {
            Process kill = new ProcessBuilder("/bin/kill", "-KILL", "--", "-" + process.pid())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!kill.waitFor(1, TimeUnit.SECONDS)) {
                kill.destroyForcibly();
            }
        } catch (IOException ignored) {
            // 系统 kill 失败时仍保留 Java 的直接子进程兜底，不冒称后代一定清理成功。
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            process.destroyForcibly();
        }
    }

    /** 重复调用也没问题：先禁止新启动并取一份快照，再在锁外停止；正常关闭时移除 JVM 钩子。 */
    @Override
    public void close() {
        List<Running> active;
        synchronized (processes) {
            if (closed) {
                return;
            }
            closed = true;
            active = List.copyOf(processes);
        }
        active.forEach(Running::stop);
        if (hookRegistered && Thread.currentThread() != shutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM 已开始 shutdown，不能再修改 hook 表；组清理已尝试。
            }
        }
    }
}
