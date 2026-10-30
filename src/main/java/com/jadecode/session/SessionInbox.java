package com.jadecode.session;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.jadecode.execution.ExecutionContext;

/**
 * 会话的单个收件箱：独立 Request 排队开始工作，Incoming 只在已有大轮次中采用。
 * 生产者只排队，消费线程独占历史写入；本类不请求模型、不写磁盘，也不识别业务目标。
 * 普通项最多 64 个；后台/Team 结果使用预留通道，生产端各限制 32 项未交付工作。
 * 队列、缓冲升级和完成检查共用本对象的锁；退出后队列不恢复。
 */
final class SessionInbox {
    private static final int CAPACITY = 64;
    private final List<Entry> requests = new ArrayList<>();
    /** 用户尚未被采用的缓冲批次；升级后仍指向同一批次，采用时解除。 */
    private Entry buffered;
    private boolean closed;
    private long revision;

    /**
     * 排队载体只保存请求或通知，不额外复制后台/Team 的完整记录。
     * result 为调用者的完成句柄；普通通知可为 null，只有需要回执的请求才分配 Future。
     */
    record Entry(SessionEvent event, CompletableFuture<String> result) {
        Entry {
            if (!(event instanceof SessionEvent.Request || event instanceof SessionEvent.Incoming))
                throw new IllegalArgumentException("Only requests and incoming messages can be queued");
            if (event instanceof SessionEvent.Request && result == null)
                throw new IllegalArgumentException("A request needs a result future");
        }
        /** 只有 pollRequest 返回的独立工作才调用；通知不会走执行入口。 */
        SessionEvent.Request request() { return (SessionEvent.Request) event; }
        /** 完成队列调用者，普通通知没有执行回执，不制造虚假的任务 Future。 */
        void fail(Throwable failure) { if (result != null) result.completeExceptionally(failure); }
    }

    /** 查看业务缓冲；只构造显示快照，不消费或升级它。 */
    synchronized SessionEvent.Incoming bufferedView() {
        if (buffered == null) return null;
        if (buffered.event() instanceof SessionEvent.Incoming incoming) return incoming;
        var request = buffered.request();
        return new SessionEvent.Incoming(request.context().id(), "user", "leader", null, null,
                "user", request.prompt(), null, false);
    }

    /** 包含停放的通知；切换会话必须确认所有信息均已处理。 */
    synchronized boolean isEmpty() { return requests.isEmpty(); }

    /** 按来源分配预留槽；这里只按可信 kind 分类容量，不限制消息的跨轮次采用。 */
    private static String slot(Entry entry) {
        if (entry.event() instanceof SessionEvent.Incoming incoming) {
            if (incoming.kind().equals("background")) return "background";
            if (incoming.kind().equals("result") || incoming.kind().equals("result_error")) return "team";
        }
        return "ordinary";
    }

    /**
     * 非阻塞入队；重复通知 ID 只保留一项，满队列/关闭返回 false 供生产者明确报告失败。
     * 相同锁内登记和发出变更信号，消费者不会在看到旧版本后遗漏一次已到达通知。
     */
    synchronized boolean offer(Entry entry) {
        if (closed) return false;
        if (entry.event() instanceof SessionEvent.Incoming incoming
                && requests.stream().anyMatch(e -> e.event() instanceof SessionEvent.Incoming other && other.id().equals(incoming.id())))
            return true;
        String category = slot(entry);
        // 终态通道不再叠加普通队列额度。取消旧轮次后仍可能有迟到报告，不能让这些
        // 事实挤掉新派工的终态解释；工作额度在 BackgroundManager/Runtime 登记时控制。
        if (category.equals("ordinary") && requests.stream().filter(e -> slot(e).equals("ordinary")).count() >= CAPACITY)
            return false;
        requests.add(entry);
        wake();
        return true;
    }

