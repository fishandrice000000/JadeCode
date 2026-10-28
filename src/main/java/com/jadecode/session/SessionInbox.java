package com.jadecode.session;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 保存本 Session 的待处理请求和外部通知；普通事件最多 64 项，后台另预留 32 项，插入式优先。
 *
 * <p>用户、定时器、邮箱与后台线程把事件放进来，只有 Session 消费线程取走它们。
 * 普通事件最多 64 项；后台完成事件有独立预留槽，由 BackgroundManager 的 32 项总额度限制。
 * 命令跑完调用 wake，处理线程再按投递设置决定继续当前任务、启动新轮次或暂存。</p>
 * <p>本类不请求模型、不改历史、不存文件，退出后队列不会恢复。</p>
 */
final class SessionInbox {
    /** 普通请求/邮箱通知额度，不包含后台完成事件的预留槽。 */
    private static final int CAPACITY = 64;
    /** 访问这个队列前都要拿 synchronized 锁；从尾部加入，从头部取走。 */
    private final List<Entry> requests = new ArrayList<>();
    /** 尚未取走的用户缓冲批次；追加和升级都替换这一条，不复制其正文再次入队。 */
    private Entry buffered;
    /** 只读显示快照；不取走、不升级缓冲，也不暴露 future。调用方须在相同锁内按序发通知。 */
    synchronized SessionEvent.Incoming bufferedView() { return buffered == null ? null : buffered.request().incoming(); }
    /** 退出后不再收请求，剩余排队任务也不执行。 */
    private boolean closed;
    /** 每次 wake 加一；用来判断刚才是否有线程叫我们检查，不代表结果个数。 */
    private long revision;

    /** 切换会话时连不唤起模型的参考通知也不能丢，必须确认整个收件箱为空。 */
    synchronized boolean isEmpty() { return requests.isEmpty(); }

    /**
     * 把一项请求和将来返回答案的对象放在一起。
     * @param request 要执行的请求
     * @param result 给调用者取最终答案或错误用的 future，不会把它发给模型
     */
    record Entry(SessionEvent.Request request, CompletableFuture<String> result,
            com.jadecode.process.BackgroundManager.Completion background, TeamReceipt team) {
        Entry(SessionEvent.Request request, CompletableFuture<String> result) { this(request, result, null, null); }
        Entry(SessionEvent.Request request, CompletableFuture<String> result,
                com.jadecode.process.BackgroundManager.Completion background) { this(request, result, background, null); }
    }

    /** 必等报告的所属执行由 Harness 登记，不能从模型正文猜测。 */
    record TeamReceipt(String assignment, String owner, boolean required) { }

    /**
     * 把请求加到队尾，立即返回，不等任务执行，也不等队列腾位置。
     * @param entry 要加入的请求和结果 future，不可为 null
     * @return true 表示已经排队，并叫醒等待线程；false 表示已退出或队列满，没有加入。
     *         调用者要报告排队失败；定时请求则留到以后重试，不能当成已经送到模型
     */
    synchronized boolean offer(Entry entry) {
        if (closed || requests.stream().filter(item -> item.background() == null && (item.team() == null || !item.team().required())).count() >= CAPACITY) return false;
        requests.add(entry);
        wake();
        return true;
    }

    /**
     * 后台最多 32 项，由进程管理器限制；独立预留槽，不能因为普通请求排满而丢完成结果。
     * 同一个 bg_id 不重复入队。退出后的事件明确不接收，进程管理器随后统一关闭。
     */
    synchronized boolean offerBackground(Entry entry) {
        if (closed) return false;
        if (requests.stream().noneMatch(item -> item.background() != null
                && item.background().id().equals(entry.background().id()))) requests.add(entry);
        wake();
        return true;
    }

    /**
     * 必等报告使用独立槽，普通进度不能挤占；数量由会话的 32 项未收讫派工上限约束。
     * 相同邮件重试不重复占槽，关闭时明确拒绝。
     */
    synchronized boolean offerTeam(Entry entry) {
        if (closed) return false;
        if (requests.stream().noneMatch(item -> item.request().incoming() != null
                && item.request().incoming().id().equals(entry.request().incoming().id()))) requests.add(entry);
        wake();
        return true;
    }

    /**
     * 空闲时优先取允许插入且能唤起的消息，再取其它可唤起消息或普通请求。
     * 不取 wakeWhenIdle=false 的参考消息，否则普通进度也会触发付费模型请求。
     */
    synchronized Entry pollIdle() {
        for (boolean insertionFirst : new boolean[]{true, false}) {
            for (int i = 0; i < requests.size(); i++) {
                Entry entry = requests.get(i);
                var incoming = entry.request().incoming();
                if (entry.background() != null && entry.background().required()
                        || entry.team() != null && entry.team().required()) continue;
                if (incoming != null && !incoming.wakeWhenIdle()) continue;
                boolean insertion = incoming != null && incoming.allowInsertion();
                if (insertion != insertionFirst) continue;
                requests.remove(i);
                if (entry == buffered) buffered = null;
                return entry;
            }
        }
        return null;
    }

