package com.jadecode.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import com.jadecode.cron.CronJob;
import com.jadecode.cron.CronScheduler;
import com.jadecode.llm.LlmException;
import com.jadecode.messages.Message;
import com.jadecode.safety.Hooks;

/**
 * REPL 的定时回合运行时：让到期 prompt 在无人主动发消息时也能启动 Agent。
 *
 * <p>一条线程计时、一条线程尝试交付；只有拿到回合锁的执行者能访问共享历史和模型。
 * 人工输入的 readLine 在锁外，因此等待输入不算 Agent 忙。它不是 s15 通用事件循环，
 * 不把 s11 完成通知变成主动唤醒源，也不在导入/装配 Main 时启动线程。</p>
 */
public final class CronRuntime implements AutoCloseable {
    private final AgentLoop loop;
    private final CronScheduler scheduler;
    private final Consumer<String> output;
    private final Consumer<String> errors;
    private final ReentrantLock turn = new ReentrantLock();
    private final List<Message> history = new ArrayList<>();
    private ScheduledExecutorService executor;
    private volatile boolean closed;
    /** 只由交付线程访问，防止首请求失败时高频无限重试。 */
    private int failures;
    private long nextAttempt;
    private volatile String lastPollError;

    /**
     * 绑定同一父循环/调度器与输出接收方，不启动线程、不读取持久任务。
     * @param loop 实际生产或离线替身客户端装配的循环，由本运行时关闭
     * @param scheduler 必须与工具使用相同实例
     * @param output 正常回复接收方，生产打印到 stdout
     * @param errors 调度/交付错误接收方，生产打印到 stderr
     */
    public CronRuntime(AgentLoop loop, CronScheduler scheduler, Consumer<String> output, Consumer<String> errors) {
        this.loop = java.util.Objects.requireNonNull(loop);
        this.scheduler = java.util.Objects.requireNonNull(scheduler);
        this.output = java.util.Objects.requireNonNull(output);
        this.errors = java.util.Objects.requireNonNull(errors);
    }

    /**
     * REPL 显式启动计时和交付线程；幂等，不在构造或 Main.buildLoop 时偷偷运行任务。
     * 每秒检查当前分钟，每 200ms 尝试取得空闲回合；交付耗时不会阻塞另一条计时线程。
     */
    public synchronized void start() {
        if (closed) throw new IllegalStateException("Cron runtime is closed");
        if (executor != null) return;
        executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "jade-cron");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::deliver, 0, 200, TimeUnit.MILLISECONDS);
    }

    /**
     * 人工请求入口：取得同一回合锁后才读写历史，正常沿用原 UserPromptSubmit / run 流程。
     * 失败向 REPL 传播，保持原交互错误处理；等待键盘输入不应放在本方法内。
     */
    public void runUser(String query) throws LlmException {
        turn.lock();
        try {
            if (closed) throw new IllegalStateException("Cron runtime is closed");
            loop.hooks().trigger(Hooks.USER_PROMPT_SUBMIT, Map.of(Hooks.PARAM_QUERY, query));
            history.add(Message.userText(query));
            loop.run(history);
            output.accept(history.getLast().extractText());
        } finally {
            turn.unlock();
        }
    }

    /** 计时线程只更新调度状态，不获取历史锁、不调用模型。重复同一错误只报告一次，避免坏文件刷屏。 */
    private void poll() {
        if (closed) return;
        try {
            scheduler.pollDue();
            lastPollError = null;
        } catch (IOException | RuntimeException failure) {
            String reason = failure.getMessage();
            if (!java.util.Objects.equals(reason, lastPollError)) errors.accept("[Cron] Cannot poll: " + reason);
            lastPollError = reason;
        }
    }

    /**
     * 队列线程只在 Agent 空闲时取一项；不持有 scheduler 的状态锁等待模型。
     * 首回复前失败重排且退避，首回复后失败不能重新执行整项任务，否则可能重复文件/命令副作用。
     */
    private void deliver() {
        // poll 读不回状态时先停止交付，避免同一个坏文件每 200ms 又报一次。
        // nanoTime 只用于相对退避，不能假定它从 0 起算或一定为正。
        if (closed || lastPollError != null || failures >= 3
                || (nextAttempt != 0 && nextAttempt - System.nanoTime() > 0) || !turn.tryLock()) return;
        CronJob job = null;
        boolean[] received = {false};
        try {
            if (closed) return;
            job = scheduler.takeDue();
            if (job == null) return;
            final CronJob delivery = job;
            output.accept("[Cron] Delivering " + job.id());
            loop.runScheduled(history, job.prompt(), () -> {
                received[0] = true;
                failures = 0;
                try {
                    // 收到首回复就确认，不能等完整业务结束才删一次性任务。
                    scheduler.acknowledge(delivery.id());
                } catch (IOException failure) {
                    // pending/lease 保留，本进程不立即重送；重启可能重送，这是至少一次边界。
                    errors.accept("[Cron] Received but acknowledgement failed for " + delivery.id() + ": " + failure.getMessage());
                }
            });
            output.accept(history.getLast().extractText());
        } catch (IOException | LlmException | RuntimeException failure) {
            if (job != null && !received[0]) {
                scheduler.restore(job.id());
                failures++;
                nextAttempt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                if (failures >= 3) errors.accept("[Cron] Automatic delivery paused after 3 failures; durable pending jobs remain for restart.");
            }
            if (!closed) errors.accept("[Cron] Turn failed: " + failure.getMessage());
        } finally {
            turn.unlock();
        }
    }

    /**
     * q/EOF 结束整场会话：先停止派发并中断线程，再关闭模型循环/命令组，最多等 2 秒收尾。
     * HTTP 是否响应中断由客户端决定；晚到回复会被循环拒绝派发工具，已发生 IO 不回滚。
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (executor != null) executor.shutdownNow();
        loop.close();
        if (executor != null) {
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) errors.accept("[Cron] Model/IO still finishing; new tools are disabled.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
