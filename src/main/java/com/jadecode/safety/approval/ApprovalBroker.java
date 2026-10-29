package com.jadecode.safety.approval;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 会话持有的审批账本；不依赖终端、socket 或工具执行器。
 * 登记后等待确切请求的结果，连接离线不改变账本；超时、取消和关闭才结束等待。
 * 展示证据由客户端适配器验证，适配器只能完成仍在本账本中的原请求一次。
 */
public final class ApprovalBroker implements AutoCloseable {
    private final Map<String, Pending> waiting = new LinkedHashMap<>();
    private final long timeoutMillis;
    private boolean closed;
    public record Pending(ApprovalRequest request, CompletableFuture<ApprovalAnswer> result) { }
    /** timeout 必须为正；默认人工通道最长等待一小时，服务可以显式注入更窄限制。 */
    public ApprovalBroker(Duration timeout) {
        timeoutMillis = timeout.toMillis();
        if (timeoutMillis < 1) throw new IllegalArgumentException("Approval timeout must be positive");
    }
    /** 在显示之前登记；关闭后或重复 ID 明确失败，输入快照不被修改。 */
    public synchronized Pending register(ApprovalRequest request) {
        if (closed) throw new IllegalStateException("Approval channel is closed");
        if (waiting.containsKey(request.id())) throw new IllegalStateException("Duplicate approval request");
        var pending = new Pending(request, new CompletableFuture<>());
        waiting.put(request.id(), pending); return pending;
    }
    /** 等待指定记录，超时按未获许可处理；finally 始终移除该确切记录。 */
    public ApprovalAnswer await(Pending pending) throws InterruptedException {
        try { return pending.result().get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (ExecutionException | TimeoutException failure) { return ApprovalAnswer.DENY; }
        finally { synchronized (this) { waiting.remove(pending.request().id(), pending); } }
    }
    /** 原子完成一次；陈旧或重复答复不能影响下一份请求。 */
    public synchronized boolean answer(String id, ApprovalAnswer answer) {
        Pending pending = waiting.remove(id);
        return pending != null && pending.result().complete(answer);
    }
    /** 供重连显示和私有状态记录；列表不可变，Future 不通过协议传输。 */
    public synchronized List<ApprovalRequest> requests() {
        return waiting.values().stream().map(Pending::request).toList();
    }
    /** 执行取消或权限变化时结束选定等待；断开控制连接不会调用此方法。 */
    public synchronized void cancelAll(ApprovalAnswer answer) {
        for (Pending pending : waiting.values()) pending.result().complete(answer);
        waiting.clear();
    }
    @Override public synchronized void close() { closed = true; cancelAll(ApprovalAnswer.DENY); }
}
