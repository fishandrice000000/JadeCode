package com.jadecode.cli.tui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
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
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemeManager;
import com.jadecode.tui.theme.ThemePalette;

/** 全屏屏幕级选区：聊天与编辑器拖选、单击定位、审批不误批的宿主级测试。 */
class TuiScreenSelectionTest {
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);

    private TuiController controller(Editor editor, TuiState state) {
        var themes = new ThemeManager("dark", null, true, "light", "dark");
        var chat = new ChatViewport(new ConversationView(), ChatViewport.Scrollbar.AUTO, true);
        var controller = new TuiController(state, null, null, chat, editor, null,
                new KeyBindings(java.util.Map.of()), null, null, themes, event -> { }, () -> { }, null);
        return controller;
    }

    private Frame frame(String... lines) {
        var styled = new java.util.ArrayList<StyledLine>();
        for (String line : lines) styled.add(StyledLine.of(line, Style.PLAIN));
        return new Frame(1, 1, 1, styled, new Frame.Cursor(0, 0, false), List.of());
    }

    private static InputEvent mouse(InputEvent.Mouse.Action action, int x, int y) {
        return InputEvent.mouse(new InputEvent.Mouse(0, action, x, y, false, false, false), 10, 1);
    }

    @Test void draggingAcrossRowsSelectsScreenText() {
        var state = new TuiState();
        state.installGeneration(0);
        var editor = new Editor(new KeyBindings(java.util.Map.of()), null);
        var controller = controller(editor, state);
        var frame = frame("hello 中文", "second line");
        controller.presented(new PresentedFrame(1, 1, 80, 24, 1, List.of()), frame);
        controller.layout(80, 24, 0, 10, 10, true);
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.PRESS, 0, 0)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.DRAG, 4, 1)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.RELEASE, 4, 1)));
        assertTrue(controller.selectionActive());
        assertEquals("hello 中文\nseco", controller.selectedText());
        assertFalse(controller.applySelection(frame.lines()).getFirst().equals(frame.lines().getFirst()));
    }

    @Test void singleClickOnEditorPositionsCaretInsteadOfSelecting() {
        var state = new TuiState();
        state.installGeneration(0);
        var editor = new Editor(new KeyBindings(java.util.Map.of()), null);
        editor.insert("hello world", false);
        var controller = controller(editor, state);
        var frame = frame("────", "hello world", "────");
        controller.presented(new PresentedFrame(1, 1, 80, 24, 1, List.of()), frame);
        controller.layout(80, 24, 0, 10, 0, true);
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.PRESS, 3, 1)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.RELEASE, 3, 1)));
        assertFalse(controller.selectionActive());
        assertEquals(3, editor.caret());
    }

    @Test void approvalDragAndReleaseDoNotAnswerOnlyExplicitPressDoes() {
        var state = new TuiState();
        state.installGeneration(0);
        var editor = new Editor(new KeyBindings(java.util.Map.of()), null);
        var themes = new ThemeManager("dark", null, true, "light", "dark");
        var events = new java.util.concurrent.LinkedBlockingQueue<UiEvent>();
        var broker = new com.jadecode.cli.tui.bridge.ApprovalBroker(events::offer);
        var controller = new TuiController(state, null, broker, null, editor, null,
                new KeyBindings(java.util.Map.of()), null, null, themes, event -> { }, () -> { }, null);
        var request = new UiEvent.ApprovalRequested("id-1", "leader", "bash", "{}", "reason", "", 7L);
        state.terminalEpoch = 1;
        state.approvals.addLast(request);
        state.approval.show(request, 0, 1);
        var token = new com.jadecode.tui.core.ActionToken(com.jadecode.tui.core.ActionToken.Kind.APPROVE, "id-1", 7L);
        var raw = new Frame(1, 1, 1, List.of(StyledLine.of("approval", Style.PLAIN)), Frame.NO_CURSOR,
                List.of(new Frame.Hit(new com.jadecode.tui.geometry.Rect(0, 0, 8, 1), token)));
        controller.presented(new PresentedFrame(1, 1, 80, 24, 1, List.of(token)), raw);
        controller.layout(80, 24, 0, 10, 0, true);
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.DRAG, 2, 0)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.RELEASE, 2, 0)));
        assertTrue(state.approval.active(), "drag/release must not answer an approval");
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.PRESS, 2, 0)));
        assertFalse(state.approval.active(), "explicit press on the presented button may answer");
        broker.close();
    }

    @Test void approvalClickRequiresExplicitPresentedButton() {
        var state = new TuiState();
        state.installGeneration(0);
        var editor = new Editor(new KeyBindings(java.util.Map.of()), null);
        var controller = controller(editor, state);
        var frame = frame("approval");
        controller.presented(new PresentedFrame(1, 1, 80, 24, 1, List.of()), frame);
        controller.layout(80, 24, 0, 10, 0, true);
        // 没有审批申请时，拖动只是普通选区，不会触发任何答复。
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.PRESS, 0, 0)));
        controller.accept(new UiEvent.Input(mouse(InputEvent.Mouse.Action.RELEASE, 0, 0)));
        assertFalse(controller.selectionActive());
    }
}
