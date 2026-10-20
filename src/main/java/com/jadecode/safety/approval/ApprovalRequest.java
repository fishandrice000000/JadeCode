package com.jadecode.safety.approval;

import java.nio.file.Path;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.safety.rules.PermissionRule;

/**
 * 一次具体操作的审批快照，不持有执行上下文、会话或可运行工具。
 * suggestion 由权限层生成；界面只能选择，不能替换建议。参数深复制，防止等待时被改写。
 */
public record ApprovalRequest(String id, String actor, String runId, String tool, ObjectNode input,
        Path cwd, String reason, long revision, PermissionRule suggestion) {
    public ApprovalRequest { input = input.deepCopy(); }
    /** 调用者取得自己的副本，不能修改原申请的实际授权内容。 */
    @Override public ObjectNode input() { return input.deepCopy(); }
}
