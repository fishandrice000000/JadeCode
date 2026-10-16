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
    /**
     * 两种可信的新任务来源：USER 可携带人工审批通道，SCHEDULED 不允许交互审批。
     * 后台完成和 Stop 是当前任务的续跑，不创建新的来源；也不能因执行线程改变而变成 USER。
     */
    public enum Source {
        /** 用户直接提交；只有同时携带审批通道且会话未关闭，才可询问用户。 */
        USER,
        /** 定时定义到期触发；不能把后续工具/子代理的执行线程当作人工审批资格。 */
        SCHEDULED
    }

    /**
     * 在 Harness 构造边界校验身份完整性，不推断权限或执行安全规则。
     * @param id 非空白执行 ID，区分同一会话中的不同目标
     * @param source 非 null 的真实发起来源
     * @param approval 可选审批通道；即使非 null，SCHEDULED 也不会因此获得交互审批
     * @param sessionClosed 非 null、线程安全的只读关闭视图
     * @throws IllegalArgumentException id 缺失或为空白
     * @throws NullPointerException source 或 sessionClosed 为 null
     */
    public ExecutionContext {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Execution ID is required");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sessionClosed, "sessionClosed");
    }

    /**
     * 兼容独立调用方：不绑定父会话关闭信号，其 isCancelled 始终为 false。
     * 生产会话应使用四参数版本，才能让子代理观察父会话关闭；此默认值不是额外权限。
     * @param id 非空白执行 ID
     * @param source 可信任务来源
     * @param approval 可选审批通道
     * @throws IllegalArgumentException id 缺失或为空白
     * @throws NullPointerException source 为 null
     */
    public ExecutionContext(String id, Source source, PermissionGate.Approval approval) {
        this(id, source, approval, () -> false);
    }

    /**
     * 为一次新提交的任务分配执行 ID；排队前即固定身份，不启动模型、命令或文件 IO。
     * @param source 发起来源
     * @param approval 可选的人工审批通道
     * @return 只供本次执行使用的上下文
     * @throws NullPointerException source 为 null
     */
    public static ExecutionContext create(Source source, PermissionGate.Approval approval) {
        return new ExecutionContext("run_" + UUID.randomUUID(), source, approval);
    }

    /**
     * 为会话的新请求分配执行 ID，并绑定可被子代理继承的关闭视图。
     * 构造不启动线程或 IO；supplier 在查询时读取状态，不是注册的取消回调。
     * @param source 可信发起来源，不从模型文本推断
     * @param approval 可选人工审批通道
     * @param sessionClosed 读取所属会话关闭状态的线程安全函数，例如读取 volatile 标记
     * @return 带唯一 run_ ID 的上下文；同一任务的工具与子代理传递同一身份
     * @throws NullPointerException source 或 sessionClosed 为 null
     */
    public static ExecutionContext create(Source source, PermissionGate.Approval approval, BooleanSupplier sessionClosed) {
        return new ExecutionContext("run_" + UUID.randomUUID(), source, approval, sessionClosed);
    }

    /**
     * 查询所属会话是否关闭，供工具/子代理在执行边界拒绝晚到回复或批准。
     * @return 关闭视图当前值；false 不承诺之后不会关闭，true 也不能撤销已开始的 IO
     */
    public boolean isCancelled() { return sessionClosed.getAsBoolean(); }
}
