package com.jadecode.execution;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import com.jadecode.safety.approval.Approval;
import com.jadecode.safety.approval.ApprovalAnswer;
import com.jadecode.safety.approval.ApprovalRequest;

/**
 * 一项大轮次的单调计时器：统计工作时间，扣掉等待用户审批的时间。
 *
 * <p>SessionRuntime 取出请求时创建，TUI 只读取它发出的时间快照。等待辅助模型审批、
 * 模型生成或后台命令仍属于工作时间；只有真正调用人工审批函数时暂停。
 * 并行子代理可能同时询问，使用等待数量统计重叠区间，只扣一次，不把时间扣成负数。
 * 本类不持久化、不审批操作，也不修改模型消息。</p>
 */
public final class TurnTimer {
    private final LongSupplier clock;
    private final Consumer<ExecutionNotice> notices;
    private final String runId;
    private final long started;
    private long pausedAt, excluded;
    private int waiting;

    /** 生产使用同一 JVM 的 nanoTime；构造只记录开始时刻，不发通知或启动线程。 */
    public TurnTimer(String runId, Consumer<ExecutionNotice> notices) { this(runId, notices, System::nanoTime); }

    /** 注入单调时钟以便离线检查重叠等待，不需要真实 sleep；三个依赖都不可为空。 */
    public TurnTimer(String runId, Consumer<ExecutionNotice> notices, LongSupplier clock) {
        this.runId = java.util.Objects.requireNonNull(runId);
        this.notices = java.util.Objects.requireNonNull(notices);
        this.clock = java.util.Objects.requireNonNull(clock);
        started = clock.getAsLong();
    }

    public long startedNanos() { return started; }

    /** 返回截至此刻的工作时长；暂停中使用暂停起点，不让界面数字继续增长。 */
    public synchronized long elapsedNanos() { return elapsed(clock.getAsLong()); }

    private long elapsed(long now) { return Math.max(0, (waiting > 0 ? pausedAt : now) - started - excluded); }

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
        if (waiting++ == 0) {
            pausedAt = clock.getAsLong();
            notices.accept(ExecutionNotice.event(ExecutionNotice.Kind.TURN_PAUSE, runId, "", "", "APPROVAL")
                    .timed(elapsed(pausedAt)).started(pausedAt));
        }
    }

    private synchronized void resume() {
        if (--waiting == 0) {
            long now = clock.getAsLong(); excluded += Math.max(0, now - pausedAt);
            notices.accept(ExecutionNotice.event(ExecutionNotice.Kind.TURN_RESUME, runId, "", "", "RUNNING")
                    .timed(elapsed(now)).started(now));
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
