package com.jadecode.execution;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import com.jadecode.safety.approval.ApprovalAnswer;

/** 回答边界、继续工作与维护/人工等待的重叠，用可控时钟验证而不依赖真实 sleep。 */
class TurnTimerTest {
    @Test void finalAnswerStaysFrozenDuringMaintenanceAndResumesForLateInput() {
        var time = new AtomicLong(1);
        var notices = new ArrayList<ExecutionNotice>();
        var timer = new TurnTimer("run", notices::add, time::get);
        time.set(5_000_000_001L);
        timer.answerCompleted(time.get());
        time.set(5_100_000_001L); // Stop/结算后接受这个回答作为末尾候选
        timer.beginMaintenance();
        time.set(7_000_000_001L);
        assertEquals(5_000_000_000L, timer.elapsedNanos());
        timer.resumeDisplay(); // Memory 期间忙时插入，仍是同一轮
        time.set(8_000_000_001L);
        assertEquals(6_000_000_000L, timer.elapsedNanos());
        timer.answerCompleted(time.get());
        assertEquals(ExecutionNotice.Kind.TURN_DISPLAY_STOP, notices.getLast().kind());
        assertEquals(6_000_000_000L, notices.getLast().elapsedNanos());
    }

    @Test void nonFinalCandidateIncludesStopContinueWorkAndApprovalStillExcludesWaiting() {
        var time = new AtomicLong(-10_000_000_000L); // nanoTime 的绝对值可以为负
        var timer = new TurnTimer("run", ignored -> { }, time::get);
        time.addAndGet(5_000_000_000L);
        timer.answerCompleted(time.get());
        time.addAndGet(2_000_000_000L);
        timer.resumeDisplay(); // 还没进入末尾维护，Stop 的工作应计入
        assertEquals(7_000_000_000L, timer.elapsedNanos());
        var approval = timer.track(request -> {
            time.addAndGet(3_000_000_000L);
            assertEquals(7_000_000_000L, timer.elapsedNanos());
            return ApprovalAnswer.DENY;
        });
        approval.ask(null);
        time.addAndGet(1_000_000_000L);
        assertEquals(8_000_000_000L, timer.elapsedNanos());
    }

    @Test void approvalCompletionCannotRestartFrozenMaintenanceClock() {
        var time = new AtomicLong(1);
        var timer = new TurnTimer("run", ignored -> { }, time::get);
        time.set(5_000_000_001L);
        timer.answerCompleted(time.get()); timer.beginMaintenance();
        timer.track(request -> { time.set(9_000_000_001L); return ApprovalAnswer.DENY; }).ask(null);
        assertEquals(5_000_000_000L, timer.elapsedNanos());
        timer.resumeDisplay();
        time.set(10_000_000_001L);
        assertEquals(6_000_000_000L, timer.elapsedNanos());
    }
}
