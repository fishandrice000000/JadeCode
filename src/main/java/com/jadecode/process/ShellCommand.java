package com.jadecode.process;

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
 * 同步执行一条 Shell 命令，统一收集退出码、有界输出、超时与取消结果。注入 runner 时交给受限后端；
 * 本地入口使用 setsid /bin/sh -c，并在收尾时清理原进程组。实例管理其启动的进程，由会话或一次调用的资源块关闭。
 */
public final class ShellCommand implements AutoCloseable {
    private static final int MAX_OUTPUT = 50_000;
    private final Path cwd;
    private final long timeoutSeconds;
    /** 注入的执行后端；null 仅供明确的宿主/离线入口使用。 */
    private final Runner runner;

    /**
     * 同步/后台共用的执行契约，必须保留超时、取消和失败状态。
     */
    @FunctionalInterface
    public interface Runner {
        /**
         * 使用给定命令、超时时间和取消条件执行 Shell，返回退出码、输出及截断状态。生产环境可注入 Docker runner，
         * 容器内使用本地 runner。
         *
         * @param command 本次需要执行或解析的完整 Shell 命令文本
         * @param cwd 本次操作起始工作目录，相对路径以此为基准
         * @param timeoutSeconds 每次命令或容器操作的正数等待上限，单位秒
         * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
         * @return 本次执行的退出码、有界输出和截断状态
         */
        Result run(String command, Path cwd, long timeoutSeconds, com.jadecode.execution.ExecutionContext context);
    }

    /** 同一锁保护启动登记与关闭，避免 close 扫描结束后又启动新进程。 */
    private final Set<Running> processes = new HashSet<>();
    private final Thread shutdownHook = new Thread(this::close, "jade-shell-cleanup");
    private boolean hookRegistered;
    private volatile boolean closed;

    /**
     * 绑定命令初始目录和每条命令的正数超时，使用本地进程执行入口。调用 run 时启动命令，内部 cd 只影响该次 Shell。
     *
     * @param cwd 本次操作起始工作目录，相对路径以此为基准
     * @param timeoutSeconds 每次命令或容器操作的正数等待上限，单位秒
     */
    public ShellCommand(Path cwd, long timeoutSeconds) {
        this(cwd, timeoutSeconds, null);
    }

