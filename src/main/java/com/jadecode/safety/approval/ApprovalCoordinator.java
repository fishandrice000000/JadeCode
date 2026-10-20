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
            return new Outcome(ApprovalAnswer.DENY, "当前档位不发起审批；此操作仍要求确认：" + request.reason());
        String handoff = "";
        if (!decision.humanOnly() && snapshot.profile().policy().reviewer() == PermissionPolicy.Reviewer.MODEL && reviewer != null) {
            var result = reviewer.review(request, goal);
            if (state.snapshot().revision() != request.revision()) return new Outcome(ApprovalAnswer.RECHECK, "");
            if (result == ModelApprovalReviewer.Result.ALLOW) return new Outcome(ApprovalAnswer.ALLOW_ONCE, "");
            if (result == ModelApprovalReviewer.Result.DENY) return new Outcome(ApprovalAnswer.DENY, "辅助审批拒绝本次操作：" + request.reason());
            handoff = "辅助审批未能确认，转交用户。";
        }
        if (human == null) return new Outcome(ApprovalAnswer.DENY, handoff + "当前任务没有人工审批通道：" + request.reason());
        try (var subscription = state.onChange(() -> human.reconsider(state.snapshot().revision()))) {
            // 先注册，再公布最新版本；防止切换恰好发生在进入等待前，导致旧申请永久挂起。
            human.reconsider(state.snapshot().revision());
            if (state.snapshot().revision() != request.revision()) return new Outcome(ApprovalAnswer.RECHECK, "");
            var display = new ApprovalRequest(request.id(), request.actor(), request.runId(), request.tool(), request.input(),
                    request.cwd(), handoff + request.reason(), request.revision(), request.suggestion());
            var answer = human.forActor(request.actor()).ask(display);
            return new Outcome(state.snapshot().revision() == request.revision() ? answer : ApprovalAnswer.RECHECK,
                    "审批未批准本次操作：" + request.reason());
        } catch (Exception failure) { return new Outcome(ApprovalAnswer.DENY, "审批未完成，操作未执行：" + request.reason()); }
    }
}
