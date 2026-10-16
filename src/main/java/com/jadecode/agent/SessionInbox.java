package com.jadecode.agent;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 进程内收件箱：有界 FIFO 存新任务，变化信号唤醒唯一消费者。
 *
 * <p>后台结果由 BackgroundManager 的有界队列保管，只发送唤醒信号，不与新请求争抢容量。
 * 这里不调用模型、不修改历史、不持久化。当前任务等待后台结果时不能领取下一任务。</p>
 */
final class SessionInbox {
    /** 只限制尚未领取的新请求，不含当前任务或后台完成记录。 */
    private static final int CAPACITY = 64;
    /** 所有访问受本对象监视器保护；队尾写入、队首领取实现 FIFO。 */
    private final ArrayDeque<Entry> requests = new ArrayDeque<>();
    /** 关闭后不再收请求，也不执行剩余队列。 */
    private boolean closed;
    /** 变化版本只用于判断是否值得等待，不代表结果数或消息序号。 */
    private long revision;

    /**
     * 绑定请求与提交者等待的完成通道；future 不进入模型上下文。
     * @param request 由可信生产者创建的新目标
     * @param result 入队不完成它，只有任务结束/失败/关闭后才完成
     */
    record Entry(SessionEvent.Request request, CompletableFuture<String> result) { }

    /**
     * 非阻塞地提交到队尾，并发生产者通过同一锁确定入队顺序。
     * @param entry 不可为 null 的入口，由生产者创建，不在这里执行模型或写历史
     * @return true 表示已入队且已唤醒消费者；false 表示关闭或已满，没有改变队列。
     *         入口必须据此拒绝人工 future 或归还 cron lease，不能当成交付成功
     */
    synchronized boolean offer(Entry entry) {
        if (closed || requests.size() >= CAPACITY) return false;
        requests.addLast(entry);
        wake();
        return true;
    }

    /**
     * 空闲消费者阻塞领取下一新目标；当前任务等待后台时不得调用此方法。
     * 后台唤醒可能使 wait 返回，但队列仍空时继续等，不会凭通知创造一项任务。
     * @return 队首入口，领取后从队列移除；已关闭时为 null，即使关闭前曾有排队项
     * @throws InterruptedException 等待被中断，交由消费循环决定退出
     */
    synchronized Entry take() throws InterruptedException {
        while (!closed && requests.isEmpty()) wait();
        return closed ? null : requests.removeFirst();
    }

    /**
     * 在扫描后台结果前记录变化版本，与 awaitChange 配合避免“结果已到但消费者睡下去”。
     * @return 当前版本；它不是需要逐一确认的事件数量
     */
    synchronized long revision() { return revision; }

    /**
     * 仅在版本仍未变化且未关闭时等待，不取走请求、不扫描结果也不轮询模型。
     * @param observed 调用方在扫描后台结果之前读取的 revision；已有变化则立即返回
     * @throws InterruptedException 等待被中断
     */
    synchronized void awaitChange(long observed) throws InterruptedException {
        while (!closed && revision == observed) wait();
    }

    /**
     * 更新版本并唤醒等待者；可由生产者、后台工作者或关闭线程调用。
     * 信号可合并，真实请求/结果在各自有界容器中，不靠唤醒次数还原数据。
     * 因此新请求已满也不会阻止后台结果发送此信号。
     */
    synchronized void wake() { revision++; notifyAll(); }

    /**
     * 幂等关闭：停止接收，清空未执行入口并让其 future 明确失败，不排空业务。
     *
     * <p>先在锁内取出入口并唤醒消费者，再在锁外完成 future。CompletableFuture 可能同步
     * 执行调用方回调，若持锁完成它，会把外部代码带入收件箱临界区。当前任务不在此队列，
     * 其完成通道由 SessionRuntime.close 负责；本方法也不关闭模型或 shell。</p>
     */
    void close() {
        List<Entry> abandoned;
        synchronized (this) {
            if (closed) return;
            closed = true;
            abandoned = List.copyOf(requests);
            requests.clear();
            wake();
        }
        abandoned.forEach(entry -> entry.result().completeExceptionally(
                new IllegalStateException("Session closed before task execution")));
    }
}
