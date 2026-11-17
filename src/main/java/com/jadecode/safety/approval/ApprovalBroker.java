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

    /**
     * 保存待审批请求和等待结果的 Future，供审批账本登记、作答和超时清理使用。
     *
     * @param request 固定本次工具、输入、真实身份和权限版本的审批请求
     * @param result 等待当前审批得到允许、拒绝或重判结果的 Future
     */
    public record Pending(ApprovalRequest request, CompletableFuture<ApprovalAnswer> result) {
    }

    /**
     * timeout 必须为正；默认人工通道最长等待一小时，服务可以显式注入更窄限制。
     *
     * @param timeout 人工审批允许等待的正数时长，构造时换算为毫秒
     * @throws IllegalArgumentException 审批等待时间不足一毫秒
     */
    public ApprovalBroker(Duration timeout) {
        timeoutMillis = timeout.toMillis();
        if (timeoutMillis < 1)
            throw new IllegalArgumentException("Approval timeout must be positive");
    }

    /**
     * 在显示之前登记；关闭后或重复 ID 明确失败，输入快照不被修改。
     *
     * @param request 固定本次工具、输入、真实身份和权限版本的审批请求
     * @return 已经登记并分配结果 Future 的待审批项
     * @throws IllegalStateException 账本已经关闭或请求 ID 已登记
     */
    public synchronized Pending register(ApprovalRequest request) {
        if (closed)
            throw new IllegalStateException("Approval channel is closed");
        if (waiting.containsKey(request.id()))
            throw new IllegalStateException("Duplicate approval request");
        var pending = new Pending(request, new CompletableFuture<>());
        waiting.put(request.id(), pending);
        return pending;
    }

    /**
     * 等待指定记录，超时按未获许可处理；finally 始终移除该确切记录。
     *
     * @param pending 本次待处理的队列事件或审批记录
     * @return 该请求的实际答复；超时或结果异常时为 DENY
     * @throws InterruptedException 等待审批结果的工具线程被中断；finally 移除本请求
     */
    public ApprovalAnswer await(Pending pending) throws InterruptedException {
        try {
            return pending.result().get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException failure) {
            return ApprovalAnswer.DENY;
        } finally {
            synchronized (this) {
                waiting.remove(pending.request().id(), pending);
            }
        }
    }

    /**
     * 原子完成一次；陈旧或重复答复不能影响下一份请求。
     *
     * @param id 待审批请求 ID，用于找到原等待记录
     * @param answer 本次审批的允许、拒绝或重新检查结果
     * @return 请求仍在等待且本次成功完成其 Future 时为 true
     */
    public synchronized boolean answer(String id, ApprovalAnswer answer) {
        Pending pending = waiting.remove(id);
        return pending != null && pending.result().complete(answer);
    }

    /**
     * 供重连显示和私有状态记录；列表不可变，Future 不通过协议传输。
     *
     * @return 当前仍在等待的审批请求不可变列表
     */
    public synchronized List<ApprovalRequest> requests() {
        return waiting.values().stream().map(Pending::request).toList();
    }

    /**
     * 执行取消或权限变化时结束选定等待；断开控制连接不会调用此方法。
     *
     * @param answer 本次审批的允许、拒绝或重新检查结果
     */
    public synchronized void cancelAll(ApprovalAnswer answer) {
        for (Pending pending : waiting.values())
            pending.result().complete(answer);
        waiting.clear();
    }

    /**
     * 标记审批账本关闭，并将全部待审批请求完成为拒绝，唤醒等待审批结果的工具线程。
     */
    @Override
    public synchronized void close() {
        closed = true;
        cancelAll(ApprovalAnswer.DENY);
    }
}
