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
 * 管理会话的前台 Shell 和最多 32 项未交付后台命令。后台 start 先登记 Job 并立即返回编号，
 * 工作线程完成后向 Inbox 投递 Completion，正式采用结果后移除 Job。后台保留启动轮次身份，原轮次等待该结果，
 * 取消原轮次后结果可由后续轮次采用。
 */
public final class BackgroundManager implements AutoCloseable {
    private static final int MAX_PENDING = 32;
    private final Path cwd;
    private final long timeoutSeconds;
    private final ShellCommand.Runner runner;
    private final ShellCommand foreground;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private Consumer<Completion> resultListener = result -> {
    };
    private volatile ExecutionObserver observer = ExecutionObserver.NONE;
    private long counter;
    private volatile boolean closed;

    /**
     * 绑定工作目录，以默认 120 秒作为每条命令的超时，建立会话 Shell 服务。
     *
     * @param cwd 本次操作起始工作目录，相对路径以此为基准
     */
    public BackgroundManager(Path cwd) {
        this(cwd, 120);
    }

    /**
     * 绑定工作目录和正数超时，使用本地 Shell 入口建立前台与后台执行服务。
     *
     * @param cwd 本次操作起始工作目录，相对路径以此为基准
     * @param timeoutSeconds 每次命令或容器操作的正数等待上限，单位秒
     */
    public BackgroundManager(Path cwd, long timeoutSeconds) {
        this(cwd, timeoutSeconds, null);
    }

    /**
     * 校验工作目录和正数超时，保存共享执行后端并创建前台 Shell；每个后续后台 Job 使用独立 Shell 实例和调用上下文。
     *
     * @param cwd 本次操作起始工作目录，相对路径以此为基准
     * @param timeoutSeconds 每次命令或容器操作的正数等待上限，单位秒
     * @param runner Shell 执行后端；null 时使用本地进程入口
     * @throws IllegalArgumentException 每条命令的 timeoutSeconds 不是正数
     * @throws NullPointerException cwd 为 null
     */
    public BackgroundManager(Path cwd, long timeoutSeconds, ShellCommand.Runner runner) {
        this.cwd = Objects.requireNonNull(cwd);
        if (timeoutSeconds <= 0)
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        this.timeoutSeconds = timeoutSeconds;
        this.runner = runner;
        foreground = new ShellCommand(cwd, timeoutSeconds, runner);
    }

    /**
     * 绑定后台状态观察器，供命令接纳、停止和完成时向界面发送执行事件。
     *
     * @param value 需要在后台接纳、停止和完成时接收执行事实的观察器
     * @throws NullPointerException value 为 null
     */
    public void observe(ExecutionObserver value) {
        observer = Objects.requireNonNull(value);
    }

    /**
     * 在启动命令前绑定结果接收者，供工作线程将 Completion 交给会话 Inbox。
     *
     * @param listener 结果交付给会话 Inbox 的接收者
     * @throws NullPointerException listener 为 null
     */
    public synchronized void onResult(Consumer<Completion> listener) {
        resultListener = Objects.requireNonNull(listener);
    }

    /**
     * 返回当前是否仍有运行中或结果尚未采用的 Job，供会话切换和闲置回收判断。
     *
     * @return 存在运行中或结果尚未采用的后台 Job 时为 true
     */
    public synchronized boolean hasPending() {
        return !jobs.isEmpty();
    }

    /**
     * 返回当前仍在登记表中的后台 ID 快照，供压缩重建状态保留待交付工作。
     *
     * @return 所有运行中或结果未采用的后台 ID 快照
     */
    public synchronized List<String> pendingIds() {
        return List.copyOf(jobs.keySet());
    }

    /**
     * 返回是否仍有归属于 runId 的未交付 Job，供启动它的轮次在正常结束前等待结果采用。
     *
     * @param runId 当前轮次 ID，用于关联取消、后台命令和任务收尾
     * @return 指定 runId 仍有未采用结果的后台 Job 时为 true
     */
    public synchronized boolean hasPending(String runId) {
        return jobs.values().stream().anyMatch(job -> job.context().id().equals(runId));
    }

