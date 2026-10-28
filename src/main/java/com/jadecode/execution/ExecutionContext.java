package com.jadecode.execution;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import com.jadecode.safety.approval.Approval;


/**
 * 记录这项任务由谁发起，以及程序如何处理它的权限和退出。
 *
 * <p>用户和定时器都用同一线程执行任务，不能靠线程判断要不要询问用户。
 * 所以程序在排队前记录 source，再原样传给工具和子代理；模型不能在 JSON 里填写它。
 * 子代理接到定时任务仍然是 SCHEDULED，不能因此获得人工批准。</p>
 *
 * <p>id 用于找回这项任务启动的后台命令。approval 是询问用户的函数，不负责判断哪些命令
 * 需要批准；那由 PermissionGate 决定。sessionClosed 是一个检查函数，调用时才读取
 * 用户是否退出，让子代理在等模型或等批准后，也能停止后续操作。</p>
 * @param id 本次执行的 run_ ID，不同于定时定义 ID、工具调用 ID 和后台命令 bg_id
 * @param source USER 表示用户提交，SCHEDULED 表示定时触发
 * @param approval 询问用户的函数，null 表示不能询问
 * @param sessionClosed 检查所属会话是否已退出的函数，由程序提供，不是模型参数
 * @param goal 当前任务说明，供独立审批请求核对操作用途，不传入整段聊天历史
 * @param assignment Harness 绑定的派工快照；普通用户任务为 null，不从模型正文反解析
 * @param sandboxPlan 仅限本次工具调用的执行许可；普通任务为 null，不写入模型参数或历史
 * @param actorScope 一次委派的内部隔离编号；普通会话为空，不能从模型参数取得
 */
