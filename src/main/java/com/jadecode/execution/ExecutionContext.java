package com.jadecode.execution;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import com.jadecode.safety.approval.Approval;

/**
 * 保存 Harness 在请求入队时建立的执行身份，并传给模型执行器、工具、子代理与后台命令。source 标识发起来源，
 * approval 提供人工询问端口，取消函数在执行边界读取状态；
 * 可选 Assignment 和 ExecutionPlan 分别携带派工范围及本次工具的执行快照。
 *
 * @param id 当前 Agent 或会话的稳定身份标识
 * @param source 由 Harness 确定的 USER 或 SCHEDULED 任务来源
 * @param approval 本轮人工审批通道；null 表示本轮没有人工通道
 * @param sessionClosed 读取会话或轮次取消状态的函数
 * @param goal 本轮真实任务说明，供辅助审批核对操作用途
 * @param assignment Harness 已绑定的派工身份和路径范围；普通用户请求为 null
 * @param sandboxPlan 本次具体工具调用的沙箱执行快照；普通请求为 null
 * @param actorScope 一次子代理委派的隔离编号；普通会话使用空串
 */
public record ExecutionContext(String id, Source source, Approval approval, BooleanSupplier sessionClosed, String goal,
        AssignmentContext assignment, com.jadecode.sandbox.ExecutionPlan sandboxPlan, String actorScope) {

    /**
     * 仅在权限检查后派生本次工具上下文，原任务和其它工具不取得这份许可。
     *
     * @param value 已经授权的本次工具执行计划
     * @return 带本次计划的新上下文，沿用原轮次身份
     */
    public ExecutionContext withSandboxPlan(com.jadecode.sandbox.ExecutionPlan value) {
        return new ExecutionContext(id, source, approval, sessionClosed, goal, assignment, value, actorScope);
    }

    /**
     * Shell 实例关闭也要取消其 Docker 执行；合并检查函数，不覆盖会话原本的取消条件。
     * 仅派生本次执行的快照，保留来源、参数许可和身份，不修改父任务或其它后台命令。
     *
     * @param cancelled 执行过程中读取的取消检查函数
     * @return 原取消条件或新增条件任一成立即取消的新上下文
     * @throws NullPointerException cancelled 为 null
     */
    public ExecutionContext withAdditionalCancellation(BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        return new ExecutionContext(id, source, approval,
                () -> sessionClosed.getAsBoolean() || cancelled.getAsBoolean(),
                goal, assignment, sandboxPlan, actorScope);
    }

    /**
     * 已接纳的后台调用派生独立取消生命周期；仅可信执行端调用，不从模型参数取得。
     * 身份、审批来源、派工与调用级许可保持不变，原轮次结束不扩大已有授权。
     *
     * @param cancelled 执行过程中读取的取消检查函数
     * @return 替换取消条件后、仍沿用原身份和许可的新上下文
     * @throws NullPointerException cancelled 为 null
     */
    public ExecutionContext withCancellation(BooleanSupplier cancelled) {
        return new ExecutionContext(id, source, approval, Objects.requireNonNull(cancelled), goal,
                assignment, sandboxPlan, actorScope);
    }

    /**
     * 子代理一次委派的隔离编号由 Harness 生成，保留父 run/source，丢弃父工具的调用级许可。
     *
     * @param scope 本次子代理委派编号，用于派生独立资源作用域
     * @return 绑定新委派编号并清除父工具计划的新上下文
     */
    public ExecutionContext withActorScope(String scope) {
        return new ExecutionContext(id, source, approval, sessionClosed, goal, assignment, null, scope);
    }

    /**
     * 给辅助审批携带当前真实任务说明，不把整个可变历史交给审批组件。
     *
     * @param value 包含最新用户补充的任务说明
     * @return 更新任务说明后的新上下文
     */
    public ExecutionContext withGoal(String value) {
        return new ExecutionContext(id, source, approval, sessionClosed, value == null ? "" : value, assignment,
                sandboxPlan, actorScope);
    }

    /**
     * 工厂/派工路由绑定真实身份；不可变重建保留原权限来源，不扩大授权。
     *
     * @param value 路由层已经验证的派工身份和路径范围
     * @return 绑定派工快照后的新上下文
     */
    public ExecutionContext withAssignment(AssignmentContext value) {
        return new ExecutionContext(id, source, approval, sessionClosed, goal, value, sandboxPlan, actorScope);
    }

    /**
     * 区分用户直接发起和定时触发。后台结果和 Stop 要求继续，都沿用原任务来源。
     */
    public enum Source {
        /** 用户直接提交的任务；还需要有询问用户的函数，且会话没退出，才能申请批准。 */
        USER,
        /** 保留的非人工来源标记，工具和子代理不得申请人工批准；当前运行时不生产定时请求。 */
        SCHEDULED
    }

    /**
     * 检查任务 ID 和必须提供的字段，不在构造时检查命令权限或执行任何工作。
     *
     * @param id 非空白的任务 ID
     * @param source 任务来源，不可为 null
     * @param approval 可以为 null；即使提供，SCHEDULED 也不能询问用户
     * @param sessionClosed 检查会话是否退出的函数，不可为 null，应能安全地由不同线程调用
     * @param goal 本轮真实任务说明，供辅助审批核对操作用途
     * @param assignment Harness 已绑定的派工身份和路径范围；普通用户请求为 null
     * @param sandboxPlan 本次具体工具调用的沙箱执行快照；普通请求为 null
     * @param actorScope 一次子代理委派的隔离编号；普通会话使用空串
     * @throws IllegalArgumentException id 为 null 或空白
     * @throws NullPointerException source 或 sessionClosed 为 null
     */
    public ExecutionContext {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("Execution ID is required");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sessionClosed, "sessionClosed");
        goal = goal == null ? "" : goal;
        actorScope = actorScope == null ? "" : actorScope;
    }

    /**
     * 创建一个新的 run_ ID，供独立调用者执行一项任务，不启动线程或命令。
     *
     * @param source 任务来自用户还是定时器
     * @param approval 询问用户的函数，可为 null
     * @return 新任务的信息，没有父会话退出检查，isCancelled 默认一直为 false
     * @throws NullPointerException source 为 null
     */
    public static ExecutionContext create(Source source, Approval approval) {
        return new ExecutionContext("run_" + UUID.randomUUID(), source, approval, () -> false, "", null, null, "");
    }

    /**
     * 为刚提交的任务分配 run_ ID，并记下以后如何检查用户是否退出。
     *
     * <p>同一任务的工具和子代理使用同一份信息，不另换来源或任务 ID。
     * 这里仅保存函数，不调用它来等待退出，也不启动线程、模型或命令。</p>
     *
     * @param source 程序确定的任务来源，不能从模型文字中取
     * @param approval 询问用户的函数，可为 null
     * @param sessionClosed 检查会话是否退出的函数，例如读取 SessionRuntime 的 volatile closed 字段
     * @return 新任务的信息，工具和子代理沿用它
     * @throws NullPointerException source 或 sessionClosed 为 null
     */
    public static ExecutionContext create(Source source, Approval approval, BooleanSupplier sessionClosed) {
        return new ExecutionContext("run_" + UUID.randomUUID(), source, approval, sessionClosed, "", null, null, "");
    }

    /**
     * 读取本轮取消检查函数，返回当前会话、任务或附加执行条件是否已要求停止，供等待、请求和工具步骤选择收尾。
     *
     * @return 绑定的 sessionClosed 检查函数当前返回的取消状态
     */
    public boolean isCancelled() {
        return sessionClosed.getAsBoolean();
    }
}
