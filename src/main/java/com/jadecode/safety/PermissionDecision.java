package com.jadecode.safety;

/** 判断结果与规则编号分开保存。humanOnly 表示不能由辅助模型或可复用允许规则代替人工确认。 */
public record PermissionDecision(Kind kind, String ruleId, String reason, boolean humanOnly) {
    public enum Kind { ALLOW, DENY, ASK }
    public static PermissionDecision allow() { return new PermissionDecision(Kind.ALLOW, "", "", false); }
    public static PermissionDecision deny(String id, String why) { return new PermissionDecision(Kind.DENY, id, why, false); }
    public static PermissionDecision ask(String id, String why, boolean human) { return new PermissionDecision(Kind.ASK, id, why, human); }
}
