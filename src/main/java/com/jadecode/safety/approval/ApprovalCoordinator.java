package com.jadecode.safety.approval;

import com.jadecode.safety.*;

/** 处理已经确定需要审批的请求，不解释 shell。来源资格由 Gate 判断，这里不依赖执行上下文。 */
public final class ApprovalCoordinator {
    /** 答复与拒绝原因一起返回，避免把“无人可审批”说成已由用户拒绝。 */
    public record Outcome(ApprovalAnswer answer, String reason) { }
    private final ModelApprovalReviewer reviewer;
    public ApprovalCoordinator(ModelApprovalReviewer reviewer) { this.reviewer = reviewer; }
    /**
     * never 直接拒绝。普通询问可交辅助模型；强制人工、不确定或审查失败则使用可用人工通道。
     * 配置变化只要求 Gate 重判，不把变化本身解释为用户同意。
     */
    public Outcome request(ApprovalRequest request, PermissionDecision decision, PermissionState state, Approval human, String goal) {
        var snapshot = state.snapshot();
        if (snapshot.revision() != request.revision()) return new Outcome(ApprovalAnswer.RECHECK, "");
        if (snapshot.profile().policy().approval() == PermissionPolicy.ApprovalPolicy.NEVER)
            return new Outcome(ApprovalAnswer.DENY, "The current profile does not request approval; this operation still requires confirmation: " + request.reason());
        String handoff = "";
        if (!decision.humanOnly() && snapshot.profile().policy().reviewer() == PermissionPolicy.Reviewer.MODEL && reviewer != null) {
            var result = reviewer.review(request, goal);
            if (state.snapshot().revision() != request.revision()) return new Outcome(ApprovalAnswer.RECHECK, "");
            if (result == ModelApprovalReviewer.Result.ALLOW) return new Outcome(ApprovalAnswer.ALLOW_ONCE, "");
            if (result == ModelApprovalReviewer.Result.DENY) return new Outcome(ApprovalAnswer.DENY, "Reviewer approval denied this operation: " + request.reason());
            handoff = "Reviewer could not confirm; handing off to the user. ";
        }
        if (human == null) return new Outcome(ApprovalAnswer.DENY, handoff + "No human approval channel is available for this task: " + request.reason());
        try (var subscription = state.onChange(() -> human.reconsider(state.snapshot().revision()))) {
            // 先注册，再公布最新版本；防止切换恰好发生在进入等待前，导致旧申请永久挂起。
            human.reconsider(state.snapshot().revision());
            if (state.snapshot().revision() != request.revision()) return new Outcome(ApprovalAnswer.RECHECK, "");
            var display = new ApprovalRequest(request.id(), request.actor(), request.runId(), request.tool(), request.input(),
                    request.cwd(), handoff + request.reason(), request.revision(), request.suggestion());
            var answer = human.forActor(request.actor()).ask(display);
            return new Outcome(state.snapshot().revision() == request.revision() ? answer : ApprovalAnswer.RECHECK,
                    "Approval did not authorize this operation: " + request.reason());
        } catch (Exception failure) { return new Outcome(ApprovalAnswer.DENY, "Approval did not finish; the operation was not executed: " + request.reason()); }
    }
}
