package com.jadecode.session;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import com.jadecode.execution.ExecutionContext;

/** 直接验证 Inbox 投递边界，避免用线程调度概率代替筛选契约。 */
class SessionTeamDeliveryTest {
    /** 普通请求排满仍能交付报告；来源 run 不影响插入，重复投递不增加队列项。 */
    @Test
    void reportUsesReservedSlotAndCanBeAdoptedByAnyTurn() {
        var inbox = new SessionInbox();
        var context = ExecutionContext.create(ExecutionContext.Source.USER, null);
        for (int i = 0; i < 64; i++)
            assertTrue(inbox.offer(new SessionInbox.Entry(
                    new SessionEvent.Request("next task " + i, context), new CompletableFuture<>())));
        assertFalse(inbox.offer(
                new SessionInbox.Entry(new SessionEvent.Request("overflow", context), new CompletableFuture<>())));
        var incoming = notice("report", "result", true);
        var report = new SessionInbox.Entry(incoming, null);
        assertTrue(inbox.offer(report));
        assertTrue(inbox.offer(report));
        assertEquals(List.of(report), inbox.drainInsertions());
        assertEquals("next task 0", inbox.pollRequest().request().prompt());
        assertTrue(inbox.drainInsertions().isEmpty());
    }

    /** 关闭拒绝尚未采用的消息，并结束用户补充的回执，不能把排队成功冒充完成。 */
    @Test
    void closeRejectsUnconsumedReport() {
        var inbox = new SessionInbox();
        var future = new CompletableFuture<String>();
        var report = new SessionInbox.Entry(notice("report", "result", true), future);
        assertTrue(inbox.offer(report));
        inbox.close();
        assertTrue(future.isCompletedExceptionally());
        assertFalse(inbox.offer(report));
    }

    /** 插入项可越过新工作请求，但未选中项顺序保持不变；空闲只领取显式请求。 */
    @Test
    void insertionDrainPreservesQueuedWorkOrder() {
        var inbox = new SessionInbox();
        var context = ExecutionContext.create(ExecutionContext.Source.USER, null);
        var first = new SessionInbox.Entry(new SessionEvent.Request("first", context), new CompletableFuture<>());
        var last = new SessionInbox.Entry(new SessionEvent.Request("last", context), new CompletableFuture<>());
        var result = new SessionInbox.Entry(notice("bg", "background", true), null);
        var question = new SessionInbox.Entry(notice("question", "question", true), null);
        assertTrue(inbox.offer(first));
        assertTrue(inbox.offer(result));
        assertTrue(inbox.offer(last));
        assertTrue(inbox.offer(question));
        assertEquals(List.of(result, question), inbox.drainInsertions());
        assertSame(first, inbox.pollRequest());
        assertSame(last, inbox.pollRequest());
        assertTrue(inbox.offer(question));
        assertNull(inbox.pollRequest(), "通知不能自动开启工作");
    }

    /** 空回车升级原批次；后续追加保持插入属性，已采用缓冲不能复活或重复执行。 */
    @Test
    void promotedBufferRetainsFutureAndCannotBeConsumedTwice() {
        var inbox = new SessionInbox();
        var context = ExecutionContext.create(ExecutionContext.Source.USER, null);
        var result = inbox.buffer("first", context);
        assertTrue(inbox.drainInsertions().isEmpty());
        assertTrue(inbox.promote());
        assertSame(result, inbox.buffer("second", context));
        var selected = inbox.drainInsertions();
        assertEquals(1, selected.size());
        assertSame(result, selected.getFirst().result());
        assertEquals("first\n\nsecond", ((SessionEvent.Incoming) selected.getFirst().event()).summary());
        assertNull(inbox.bufferedView());
        assertFalse(inbox.promote());
        assertNull(inbox.pollRequest());
    }

    /** 测试通知由 Harness 元数据组成；正文不用于推断投递策略。 */
    private static SessionEvent.Incoming notice(String id, String kind, boolean insertion) {
        return new SessionEvent.Incoming(id, "agent", "leader", "old_run", "assignment", kind, "Evidence", null,
                insertion);
    }
}
