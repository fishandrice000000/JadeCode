package com.jadecode.agent;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 给尚未开始的任务排队，先来的先执行，最多保存 64 项。
 *
 * <p>用户和定时检查线程把任务放进来，SessionRuntime 的处理线程取走任务。
 * 后台命令的结果另存在 BackgroundManager，不占这 64 个名额；
 * 命令跑完只需要调用 wake，叫处理线程去查结果，不会因此开始一项新任务。</p>
 * <p>本类不请求模型、不改历史、不存文件，退出后队列不会恢复。</p>
 */
final class SessionInbox {
    /** 只数排队中的请求，不数正在做的任务和已经完成的后台命令。 */
    private static final int CAPACITY = 64;
    /** 访问这个队列前都要拿 synchronized 锁；从尾部加入，从头部取走。 */
    private final ArrayDeque<Entry> requests = new ArrayDeque<>();
    /** 退出后不再收请求，剩余排队任务也不执行。 */
    private boolean closed;
    /** 每次 wake 加一；用来判断刚才是否有线程叫我们检查，不代表结果个数。 */
    private long revision;

    /**
     * 把一项请求和将来返回答案的对象放在一起。
     * @param request 要执行的请求
     * @param result 给调用者取最终答案或错误用的 future，不会把它发给模型
     */
    record Entry(SessionEvent.Request request, CompletableFuture<String> result) { }

    /**
     * 把请求加到队尾，立即返回，不等任务执行，也不等队列腾位置。
     * @param entry 要加入的请求和结果 future，不可为 null
     * @return true 表示已经排队，并叫醒等待线程；false 表示已退出或队列满，没有加入。
     *         调用者要报告排队失败；定时请求则留到以后重试，不能当成已经送到模型
     */
    synchronized boolean offer(Entry entry) {
        if (closed || requests.size() >= CAPACITY) return false;
        requests.addLast(entry);
        wake();
        return true;
    }

    /**
     * 从队首取任务。队列空时线程在 wait 里等，不会反复循环占用 CPU。
     *
     * <p>只有当前没在做任务时才调用这里。等后台构建时也不能取下一项。
     * 后台命令可能调用 wake，但没有新任务的话仍继续等，不会因为被叫醒就凭空执行任务。</p>
     * @return 取出并移除的请求；会话已退出时返回 null，不执行剩余任务
     * @throws InterruptedException 等待线程被中断，由外层循环处理
     */
    synchronized Entry take() throws InterruptedException {
        while (!closed && requests.isEmpty()) wait();
        return closed ? null : requests.removeFirst();
    }

    /**
     * 取得“有事发生”的计数，供 awaitChange 判断该不该等待。
     * 调用者先读它，再去检查后台结果；这样不容易错过刚跑完的命令。
     * @return 当前计数，不是后台结果数量，也不用按这个数去取消息
     */
    synchronized long revision() { return revision; }

    /**
     * 计数还和刚才一样时，让线程等着；计数已改变就直接返回。
     *
     * <p>例如先读到 5，检查结果后还没来得及等，命令就跑完，把计数变成 6。
     * 这里发现 6 不等于 5，就不会睡下去。返回后由调用者重新查结果，不在这里取下一项任务。</p>
     * @param observed 调用者检查后台结果之前读到的计数
     * @throws InterruptedException 等待线程被中断
     */
    synchronized void awaitChange(long observed) throws InterruptedException {
        while (!closed && revision == observed) wait();
    }

    /**
     * 把计数加一，并用 notifyAll 叫醒 wait 中的线程，让它们再检查一次。
     *
     * <p>这里不保存结果正文，不要求每叫醒一次就对应处理一条结果。
     * 结果已经保存在 BackgroundManager，因此任务队列满了也不影响命令报告完成。</p>
     */
    synchronized void wake() { revision++; notifyAll(); }

    /**
     * 不再接收任务，清掉排队请求，并通知这些请求的调用者“退出了，没执行”。
     *
     * <p>先在 synchronized 里取出并清空队列，再到锁外让 future 失败。
     * 因为完成 future 可能马上运行调用者注册的函数，不要让那些函数一直拿着队列锁。
     * 已经开始的任务不在队列里，由 SessionRuntime.close 通知；本类不停止模型或命令。
     * 重复调用也可以。</p>
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
