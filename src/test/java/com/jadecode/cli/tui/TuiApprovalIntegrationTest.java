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
    @Test void producerSequenceDoesNotMixChunkCountersOrMaximumResizeSentinels() {
        var events = new java.util.ArrayList<UiEvent>();
        var publisher = new TuiApplication.InputPublisher(events::add);
        publisher.publish(com.jadecode.tui.input.InputEvent.resize(Long.MAX_VALUE, 2));
        publisher.publish(com.jadecode.tui.input.InputEvent.key(com.jadecode.tui.input.KeyChord.of("enter"), 1, 2));
        assertEquals(2, publisher.watermark());
        assertEquals(1, ((UiEvent.Input) events.getFirst()).input().sequence());
        assertEquals(2, ((UiEvent.Input) events.getLast()).input().sequence());
    }

    @Test void resizedRequestRebindsEpochAndProducerWatermarkWithoutAcceptingQueuedKeys() {
        var state = new ApprovalViewState();
        state.show(new UiEvent.ApprovalRequested("r", "leader", "bash", "{}", "", "", 7), 0, 1);
        state.move(1, false);
        var token = new ActionToken(ActionToken.Kind.APPROVE, "r", 7);
        var first = new PresentedFrame(1, 1, 80, 24, 1, List.of(token));
        state.presented(first, 1, 10);
        state.invalidate();
        var next = new PresentedFrame(2, 2, 80, 24, 2, List.of(token));
        state.presented(next, 2, 20);
        assertEquals(0, state.choice(), "same request retains explicit selection");
        assertFalse(state.canAnswer(20, 2, next, ActionToken.Kind.APPROVE));
        assertFalse(state.canAnswer(21, 1, first, ActionToken.Kind.APPROVE));
        assertTrue(state.canAnswer(21, 2, next, ActionToken.Kind.APPROVE));
        state.presented(new PresentedFrame(3, 2, 80, 24, 3, List.of(token)), 2, 30);
        assertTrue(state.canAnswer(22, 2, new PresentedFrame(3, 2, 80, 24, 3, List.of(token)),
                ActionToken.Kind.APPROVE), "ordinary redraw must not starve confirmation");
    }

    @Test void escapeDeniesBrokerFutureOnlyAfterPresentationAndOnlyForPress() throws Exception {
        var events = new LinkedBlockingQueue<UiEvent>();
        var catalog = new CommandCatalog(java.nio.file.Path.of("."));
        try (var broker = new ApprovalBroker(events::offer)) {
            var state = new TuiState();
            var bindings = new com.jadecode.tui.input.KeyBindings(java.util.Map.of());
            var editor = new com.jadecode.tui.components.Editor(bindings, null);
            editor.insert("KEEP", false);
            var controller = new TuiController(state, null, broker, null, editor, null, bindings, catalog, null,
                    new com.jadecode.tui.theme.ThemeManager("dark", null, true, "light", "dark"),
                    events::offer, () -> fail("Esc must not close the whole session"), null);
            var result = CompletableFuture.supplyAsync(() -> broker.ask(request()));
            var requested = (UiEvent.ApprovalRequested) events.poll(2, TimeUnit.SECONDS);
            assertNotNull(requested); controller.accept(requested);
            var token = new ActionToken(ActionToken.Kind.DENY, requested.id(), requested.revision());
            var raw = new Frame(1, 1, 1, List.of(StyledLine.empty()), Frame.NO_CURSOR,
                    List.of(new Frame.Hit(new Rect(0, 0, 8, 1), token)));
            controller.presented(new PresentedFrame(1, 1, 80, 24, 1, List.of(token)), raw, 10, 1);
            controller.accept(new UiEvent.Input(com.jadecode.tui.input.InputEvent.key(
                    com.jadecode.tui.input.KeyChord.of("escape"), 10, 1)));
            assertFalse(result.isDone(), "queued pre-frame Esc cannot deny");
            for (var phase : List.of(com.jadecode.tui.input.KeyChord.Phase.REPEAT, com.jadecode.tui.input.KeyChord.Phase.RELEASE)) {
                var key = new com.jadecode.tui.input.KeyChord("escape", java.util.Set.of(), phase);
                controller.accept(new UiEvent.Input(com.jadecode.tui.input.InputEvent.key(key, 11, 1)));
                assertFalse(result.isDone(), "only a fresh press answers");
            }
            controller.accept(new UiEvent.Input(com.jadecode.tui.input.InputEvent.key(
                    com.jadecode.tui.input.KeyChord.of("escape"), 12, 1)));
            assertEquals(ApprovalAnswer.DENY, result.get(2, TimeUnit.SECONDS));
            assertFalse(state.approval.active()); assertEquals("KEEP", editor.text());
        } finally { catalog.close(); }
    }
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
