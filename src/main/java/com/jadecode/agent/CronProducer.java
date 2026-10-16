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
 *
 * <p>lease 是“本进程已经拿去投递”的临时资格，不等于模型收到任务。生产线程只入队；
 * 消费者拿到首个合法正式回复后才调用 received。首回复前失败允许退避重投；首回复后的
 * 工具失败不能在这里触发整项业务重跑。文件中的 pending 可恢复，但会话收件箱不持久化。</p>
 */
final class CronProducer implements AutoCloseable {
    /** 与定时工具共享定义及 pending 状态；旧装配可为 null。 */
    private final CronScheduler scheduler;
    /** 非阻塞投递函数，true 只说明入队，不说明模型已收到。 */
    private final Predicate<CronJob> publish;
    /** 调度诊断输出，不作为模型上下文消息。 */
    private final Consumer<String> errors;
    /** 单一到期检查线程，不承担模型或工具执行。 */
    private ScheduledExecutorService timer;
    /** 以下状态供生产线程与会话消费者共同观察。 */
    private volatile boolean closed;
    /** 此适配器连续首回复前失败次数，是会话级投递熔断计数，不是每项业务的重试次数。 */
    private volatile int failures;
    /** 单调时钟的下一可尝试时点，0 表示未设置退避。 */
    private volatile long nextAttempt;
    /** 最近一次轮询错误；相同错误不反复刷屏，轮询成功清除。 */
    private volatile String lastPollError;

    /**
     * 绑定投递适配所需依赖，不读取任务文件、不启动计时或模型。
     * @param scheduler 定时状态对象；可为 null，表示此装配没有 cron
     * @param publish 把一个已领取定义包装成会话请求的非阻塞函数
     * @param errors 诊断回调，不应抛异常或长时间阻塞
     */
    CronProducer(CronScheduler scheduler, Predicate<CronJob> publish, Consumer<String> errors) {
        this.scheduler = scheduler;
        this.publish = publish;
        this.errors = errors;
    }

    /**
     * REPL 显式启动每秒一次的到期检查；重复调用、无 scheduler 或已关闭时不启动。
     * 首次检查立即安排在独立 daemon 线程上。本方法不执行任何模型请求，单次 run
     * 不调用它，因此“装配定时工具”与“运行定时服务”是两件事。
     */
    synchronized void start() {
        if (scheduler == null || timer != null || closed) return;
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jade-cron-producer");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * 一次定时生产：更新到期 pending → 复核投递门槛 → 领取 lease → 尝试入队。
     *
     * <p>队列满立即归还当前 lease 并结束此次检查，下次时钟再试，不忙等；此前已入队项
     * 保留其 lease。IO/运行异常转为诊断，并暂时阻止交付，直到一次轮询成功清除错误。
     * 此处不确认模型接收、不修改历史，也不吞掉错误伪装成成功投递。</p>
     */
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

    /**
     * 检查现在是否允许投递，供生产及“已排队、尚未执行”的复核共用。
     * @return 未关闭、无轮询错误、未达三次首回复前失败且五秒退避已到期时为 true。
     *         只读状态，不领取任务或确认 pending
     */
    boolean canDeliver() {
        return !closed && lastPollError == null && failures < 3
                && (nextAttempt == 0 || nextAttempt - System.nanoTime() <= 0);
    }

    /**
     * 执行前确认定义仍存在且 pending，避免排队期间取消的任务仍被执行。
     * @param id 定时定义 ID，不是当前执行的 run_ ID
     * @return 同 ID 定义仍待投递时为 true；无 scheduler 或已取消时为 false
     * @throws IOException 读取定义失败，交由会话按首回复前失败处理
     */
    boolean pending(String id) throws IOException {
        return scheduler != null && scheduler.list().stream()
                .anyMatch(job -> job.id().equals(id) && job.pendingDelivery());
    }

    /**
     * 首个合法正式模型回复到达后确认本次投递，并清除连续失败/退避。
     *
     * <p>辅助召回或摘要不算收到任务，工具执行成功也不是此确认的条件。确认保存失败时
     * 报告诊断，调度器仍保留 pending/lease，避免本进程立即重投并重复副作用；跨重启仍可能
     * 再次投递，不能据此宣称恰好一次执行。</p>
     * @param id 已得到首回复的定时定义 ID
     */
    void received(String id) {
        failures = 0;
        nextAttempt = 0;
        try {
            scheduler.acknowledge(id);
        } catch (IOException failure) {
            errors.accept("[Cron] Received but acknowledgement failed for " + id + ": " + failure.getMessage());
        }
    }

    /**
     * 记录首个正式回复之前的投递失败：归还 lease、退避五秒，累计三次则暂停自动交付。
     * 持久 pending 不删除，重启可再次处理。只允许未确认投递走此路径，不能用于补救已运行
     * 的业务，否则可能重复工具副作用。
     * @param id 尚未得到首回复的定时定义 ID
     */
    void failed(String id) {
        release(id);
        failures++;
        nextAttempt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        if (failures >= 3) errors.accept("[Cron] Automatic delivery paused after 3 failures; durable pending jobs remain for restart.");
    }

    /**
     * 仅归还进程内 lease，不清除持久 pending、不增加失败次数，也不恢复已取消的定义。
     * @param id 因队列满、关闭、退避等原因未能继续投递的定义 ID
     */
    void release(String id) { if (scheduler != null) scheduler.restore(id); }

    /**
     * 幂等停止计时生产并尝试中断在途检查；不关闭会话、模型或 shell，不保存会话队列。
     * 已持久化定义/pending 不删除，由下一次启动的调度器恢复。
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (timer != null) timer.shutdownNow();
    }
}
