package com.jadecode.cli.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.backend.protocol.PayloadTransfer;
import com.jadecode.cli.backend.protocol.ProtocolException;
import com.jadecode.cli.backend.protocol.ProtocolSession;
import com.jadecode.safety.approval.ApprovalAnswer;
import com.jadecode.safety.approval.ApprovalRequest;
import com.jadecode.safety.rules.PermissionRule;
import com.jadecode.util.Json;

/**
 * 审批桥的 Future 结果与新鲜证据契约。
 *
 * <p>覆盖 review-evidence 的反例：Esc 必须能拒当前；旧帧/旧 revision/无建议 similar 都被拒绝；
 * 重复答复不会作用到下一条申请；关闭时等待中的 Future 得到 DENY。</p>
 */
class BridgeApprovalTest {
    private static ApprovalRequest request(String id, PermissionRule suggestion) {
        ObjectNode input = Json.mapper().createObjectNode();
        input.put("command", "rm -rf build");
        return new ApprovalRequest(id, "leader", "run-1", "bash", input,
                java.nio.file.Path.of("/tmp/ws"), "Needs confirmation", 0L, suggestion);
    }

    private static BridgeApproval bridge() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProtocolSession session = new ProtocolSession(out, "cred", "conn-1", hello -> { throw new AssertionError(); },
                command -> { }, () -> { });
        return new BridgeApproval(new WireContext(session, new PayloadTransfer(session.outbox())));
    }

    private static BridgeApproval.Presentation presentation(String runId, long revision, long watermark) {
        return new BridgeApproval.Presentation("frame-1", 0L, 80, 24,
                List.of(new BridgeApproval.Rect(2, 8, 16, 1, 0), new BridgeApproval.Rect(2, 10, 9, 1, 2)),
                "digest", watermark, 0L, revision, runId);
    }

    private static BridgeApproval.InputEvidence keyboard(String action, long frameWatermark) {
        return new BridgeApproval.InputEvidence("PRESS", 0L, "frame-1", 0L,
                frameWatermark + 1, frameWatermark + 1, 1L, action, null);
    }

    private static ApprovalAnswer askAsync(BridgeApproval approval, ApprovalRequest request,
            AtomicReference<ApprovalAnswer> answer) {
        CompletableFuture.runAsync(() -> answer.set(approval.ask(request)));
        return null;
    }

    @Test
    void freshEscapeDeniesOnlyTheCurrentRequest() throws Exception {
        BridgeApproval approval = bridge();
        AtomicReference<ApprovalAnswer> answer = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> answer.set(approval.ask(request("r1", null))));
        awaitPending(approval, 1);
        approval.presented("r1", presentation("run-1", 0L, 0L));
        approval.answer("r1", 2, keyboard("approval.deny", 0L));
        waiter.join(2000);
        assertEquals(ApprovalAnswer.DENY, answer.get());
    }

    @Test
    void staleFrameOrRevisionCannotAnswer() throws Exception {
        BridgeApproval approval = bridge();
        Thread waiter = Thread.ofVirtual().start(() -> approval.ask(request("r1", null)));
        awaitPending(approval, 1);
        approval.presented("r1", presentation("run-1", 0L, 3L));
        ProtocolException stale = assertThrows(ProtocolException.class,
                () -> approval.answer("r1", 0, keyboard("approval.confirm", 0L)));
        assertEquals("STALE_REVISION", stale.error().name());
        // 旧画面（水位=3）之后的按键才有效，且 release/paste 不是 PRESS。
        ProtocolException notPressed = assertThrows(ProtocolException.class,
                () -> approval.answer("r1", 0, new BridgeApproval.InputEvidence("RELEASE", 0L, "frame-1", 0L,
                        4, 4, 2L, "approval.confirm", null)));
        assertEquals("INVALID_MESSAGE", notPressed.error().name());
        approval.answer("r1", 0, keyboard("approval.confirm", 3L));
        waiter.join(2000);
        assertEquals(0, approval.pendingCount());
    }

    @Test
    void allowSimilarNeedsARealSuggestion() throws Exception {
        BridgeApproval approval = bridge();
        Thread waiter = Thread.ofVirtual().start(() -> approval.ask(request("r1", null)));
        awaitPending(approval, 1);
        approval.presented("r1", presentation("run-1", 0L, 0L));
        ProtocolException rejected = assertThrows(ProtocolException.class,
                () -> approval.answer("r1", 1, keyboard("approval.confirm", 0L)));
        assertEquals("UNSUPPORTED", rejected.error().name());
        approval.close();
        waiter.join(2000);
    }

    @Test
    void closeDeniesAWaitingFutureAndInvalidateRequiresANewFrame() throws Exception {
        BridgeApproval approval = bridge();
        AtomicReference<ApprovalAnswer> answer = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> answer.set(approval.ask(request("r1", null))));
        awaitPending(approval, 1);
        approval.presented("r1", presentation("run-1", 0L, 0L));
        approval.invalidatePresented();
        ProtocolException stale = assertThrows(ProtocolException.class,
                () -> approval.answer("r1", 2, keyboard("approval.deny", 0L)));
        assertEquals("NOT_READY", stale.error().name());
        approval.close();
        waiter.join(2000);
        assertEquals(ApprovalAnswer.DENY, answer.get());
    }

    private static void awaitPending(BridgeApproval approval, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (approval.pendingCount() < expected && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(expected, approval.pendingCount());
    }
}
