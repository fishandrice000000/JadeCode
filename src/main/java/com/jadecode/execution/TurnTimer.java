package com.jadecode.execution;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import com.jadecode.safety.approval.Approval;
import com.jadecode.safety.approval.ApprovalAnswer;
import com.jadecode.safety.approval.ApprovalRequest;

/**
 * 一项大轮次的单调计时器：统计工作时间，扣掉等待用户审批的时间。
 *
 * <p>SessionRuntime 取出请求时创建，TUI 只读取它发出的时间快照。模型、工具和必等后台
 * 属于工作时间，人工审批暂停。无工具回答先冻结显示；若 Stop/后台要求继续就恢复，
 * 若进入末尾 Memory 维护则从回答截止点停止累计，但业务本身仍保持忙碌。
 * 并行子代理可能同时询问，使用等待数量统计重叠区间，只扣一次，不把时间扣成负数。
 * 本类不持久化、不审批操作，也不修改模型消息。</p>
 */
public final class TurnTimer {
    private final LongSupplier clock;
    private final Consumer<ExecutionNotice> notices;
    private final String runId;
    private final long started;
    private long sampledAt, work;
    /** 候选回答的显示截止；null 表示仍插值计时，与审批等待分别管理。 */
    private Long answerElapsed;
    private boolean maintenance;
    private int waiting;

    /** 生产使用同一 JVM 的 nanoTime；构造只记录开始时刻，不发通知或启动线程。 */
    public TurnTimer(String runId, Consumer<ExecutionNotice> notices) { this(runId, notices, System::nanoTime); }

    /** 注入单调时钟以便离线检查重叠等待，不需要真实 sleep；三个依赖都不可为空。 */
    public TurnTimer(String runId, Consumer<ExecutionNotice> notices, LongSupplier clock) {
        this.runId = java.util.Objects.requireNonNull(runId);
        this.notices = java.util.Objects.requireNonNull(notices);
        this.clock = java.util.Objects.requireNonNull(clock);
        started = clock.getAsLong();
        sampledAt = started;
    }

    public long startedNanos() { return started; }

    /** 返回截至此刻的工作时长；暂停中使用暂停起点，不让界面数字继续增长。 */
    public synchronized long elapsedNanos() {
        return answerElapsed != null ? answerElapsed : workAt(clock.getAsLong());
    }

    /** 不修改状态的采样；等待与维护重叠也只排除一次。 */
    private long workAt(long now) {
        return work + (waiting > 0 || maintenance ? 0 : Math.max(0, now - sampledAt));
    }

    /** 在改变暂停原因前结算上一段时间，避免用单一 paused 标志互相覆盖。 */
    private void advance(long now) { work = workAt(now); sampledAt = now; }

    /**
     * 记录无工具回答输出完成的候选边界；只停止显示，不完成业务任务。
     * @param completedNanos 生产端单调时间；旧客户端缺值时取当前时刻，绝对值允许为负
     */
    public synchronized void answerCompleted(Long completedNanos) {
        long now = clock.getAsLong();
        long cutoff = completedNanos == null ? now : Math.max(sampledAt, Math.min(now, completedNanos));
        advance(cutoff);
        answerElapsed = work;
        // 暂时继续记内部工作账：Stop Continue/必等后台若要求继续，判断耗时仍应计入。
        advance(now);
        notices.accept(ExecutionNotice.event(ExecutionNotice.Kind.TURN_DISPLAY_STOP, runId, "", "", "ANSWER")
                .timed(answerElapsed).started(cutoff));
    }

    /**
     * 确认进入回答后的 Memory/收尾；显示保留回答截止，维护不再计入工作量。
     * 只能在已有回答候选时调用，不改变 runtime 状态、future 或 Memory 执行策略。
     */
    public synchronized void beginMaintenance() {
        if (answerElapsed == null) return;
        advance(clock.getAsLong());
        work = answerElapsed;
        maintenance = true;
    }

    /**
     * 同一轮确实有后续工作时恢复；维护期间的补充沿原轮次继续，保留此前工作量。
     * 普通候选被 Stop/后台撤回时没有进入 maintenance，判断/等待时间仍在工作账中。
     */
    public synchronized void resumeDisplay() {
        if (answerElapsed == null) return;
        long now = clock.getAsLong();
        advance(now);
        maintenance = false;
        answerElapsed = null;
        notices.accept(ExecutionNotice.event(ExecutionNotice.Kind.TURN_DISPLAY_RESUME, runId, "", "", "RUNNING")
                .timed(work).started(now));
    }

    /**
     * 给原有人工审批函数加计时，不改变批准结果、规则重判或退出语义。
     * 新常驻会话如果收到父会话的包装，先解开旧计时层，再绑定自己的计时器；
     * 否则队友等待用户会把仍在做其它工作的 Leader 也错误暂停。
     * 一次性子代理不创建 Session，沿用父轮次的包装即可。
     */
    public Approval track(Approval approval) {
        while (approval instanceof TimedApproval timed) approval = timed.delegate;
        return approval == null ? null : new TimedApproval(approval);
    }

    /** 仅首次等待和最后一次答复改变显示状态；通知带实际时刻，不用 UI 收到事件的时间猜测。 */
    private synchronized void pause() {
        long now = clock.getAsLong();
        advance(now);
        if (waiting++ == 0) {
            notices.accept(ExecutionNotice.event(ExecutionNotice.Kind.TURN_PAUSE, runId, "", "", "APPROVAL")
                    .timed(elapsedNanos()).started(now));
        }
    }

    private synchronized void resume() {
        long now = clock.getAsLong();
        advance(now);
        if (--waiting == 0) {
            notices.accept(ExecutionNotice.event(ExecutionNotice.Kind.TURN_RESUME, runId, "", "", "RUNNING")
                    .timed(elapsedNanos()).started(now));
        }
    }

    /** 所有退出路径都在 finally 恢复计时；reconsider 仍直接唤醒底层审批等待。 */
    private final class TimedApproval implements Approval {
        private final Approval delegate;
        TimedApproval(Approval delegate) { this.delegate = delegate; }
        @Override public ApprovalAnswer ask(ApprovalRequest request) {
            pause();
            try { return delegate.ask(request); }
            finally { resume(); }
        }
        @Override public Approval forActor(String actor) { return new TimedApproval(delegate.forActor(actor)); }
        @Override public void reconsider(long revision) { delegate.reconsider(revision); }
    }
}
