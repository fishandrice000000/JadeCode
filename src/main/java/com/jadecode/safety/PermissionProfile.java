package com.jadecode.safety;

/** 四种界面预设；只组合应用内审批行为，不代表已经启用操作系统沙箱。 */
public enum PermissionProfile {
    READ_ONLY("read-only", "Read Only"), WORKSPACE("workspace", "Workspace"),
    AUTO_REVIEW("auto-review", "Approve for Me"), FULL_ACCESS("full-access", "Full Access");
    private final String value, label;
    PermissionProfile(String value, String label) { this.value = value; this.label = label; }
    public String value() { return value; }
    public String label() { return label; }
    /** 只接受明确名称，拼错不能默默退回更宽权限。 */
    public static PermissionProfile parse(String text) {
        for (var p : values()) if (p.value.equals(text) || p.name().equals(text) || p.label.equals(text)) return p;
        throw new IllegalArgumentException("权限档位须为 read-only / workspace / auto-review / full-access");
    }
    /** 审批策略与审查者分别保存；只有 Full Access 不发起审批。 */
    public PermissionPolicy policy() {
        return new PermissionPolicy(this == READ_ONLY,
                this == FULL_ACCESS ? PermissionPolicy.ApprovalPolicy.NEVER : PermissionPolicy.ApprovalPolicy.ON_REQUEST,
                this == AUTO_REVIEW ? PermissionPolicy.Reviewer.MODEL : PermissionPolicy.Reviewer.USER);
    }
}
