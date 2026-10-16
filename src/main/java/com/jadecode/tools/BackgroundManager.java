package com.jadecode.tools;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jadecode.agent.ExecutionContext;

/**
 * 会话后台命令所有者：启动回执、完成事件和任务结束是三个不同的阶段。
 *
 * <p>每条后台命令拥有独立 shell 资源，失败任务只清理自己的必等命令。工作者只登记结果和
 * 唤醒消费者，不写历史、不调用模型、不读取终端。运行中、待领取、已领取待确认的总数最多
 * 32 项；摘要保留 500 字符，不增加完整输出落盘或跨重启恢复。</p>
 */
public final class BackgroundManager implements AutoCloseable {
    private static final int MAX_PENDING = 32;
    private static final int SUMMARY_CHARS = 500;
    private final Path cwd;
    private final long timeoutSeconds;
    private final ShellCommand foreground;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final ArrayDeque<Completion> ready = new ArrayDeque<>();
    private Runnable listener = () -> { };
    private long counter;
    private boolean closed;

    /** 生产默认 120 秒；构造不启动进程、线程或文件 IO。 */
    public BackgroundManager(Path cwd) { this(cwd, 120); }

    /** 注入超时供离线检查使用；目录绑定不由模型改变。 */
    public BackgroundManager(Path cwd, long timeoutSeconds) {
        this.cwd = cwd;
        this.timeoutSeconds = timeoutSeconds;
        foreground = new ShellCommand(cwd, timeoutSeconds);
    }

    /** 绑定非阻塞的消费者唤醒入口；更换监听器不消费或丢弃已有结果。 */
    public synchronized void onCompletion(Runnable listener) {
        this.listener = java.util.Objects.requireNonNull(listener);
    }

