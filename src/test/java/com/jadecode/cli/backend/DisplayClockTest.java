package com.jadecode.cli.backend;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** 直接采用领域事件的相对采样，不用 sleep 或机器速度猜测显示时间。 */
class DisplayClockTest {
    /** 单个队友退出不能清掉 Leader 的实时计时；整体会话退出才清理所有活动轮次。 */
    @org.junit.jupiter.api.Test void closingOneTeammatePreservesLeaderClock() throws Exception {
        var clock = new DisplayClock((actor, tick) -> { });
        clock.started("leader", "run_leader"); clock.started("agent_1", "run_member");
        var observer = new BackendObserver(null, clock, null);
        var track = BackendObserver.class.getDeclaredMethod("trackState", com.jadecode.execution.ExecutionNotice.class);
        track.setAccessible(true);
        track.invoke(observer, com.jadecode.execution.ExecutionNotice.event(
                com.jadecode.execution.ExecutionNotice.Kind.SESSION, "", "", "", "CLOSED").actor("agent_1"));
        assertTrue(clock.tick("leader").running()); assertFalse(clock.tick("agent_1").running());
        track.invoke(observer, com.jadecode.execution.ExecutionNotice.event(
                com.jadecode.execution.ExecutionNotice.Kind.SESSION, "", "", "", "CLOSED").actor("leader"));
        assertFalse(clock.tick("leader").running());
    }

    @Test void sampledPauseAndDisplayStopSurviveOverlappingApprovalAndLaterMemoryCompletion() {
        DisplayClock clock = new DisplayClock((actor, tick) -> { });
        clock.started("leader", "turn");
        clock.paused("leader", true, 29_000_000_000L);
        assertEquals(29_000, clock.tick("leader").elapsedMs());
        clock.paused("leader", true, 29_000_000_000L);
        clock.paused("leader", false, 29_000_000_000L);
        assertTrue(clock.tick("leader").paused());
        clock.displayStopped("leader", true, 29_000_000_000L);
        assertTrue(clock.tick("leader").running(), "显示截止不能冒充业务已结束");
        clock.paused("leader", false, 50_000_000_000L);
        assertEquals(29_000, clock.tick("leader").elapsedMs());
        clock.ended("leader", "turn", 99_000_000_000L);
        assertEquals(29_000, clock.tick("leader").elapsedMs());
        assertFalse(clock.tick("leader").running());
    }
}
