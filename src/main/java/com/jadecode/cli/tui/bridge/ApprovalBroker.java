package com.jadecode.cli.tui.bridge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.tui.UiEvent;
import com.jadecode.execution.Approval;

/**
 * 工具线程等审批，界面线程只显示和确认。future 留在 Broker，UI 事件只有编号与显示快照。
 * 关闭／中断均拒绝，不把未答复或旧 Enter 当批准；不决定哪些操作应申请审批。
 */
public final class ApprovalBroker implements Approval, AutoCloseable {
    private final Map<String, CompletableFuture<Boolean>> waiting = new ConcurrentHashMap<>();
    private final Consumer<UiEvent> sink;
    private boolean closed;
    public ApprovalBroker(Consumer<UiEvent> sink) { this.sink = sink; }
    /** 保留底层 Broker，队友再次绑定身份时不会沿用 Leader 的显示名。 */
    @Override public Approval forActor(String actor) {
        return new Approval() {
            @Override public boolean ask(String tool, ObjectNode input, String reason) { return askFor(actor, tool, input, reason); }
            @Override public Approval forActor(String next) { return ApprovalBroker.this.forActor(next); }
        };
    }
    @Override public boolean ask(String tool, ObjectNode input, String reason) {
        return askFor("leader", tool, input, reason);
    }
    /**
     * 为一次具体操作登记申请，并让调用它的工具线程等待答复，不让界面线程等待。
     * 参数先复制成显示字符串，模型或后续工具不能再改屏幕上的审批内容。
     * 申请和关闭互斥：已经关闭时直接拒绝；等待期间退出或中断也拒绝，最后移除面板。
     */
    private boolean askFor(String actor, String tool, ObjectNode input, String reason) {
        String id = UUID.randomUUID().toString(); var result = new CompletableFuture<Boolean>();
        synchronized (this) {
            if (closed) return false;
            waiting.put(id, result);
            sink.accept(new UiEvent.ApprovalRequested(id, actor, tool, input.deepCopy().toString(), reason));
        }
        // 锁内只登记，不在锁内等用户，否则界面无法调用 answer/close，形成互相等待。
        try { return result.get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (java.util.concurrent.ExecutionException failed) { return false; }
        finally { waiting.remove(id); sink.accept(new UiEvent.ApprovalRemoved(id)); }
    }
    /** 仅完成指定申请。重复点击或过期请求不会批准下一份申请。 */
    public void answer(String id, boolean approved) { var request = waiting.remove(id); if (request != null) request.complete(approved); }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        waiting.values().forEach(future -> future.complete(false)); waiting.clear();
    }
}
