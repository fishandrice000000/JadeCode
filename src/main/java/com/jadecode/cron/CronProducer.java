package com.jadecode.cron;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.jadecode.cron.CronJob;
import com.jadecode.cron.CronScheduler;

/**
 * 每秒检查定时任务，把到期任务加进 SessionRuntime 的队列，不自己调用模型。
 *
 * <p>调度器取出一项任务时，先在内存标记“正在发送”，防止下一次检查又把它排一遍。
 * 排队成功还不算模型收到；SessionRuntime 拿到第一次正常模型回复后，才调用 received。
 * 队列满就去掉“正在发送”标记，下次再试，不丢掉任务。</p>
 *
 * <p>第一次回复前失败，等五秒再试，连续三次则暂停自动发送。
 * 第一次回复后的工具失败不能让整个任务重做，以免再次写文件或运行命令。
 * 定时文件和待发送记录保留原格式；排队中的会话请求不保存，程序退出后不会恢复。
 * 定时任务暂缓执行时，用户直接输入的任务照常排队和执行。</p>
 */
public final class CronProducer implements AutoCloseable {
    /** 与定时工具共用的任务记录，包含哪些任务还没送到模型；没有定时功能时为 null。 */
    private final CronScheduler scheduler;
    /** 往会话队列放任务的函数；返回 true 只说明排队成功。 */
    private final Predicate<CronJob> publish;
    /** 显示定时检查错误的函数，不把这些提示发给模型当作请求。 */
    private final Consumer<String> errors;
    /** 定时检查线程，不请求模型或执行工具。 */
    private ScheduledExecutorService timer;
    /** 定时检查线程和任务处理线程都要知道是否已经退出。 */
    private volatile boolean closed;
    /** 本会话连续几次在拿到模型第一次回复前失败，不是每项业务各自的重试次数。 */
    private volatile int failures;
    /** 用 nanoTime 记录下次允许重试的时间，0 表示不用等；不是日期时间戳。 */
    private volatile long nextAttempt;
    /** 最近一次检查的错误原因，便于少重复显示；检查成功后清掉。 */
    private volatile String lastPollError;

    /**
     * 记住定时任务记录、排队函数和错误输出函数，不读文件或启动线程。
     * @param scheduler 与定时工具共用的调度器；null 表示没有定时功能
     * @param publish 把选中的任务放进会话队列的函数，必须尽快返回
     * @param errors 显示错误的函数，不应抛异常或长时间不返回
     */
    public CronProducer(CronScheduler scheduler, Predicate<CronJob> publish, Consumer<String> errors) {
        this.scheduler = scheduler;
        this.publish = publish;
        this.errors = errors;
    }

    /**
     * 开始每秒检查一次时间，第一次立即安排检查，线程是 daemon。
     *
     * <p>没有调度器、已经启动过或已经退出，就直接返回。这里只运行 poll，
     * 不会请求模型；单次 run 不调用它，所以即使能创建定时任务，也不代表会一直等待任务到期。</p>
     */
    public synchronized void start() {
        if (scheduler == null || timer != null || closed) return;
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jade-cron-producer");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * 检查到期任务，并把能发送的任务逐个排进队列。
     *
     * <p>先让调度器更新哪些任务待发送，再检查是否需要暂停或等五秒。
     * 取任务时调度器会防止它重复排队；如果队列满，去掉这个标记，让它下次再试，
     * 然后结束本次检查，不在这里一直等空位。</p>
     *
     * <p>读写文件或排队出错就显示原因，暂时不再执行定时任务，直到下一次检查成功。
     * 此处不修改 history、不调用模型，也不会把仅仅排好队的任务记成已经送到模型。</p>
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
     * 现在能否继续发送/开始处理定时任务，排队之前和取出执行之前都检查。
     * @return 尚未退出、最近一次检查没出错、连续失败少于三次，并且五秒等待已经结束时为 true。
     *         只检查，不取任务，也不修改待发送记录
     */
    public boolean canDeliver() {
        return !closed && lastPollError == null && failures < 3
                && (nextAttempt == 0 || nextAttempt - System.nanoTime() <= 0);
    }

    /**
     * 开始执行前，再查一次定时任务是否还存在、是否仍待发送。
     *
     * <p>用户可能在任务排队期间把它取消；那就不能继续执行，也不能把已取消记录重新创建。</p>
     * @param id 定时任务定义的 ID，不是本次执行的 run_ ID
     * @return 仍有这项待发送任务时为 true；已取消或没有调度器时为 false
     * @throws IOException 读取任务记录失败，交给 SessionRuntime 按第一次回复前失败处理
     */
    public boolean pending(String id) throws IOException {
        return scheduler != null && scheduler.list().stream()
                .anyMatch(job -> job.id().equals(id) && job.pendingDelivery());
    }

    /**
     * 模型第一次正常回答后，把定时任务记为已经送达，并清零连续失败次数。
     *
     * <p>查长期记忆和生成摘要的辅助回答不算这次回答，也不等工具成功才记录。
     * 如果记录写盘失败，就显示错误，保留调度器当前的记录，避免本进程立即重做任务。
     * 重启后仍可能再发送一次，所以不能承诺业务只执行一次。</p>
     * @param id 已拿到第一次正常模型回复的定时定义 ID
     */
    public void received(String id) {
        failures = 0;
        nextAttempt = 0;
        try {
            scheduler.acknowledge(id);
        } catch (IOException failure) {
            errors.accept("[Cron] Received but acknowledgement failed for " + id + ": " + failure.getMessage());
        }
    }

    /**
     * 第一次正常模型回复前失败：让调度器可以再次取出它，等五秒，再增加失败计数。
     *
     * <p>累计三次会暂停自动发送，但不删文件里的待发送记录，重启后还能处理。
     * 只能用于模型尚未收到任务的情况；已经执行工具后再走这里，可能重复已有操作。</p>
     * @param id 尚未拿到第一次正常回复的定时定义 ID
     */
    public void failed(String id) {
        release(id);
        failures++;
        nextAttempt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        if (failures >= 3) errors.accept("[Cron] Automatic delivery paused after 3 failures; durable pending jobs remain for restart.");
    }

    /**
     * 去掉本进程中“这项任务正在发送”的标记，让以后还能取它。
     * 不删待发送记录、不增加失败次数，也不会重新创建已取消的任务。
     * @param id 因队列满、会话退出或暂缓执行而没能继续的定时定义 ID
     */
    public void release(String id) { if (scheduler != null) scheduler.restore(id); }

    /**
     * 停止定时检查，重复调用也可以，并尝试中断当前检查。
     *
     * <p>不停止模型或 shell，不保存会话队列；这些由 SessionRuntime 负责。
     * 已经保存的定时任务和待发送记录不删除，下次启动时调度器还会读取它们。</p>
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (timer != null) timer.shutdownNow();
    }
}