    /**
     * 规范化工作目录、校验正数超时并保存可选 runner；传入后端时由后端执行，runner 为 null 时使用本地进程。
     * 非法超时抛出 IllegalArgumentException。
     *
     * @param cwd 本次操作起始工作目录，相对路径以此为基准
     * @param timeoutSeconds 每次命令或容器操作的正数等待上限，单位秒
     * @param runner Shell 执行后端；null 时使用本地进程入口
     * @throws IllegalArgumentException 每条命令的 timeoutSeconds 不是正数
     */
    public ShellCommand(Path cwd, long timeoutSeconds, Runner runner) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        this.cwd = cwd.toAbsolutePath().normalize();
        this.timeoutSeconds = timeoutSeconds;
        this.runner = runner;
    }

    /**
     * 最终结果：exitCode=null 表示超时/IO/中断；output 有界，truncated 表示不完整。
     *
     * @param output 命令有界输出或子代理最终结果正文
     * @param exitCode 实际进程退出码；null 表示未取得正常退出状态
     * @param truncated 返回的输出是否因保留预算而截断
     */
    public record Result(String output, Integer exitCode, boolean truncated) {
        /**
         * 将命令结果转换为工具文本：空输出显示 no output，非零退出码在原输出前加状态说明，其余情况返回输出正文。
         *
         * @return 原始有界输出；空输出显示占位，非零退出附带状态说明
         */
        public String text() {
            String body = output.isEmpty() ? "(no output)" : output;
            return exitCode != null && exitCode != 0
                    ? "Error: command exited with status " + exitCode + "\n" + body
                    : body;
        }
    }

    /**
     * 以当前工作目录和默认超时同步执行命令，使用空上下文调用完整入口。返回带退出码、有界输出和截断状态的 Result。
     *
     * @param command 本次需要执行或解析的完整 Shell 命令文本
     * @return 命令结果，正常退出保留实际退出码，超时或执行故障的退出码为 null
     */
    public Result run(String command) {
        return run(command, null);
    }

    /**
     * 执行命令并等待实际结果，返回退出码、合并输出和截断标记。
     *
     * <p>存在 runner 时为上下文追加 Shell 关闭的取消条件，再交给后端；本地执行时在进程表锁内核对状态，
     * 注册退出清理并启动 setsid /bin/sh -c。关闭子进程 stdin，虚拟线程持续排空合并输出，主线程检查超时与取消。</p>
     * <p>进程退出后清理原进程组，取得最多 50000 个 Java 字符的输出。超时、中断、启动或采集失败返回
     * exitCode 为 null 的错误结果；正常退出保留实际退出码。finally 停止进程、取消采集并移除登记。</p>
     *
     * @param command 本次需要执行或解析的完整 Shell 命令文本
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     * @return 命令结果，正常退出保留实际退出码，超时或执行故障的退出码为 null
     */
    public Result run(String command, com.jadecode.execution.ExecutionContext context) {
        if (runner != null) {
            // 关闭注入后端的 Shell 也必须停止实际容器操作，不能只清空本地进程表。
            // 即使已经关闭仍向受限后端传入取消条件，让它撤销尚未领取的后台许可。
            if (context == null && closed)
                return new Result("Error: Shell is closed", null, false);
            return runner.run(command, cwd, timeoutSeconds,
                    context == null ? null : context.withAdditionalCancellation(() -> closed));
        }
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
                if (context != null && context.isCancelled())
                    return new Result("Error: Execution source has exited", null, false);
                ProcessBuilder builder = new ProcessBuilder("/usr/bin/setsid", "/bin/sh", "-c", command)
                        .directory(cwd.toFile()).redirectErrorStream(true);
                // 普通命令无需模型认证；宿主与容器执行均只继承工具链/locale 等基础设置。
                var parent = new java.util.HashMap<>(builder.environment());
                builder.environment().clear();
                for (String key : List.of("PATH", "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE", "TZ",
                        "TMPDIR", "JAVA_HOME"))
                    if (parent.containsKey(key))
                        builder.environment().put(key, parent.get(key));
                Process process = builder.start();
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
            // 注入后端可能选择明确的宿主 Full Access 路径；其外层 Shell 关闭通过 context
            // 传入，因此等待期间也检查取消，不能只在启动前检查一次。后台使用独立取消条件。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (process.isAlive()) {
                if (context != null && context.isCancelled())
                    return new Result("Error: Execution cancelled", null, false);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    return new Result("Error: Timeout (" + timeoutSeconds + "s)", null, false);
                process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
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

    /**
     * 以 UTF-8 持续读取进程合并输出，最多保留 MAX_OUTPUT 个 Java 字符；超过保留量后继续读取并标为 truncated，
     * 直到 EOF 返回采集结果。
     *
     * @param process 本执行器已经启动的命令进程
     * @return 保留预算内的文本和是否发生截断的标记
     * @throws IOException 命令合并输出流读取失败
     */
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

    /**
     * 保存输出线程收集的文本及截断状态，供主线程取得结果后构造 Shell 执行结果。
     *
     * @param text 本次处理的完整文本，按该入口执行编码、脱敏、显示或组装
     * @param truncated 返回的输出是否因保留预算而截断
     */
    private record Captured(String text, boolean truncated) {
    }

    /**
     * 管理一条本地命令进程的一次性停止状态，正常收尾、finally 和关闭调用共享同一把实例锁。
     */
    private static final class Running {
        private final Process process;
        private boolean stopped;

        /**
         * 保存已经启动的命令进程，供执行收尾与并发关闭共同停止该进程组。
         *
         * @param process 本执行器已经启动的命令进程
         */
        Running(Process process) {
            this.process = process;
        }

        /**
         * 首次调用标记 stopped 并清理原进程组；后续调用读取同一标记后返回。
         */
        synchronized void stop() {
            if (!stopped) {
                stopped = true;
                stopGroup(process);
            }
        }
    }

    /**
     * 向该进程 PID 对应的原进程组发送 KILL，最多等待一秒；最终仍存活的直接子进程使用 Java API 强制结束。
     * IO 异常结束本次信号尝试，中断恢复线程标记。
     *
     * @param process 本执行器已经启动的命令进程
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
            // 已退出的父进程仍有待读取的管道缓冲；再次 destroy 会关流并丢失输出。
            if (process.isAlive())
                process.destroyForcibly();
        }
    }

    /**
     * 在进程表锁内设置关闭标记并取得活动进程快照，再在锁外逐个停止进程，最后移除已注册的 JVM 关闭 Hook。
     * 重复关闭直接返回。
     */
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
