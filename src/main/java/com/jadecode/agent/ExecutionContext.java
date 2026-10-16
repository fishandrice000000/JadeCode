package com.jadecode.agent;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.jadecode.safety.PermissionGate;

/**
 * Harness 创建的一次任务执行身份，不是模型参数，也不是 s10 的持久任务条目。
 *
 * <p>任务 ID 将后台命令与发起任务关联；来源决定能否交互审批。两者都由运行器传给工具与
 * 子代理，不能从模型提交的 JSON 中恢复。approval 是终端提供的审批通道，不负责安全规则；
 * 没有通道时需要询问的操作仍然拒绝。身份不可变；sessionClosed 是所属会话取消状态的
 * 只读视图，子代理借它拒绝关闭后的晚到回复，不接管父队列或跨进程状态。</p>
 * @param id 本次执行的身份，与 cron 定义 ID、tool_use_id 和 bg_id 分开
 * @param source 真实发起来源，子代理继承，不因换线程而改变
 * @param approval 人工任务的审批通道；null 表示没有交互通道
 * @param sessionClosed Harness 提供的线程安全只读关闭信号，不由模型设置
 */
public record ExecutionContext(String id, Source source, PermissionGate.Approval approval, BooleanSupplier sessionClosed) {
    /** 两种任务入口；后台完成和 Stop 续跑不是新的入口来源。 */
    public enum Source { USER, SCHEDULED }

    /** 在构造边界拒绝缺失身份；模型没有设置这个记录的工具入口。 */
    public ExecutionContext {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Execution ID is required");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sessionClosed, "sessionClosed");
    }

    /** 独立调用方的兼容构造：没有父会话关闭信号，也不因此获得审批资格。 */
    public ExecutionContext(String id, Source source, PermissionGate.Approval approval) {
        this(id, source, approval, () -> false);
    }

    /**
     * 为一次新提交的任务分配执行 ID；排队前即固定身份，不启动模型、命令或文件 IO。
     * @param source 发起来源
     * @param approval 可选的人工审批通道
     * @return 只供本次执行使用的上下文
     */
    public static ExecutionContext create(Source source, PermissionGate.Approval approval) {
        return new ExecutionContext("run_" + UUID.randomUUID(), source, approval);
    }

    /** 会话提交入口额外绑定关闭视图；后台结果只记录 ID，不持有或修改这个信号。 */
    public static ExecutionContext create(Source source, PermissionGate.Approval approval, BooleanSupplier sessionClosed) {
        return new ExecutionContext("run_" + UUID.randomUUID(), source, approval, sessionClosed);
    }

    /** 只查询所属会话是否已关闭，不能据此撤销已经开始的文件或命令副作用。 */
    public boolean isCancelled() { return sessionClosed.getAsBoolean(); }
}
