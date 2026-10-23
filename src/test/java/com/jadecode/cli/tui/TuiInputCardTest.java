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
        // S93 迁移：信息页是 Pi 上下横线 + 右上标题，而不是闭合卡片。
        assertTrue(text(fullscreen.lines()).contains(" Hotkeys "), text(fullscreen.lines()));
        assertFalse(text(fullscreen.lines()).contains("┌"));
        assertFalse(text(fullscreen.lines()).contains("KEEP_DRAFT"));
        assertTrue(fullscreen.lines().size() <= 23);
        assertFalse(fullscreen.cursorVisible());

        var regular = new DockView().render(state, new RenderContext(80, 24, theme), 80, 24, null);
        assertTrue(text(regular.lines()).contains(" Hotkeys "));
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
            assertTrue(all.contains("─") && all.toLowerCase().contains(command.substring(1)),
                    command + " missing panel: " + all);
            assertTrue(dock.lines().stream().allMatch(line -> line.width() <= 40), all);
            // 面板（从第一条上横线到全宽下横线）自身不超过终端 65% 预算，状态/footer 仍保留。
            int top = -1, bottom = -1;
            for (int i = 0; i < dock.lines().size(); i++) {
                String line = dock.lines().get(i).text();
                if (line.startsWith("─") && line.contains(" ")) top = i;
                if (top >= 0 && i > top && line.matches("─+")) bottom = i;
            }
            assertTrue(top >= 0 && bottom > top, command + " frame missing");
            assertTrue(bottom - top + 1 <= 7, command + " card exceeded 65% budget: " + (bottom - top + 1));
            controller.accept(new UiEvent.Input(InputEvent.key(KeyChord.of("escape"), 1, 1)));
            assertNull(state.commandPanel, command);
        }
        // 20x8 只要求安全降级：不越界、保留可关闭提示，不输出半张卡。
        assertTrue(controller.submit("/hotkeys"));
        var tiny = new DockView().render(state, new RenderContext(20, 8, theme), 20, 7, null);
        assertTrue(tiny.lines().size() <= 7, tiny.lines().size() + " rows");
        assertTrue(tiny.lines().stream().allMatch(line -> line.width() <= 20));
        String tinyText = text(tiny.lines());
        assertTrue(tinyText.contains("─") || tinyText.contains("Esc"), tinyText);
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
        assertTrue(all.contains(" Search "), all);
        assertTrue(all.contains("Enter next · Shift+Enter previous · Esc close"), all);
        assertFalse(all.contains("KEEP_DRAFT"), "search replaces the editor instead of stacking an overlay");
        assertTrue(dock.cursorVisible());
        // Pi 布局：上横线之后就是查询行（不再有额外的状态行）。
        assertEquals(1, dock.cursorRow(), "top rule then query");
        assertTrue(dock.cursorColumn() >= 2, "query text starts at x=2: " + dock.cursorColumn());
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
            if (dock.lines().get(i).text().contains("Light theme")) { row = i; break; }
        assertTrue(row >= 0, text(dock.lines()));
        String before = state.selector.selected();
        // S93 迁移：Pi 的箭头/候选从 0 列开始，x=0 现在是真实行命中；末尾留白与横线不命中。
        int titleRow = -1;
        for (int i = 0; i < dock.lines().size(); i++)
            if (dock.lines().get(i).text().startsWith("─") && dock.lines().get(i).text().contains("Settings"))
                titleRow = i;
        assertTrue(titleRow >= 0, text(dock.lines()));
        controller.accept(new UiEvent.Input(InputEvent.mouse(new InputEvent.Mouse(0,
                InputEvent.Mouse.Action.PRESS, 0, titleRow, false, false, false), 30, 1)));
        assertEquals(before, state.selector.selected(), "top rule must not be a candidate hit");
        controller.accept(new UiEvent.Input(InputEvent.mouse(new InputEvent.Mouse(0,
                InputEvent.Mouse.Action.PRESS, 79, row, false, false, false), 31, 1)));
        assertEquals(before, state.selector.selected(), "last column is reserved whitespace");
        controller.accept(new UiEvent.Input(InputEvent.mouse(new InputEvent.Mouse(0,
                InputEvent.Mouse.Action.PRESS, 0, row, false, false, false), 32, 1)));
        assertEquals("lightTheme", state.selector.selected(), "Pi arrow column selects the row");
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
