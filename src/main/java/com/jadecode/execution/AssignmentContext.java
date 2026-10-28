package com.jadecode.execution;

/**
 * Harness 绑定的一次派工身份。模型不能通过通信正文或工具的额外字段冒充它。
 * 子代理沿用此值，后台任务在启动时保存它；之后的派工不会改变旧任务的范围。
 * @param id 本次派工编号
 * @param taskId 持久任务编号
 * @param actor 实际接单的成员 ID
 * @param leaderRunId 发出派工的 Leader 执行编号
 * @param scope 已校验的不可变范围快照；不增加现有授权
 */
public record AssignmentContext(String id, String taskId, String actor, String leaderRunId, AssignmentScope scope) {
    /** 只接受程序已经绑定的完整身份；构造不执行模型或文件 IO。 */
    public AssignmentContext {
        if (id == null || taskId == null || actor == null || leaderRunId == null || scope == null)
            throw new IllegalArgumentException("Assignment context is incomplete");
    }
}