    /** 空闲时只取独立工作，跳过停放的通知；通知不会自动创建模型轮次。 */
    synchronized Entry pollRequest() {
        var iterator = requests.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!(entry.event() instanceof SessionEvent.Request)) continue;
            iterator.remove();
            if (entry == buffered) buffered = null;
            return entry;
        }
        return null;
    }

    /**
     * 在安全边界按入队顺序取走全部插入项；不接收 owner 或执行阶段，不检查业务归属。
     * 只移除选中项，其他请求/缓冲保持原位。返回不可变列表，之后由消费线程在锁外写历史。
     */
    synchronized List<Entry> drainInsertions() {
        List<Entry> selected = new ArrayList<>();
        var iterator = requests.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!(entry.event() instanceof SessionEvent.Incoming incoming) || !incoming.allowInsertion()) continue;
            iterator.remove();
            if (entry == buffered) buffered = null;
            selected.add(entry);
        }
        return List.copyOf(selected);
    }

    /** 最终完成与入队共用锁；已经到达的补充必须再交给模型，不能提前结束。 */
    synchronized boolean hasInsertions() {
        return requests.stream().anyMatch(e -> e.event() instanceof SessionEvent.Incoming incoming && incoming.allowInsertion());
    }

    /**
     * 忙时追加用户缓冲；首次建立可排队的 Request，升级后则追加到同一 Incoming。
     * 保留最初 Future 和编号，不修改历史；容量不足或关闭返回 null。
     */
    synchronized CompletableFuture<String> buffer(String text, ExecutionContext context) {
        if (closed) return null;
        if (buffered == null) {
            Entry entry = new Entry(new SessionEvent.Request(text, context), new CompletableFuture<>());
            if (!offer(entry)) return null;
            buffered = entry;
        } else {
            SessionEvent event;
            if (buffered.event() instanceof SessionEvent.Request request) {
                event = new SessionEvent.Request(request.prompt() + "\n\n" + text, request.context());
            } else {
                var old = (SessionEvent.Incoming) buffered.event();
                event = new SessionEvent.Incoming(old.id(), old.fromAgentId(), old.toAgentId(), old.taskId(),
                        old.assignmentId(), old.kind(), old.summary() + "\n\n" + text, old.bodyPath(), true);
            }
            Entry updated = new Entry(event, buffered.result());
            requests.set(requests.indexOf(buffered), updated);
            buffered = updated;
            wake();
        }
        return buffered.result();
    }

    /** 空回车将原缓冲升级成插入通知；不复制任务，采用后再升级返回 false。 */
    synchronized boolean promote() {
        if (closed || buffered == null) return false;
        if (buffered.event() instanceof SessionEvent.Incoming) return true;
        var old = bufferedView();
        var incoming = new SessionEvent.Incoming(old.id(), old.fromAgentId(), old.toAgentId(), old.taskId(),
                old.assignmentId(), old.kind(), old.summary(), old.bodyPath(), true);
        Entry updated = new Entry(incoming, buffered.result());
        requests.set(requests.indexOf(buffered), updated);
        buffered = updated;
        wake();
        return true;
    }

    /** 只读变更版本，用于检查后等待，避免通知恰好落在二者之间时丢失信号。 */
    synchronized long revision() { return revision; }
    /** 没有变更才等待；唤起仅表示重新检查，不意味着执行队首任务。 */
    synchronized void awaitChange(long observed) throws InterruptedException {
        while (!closed && revision == observed) wait();
    }
    /** 线程通知继续原消费流程，不具有空闲时新建业务轮次的语义。 */
    synchronized void wake() { revision++; notifyAll(); }

    /** 关闭后拒绝新消息；先清队列再在锁外完成失败回执，避免用户回调占住队列锁。 */
    void close() {
        List<Entry> abandoned;
        synchronized (this) {
            if (closed) return;
            closed = true;
            abandoned = List.copyOf(requests);
            requests.clear(); buffered = null; wake();
        }
        abandoned.forEach(entry -> entry.fail(new IllegalStateException("Session closed before task execution")));
    }
}