public record ExecutionContext(String id, Source source, Approval approval, BooleanSupplier sessionClosed, String goal,
        AssignmentContext assignment, com.jadecode.sandbox.ExecutionPlan sandboxPlan, String actorScope) {
    /** 任务上下文不默认携带调用级许可；旧入口不能借空字段绕过沙箱。 */
    public ExecutionContext(String id, Source source, Approval approval, BooleanSupplier sessionClosed, String goal,
            AssignmentContext assignment) {
        this(id, source, approval, sessionClosed, goal, assignment, null, "");
    }
    /** 仅在权限检查后派生本次工具上下文，原任务和其它工具不取得这份许可。 */
    public ExecutionContext withSandboxPlan(com.jadecode.sandbox.ExecutionPlan value) {
        return new ExecutionContext(id, source, approval, sessionClosed, goal, assignment, value, actorScope);
    }
    /**
     * Shell 实例关闭也要取消其 Docker 执行；合并检查函数，不覆盖会话原本的取消条件。
     * 仅派生本次执行的快照，保留来源、参数许可和身份，不修改父任务或其它后台命令。
     */
    public ExecutionContext withAdditionalCancellation(BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        return new ExecutionContext(id, source, approval, () -> sessionClosed.getAsBoolean() || cancelled.getAsBoolean(),
                goal, assignment, sandboxPlan, actorScope);
    }
    /** 子代理一次委派的隔离编号由 Harness 生成，保留父 run/source，丢弃父工具的调用级许可。 */
    public ExecutionContext withActorScope(String scope) {
        return new ExecutionContext(id, source, approval, sessionClosed, goal, assignment, null, scope);
    }
    /** 保留非 Team 调用方的构造契约；普通任务没有派工范围。 */
    public ExecutionContext(String id, Source source, Approval approval, BooleanSupplier sessionClosed, String goal) {
        this(id, source, approval, sessionClosed, goal, null);
    }
    /** 旧调用未提供目标时保留原签名；目标由会话在运行时补入，不改变来源。 */
    public ExecutionContext(String id, Source source, Approval approval, BooleanSupplier sessionClosed) { this(id, source, approval, sessionClosed, ""); }
    /** 给辅助审批携带当前真实任务说明，不把整个可变历史交给审批组件。 */
    public ExecutionContext withGoal(String value) { return new ExecutionContext(id, source, approval, sessionClosed, value == null ? "" : value, assignment, sandboxPlan, actorScope); }
    /** 工厂/派工路由绑定真实身份；不可变重建保留原权限来源，不扩大授权。 */
    public ExecutionContext withAssignment(AssignmentContext value) {
        return new ExecutionContext(id, source, approval, sessionClosed, goal, value, sandboxPlan, actorScope);
    }
    /**
     * 区分用户直接发起和定时触发。后台结果和 Stop 要求继续，都沿用原任务来源。
     */
    public enum Source {
        /** 用户直接提交的任务；还需要有询问用户的函数，且会话没退出，才能申请批准。 */
        USER,
        /** 时间到了自动触发的任务，工具和子代理都不能向用户申请批准。 */
        SCHEDULED
    }

    /**
     * 检查任务 ID 和必须提供的字段，不在构造时检查命令权限或执行任何工作。
     * @param id 非空白的任务 ID
     * @param source 任务来源，不可为 null
     * @param approval 可以为 null；即使提供，SCHEDULED 也不能询问用户
     * @param sessionClosed 检查会话是否退出的函数，不可为 null，应能安全地由不同线程调用
     * @throws IllegalArgumentException id 为 null 或空白
     * @throws NullPointerException source 或 sessionClosed 为 null
     */
    public ExecutionContext {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Execution ID is required");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sessionClosed, "sessionClosed");
        goal = goal == null ? "" : goal;
        actorScope = actorScope == null ? "" : actorScope;
    }

    /**
     * 给没有父会话的旧调用者使用，默认 isCancelled 一直返回 false。
     *
     * <p>真正的 CLI 任务应传四个参数，把检查会话退出的函数也传进来，
     * 否则子代理不知道父会话已经退出。这里默认 false 不代表跳过权限检查。</p>
     * @param id 非空白的任务 ID
     * @param source 任务来源
     * @param approval 询问用户的函数，可为 null
     * @throws IllegalArgumentException id 为 null 或空白
     * @throws NullPointerException source 为 null
     */
    public ExecutionContext(String id, Source source, Approval approval) {
        this(id, source, approval, () -> false);
    }

    /**
     * 创建一个新的 run_ ID，供独立调用者执行一项任务，不启动线程或命令。
     * @param source 任务来自用户还是定时器
     * @param approval 询问用户的函数，可为 null
     * @return 新任务的信息，没有父会话退出检查，isCancelled 默认一直为 false
     * @throws NullPointerException source 为 null
     */
    public static ExecutionContext create(Source source, Approval approval) {
        return new ExecutionContext("run_" + UUID.randomUUID(), source, approval);
    }

    /**
     * 为刚提交的任务分配 run_ ID，并记下以后如何检查用户是否退出。
     *
     * <p>同一任务的工具和子代理使用同一份信息，不另换来源或任务 ID。
     * 这里仅保存函数，不调用它来等待退出，也不启动线程、模型或命令。</p>
     * @param source 程序确定的任务来源，不能从模型文字中取
     * @param approval 询问用户的函数，可为 null
     * @param sessionClosed 检查会话是否退出的函数，例如读取 SessionRuntime 的 volatile closed 字段
     * @return 新任务的信息，工具和子代理沿用它
     * @throws NullPointerException source 或 sessionClosed 为 null
     */
    public static ExecutionContext create(Source source, Approval approval, BooleanSupplier sessionClosed) {
        return new ExecutionContext("run_" + UUID.randomUUID(), source, approval, sessionClosed);
    }

    /**
     * 检查用户现在是否已退出这项任务所在的会话。
     * @return 已退出为 true；false 只表示此刻没退出，不保证下一刻仍然如此。
     *         true 也不会还原已经写入的文件或自动停止所有正在进行的操作
     */
    public boolean isCancelled() { return sessionClosed.getAsBoolean(); }
}
