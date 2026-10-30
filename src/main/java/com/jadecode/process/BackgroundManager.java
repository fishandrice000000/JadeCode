package com.jadecode.process;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.execution.ExecutionObserver;

/**
 * 会话拥有的 Shell 服务：后台命令立即返回编号，完成后只投递一次结果，不请求模型或写历史。
 * 每条命令属于启动它的 run，只有该轮正常完成前需要等待；结果可被任意后续轮次采用。
 * 大轮次取消不停止已接纳命令，会话关闭/显式停止/超时仍停止；所有记录只在本进程保存。
 * jobs 包含运行中和结果尚未采用的命令，最多 32 项；历史正式采用结果后释放记录。
 */
public final class BackgroundManager implements AutoCloseable {
    private static final int MAX_PENDING = 32;
    private final Path cwd;
    private final long timeoutSeconds;
    private final ShellCommand.Runner runner;
    private final ShellCommand foreground;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private Consumer<Completion> resultListener = result -> { };
    private volatile ExecutionObserver observer = ExecutionObserver.NONE;
    private long counter;
    private volatile boolean closed;

    /** 保存工作目录和默认超时，不执行命令。 */
    public BackgroundManager(Path cwd) { this(cwd, 120); }
    /** 保存正数超时，宿主执行仅用于明确装配的宿主/离线路径。 */
    public BackgroundManager(Path cwd, long timeoutSeconds) { this(cwd, timeoutSeconds, null); }
    /** 每个后台命令有独立 Shell；共享可信 runner，许可来自每次调用的上下文快照。 */
    public BackgroundManager(Path cwd, long timeoutSeconds, ShellCommand.Runner runner) {
        this.cwd = Objects.requireNonNull(cwd);
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds must be positive");
        this.timeoutSeconds = timeoutSeconds; this.runner = runner;
        foreground = new ShellCommand(cwd, timeoutSeconds, runner);
    }

    /** 安装非阻塞显示端；不影响结果交付端。 */
    public void observe(ExecutionObserver value) { observer = Objects.requireNonNull(value); }
    /** 安装只负责 Inbox 投递的函数；在启动任何命令之前由会话装配。 */
    public synchronized void onResult(Consumer<Completion> listener) { resultListener = Objects.requireNonNull(listener); }
    /** 用于切换/回收检查；已完成但未采用的通知仍属于当前会话。 */
    public synchronized boolean hasPending() { return !jobs.isEmpty(); }
    /** 压缩快照保留工作编号，不读取命令输出或声称命令成功。 */
    public synchronized List<String> pendingIds() { return List.copyOf(jobs.keySet()); }
    /** 只判断本轮发出的命令，不把以前轮次遗留命令变成新轮次的等待义务。 */
    public synchronized boolean hasPending(String runId) {
        return jobs.values().stream().anyMatch(job -> job.context().id().equals(runId));
    }

    /**
     * 在权限检查后接纳后台命令；登记先于启动虚拟线程，极快结果也能找到对应 Job。
     * 只在接纳前检查本轮取消；接纳后替换取消条件为管理器关闭，保留身份和不可变执行许可。
     * 返回 bg_id 只表示启动成功，不代表命令完成。线程启动失败会撤销登记并关闭资源。
     */
    public String start(String command, ExecutionContext origin) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("Bash command cannot be empty");
        Objects.requireNonNull(origin, "context");
        Job job;
        synchronized (this) {
            if (closed || origin.isCancelled()) throw new IllegalStateException("Background execution is cancelled");
            if (jobs.size() >= MAX_PENDING) throw new IllegalStateException("Too many pending background tasks");
            var context = origin.withCancellation(() -> closed);
            String id = "bg_%04d".formatted(++counter);
            job = new Job(id, command, new ShellCommand(cwd, timeoutSeconds, runner), context);
            jobs.put(id, job);
        }
        // 外部显示和结果回调不持管理器锁，避免与 Inbox 的完成检查反向等待。
        try {
            display(job.id(), "RUNNING", "Background command accepted; final result will follow");
            Thread.ofVirtual().name("jade-" + job.id()).start(() -> run(job));
        }
        catch (RuntimeException failure) {
            synchronized (this) { jobs.remove(job.id()); }
            job.shell().close();
            throw failure;
        }
        return job.id();
    }

    /** 同步调用直接等待，不产生通知，沿用调用方的取消信号。 */
    public ShellCommand.Result runForegroundResult(String command, ExecutionContext context) {
        return foreground.run(command, context);
    }

    /**
     * 执行一次命令，保留实际退出状态和有界输出，异常也形成终态结果；绝不自动重跑。
     * Job 在正式历史采用前保留，保证进程退出与结果入队之间不会提前解除等待。
     */
    private void run(Job job) {
        Completion result;
        try (var shell = job.shell()) {
            var output = shell.run(job.command(), job.context());
            result = new Completion(job.id(), job.context().id(), job.command(),
                    Integer.valueOf(0).equals(output.exitCode()) ? "completed" : "failed",
                    output.exitCode(), output.text(), output.truncated());
        } catch (RuntimeException failure) {
            result = new Completion(job.id(), job.context().id(), job.command(), "failed", null,
                    "Error: " + failure.getMessage(), false);
        }
        Consumer<Completion> publish;
        synchronized (this) {
            if (closed || !jobs.containsKey(job.id())) return;
            publish = resultListener;
        }
        // 展示端故障不能吞掉终态事实；Inbox 投递仍然必须执行。
        try { display(result.id(), result.status(), "exit=" + result.exitCode()); }
        finally { publish.accept(result); }
    }

    /** 结果已正式写入历史后释放记录；按可信通知 ID 查找，不重跑命令，不限制采用轮次。 */
    public synchronized void adopted(String id) { jobs.remove(id); }

    /**
     * 显式停止一条命令。保持 Job 到执行线程返回终态，等待中的原轮次仍能收到通知。
     * 大轮次取消不调用本方法；调用方需要按命令 ID 明确选择停止对象。
     */
    public void stop(String id) {
        Job job;
        synchronized (this) { job = jobs.get(id); }
        if (job != null) { job.shell().close(); display(id, "STOPPING", "Cancellation requested; final process result will follow"); }
    }

    /** Job 只持身份、命令和资源；不携带可选投递策略，也不重复保存一份结果队列。 */
    private record Job(String id, String command, ShellCommand shell, ExecutionContext context) { }
    /** 完成事实只保存来源编号与执行结果；无需把审批函数/执行上下文送进 Inbox。 */
    public record Completion(String id, String owner, String command, String status,
            Integer exitCode, String output, boolean truncated) { }

    /** 后台状态只是展示事实，结果仍由独立 Inbox 路径交付。 */
    private void display(String id, String state, String detail) {
        observer.publish(ExecutionNotice.event(ExecutionNotice.Kind.BACKGROUND, id, "bash", detail, state));
    }

    /** 会话关闭时取出全部资源后锁外终止；重复关闭安全，禁止新工作与迟到结果发布。 */
    @Override public void close() {
        List<Job> active;
        synchronized (this) {
            if (closed) return;
            closed = true; active = List.copyOf(jobs.values()); jobs.clear();
        }
        foreground.close();
        active.forEach(job -> job.shell().close());
    }
}
