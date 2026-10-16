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
    private static final int CAPACITY = 64;
    private final ArrayDeque<Entry> requests = new ArrayDeque<>();
    private boolean closed;
    private long revision;

    /** 请求和给提交者的结果通道，future 不进入模型上下文。 */
    record Entry(SessionEvent.Request request, CompletableFuture<String> result) { }

    /** 非阻塞提交；false 要由入口明确拒绝或归还 cron lease，不能当成交付成功。 */
    synchronized boolean offer(Entry entry) {
        if (closed || requests.size() >= CAPACITY) return false;
        requests.addLast(entry);
        wake();
        return true;
    }

    /** 空闲才领取；后台通知不能让空闲会话凭空启动任务。 */
    synchronized Entry take() throws InterruptedException {
        while (!closed && requests.isEmpty()) wait();
        return closed ? null : requests.removeFirst();
    }

    /** 在扫描结果前取版本，避免结果恰在检查与等待之间到达而丢失唤醒。 */
    synchronized long revision() { return revision; }

    /** 不取走新请求、不轮询模型；仅在观察到的版本仍未变化时阻塞。 */
    synchronized void awaitChange(long observed) throws InterruptedException {
        while (!closed && revision == observed) wait();
    }

    /** 信号可以合并，正文仍在拥有者的有界队列，不靠信号次数恢复。 */
    synchronized void wake() { revision++; notifyAll(); }

    /** 关闭不执行排队任务；在锁外完成 future，避免调用者回调在收件箱锁内执行。 */
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