    /**
     * 取通知而不是新派工。执行途中只取插入式消息；开始新任务时也带上暂存参考。
     * 同一次先取插入式，非插入式仅在没有插入式时取，避免优先级被批量合并抹掉。
     */
    synchronized List<Entry> drain(boolean insertionOnly, boolean finishing, String owner) {
        List<Entry> selected = new ArrayList<>();
        boolean hasInsertion = requests.stream().anyMatch(e -> eligible(e, owner)
                && (e.request().incoming().allowInsertion() || finishing && ownRequired(e, owner)));
        var iterator = requests.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!eligible(entry, owner)) continue;
            boolean insertion = entry.request().incoming().allowInsertion() || finishing && ownRequired(entry, owner);
            if ((insertionOnly || hasInsertion) && !insertion) continue;
            iterator.remove();
            if (entry == buffered) buffered = null;
            selected.add(entry);
        }
        return List.copyOf(selected);
    }

    /** 必等结果只归启动它的当前大轮次，不能偷给别的任务处理。 */
    private static boolean ownRequired(Entry entry, String owner) {
        return entry.background() != null && entry.background().required() && entry.background().owner().equals(owner)
                || entry.team() != null && entry.team().required() && entry.team().owner().equals(owner);
    }

    /** 新派工和新用户请求不是参考通知；其它任务的必等结果也不能取走。 */
    private static boolean eligible(Entry entry, String owner) {
        return isNotice(entry) && (entry.background() == null || !entry.background().required() || ownRequired(entry, owner))
                && (entry.team() == null || !entry.team().required() || ownRequired(entry, owner));
    }

    /** 最终结束判定与生产者入队用同一把锁：已经入队的插入式消息不能被漏过去。 */
    synchronized boolean hasCurrentInput(String owner) {
        return requests.stream().anyMatch(entry -> eligible(entry, owner)
                && (entry.request().incoming().allowInsertion() || ownRequired(entry, owner)));
    }

    /** 原轮次失败时丢弃其已取消的必等通知，不让它永久占着接收队列。 */
    synchronized void discardRequired(String owner) {
        requests.removeIf(entry -> ownRequired(entry, owner));
    }

    /** 新派工永不混入其它当前任务；用户升级的缓冲则允许作为补充。 */
    private static boolean isNotice(Entry entry) {
        var incoming = entry.request().incoming();
        return incoming != null && !incoming.kind().equals("assignment")
                && (!incoming.kind().equals("user") || incoming.allowInsertion());
    }

    /**
     * 合并忙时用户输入，返回同一个 future。消费、追加、升级共用同一锁，已取走批次不能复活。
     * @return null 表示已关闭或没有新批次的空间，调用方必须报告拒绝
     */
    synchronized CompletableFuture<String> buffer(String text, com.jadecode.execution.ExecutionContext context) {
        if (closed) return null;
        String combined = text;
        boolean insertion = false;
        CompletableFuture<String> result = new CompletableFuture<>();
        String id = java.util.UUID.randomUUID().toString();
        if (buffered != null) {
            var old = buffered.request().incoming();
            combined = old.summary() + "\n\n" + text;
            insertion = old.allowInsertion();
            result = buffered.result();
            id = old.id();
        } else if (requests.stream().filter(item -> item.background() == null && (item.team() == null || !item.team().required())).count() >= CAPACITY) return null;
        var incoming = new SessionEvent.Incoming(id, "user", "leader", null, null,
                "user", combined, null, insertion, true);
        Entry updated = new Entry(new SessionEvent.Request(combined, context, null, incoming), result);
        if (buffered != null) requests.set(requests.indexOf(buffered), updated);
        else requests.add(updated);
        buffered = updated;
        wake();
        return result;
    }

    /** 空 Enter 只升级原批次。返回 false 时没有可升级内容，不能生成一份重复请求。 */
    synchronized boolean promote() {
        if (buffered == null || closed) return false;
        var request = buffered.request();
        var old = request.incoming();
        var incoming = new SessionEvent.Incoming(old.id(), old.fromAgentId(), old.toAgentId(), old.taskId(),
                old.assignmentId(), old.kind(), old.summary(), old.bodyPath(), true, true);
        Entry updated = new Entry(new SessionEvent.Request(request.prompt(), request.context(), null, incoming), buffered.result());
        requests.set(requests.indexOf(buffered), updated);
        buffered = updated;
        wake();
        return true;
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
            buffered = null;
            wake();
        }
        abandoned.forEach(entry -> entry.result().completeExceptionally(
                new IllegalStateException("Session closed before task execution")));
    }
}
