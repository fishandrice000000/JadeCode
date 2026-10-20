package com.jadecode.cli.tui.bridge;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import com.jadecode.cli.tui.UiEvent;
import com.jadecode.safety.approval.*;

/**
 * 工具线程等审批，界面线程只显示和确认。future 留在 Broker，UI 事件只有编号与显示快照。
 * 关闭／中断均拒绝，不把未答复或旧 Enter 当批准；不决定哪些操作应申请审批。
 */
public final class ApprovalBroker implements Approval, AutoCloseable {
    private record Pending(ApprovalRequest request, CompletableFuture<ApprovalAnswer> result) { }
    private final Map<String, Pending> waiting = new HashMap<>();
    private final Consumer<UiEvent> sink;
    private boolean closed;
    private long revision;
    public ApprovalBroker(Consumer<UiEvent> sink) { this.sink = sink; }
    /**
     * 为一次具体操作登记申请，并让调用它的工具线程等待答复，不让界面线程等待。
     * 参数先复制成显示字符串，模型或后续工具不能再改屏幕上的审批内容。
     * 申请和关闭互斥：已经关闭时直接拒绝；等待期间退出或中断也拒绝，最后移除面板。
     */
    @Override public ApprovalAnswer ask(ApprovalRequest request) {
        String id = request.id(); var pending = new Pending(request, new CompletableFuture<>());
        synchronized (this) {
            if (closed) return ApprovalAnswer.DENY;
            if (request.revision() < revision) return ApprovalAnswer.RECHECK;
            waiting.put(id, pending);
            sink.accept(new UiEvent.ApprovalRequested(id, request.actor(), request.tool(), request.input().toString(),
                    request.reason(), request.suggestion() == null ? "" : request.suggestion().description()));
        }
        // 锁内只登记，不在锁内等用户，否则界面无法调用 answer/close，形成互相等待。
        try { return pending.result().get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return ApprovalAnswer.DENY; }
        catch (java.util.concurrent.ExecutionException failed) { return ApprovalAnswer.DENY; }
        finally { synchronized (this) { waiting.remove(id); } sink.accept(new UiEvent.ApprovalRemoved(id)); }
    }
    /** 仅完成指定申请。重复点击或过期请求不会批准下一份申请。 */
    public synchronized void answer(String id, ApprovalAnswer answer) {
        var pending = waiting.get(id);
        if (pending == null || answer == null || answer == ApprovalAnswer.RECHECK) return;
        if (answer == ApprovalAnswer.ALLOW_SIMILAR && pending.request().suggestion() == null) return;
        waiting.remove(id); pending.result().complete(answer);
    }
    /** 权限变化唤醒旧申请，由 Gate 重新判断，不把变更当成用户批准。 */
    @Override public synchronized void reconsider(long value) {
        revision = Math.max(revision, value);
        waiting.values().removeIf(p -> {
            if (p.request().revision() >= revision) return false;
            p.result().complete(ApprovalAnswer.RECHECK); return true;
        });
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        waiting.values().forEach(p -> p.result().complete(ApprovalAnswer.DENY)); waiting.clear();
    }
}
