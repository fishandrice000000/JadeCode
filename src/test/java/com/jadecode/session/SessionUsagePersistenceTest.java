package com.jadecode.session;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.jadecode.config.WorkspacePaths;
import com.jadecode.llm.ModelResponse;
import com.jadecode.llm.UsageRecord;
import com.jadecode.messages.Message;
import com.jadecode.session.history.JournalEntry;
import com.jadecode.session.history.SessionStore;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实文件通道验证取消后的用量保存，不能用无日志的账本掩盖 NIO 中断语义。 */
@Timeout(10)
class SessionUsagePersistenceTest {
    /** 预算只阻止后续请求，不能拒绝保存已经发生的在途用量。 */
    @Test void reportedBudgetStopsNewRequestsButKeepsLateUsage() {
        var ledger=new SessionUsage(ignored->{});
        ledger.limitAdditionalTokens(5);
        ledger.record(new UsageRecord("first","fixture","leader",UsageRecord.Purpose.FORMAL,new ModelResponse.Usage(3,3)));
        assertThrows(IllegalStateException.class,ledger::ensureRequestAllowed);
        ledger.record(new UsageRecord("late","fixture","teammate",UsageRecord.Purpose.MEMORY,new ModelResponse.Usage(2,2)));
        assertEquals(5,ledger.snapshot().total().inputTokens());
        assertEquals(5,ledger.snapshot().total().outputTokens());
        ledger.limitAdditionalTokens(5);assertDoesNotThrow(ledger::ensureRequestAllowed);
    }
    @TempDir Path workspace;

    /** 已中断的辅助请求仍有费用事实；保存后应保留中断标志，并允许主会话继续写日志。 */
    @Test void interruptedUsageWriterDoesNotCloseSharedJournal() throws Exception {
        var store = new SessionStore(com.jadecode.support.TestPaths.workspace(workspace), ignored -> { });
        String id;
        try (var opened = store.create("offline")) {
            id = opened.journal().metadata().id();
            var ledger = new SessionUsage(ignored -> { }); ledger.restore(opened.journal(), opened.restored().entries());
            var error = new AtomicReference<Throwable>(); var retained = new AtomicBoolean();
            Thread worker = Thread.ofVirtual().start(() -> {
                Thread.currentThread().interrupt();
                try { ledger.record(new UsageRecord("interrupted-call", "offline", "leader", UsageRecord.Purpose.WORKFLOW, null)); }
                catch (Throwable failure) { error.set(failure); }
                finally { retained.set(Thread.currentThread().isInterrupted()); }
            });
            worker.join(3000); assertFalse(worker.isAlive());
            if (error.get() != null) throw new AssertionError("Interrupted worker corrupted session usage", error.get());
            assertTrue(retained.get(), "Persistence must preserve cancellation for the caller's next boundary");
            assertFalse(ledger.failed()); assertEquals(1, ledger.snapshot().total().missingRequests());
            opened.journal().append(JournalEntry.Kind.APPEND, List.of(Message.userText("continue")), Json.mapper().createArrayNode(), "request");
            ledger.record(new UsageRecord("next-call", "offline", "leader", UsageRecord.Purpose.FORMAL, new ModelResponse.Usage(2, 3)));
        }
        try (var restored = store.open(id)) {
            assertEquals("continue", restored.restored().history().getFirst().extractText());
            assertEquals(2, restored.restored().entries().stream().filter(e -> e.kind() == JournalEntry.Kind.USAGE).count());
        }
    }

    /** 取消隔离不能吞掉真实不可写错误：日志已关闭时仍锁定账本，不发布虚假累计。 */
    @Test void unavailableJournalStillStopsNewRequests() throws Exception {
        var store = new SessionStore(com.jadecode.support.TestPaths.workspace(workspace), ignored -> { });
        try (var opened = store.create("offline")) {
            var ledger = new SessionUsage(ignored -> { }); ledger.restore(opened.journal(), opened.restored().entries());
            opened.journal().close();
            assertThrows(IllegalStateException.class, () -> ledger.record(new UsageRecord("unwritable", "offline", "leader", UsageRecord.Purpose.WORKFLOW, null)));
            assertTrue(ledger.failed()); assertThrows(IllegalStateException.class, ledger::ensureOpen);
            assertEquals(0, ledger.snapshot().total().missingRequests());
        }
    }
}
