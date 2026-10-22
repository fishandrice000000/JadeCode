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
        assertTrue(model.contains("Model switching") && (model.contains("unavai") || model.contains("unavailable")), model);
        assertTrue(model.contains("No context/input/output limits") == false, model);
        controller.accept(new UiEvent.Input(key("escape")));
        assertTrue(controller.submit("/session"));
        String session = text(state.commandPanel.render(new RenderContext(100, 30, theme), 100, 24));
        assertTrue(session.contains("Actual title"), session);
        assertTrue(session.replaceAll("\\s+", "").contains("11111111-1111-1111-1111-111111111111"), session);
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
        assertTrue(wideText.contains("Global"), wideText);
        assertTrue(wideText.contains("ctrl+x"), wideText);
        assertTrue(wideText.contains("Commands"), wideText);
        assertTrue(wideText.contains("/settings"), wideText);
        assertTrue(wide.stream().anyMatch(line -> line.text().contains("Global") && line.text().contains("Editor")),
                "wide panel should place multiple groups side by side: " + wideText);
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
        assertTrue(all.contains("connected; no tools") && all.contains("advertis"), all);
        assertTrue(all.contains("off"), all);
    }
}
