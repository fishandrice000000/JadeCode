package com.jadecode.session;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import com.jadecode.execution.ExecutionContext;

/** 必等 Team 结果和普通队列共存的边界；直接检查 Inbox，不靠线程调度概率。 */
class SessionTeamDeliveryTest {
    /** 满普通队列不挤掉本轮报告，另一个轮次不能拿走它，前面的新任务也不能阻塞它。 */
    @Test void requiredReportHasReservedDeliveryAndStaysWithOriginalTurn() {
        var inbox = new SessionInbox();
        var context = new ExecutionContext("run_owner", ExecutionContext.Source.USER, null);
        for (int i = 0; i < 64; i++) assertTrue(inbox.offer(new SessionInbox.Entry(
                new SessionEvent.Request("next task " + i, context, null), new CompletableFuture<>())));
        assertFalse(inbox.offer(new SessionInbox.Entry(new SessionEvent.Request("overflow", context, null), new CompletableFuture<>())));
        var incoming = new SessionEvent.Incoming("report", "agent", "leader", "task", "assignment", "result", "Evidence", null, false, true);
        var report = new SessionInbox.Entry(new SessionEvent.Request(incoming.render(), context, null, incoming),
                new CompletableFuture<>(), null, new SessionInbox.TeamReceipt("assignment", "run_owner", true));
        assertTrue(inbox.offerTeam(report)); assertTrue(inbox.offerTeam(report));
        assertTrue(inbox.drain(true, true, "run_other").isEmpty());
        assertEquals(java.util.List.of(report), inbox.drain(true, true, "run_owner"));
        assertEquals("next task 0", inbox.pollIdle().request().prompt());
        assertTrue(inbox.drain(true, true, "run_owner").isEmpty(), "重试不能重复放入报告");
    }

    /** 退出时必要报告的 future 同样必须失败，不能把入队成功当成已采用。 */
    @Test void closeRejectsUnconsumedReport() {
        var inbox = new SessionInbox(); var context = new ExecutionContext("run_owner", ExecutionContext.Source.USER, null);
        var incoming = new SessionEvent.Incoming("report", "agent", "leader", "task", "assignment", "result", "Evidence", null, true, true);
        var future = new CompletableFuture<String>();
        var report = new SessionInbox.Entry(new SessionEvent.Request(incoming.render(), context, null, incoming), future,
                null, new SessionInbox.TeamReceipt("assignment", "run_owner", true));
        assertTrue(inbox.offerTeam(report)); inbox.close(); assertTrue(future.isCompletedExceptionally());
        assertFalse(inbox.offerTeam(report));
    }
}
