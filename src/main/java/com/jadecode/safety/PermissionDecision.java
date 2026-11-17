package com.jadecode.safety;

/**
 * 判断结果与规则编号分开保存。humanOnly 表示不能由辅助模型或可复用允许规则代替人工确认。
 *
 * @param kind 允许、拒绝或询问三种权限决定之一
 * @param ruleId 命中规则的稳定 ID，供拒绝或询问结果定位原因
 * @param reason 当前事件来源或失败的业务原因
 * @param humanOnly 此审批是否要求人工通道作答
 */
public record PermissionDecision(Kind kind, String ruleId, String reason, boolean humanOnly) {
    /**
     * 表示一次工具权限判断的三种结果：允许执行、拒绝执行或进入审批。
     */
    public enum Kind {
        ALLOW, DENY, ASK
    }

    /**
     * 创建允许执行的权限结果，原因和规则 ID 留空。
     *
     * @return 允许执行且原因留空的决定
     */
    public static PermissionDecision allow() {
        return new PermissionDecision(Kind.ALLOW, "", "", false);
    }

    /**
     * 创建拒绝执行的权限结果，携带命中规则 ID 和原因，供执行器生成阻止结果。
     *
     * @param id 命中的权限规则 ID，随拒绝或询问原因返回
     * @param why 本次拒绝或询问的具体业务原因
     * @return 带命中规则 ID 和业务原因的拒绝决定
     */
    public static PermissionDecision deny(String id, String why) {
        return new PermissionDecision(Kind.DENY, id, why, false);
    }

    /**
     * 创建需要审批的权限结果，携带规则 ID、原因及是否必须由人工作答，供审批协调器选择通道。
     *
     * @param id 命中的权限规则 ID，随拒绝或询问原因返回
     * @param why 本次拒绝或询问的具体业务原因
     * @param human 本次操作可用的人工审批通道；null 表示无法交给用户作答
     * @return 带原因与人工要求的询问决定
     */
    public static PermissionDecision ask(String id, String why, boolean human) {
        return new PermissionDecision(Kind.ASK, id, why, human);
    }
}
