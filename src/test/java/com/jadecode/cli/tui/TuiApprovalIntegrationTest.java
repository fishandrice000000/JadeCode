package com.jadecode.cli.tui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import com.jadecode.cli.tui.approval.ApprovalViewState;
import com.jadecode.cli.tui.bridge.ApprovalBroker;
import com.jadecode.safety.approval.ApprovalAnswer;
import com.jadecode.safety.approval.ApprovalRequest;
import com.jadecode.tui.core.ActionToken;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.PresentedFrame;
import com.jadecode.tui.geometry.Rect;
import com.jadecode.tui.text.StyledLine;

/** Broker 与 UI 证据链集成：未来完成只由当前队首+已呈现按钮+水位触发。 */
class TuiApprovalIntegrationTest {
    private static ApprovalRequest request() {
        var input = com.jadecode.util.Json.mapper().createObjectNode().put("command", "rm -rf /tmp/x");
        return new ApprovalRequest("approval-1", "leader", "turn-1", "bash", input,
                java.nio.file.Path.of("/tmp"), "delete requires approval [builtin.delete]", 3L, null);
    }

    @Test void brokerFutureCompletesOnlyAfterExplicitUiAnswer() throws Exception {
        var events = new LinkedBlockingQueue<UiEvent>();
        var broker = new ApprovalBroker(events::offer);
        var future = CompletableFuture.supplyAsync(() -> broker.ask(request()));
        UiEvent.ApprovalRequested requested = (UiEvent.ApprovalRequested) events.poll(2, TimeUnit.SECONDS);
        assertNotNull(requested);
        assertEquals(3L, requested.revision());
        assertFalse(future.isDone());
        var state = new ApprovalViewState();
        state.show(requested, 0, 7);
        var token = new ActionToken(ActionToken.Kind.APPROVE, requested.id(), requested.revision());
        var raw = new Frame(1, 7, 4, List.of(StyledLine.empty()), Frame.NO_CURSOR,
                List.of(new Frame.Hit(new Rect(0, 0, 10, 1), token)));
        var presented = new PresentedFrame(1, 7, 80, 24, 4, List.of(token));
        // 未呈现前不能批准。
        assertFalse(state.canAnswer(9, 7, presented, ActionToken.Kind.APPROVE));
        state.presented(presented, 7, 8);
        assertTrue(state.canAnswer(9, 7, presented, ActionToken.Kind.APPROVE));
        broker.answer(requested.id(), ApprovalAnswer.ALLOW_ONCE);
        assertEquals(ApprovalAnswer.ALLOW_ONCE, future.get(2, TimeUnit.SECONDS));
        assertNotNull(events.poll(2, TimeUnit.SECONDS)); // ApprovalRemoved
        broker.close();
    }

    @Test void closeDeniesPendingApprovalAndWatermarkBlocksOldEnter() throws Exception {
        var events = new LinkedBlockingQueue<UiEvent>();
        var broker = new ApprovalBroker(events::offer);
        var future = CompletableFuture.supplyAsync(() -> broker.ask(request()));
        var requested = (UiEvent.ApprovalRequested) events.poll(2, TimeUnit.SECONDS);
        var state = new ApprovalViewState();
        state.show(requested, 0, 1);
        var token = new ActionToken(ActionToken.Kind.DENY, requested.id(), requested.revision());
        var presented = new PresentedFrame(2, 1, 80, 24, 1, List.of(token));
        state.presented(presented, 1, 10);
        // 水位之前的排队 Enter 即使有已呈现按钮也不能答复。
        assertFalse(state.canAnswer(10, 1, presented, ActionToken.Kind.DENY));
        broker.close();
        assertEquals(ApprovalAnswer.DENY, future.get(2, TimeUnit.SECONDS));
    }
}
