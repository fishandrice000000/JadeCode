package com.jadecode.safety;

/**
 * 保存一个权限档位的读取限制、审批策略和审查者，供 Gate 与审批协调器选择处理方式。
 *
 * @param readOnly 当前计划是否按只读权限执行
 * @param approval 本轮人工审批通道；null 表示本轮没有人工通道
 * @param reviewer 辅助审批模型入口，用于普通操作的自动审查
 */
public record PermissionPolicy(boolean readOnly, ApprovalPolicy approval, Reviewer reviewer) {
    /**
     * 定义权限决策遇到询问时的处理方式：按需申请审批，或按从未获准处理。
     */
    public enum ApprovalPolicy {
        ON_REQUEST, NEVER
    }

    /**
     * 定义普通审批询问交给用户还是辅助模型审查。
     */
    public enum Reviewer {
        USER, MODEL
    }
}