    /**
     * 校验命令、上下文、关闭状态及容量，派生以管理器关闭为取消条件的后台上下文。先建立 Job 与独立 Shell 并登记，
     * 再发启动事件并启动虚拟线程，返回后台 ID；线程启动失败时撤销登记并关闭 Shell。
     *
     * @param command 本次要执行的完整 Shell 命令文本
     * @param origin 启动后台操作的原始轮次上下文
     * @return 新接纳命令的后台 ID；命令结果另由 Completion 交付
     */
    public String start(String command, ExecutionContext origin) {
        if (command == null || command.isBlank())
            throw new IllegalArgumentException("Bash command cannot be empty");
        Objects.requireNonNull(origin, "context");
        Job job;
        synchronized (this) {
            if (closed || origin.isCancelled())
                throw new IllegalStateException("Background execution is cancelled");
            if (jobs.size() >= MAX_PENDING)
                throw new IllegalStateException("Too many pending background tasks");
            var context = origin.withCancellation(() -> closed);
            String id = "bg_%04d".formatted(++counter);
            job = new Job(id, command, new ShellCommand(cwd, timeoutSeconds, runner), context);
            jobs.put(id, job);
        }
        // 外部显示和结果回调不持管理器锁，避免与 Inbox 的完成检查反向等待。
        try {
            display(job.id(), "RUNNING", "Background command accepted; final result will follow");
            Thread.ofVirtual().name("jade-" + job.id()).start(() -> run(job));
        } catch (RuntimeException failure) {
            synchronized (this) {
                jobs.remove(job.id());
            }
            job.shell().close();
            throw failure;
        }
        return job.id();
    }

    /**
     * 同步调用共享前台 Shell，沿用本次上下文的取消条件，直接返回最终结果。
     *
     * @param command 本次要执行的完整 Shell 命令文本
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     * @return 同步 Shell 的实际退出码、有界输出和截断状态
     */
    public ShellCommand.Result runForegroundResult(String command, ExecutionContext context) {
        return foreground.run(command, context);
    }

    /**
     * 执行一个 Job 并转成 completed 或 failed 的 Completion，运行异常也生成失败结果。
     * 确认管理器仍接纳该 Job 后发状态通知并投递完成事实；Job 保留至会话采用结果后移除。
     *
     * @param job 已经登记的后台命令及其独立 Shell 和执行上下文
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
            if (closed || !jobs.containsKey(job.id()))
                return;
            publish = resultListener;
        }
        // 展示端故障不能吞掉终态事实；Inbox 投递仍然必须执行。
        try {
            display(result.id(), result.status(), "exit=" + result.exitCode());
        } finally {
            publish.accept(result);
        }
    }

    /**
     * 按后台 ID 移除已经正式写入历史的 Job，释放等待记录与后台容量。
     *
     * @param id 后台命令 ID，格式为 bg_ 加递增编号
     */
    public synchronized void adopted(String id) {
        jobs.remove(id);
    }

    /**
     * 按 ID 查找并关闭指定 Job 的 Shell，发送 STOPPING 事件；执行线程随后仍提交实际终态结果，Job 由采用流程移除。
     *
     * @param id 后台命令 ID，格式为 bg_ 加递增编号
     */
    public void stop(String id) {
        Job job;
        synchronized (this) {
            job = jobs.get(id);
        }
        if (job != null) {
            job.shell().close();
            display(id, "STOPPING", "Cancellation requested; final process result will follow");
        }
    }

    /**
     * 保存一条后台命令的 ID、命令文本、独立 Shell 和执行上下文，供工作线程与停止入口共同管理。
     *
     * @param id 后台命令 ID，格式为 bg_ 加递增编号
     * @param command 本次要执行的完整 Shell 命令文本
     * @param shell 此 Job 独占的 Shell 实例
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     */
    private record Job(String id, String command, ShellCommand shell, ExecutionContext context) {
    }

    /**
     * 保存后台命令的最终执行事实，包括后台 ID、所属轮次、命令、状态、退出码、有界输出和截断标记，供 Inbox 投递。
     *
     * @param id 后台命令 ID，格式为 bg_ 加递增编号
     * @param owner 启动本项工作并承担交付等待的原轮次 ID
     * @param command 本次要执行的完整 Shell 命令文本
     * @param status 本次业务或展示条目的明确状态
     * @param exitCode 实际进程退出码；null 表示未取得正常退出状态
     * @param output 命令有界输出或子代理最终结果正文
     * @param truncated 返回的输出是否因保留预算而截断
     */
    public record Completion(String id, String owner, String command, String status,
            Integer exitCode, String output, boolean truncated) {
    }

    /**
     * 将后台编号、状态和说明封装成 BACKGROUND 执行通知，交给观察器更新前端卡片。
     *
     * @param id 后台命令 ID，格式为 bg_ 加递增编号
     * @param state 要发送给界面的后台状态
     * @param detail 供后台状态卡片展示的简短说明
     */
    private void display(String id, String state, String detail) {
        observer.publish(ExecutionNotice.event(ExecutionNotice.Kind.BACKGROUND, id, "bash", detail, state));
    }

    /**
     * 在锁内标记关闭并取出、清空 Job 登记，在锁外关闭前台与全部后台 Shell，供会话退出时终止所属命令。
     */
    @Override
    public void close() {
        List<Job> active;
        synchronized (this) {
            if (closed)
                return;
            closed = true;
            active = List.copyOf(jobs.values());
            jobs.clear();
        }
        foreground.close();
        active.forEach(job -> job.shell().close());
    }
}
