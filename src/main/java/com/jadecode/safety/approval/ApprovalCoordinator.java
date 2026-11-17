package com.jadecode.safety.approval;

import com.jadecode.safety.*;

/**
 * 为已经判定需要审批的操作选择用户或辅助模型通道，结合权限版本变化返回允许、拒绝或重新检查的结果。
 */
public final class ApprovalCoordinator {
    /**
     * 答复与拒绝原因一起返回，避免把“无人可审批”说成已由用户拒绝。
     *
     * @param answer 本次审批的允许、拒绝或重新检查结果
     * @param reason 当前事件来源或失败的业务原因
     */
    public record Outcome(ApprovalAnswer answer, String reason) {
    }

    private final ModelApprovalReviewer reviewer;

    /**
     * 绑定辅助审批模型，供 Approve for Me 档位审查普通询问；需要人工判断的操作沿人工通道处理。
     *
     * @param reviewer 辅助审批模型入口，用于普通操作的自动审查
     */
    public ApprovalCoordinator(ModelApprovalReviewer reviewer) {
        this.reviewer = reviewer;
    }

    /**
     * 先核对请求权限版本和档位审批策略。普通询问在 Approve for Me 下交给辅助模型，明确允许或拒绝直接返回；
     * 需要人工或模型无法确认时转交用户。等待期间订阅权限变化，版本变化返回 RECHECK；
     * 无人工通道或审批异常返回带原因的 DENY。
     *
     * @param request 固定本次工具、输入、真实身份和权限版本的审批请求
     * @param decision 权限门已经作出的询问决定，包含原因及是否要求人工
     * @param state 当前运行组共享的权限档位、规则和版本状态
     * @param human 本次操作可用的人工审批通道；null 表示无法交给用户作答
     * @param goal 本轮真实任务说明，供辅助审批核对操作用途
     * @return 允许、拒绝或 RECHECK 结果，以及相应的业务原因
     */
    public Outcome request(ApprovalRequest request, PermissionDecision decision, PermissionState state, Approval human,
            String goal) {
        var snapshot = state.snapshot();
        if (snapshot.revision() != request.revision())
            return new Outcome(ApprovalAnswer.RECHECK, "");
        if (snapshot.profile().policy().approval() == PermissionPolicy.ApprovalPolicy.NEVER)
            return new Outcome(ApprovalAnswer.DENY,
                    "The current profile does not request approval; this operation still requires confirmation: "
                            + request.reason());
        String handoff = "";
        if (!decision.humanOnly() && snapshot.profile().policy().reviewer() == PermissionPolicy.Reviewer.MODEL
                && reviewer != null) {
            var result = reviewer.review(request, goal);
            if (state.snapshot().revision() != request.revision())
                return new Outcome(ApprovalAnswer.RECHECK, "");
            if (result == ModelApprovalReviewer.Result.ALLOW)
                return new Outcome(ApprovalAnswer.ALLOW_ONCE, "");
            if (result == ModelApprovalReviewer.Result.DENY)
                return new Outcome(ApprovalAnswer.DENY, "Reviewer approval denied this operation: " + request.reason());
            handoff = "Reviewer could not confirm; handing off to the user. ";
        }
        if (human == null)
            return new Outcome(ApprovalAnswer.DENY,
                    handoff + "No human approval channel is available for this task: " + request.reason());
        try (var subscription = state.onChange(() -> human.reconsider(state.snapshot().revision()))) {
            // 先注册，再公布最新版本；防止切换恰好发生在进入等待前，导致旧申请永久挂起。
            human.reconsider(state.snapshot().revision());
            if (state.snapshot().revision() != request.revision())
                return new Outcome(ApprovalAnswer.RECHECK, "");
            var display = new ApprovalRequest(request.id(), request.actor(), request.runId(), request.tool(),
                    request.input(),
                    request.cwd(), handoff + request.reason(), request.revision(), request.suggestion());
            var answer = human.forActor(request.actor()).ask(display);
            return new Outcome(state.snapshot().revision() == request.revision() ? answer : ApprovalAnswer.RECHECK,
                    "Approval did not authorize this operation: " + request.reason());
        } catch (Exception failure) {
            return new Outcome(ApprovalAnswer.DENY,
                    "Approval did not finish; the operation was not executed: " + request.reason());
        }
    }
}