    /**
     * 登记已获准的命令并立即返回 ID，不等待执行结束。
     * @param command 非空、已通过 PreToolUse 的命令
     * @param context Harness 提供的所属身份，不从模型 JSON 读取
     * @param required 是否必须在该任务结束前处理最终结果
     * @return 启动回执 ID，不表示执行或业务成功
     * @throws IllegalStateException 已关闭或未确认结果达到上限
     */
    public synchronized String start(String command, ExecutionContext context, boolean required) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("Bash command cannot be empty");
        java.util.Objects.requireNonNull(context, "executionContext");
        if (closed || jobs.size() >= MAX_PENDING) {
            throw new IllegalStateException(closed ? "Background session is closed" : "Too many pending background tasks");
        }
        String id = "bg_%04d".formatted(++counter);
        Job job = new Job(id, context.id(), required, command, new ShellCommand(cwd, timeoutSeconds));
        // 先登记再启动，极快命令和并发关闭都能找到自己的资源。
        jobs.put(id, job);
        try {
            Thread.ofVirtual().name("jade-" + id).start(() -> run(job));
        } catch (RuntimeException failure) {
            jobs.remove(id);
            job.shell().close();
            throw failure;
        }
        return id;
    }

    /** 同步 bash 使用会话的同步执行器，不制造后台完成事件。 */
    String runForeground(String command) { return foreground.run(command).text(); }

    /** 执行 IO 并发布完成值；锁外唤醒，避免与收件箱锁形成循环等待。 */
    private void run(Job job) {
        Completion result;
        try (ShellCommand shell = job.shell()) {
            ShellCommand.Result output = shell.run(job.command());
            result = new Completion(job.id(), job.owner(), job.required(), job.command(),
                    Integer.valueOf(0).equals(output.exitCode()) ? "completed" : "failed",
                    output.exitCode(), output.text(), output.truncated());
        } catch (RuntimeException failure) {
            result = new Completion(job.id(), job.owner(), job.required(), job.command(),
                    "failed", null, "Error: " + failure.getMessage(), false);
        }
        Runnable signal;
        synchronized (this) {
            // 任务失败/会话关闭已撤销拥有权时，晚到结果不能复活任务。
            if (closed || !jobs.containsKey(job.id())) return;
            ready.addLast(result);
            signal = listener;
        }
        signal.run();
    }

    /**
     * 领取结果但暂不释放其记录：模型有效回应后才 acknowledge，交付前失败可以 restore。
     * @param owner 当前执行 ID
     * @param includeDetached true 才同时领取会话级通知，准备随正常请求发送
     * @return 已完成结果，不等待运行中命令
     */
    public synchronized List<Completion> collect(String owner, boolean includeDetached) {
        List<Completion> collected = new ArrayList<>();
        var iterator = ready.iterator();
        while (iterator.hasNext()) {
            Completion result = iterator.next();
            if (result.required() ? result.owner().equals(owner) : includeDetached) {
                iterator.remove();
                collected.add(result);
            }
        }
        return List.copyOf(collected);
    }

    /** 已完成但尚未让模型处理的结果也算依赖，不能只检查进程是否仍在运行。 */
    public synchronized boolean hasRequired(String owner) {
        return jobs.values().stream().anyMatch(job -> job.required() && job.owner().equals(owner));
    }

    /** 模型已有效回应这些结果后释放容量；这不是重新执行或证明业务成功。 */
    public synchronized void acknowledge(List<Completion> results) {
        results.forEach(result -> jobs.remove(result.id()));
    }

    /**
     * 定时首回复前失败时归还实际领取过的事件，不重跑命令、不接受模型构造的字符串通知。
     * 拥有权仍在 jobs 内，恢复只移动完成记录，不创建假的进程或改变总容量。
     */
    public synchronized void restore(List<Completion> results) {
        if (closed) return;
        for (int i = results.size() - 1; i >= 0; i--) {
            Completion result = results.get(i);
            if (jobs.containsKey(result.id()) && ready.stream().noneMatch(item -> item.id().equals(result.id()))) {
                ready.addFirst(result);
            }
        }
    }

    /**
     * 失败任务只清理自己的必等命令；先摘除拥有权，再锁外终止 IO。
     * 已发生的文件副作用不回滚，脱离任务的命令仍归会话拥有。
     */
    public void cancelRequired(String owner) {
        List<Job> cancelled;
        synchronized (this) {
            cancelled = jobs.values().stream().filter(job -> job.required() && job.owner().equals(owner)).toList();
            cancelled.forEach(job -> jobs.remove(job.id()));
            ready.removeIf(result -> result.required() && result.owner().equals(owner));
        }
        cancelled.forEach(job -> job.shell().close());
    }

    /** 资源不进入模型上下文；每条命令有自己的终止边界。 */
    private record Job(String id, String owner, boolean required, String command, ShellCommand shell) { }

    /** exitCode=null 表示超时、IO 或中断，不伪造成功退出码。 */
    public record Completion(String id, String owner, boolean required, String command,
            String status, Integer exitCode, String output, boolean truncated) {
        /** 有界的参考文本；关联执行身份，但不复用原工具调用 ID。 */
        public String notification() {
            String summary = output.length() > SUMMARY_CHARS ? output.substring(0, SUMMARY_CHARS) : output;
            if (output.length() > SUMMARY_CHARS || truncated) {
                summary += "\n[Output truncated; no full output file is retained.]";
            }
            return "<task_notification>\n<task_id>" + id + "</task_id>\n<source_run>" + escape(owner)
                    + "</source_run>\n<status>" + status + "</status>\n<exit_code>" + exitCode
                    + "</exit_code>\n<command>" + escape(command) + "</command>\n<summary>" + escape(summary)
                    + "</summary>\n</task_notification>";
        }
    }

    /** 先转义 &，避免后来产生的实体再次被转义；不宣称这能阻止一切提示注入。 */
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 退出整个会话才清理全部命令；不等自然结束，不删除命令产物。 */
    @Override
    public void close() {
        List<Job> active;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = List.copyOf(jobs.values());
            jobs.clear();
            ready.clear();
        }
        foreground.close();
        active.forEach(job -> job.shell().close());
    }
}
