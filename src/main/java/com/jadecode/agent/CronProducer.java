package com.jadecode.agent;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.jadecode.cron.CronJob;
import com.jadecode.cron.CronScheduler;

/**
 * 定时生产适配器：只负责到期入队、确认和退避，不拥有模型或会话历史。
 *
 * <p>原工作区格式与 pending/lease 契约保留。收件箱满时归还 lease，不确认交付；
 * 退避只暂停 cron 生产与交付，不让人工请求在退避期间也被阻塞。</p>
 */
final class CronProducer implements AutoCloseable {
    private final CronScheduler scheduler;
    private final Predicate<CronJob> publish;
    private final Consumer<String> errors;
    private ScheduledExecutorService timer;
    private volatile boolean closed;
    private volatile int failures;
    private volatile long nextAttempt;
    private volatile String lastPollError;

    /** 只绑定依赖，不恢复文件、不启动计时。scheduler 可空，表示本装配没有 cron。 */
    CronProducer(CronScheduler scheduler, Predicate<CronJob> publish, Consumer<String> errors) {
        this.scheduler = scheduler;
        this.publish = publish;
        this.errors = errors;
    }

    /** 只有 REPL 显式启动；单次 run 不因装配了定时工具就自动执行未来任务。 */
    synchronized void start() {
        if (scheduler == null || timer != null || closed) return;
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jade-cron-producer");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    /** 只计算到期并生产，最多受调度器 100 项上限约束；队列满不忙等。 */
    private void poll() {
        if (closed) return;
        try {
            scheduler.pollDue();
            lastPollError = null;
            if (!canDeliver()) return;
            CronJob job;
            while (!closed && (job = scheduler.takeDue()) != null) {
                if (!publish.test(job)) {
                    scheduler.restore(job.id());
                    return;
                }
            }
        } catch (IOException | RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            if (!java.util.Objects.equals(reason, lastPollError)) errors.accept("[Cron] Cannot poll: " + reason);
            lastPollError = reason;
        }
    }

    /** 队列中先前放入的 cron 也要遵守退避，不能绕过第三次失败后的暂停。 */
    boolean canDeliver() {
        return !closed && lastPollError == null && failures < 3
                && (nextAttempt == 0 || nextAttempt - System.nanoTime() <= 0);
    }

    /** 领取后可能已被工具取消；校验不执行、不确认，也不会复活被取消定义。 */
    boolean pending(String id) throws IOException {
        return scheduler != null && scheduler.list().stream()
                .anyMatch(job -> job.id().equals(id) && job.pendingDelivery());
    }

    /** 首个合法正式回复才确认；保存失败保留 pending/lease，不立即重复业务副作用。 */
    void received(String id) {
        failures = 0;
        nextAttempt = 0;
        try {
            scheduler.acknowledge(id);
        } catch (IOException failure) {
            errors.accept("[Cron] Received but acknowledgement failed for " + id + ": " + failure.getMessage());
        }
    }

    /** 首回复前失败：保留已有 5 秒退避与三次暂停，不重跑已交付后的整项业务。 */
    void failed(String id) {
        release(id);
        failures++;
        nextAttempt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        if (failures >= 3) errors.accept("[Cron] Automatic delivery paused after 3 failures; durable pending jobs remain for restart.");
    }

    /** 队列满、关闭或退避时只归还，不把“尚未尝试”算作模型失败。 */
    void release(String id) { if (scheduler != null) scheduler.restore(id); }

    /** 停止生产，不把进程内队列持久化，也不接管会话关闭。 */
    @Override
    public synchronized void close() {
        closed = true;
        if (timer != null) timer.shutdownNow();
    }
}
