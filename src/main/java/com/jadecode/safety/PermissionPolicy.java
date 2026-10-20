package com.jadecode.safety;

/** 预设生成的不可变策略；readOnly 是应用内检查要求，不是假沙箱开关。 */
public record PermissionPolicy(boolean readOnly, ApprovalPolicy approval, Reviewer reviewer) {
    public enum ApprovalPolicy { ON_REQUEST, NEVER }
    public enum Reviewer { USER, MODEL }
}
