package com.jadecode.cli.tui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import com.jadecode.cli.tui.bridge.ApprovalBroker;
import com.jadecode.cli.tui.model.TranscriptEntry;
import com.jadecode.cli.tui.view.ChatViewport;
import com.jadecode.cli.tui.view.ConversationView;
import com.jadecode.tui.components.Editor;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.PresentedFrame;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.input.KeyChord;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.ThemeManager;

/** R2-04：一次拖选至多一次自动复制、重复释放不复制、flash 只属于 UI。 */
class TuiCopyFlashTest {
    private final AtomicInteger copies = new AtomicInteger();
    private final AtomicInteger shutdowns = new AtomicInteger();
    private final List<String> copied = new ArrayList<>();
    private final List<UiEvent> events = new ArrayList<>();
    private Editor editor;
    private ApprovalBroker broker;
    private CommandCatalog catalog;

    @AfterEach void cleanup() {
        if (broker != null) broker.close();
        if (catalog != null) catalog.close();
    }

    private TuiController controller(TuiState state, boolean copyOnSelect) {
        var themes = new ThemeManager("dark", null, true, "light", "dark");
        var chat = new ChatViewport(new ConversationView(), ChatViewport.Scrollbar.AUTO, copyOnSelect);
        editor = new Editor(new KeyBindings(java.util.Map.of()), null);
        broker = new ApprovalBroker(events::add);
        catalog = new CommandCatalog(Path.of("."));
        return new TuiController(state, null, broker, chat, editor,
                null, new KeyBindings(java.util.Map.of()), catalog, null, themes, events::add,
                shutdowns::incrementAndGet,
                new TuiController.TerminalActions() {
                    @Override public void copy(String text, boolean explicit) {
                        copies.incrementAndGet(); copied.add(text);
                    }
                });
    }

    private static InputEvent mouse(InputEvent.Mouse.Action action, int x, int y, long seq) {
        return InputEvent.mouse(new InputEvent.Mouse(0, action, x, y, false, false, false), seq, 1);
    }

    private static InputEvent right(InputEvent.Mouse.Action action, int x, int y, long seq) {
        return InputEvent.mouse(new InputEvent.Mouse(2, action, x, y, false, false, false), seq, 1);
    }

    private static InputEvent key(String name, KeyChord.Modifier modifier, long seq) {
        return InputEvent.key(KeyChord.of(name, modifier), seq, 1);
    }

    private static void select(TuiController controller) {
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.PRESS, 0, 0, 10)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.DRAG, 4, 0, 11)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.RELEASE, 4, 0, 12)));
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

    @Test void ctrlXPrefersSelectionAndKeepsDraft() {
        var state = new TuiState(); state.installGeneration(0);
        var controller = prepared(state, false);
        select(controller);
        editor.insert("KEEP_DRAFT", false);
        controller.accept(new UiEvent.Input(key("x", KeyChord.Modifier.CTRL, 20)));
        assertEquals(List.of("hell"), copied, "Ctrl+X must copy the visible selection");
        assertEquals("KEEP_DRAFT", editor.text());
        assertEquals(1, copies.get());
    }

    @Test void ctrlXWithoutSelectionCopiesLastReply() {
        var state = new TuiState(); state.installGeneration(0);
        state.transcript.addHistorical("leader", TranscriptEntry.Kind.REPLY, "Reply", "LAST_REPLY", "DONE");
        var controller = prepared(state, false);
        controller.accept(new UiEvent.Input(key("x", KeyChord.Modifier.CTRL, 20)));
        assertEquals(List.of("LAST_REPLY"), copied);
    }

    @Test void slashCopyAlwaysCopiesLastReplyEvenWithSelection() {
        var state = new TuiState(); state.installGeneration(0);
        state.transcript.addHistorical("leader", TranscriptEntry.Kind.REPLY, "Reply", "LAST_REPLY", "DONE");
        var controller = prepared(state, false);
        select(controller);
        controller.submit("/copy");
        assertEquals(List.of("LAST_REPLY"), copied);
    }

    @Test void ctrlCWithSelectionCopiesKeepsDraftAndDoesNotStartExitWindow() {
        var state = new TuiState(); state.installGeneration(0);
        var controller = prepared(state, false);
        select(controller);
        editor.insert("KEEP_DRAFT", false);
        controller.accept(new UiEvent.Input(key("c", KeyChord.Modifier.CTRL, 20)));
        controller.accept(new UiEvent.Input(key("c", KeyChord.Modifier.CTRL, 21)));
        assertEquals(List.of("hell", "hell"), copied, "each explicit Ctrl+C copies selection");
        assertEquals("KEEP_DRAFT", editor.text(), "selection copy must not clear the draft");
        assertEquals(0, shutdowns.get(), "selection copy must not count toward double Ctrl+C exit");
        assertTrue(controller.selectionActive(), "selection is preserved");
    }

    @Test void ctrlCWithoutSelectionStillClearsDraftThenExitsOnSecondPress() {
        var state = new TuiState(); state.installGeneration(0);
        var controller = prepared(state, false);
        editor.insert("KEEP_DRAFT", false);
        controller.accept(new UiEvent.Input(key("c", KeyChord.Modifier.CTRL, 20)));
        assertEquals("", editor.text());
        assertEquals(0, copies.get());
        controller.accept(new UiEvent.Input(key("c", KeyChord.Modifier.CTRL, 21)));
        assertEquals(1, shutdowns.get());
    }

    @Test void rightClickCopiesSelectionOnceAndDoesNotClearItOrApprove() {
        var state = new TuiState(); state.installGeneration(0);
        var controller = prepared(state, false);
        select(controller);
        editor.insert("KEEP_DRAFT", false);
        controller.accept(new UiEvent.Input(right(InputEvent.Mouse.Action.PRESS, 2, 0, 20)));
        controller.accept(new UiEvent.Input(right(InputEvent.Mouse.Action.RELEASE, 2, 0, 21)));
        assertEquals(List.of("hell"), copied, "right click copies the existing selection once");
        assertEquals("KEEP_DRAFT", editor.text());
        assertTrue(controller.selectionActive(), "right click must not rebuild/clear the selection");
        assertEquals(0, shutdowns.get());
    }

    @Test void rightClickWithoutSelectionDoesNotCopyLastReply() {
        var state = new TuiState(); state.installGeneration(0);
        state.transcript.addHistorical("leader", TranscriptEntry.Kind.REPLY, "Reply", "LAST_REPLY", "DONE");
        var controller = prepared(state, false);
        controller.accept(new UiEvent.Input(right(InputEvent.Mouse.Action.PRESS, 2, 0, 20)));
        controller.accept(new UiEvent.Input(right(InputEvent.Mouse.Action.RELEASE, 2, 0, 21)));
        assertEquals(0, copies.get());
        assertFalse(controller.selectionActive());
    }

    @Test void resizeInvalidatesSelectionSoExplicitCopyCannotUseStaleCoordinates() {
        var state = new TuiState(); state.installGeneration(0);
        var controller = prepared(state, false);
        select(controller);
        assertTrue(controller.copyableSelectionActive());
        controller.accept(new UiEvent.Input(InputEvent.resize(30, 2)));
        assertFalse(controller.selectionActive());
        controller.accept(new UiEvent.Input(key("c", KeyChord.Modifier.CTRL, 31)));
        assertEquals(0, copies.get(), "stale selection must not be copied");
    }
}
