package com.jadecode.cli.tui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.cli.tui.view.ChatViewport;
import com.jadecode.cli.tui.view.ConversationView;
import com.jadecode.llm.ModelMetadata;
import com.jadecode.llm.ThinkingSettings;
import com.jadecode.session.ContextUsage;
import com.jadecode.session.SessionRuntime;
import com.jadecode.session.SessionUsage;
import com.jadecode.tui.components.Editor;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.input.KeyChord;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemeManager;
import com.jadecode.tui.theme.ThemePalette;

/** R5-01：四类本地信息进入结构化弹窗，不写转录；modal 输入/滚动/关闭与草稿恢复。 */
class TuiCommandPanelTest {
    @Test void commonAndCategoriesUseEffectiveBindingsAndPreservePerCategoryScroll() {
        var bindings = new KeyBindings(Map.of("tui.input.submit", List.of("ctrl+enter")));
        var model = new com.jadecode.cli.tui.view.HotkeysView().panel(bindings, List.of(), 0, 1);
        assertEquals("common", model.groups().getFirst().id());
        assertEquals("ctrl+enter", model.groups().getFirst().entries().getFirst().key());
        var panel = new com.jadecode.cli.tui.view.CommandPanelView(model);
        var context = new RenderContext(80, 24, theme);
        assertTrue(text(panel.render(context, 80, 12)).contains("Common"));
        panel.moveSection(2); // Editor
        var editor = text(panel.render(context, 80, 12));
        assertTrue(editor.contains("Editor"));
        assertFalse(editor.contains("Submit input"), "current category only");
        panel.scrollBy(3); panel.render(context, 80, 12);
        int saved = panel.scroll();
        panel.moveSection(-1); panel.render(context, 80, 12);
        panel.moveSection(1); panel.render(context, 40, 12);
        assertEquals(saved, panel.scroll(), "resize/category round trip must preserve reading position");
        var longKeys = new KeyBindings(Map.of("tui.input.submit",
                List.of("ctrl+shift+enter", "ctrl+alt+enter", "super+enter")));
        var narrow = new com.jadecode.cli.tui.view.CommandPanelView(
                new com.jadecode.cli.tui.view.HotkeysView().panel(longKeys, List.of(), 0, 1));
        String compact = text(narrow.render(context, 40, 12)).replaceAll("[\\s│]+", "");
        String effective = longKeys.chords("tui.input.submit").stream().map(KeyChord::display)
                .collect(java.util.stream.Collectors.joining(","));
        assertTrue(compact.contains(effective), "narrow help must wrap all effective keys: " + compact);
    }
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);

    private TuiController controller(TuiState state, Path dir) {
        var themes = new ThemeManager("dark", dir.resolve("themes"), true, "light", "dark");
        var chat = new ChatViewport(new ConversationView(), ChatViewport.Scrollbar.AUTO, true);
        var editor = new Editor(new KeyBindings(Map.of()), null);
        return new TuiController(state, null, null, chat, editor, null, new KeyBindings(Map.of()),
                new CommandCatalog(dir), null, themes, event -> { }, () -> { }, null);
    }

    private static InputEvent key(String name) { return InputEvent.key(KeyChord.of(name), 1, 1); }
    private static InputEvent text(String value) { return InputEvent.text(value, 1, 1); }

    private static String text(List<StyledLine> lines) {
        return lines.stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
    }

    private static TuiState stateWithSession() {
        var state = new TuiState();
        state.installGeneration(0);
        var total = new SessionUsage.Totals(1200, 300, 100, 50, 2, 1);
        var usage = new SessionUsage.Snapshot(total, Map.of("leader", total), Map.of("chat", total),
                Map.of("model-x", total));
        var metadata = new ModelMetadata(new ModelMetadata.Limits(200_000, 180_000, 8_000), "config");
        state.model = "model-x";
        state.sessionId = "11111111-1111-1111-1111-111111111111";
        state.session = new SessionRuntime.Display(state.sessionId, "Actual title", "model-x", metadata,
                ThinkingSettings.HIGH, "high", List.of(ThinkingSettings.HIGH), usage,
                new ContextUsage(1500, 200_000));
        return state;
    }

    @Test void fourCommandsOpenPanelWithoutTranscriptGrowth(@TempDir Path dir) {
        var state = stateWithSession();
        state.mcpServers = List.of(new com.jadecode.mcp.McpManager.ServerSnapshot("alpha", true, true,
                List.of("read")));
        var controller = controller(state, dir);
        for (String command : List.of("/hotkeys", "/model", "/session", "/mcp")) {
            int before = state.transcript.entries().size();
            assertTrue(controller.submit(command), command);
            assertNotNull(state.commandPanel, command + " must open a panel");
            assertEquals(before, state.transcript.entries().size(), command + " must not append transcript");
            controller.accept(new UiEvent.Input(key("escape")));
            assertNull(state.commandPanel, command + " must close on Esc");
        }
    }

    @Test void modelAndSessionPanelsKeepRealFieldsAndUsageGroups(@TempDir Path dir) {
        var state = stateWithSession();
        var controller = controller(state, dir);
        assertTrue(controller.submit("/model"));
        String model = text(state.commandPanel.render(new RenderContext(100, 30, theme), 100, 24));
        assertTrue(model.contains("Context window") && model.contains("200000"), model);
        assertTrue(model.contains("Max input") && model.contains("180000"), model);
        assertTrue(model.contains("Model switching") && (model.contains("able in this build") || model.contains("unavailable")), model);
        assertTrue(model.contains("No context/input/output limits") == false, model);
        controller.accept(new UiEvent.Input(key("escape")));
        assertTrue(controller.submit("/session"));
        String session = text(state.commandPanel.render(new RenderContext(100, 30, theme), 100, 24));
        assertTrue(session.contains("Actual title"), session);
        assertTrue(session.replaceAll("[\\s│]+", "").contains("11111111-1111-1111-1111-111111111111"), session);
        assertTrue(session.contains("By actor") && session.contains("leader"), session);
        assertTrue(session.contains("Missing usage reports") && session.contains("1"), session);
    }

    @Test void panelRendersGroupsAndScrollsInNarrowAndWideLayouts(@TempDir Path dir) {
        var state = stateWithSession();
        var controller = controller(state, dir);
        assertTrue(controller.submit("/hotkeys"));
        var panel = state.commandPanel;
        var wide = panel.render(new RenderContext(120, 30, theme), 120, 24);
        String wideText = text(wide);
        assertTrue(wideText.contains("Common"), wideText);
        assertTrue(wideText.contains("Submit input"), wideText);
        assertFalse(wideText.contains("/settings"), "other categories must not be rendered together");
        assertFalse(wideText.contains("Move word left"), "Editor category must stay hidden on Common");
        assertTrue(wide.stream().allMatch(line -> line.width() <= 120));
        assertTrue(wide.getFirst().text().startsWith("┌") && wide.getFirst().text().endsWith("┐"), wide.getFirst().text());
        assertTrue(wide.getLast().text().startsWith("└") && wide.getLast().text().endsWith("┘"), wide.getLast().text());
        assertTrue(wide.size() <= 24);
        int before = panel.scroll();
        controller.accept(new UiEvent.Input(key("pagedown")));
        assertTrue(panel.scroll() >= before, "page down must scroll or stay at end");
        controller.accept(new UiEvent.Input(key("tab")));
        assertTrue(panel.section() >= 0);
        var narrow = panel.render(new RenderContext(40, 12, theme), 40, 11);
        assertTrue(text(narrow).contains("Global") || text(narrow).contains("Commands"), text(narrow));
        assertTrue(narrow.stream().allMatch(line -> line.width() <= 40));
        controller.accept(new UiEvent.Input(key("escape")));
        assertNull(state.commandPanel);
    }

    @Test void panelConsumesModalInputAndPreservesDraft(@TempDir Path dir) {
        var state = stateWithSession();
        var controller = controller(state, dir);
        var editor = state.editor;
        editor.insert("KEEP_DRAFT", false);
        assertTrue(controller.submit("/hotkeys"));
        assertNotNull(state.commandPanel);
        controller.accept(new UiEvent.Input(InputEvent.key(KeyChord.of("c", KeyChord.Modifier.CTRL), 2, 1)));
        assertNotNull(state.commandPanel, "Ctrl+C must not close or act through the modal");
        assertEquals("KEEP_DRAFT", editor.text());
        controller.accept(new UiEvent.Input(text("x")));
        assertNotNull(state.commandPanel);
        assertEquals("KEEP_DRAFT", editor.text());
        controller.accept(new UiEvent.Input(key("escape")));
        assertNull(state.commandPanel);
        assertEquals("KEEP_DRAFT", editor.text());
    }

    @Test void panelHasClosedBordersPaddingAndOpaqueBackground(@TempDir Path dir) {
        var state = stateWithSession();
        var controller = controller(state, dir);
        assertTrue(controller.submit("/hotkeys"));
        var panelTheme = new ThemeManager("dark", null, true, "light", "dark").preview();
        var lines = state.commandPanel.render(new RenderContext(80, 24, panelTheme), 80, 24);
        assertTrue(lines.stream().allMatch(line -> line.width() == 80), "every panel row must be exactly W columns");
        assertTrue(lines.getFirst().text().startsWith("┌") && lines.getFirst().text().endsWith("┐"),
                lines.getFirst().text());
        assertTrue(lines.getLast().text().startsWith("└") && lines.getLast().text().endsWith("┘"),
                lines.getLast().text());
        var background = panelTheme.color("panelBg");
        assertTrue(lines.stream().flatMap(line -> line.spans().stream())
                .allMatch(span -> span.style().bg().equals(background)), "all spans, padding and blank rows share panelBg");
        var title = lines.getFirst().spans().stream().filter(span -> span.text().contains("Hotkeys"))
                .findFirst().orElseThrow();
        assertTrue(title.style().bold(), "embedded title must be bold");
        assertTrue(title.style().fg().equals(panelTheme.style("accent").fg()), "embedded title must use accent");
        var border = lines.getFirst().spans().getFirst();
        assertFalse(border.style().bold(), "border must stay normal weight");
        assertTrue(lines.get(1).text().startsWith("│  ") && lines.get(1).text().endsWith("  │"),
                "body rows need two-column padding on both sides: " + lines.get(1).text());
        // 窄屏缩减 padding 但仍保留闭合边框与可达提示。
        var narrow = state.commandPanel.render(new RenderContext(40, 12, panelTheme), 40, 11);
        assertTrue(narrow.stream().allMatch(line -> line.width() == 40));
        assertTrue(narrow.getFirst().text().startsWith("┌") && narrow.getFirst().text().endsWith("┐"));
        assertTrue(narrow.getLast().text().startsWith("└") && narrow.getLast().text().endsWith("┘"));
    }

    @Test void noColorPanelKeepsBordersWithoutAttributes(@TempDir Path dir) {
        var state = stateWithSession();
        var controller = controller(state, dir);
        assertTrue(controller.submit("/hotkeys"));
        var base = new ThemeManager("dark", null, true, "light", "dark").preview();
        var plain = new Theme(base.name(), base.appearance(), base.colors(), false, base.revision());
        var lines = state.commandPanel.render(new RenderContext(80, 24, plain), 80, 20);
        assertTrue(lines.getFirst().text().startsWith("┌") && lines.getFirst().text().endsWith("┐"));
        assertTrue(lines.getLast().text().startsWith("└") && lines.getLast().text().endsWith("┘"));
        assertTrue(lines.stream().flatMap(line -> line.spans().stream())
                .allMatch(span -> span.style().isPlain()), "NO_COLOR must not emit font/color attributes");
    }

    @Test void approvalArrivalClosesPanelAndMouseDoesNotPenetrate(@TempDir Path dir) {
        var state = stateWithSession();
        var controller = controller(state, dir);
        assertTrue(controller.submit("/hotkeys"));
        assertNotNull(state.commandPanel);
        controller.accept(new UiEvent.ApprovalRequested("id-1", "leader", "bash", "{}", "reason", "", 7L));
        assertNull(state.commandPanel, "approval must take priority over the command panel");
        assertTrue(state.approval.active());
        // 重新打开后用鼠标点击：modal 消费事件，不启动底层选区。
        controller.accept(new UiEvent.Input(InputEvent.key(KeyChord.of("escape"), 2, 1)));
        assertTrue(controller.submit("/hotkeys"));
        controller.accept(new UiEvent.Input(InputEvent.mouse(new InputEvent.Mouse(0,
                InputEvent.Mouse.Action.PRESS, 3, 3, false, false, false), 3, 1)));
        assertFalse(controller.selectionActive());
        assertNotNull(state.commandPanel);
    }

    @Test void mcpPanelSanitizesExternalNamesAndDistinguishesEmptyTools(@TempDir Path dir) {
        var state = stateWithSession();
        state.mcpServers = List.of(
                new com.jadecode.mcp.McpManager.ServerSnapshot("bad\nname`x", true, true, List.of()),
                new com.jadecode.mcp.McpManager.ServerSnapshot("off", false, false, List.of("ignored")));
        var controller = controller(state, dir);
        assertTrue(controller.submit("/mcp"));
        var lines = state.commandPanel.render(new RenderContext(80, 20, theme), 80, 18);
        String all = text(lines);
        assertTrue(all.contains("bad name`x") || all.contains("badname`x") || all.contains("bad"), all);
        assertTrue(lines.stream().noneMatch(line -> line.text().contains("\n")), "external control characters must be sanitized");
        assertTrue(all.contains("connected; no tools") && all.contains("advert"), all);
        assertTrue(all.contains("off"), all);
    }
}
