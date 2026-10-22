package com.jadecode.cli.tui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.jadecode.cli.tui.view.ChatViewport;
import com.jadecode.cli.tui.view.ConversationView;
import com.jadecode.tui.components.Editor;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.PresentedFrame;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.ThemeManager;

/** R2-04：一次拖选至多一次自动复制、重复释放不复制、flash 只属于 UI。 */
class TuiCopyFlashTest {
    private final AtomicInteger copies = new AtomicInteger();

    private TuiController controller(TuiState state, boolean copyOnSelect) {
        var themes = new ThemeManager("dark", null, true, "light", "dark");
        var chat = new ChatViewport(new ConversationView(), ChatViewport.Scrollbar.AUTO, copyOnSelect);
        return new TuiController(state, null, null, chat, new Editor(new KeyBindings(java.util.Map.of()), null),
                null, new KeyBindings(java.util.Map.of()), null, null, themes, event -> { }, () -> { },
                new TuiController.TerminalActions() {
                    @Override public void copy(String text, boolean explicit) { copies.incrementAndGet(); }
                });
    }

    private static InputEvent mouse(InputEvent.Mouse.Action action, int x, int y, long seq) {
        return InputEvent.mouse(new InputEvent.Mouse(0, action, x, y, false, false, false), seq, 1);
    }

    private TuiController prepared(TuiState state, boolean copyOnSelect) {
        var controller = controller(state, copyOnSelect);
        var frame = new Frame(1, 1, 1, List.of(StyledLine.of("hello world", Style.PLAIN)),
                Frame.NO_CURSOR, List.of());
        controller.presented(new PresentedFrame(1, 1, 80, 24, 1, List.of()), frame);
        controller.layout(80, 24, 0, 10, 10, true);
        return controller;
    }

    @Test void oneDragCopiesOnceAndRepeatedReleaseDoesNotCopyAgain() {
        var state = new TuiState();
        state.installGeneration(0);
        var controller = prepared(state, true);
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.PRESS, 0, 0, 10)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.DRAG, 4, 0, 11)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.RELEASE, 4, 0, 12)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.RELEASE, 4, 0, 13)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.RELEASE, 4, 0, 14)));
        assertEquals(1, copies.get(), "repeated releases must not copy again");
        assertTrue(controller.selectionActive(), "selection stays visible after copy");
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.PRESS, 0, 0, 20)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.DRAG, 5, 0, 21)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.RELEASE, 5, 0, 22)));
        assertEquals(2, copies.get(), "a second real drag copies again");
    }

    @Test void copyOnSelectDisabledSelectsButDoesNotCopy() {
        var state = new TuiState();
        state.installGeneration(0);
        var controller = prepared(state, false);
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.PRESS, 0, 0, 10)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.DRAG, 4, 0, 11)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.RELEASE, 4, 0, 12)));
        assertEquals(0, copies.get());
        assertTrue(controller.selectionActive());
    }

    @Test void flashIsUiOnlyAndExpiresWithoutInput() throws Exception {
        var state = new TuiState();
        state.installGeneration(0);
        var controller = prepared(state, true);
        controller.accept(new UiEvent.Flash("Copied!", 100, false));
        assertTrue(state.flashActive());
        assertEquals("Copied!", state.flashText());
        assertEquals(0, state.transcript.entries().size(), "flash must not create transcript entries");
        Thread.sleep(150);
        assertFalse(state.flashActive());
        assertTrue(state.clearExpiredFlash());
        assertEquals("", state.flashText());
        controller.accept(new UiEvent.Flash("Copy failed: x", 5000, true));
        assertTrue(state.flashActive() && state.flashError());
        state.clearFlash();
        assertFalse(state.flashActive());
    }
}
