package com.jadecode.cli.tui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.cli.tui.view.ChatViewport;
import com.jadecode.cli.tui.view.ConversationView;
import com.jadecode.cli.tui.view.DockView;
import com.jadecode.config.JadeSettings;
import com.jadecode.tui.components.Editor;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.input.KeyChord;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemeManager;
import com.jadecode.tui.theme.ThemePalette;

/** S81/S82：信息页/搜索卡替换 Editor，且卡片高度/命中/光标来自同一次 dock 布局。 */
class TuiInputCardTest {
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
    private ThemeManager themes;
    private TuiPreferences preferences;
    private CommandCatalog catalog;

    @AfterEach void cleanup() {
        if (preferences != null) { try { preferences.awaitWrites(); } catch (Exception ignored) { } }
        if (themes != null) themes.close();
        if (catalog != null) catalog.close();
    }

    private TuiController controller(TuiState state, Path dir) {
        themes = new ThemeManager("dark", dir.resolve("themes"), true, "light", "dark");
        catalog = new CommandCatalog(dir);
        preferences = new TuiPreferences(dir.resolve("config.json"));
        var chat = new ChatViewport(new ConversationView(), ChatViewport.Scrollbar.AUTO, true);
        var editor = new Editor(new KeyBindings(java.util.Map.of()), null);
        return new TuiController(state, null, null, chat, editor, preferences, new KeyBindings(java.util.Map.of()),
                catalog, new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes")), themes,
                event -> { }, () -> { }, null);
    }

    private static String text(List<StyledLine> lines) {
        return lines.stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test void freshSessionHotkeysReplacesEditorInBothModes(@TempDir Path dir) {
        var state = new TuiState(); state.installGeneration(0);
        var controller = controller(state, dir);
        state.editor.insert("KEEP_DRAFT", false);
        assertTrue(controller.submit("/hotkeys"));
        assertEquals(0, state.transcript.entries().size(), "info page must not append history");

        var fullscreen = new DockView().render(state, new RenderContext(80, 24, theme), 80, 23, null);
        assertTrue(text(fullscreen.lines()).contains("┌─ Hotkeys"), text(fullscreen.lines()));
        assertFalse(text(fullscreen.lines()).contains("KEEP_DRAFT"));
        assertTrue(fullscreen.lines().size() <= 23);
        assertFalse(fullscreen.cursorVisible());

        var regular = new DockView().render(state, new RenderContext(80, 24, theme), 80, 24, null);
        assertTrue(text(regular.lines()).contains("┌─ Hotkeys"));
        assertFalse(text(regular.lines()).contains("KEEP_DRAFT"));
        assertEquals("KEEP_DRAFT", state.editor.text(), "closing/opening a card must preserve the draft");
    }

    @Test void allFourInfoPagesRenderInsideTheInputAreaBudget(@TempDir Path dir) {
        var state = new TuiState(); state.installGeneration(0);
        var controller = controller(state, dir);
        for (String command : List.of("/hotkeys", "/model", "/session", "/mcp")) {
            assertTrue(controller.submit(command), command);
            var dock = new DockView().render(state, new RenderContext(40, 12, theme), 40, 11, null);
            String all = text(dock.lines());
            assertTrue(all.contains("┌─ "), command + " missing card: " + all);
            assertTrue(all.contains("└") && dock.lines().stream().allMatch(line -> line.width() <= 40), all);
            // 卡片（从┌到└）自身不超过终端 65% 预算，状态/footer 仍保留。
            int top = -1, bottom = -1;
            for (int i = 0; i < dock.lines().size(); i++) {
                String line = dock.lines().get(i).text();
                if (line.startsWith("┌")) top = i;
                if (line.startsWith("└")) bottom = i;
            }
            assertTrue(top >= 0 && bottom > top, command + " frame missing");
            assertTrue(bottom - top + 1 <= 7, command + " card exceeded 65% budget: " + (bottom - top + 1));
            controller.accept(new UiEvent.Input(InputEvent.key(KeyChord.of("escape"), 1, 1)));
            assertNull(state.commandPanel, command);
        }
    }

    @Test void searchUsesInputCardAndKeepsDraftCursor(@TempDir Path dir) {
        var state = new TuiState(); state.installGeneration(0);
        var controller = controller(state, dir);
        state.editor.insert("KEEP_DRAFT", false);
        controller.accept(new UiEvent.Input(InputEvent.key(KeyChord.of("f", KeyChord.Modifier.CTRL), 2, 1)));
        assertTrue(controller.searchOpen());
        var dock = new DockView().render(state, new RenderContext(80, 24, theme), 80, 23,
                controller.searchOverlay());
        String all = text(dock.lines());
        assertTrue(all.contains("┌─ Search"), all);
        assertFalse(all.contains("KEEP_DRAFT"), "search replaces the editor instead of stacking an overlay");
        assertTrue(dock.cursorVisible());
        assertEquals(2, dock.cursorRow(), "top border + status line, then query");
        assertTrue(dock.cursorColumn() > 3);
        assertEquals("KEEP_DRAFT", state.editor.text());
    }

    @Test void selectorHitUsesRealContentColumnsNotWholeRow(@TempDir Path dir) {
        var state = new TuiState(); state.installGeneration(0);
        var controller = controller(state, dir);
        assertTrue(controller.submit("/settings"));
        var context = new RenderContext(80, 24, theme);
        var dock = new DockView().render(state, context, 80, 23, null);
        var frame = new Frame(1, 1, context.revision(), dock.lines(), Frame.NO_CURSOR, dock.hits());
        controller.presented(new com.jadecode.tui.core.PresentedFrame(1, 1, 80, 24, context.revision(),
                dock.hits().stream().map(Frame.Hit::action).toList()), frame);
        controller.layout(80, 24, 0, 10, 10, true);
        int row = -1;
        for (int i = 0; i < dock.lines().size(); i++)
            if (dock.lines().get(i).text().contains("→ Theme")) { row = i; break; }
        assertTrue(row >= 0, text(dock.lines()));
        String before = state.selector.selected();
        // x=0 是边框/空白，不应命中候选。
        controller.accept(new UiEvent.Input(InputEvent.mouse(new InputEvent.Mouse(0,
                InputEvent.Mouse.Action.PRESS, 0, row, false, false, false), 30, 1)));
        assertEquals(before, state.selector.selected(), "border column must not select");
        // x=3 是嵌入标题卡片的正文起点。
        controller.accept(new UiEvent.Input(InputEvent.mouse(new InputEvent.Mouse(0,
                InputEvent.Mouse.Action.PRESS, 3, row, false, false, false), 31, 1)));
        assertEquals("theme", state.selector.selected(), "content column selects the row");
    }

    @Test void approvalArrivalClosesInfoAndSelectorPages(@TempDir Path dir) {
        var state = new TuiState(); state.installGeneration(0);
        var controller = controller(state, dir);
        state.editor.insert("KEEP_DRAFT", false);
        assertTrue(controller.submit("/hotkeys"));
        controller.accept(new UiEvent.ApprovalRequested("id-1", "leader", "bash", "{}", "r", "", 1));
        assertNull(state.commandPanel);
        state.approvals.clear(); state.approval.clear();
        assertTrue(controller.submit("/settings"));
        assertNotNull(state.selector);
        controller.accept(new UiEvent.ApprovalRequested("id-2", "leader", "bash", "{}", "r", "", 2));
        assertNull(state.selector, "approval must take priority over a selector");
        assertEquals("KEEP_DRAFT", state.editor.text());
    }
}
