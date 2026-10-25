package com.jadecode.goal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import com.jadecode.messages.Message;

/**
 * 会话级完成闸门，不拥有主循环、线程、权限或新的队列。
 * Session 在自己的历史锁内调用状态变更；evaluate 只做耗时判断，锁外运行。
 * 不可变 Ticket 防止清除/替换后采用迟到结果；先成功保存，再发布内存状态。
 */
public final class GoalController {
    public record Limits(int workerRequests, int consecutiveBlocks) {
        public Limits { if (workerRequests < 1 || consecutiveBlocks < 1) throw new IllegalArgumentException("Invalid goal limits"); }
        public static Limits defaults() { return new Limits(100, 8); }
    }
    /** state 必须仍是当前对象才可采用结果；相同条件重新注册也产生新身份。 */
    public record Ticket(GoalState state) { }
    public record Decision(String action, String reason) { }
    private final GoalEvaluator evaluator;
    private final Consumer<GoalState> save;
    private final Limits limits;
    private volatile GoalState state;

    /** save 只保存元数据；异常阻止发布，不在失败后继续发模型请求。构造本身无 IO。 */
    public GoalController(GoalEvaluator evaluator, Consumer<GoalState> save, Limits limits) {
        this.evaluator = java.util.Objects.requireNonNull(evaluator);
        this.save = java.util.Objects.requireNonNull(save); this.limits = java.util.Objects.requireNonNull(limits);
    }
    /** 界面可读的不可变快照；null 表示从未设置目标。 */
    public GoalState state() { return state; }
    public Limits limits() { return limits; }

    /** 创建或替换目标，立即保存新身份；宿主随后以原用户权限启动工作。 */
    public void set(String condition) {
        publish(new GoalState(UUID.randomUUID().toString(), condition, GoalState.Status.ACTIVE, 0, Instant.now().toString(), "goal set"));
    }
    /** 清除只停自动目标判断，不撤销已有工具；无目标或终态时幂等返回。 */
    public void clear() {
        if (state != null && state.active()) publish(new GoalState(state.id(), state.condition(), GoalState.Status.CLEARED,
                state.evaluations(), state.startedAt(), "cleared by user"));
    }
    /** 恢复只采用保存状态，不请求模型；活跃目标重新计数，必须等宿主收到新用户输入。 */
    public void restore(GoalState value) {
        state = value != null && value.active() ? new GoalState(value.id(), value.condition(), value.status(), 0,
                Instant.now().toString(), "restored; awaiting user input") : value;
    }
    /** 捕获一次只读判断身份；没有活跃目标时返回 null，沿原结束路径放行。 */
    public Ticket ticket() { GoalState current = state; return current != null && current.active() ? new Ticket(current) : null; }
    /** 网络调用不持有宿主锁；返回之后必须经 complete 核对身份和关闭状态。 */
    public GoalEvaluator.Evaluation evaluate(Ticket ticket, List<Message> history) throws Exception {
        return evaluator.evaluate(ticket.state().condition(), List.copyOf(history));
    }
    /**
     * 采用判断。未满足时累计一次并由 Session 决定是否继续；超过上限仍保持 ACTIVE。
     * blocks 是当前大轮次的连续阻止数，不能把历史总判断次数当成这次自动执行的额度。
     */
    public Decision complete(Ticket ticket, GoalEvaluator.Evaluation value, int blocks) {
        if (ticket == null || state != ticket.state()) return new Decision("stale", "goal changed during evaluation");
        GoalState.Status status = value.ok() ? GoalState.Status.ACHIEVED : value.impossible() ? GoalState.Status.IMPOSSIBLE : GoalState.Status.ACTIVE;
        publish(new GoalState(state.id(), state.condition(), status, state.evaluations() + 1, state.startedAt(), value.reason()));
        return new Decision(value.ok() ? "achieved" : value.impossible() ? "impossible"
                : blocks >= limits.consecutiveBlocks() ? "limit" : "block", value.reason());
    }
    /** 额度/判断故障保存未完成原因；迟到故障同样不能复活已清除/替换的目标。 */
    public boolean suspend(Ticket ticket, String reason) {
        if (ticket == null || state != ticket.state()) return false;
        publish(new GoalState(state.id(), state.condition(), state.status(), state.evaluations(), state.startedAt(), reason));
        return true;
    }
    /** @return 本地查看文本，不发请求；明确区分状态，不能用最近主模型回答代替完成判断。 */
    public String describe() {
        GoalState current = state;
        if (current == null) return "No goal set";
        return "Goal " + current.status() + ": " + current.condition() + "\nEvaluations: " + current.evaluations()
                + "\nStarted: " + current.startedAt() + "\nReason: " + current.reason();
    }
    /** 保存失败不能覆盖内存旧状态；调用者负责按现有持久化失败路径停止会话。 */
    private void publish(GoalState next) { save.accept(next); state = next; }
}
