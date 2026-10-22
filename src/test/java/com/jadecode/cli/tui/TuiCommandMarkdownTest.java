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
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemeManager;
import com.jadecode.tui.theme.ThemePalette;

/** R4-03：命令帮助从分派进入 NOTICE，再由 Conversation 的 Markdown/主题投影。 */
class TuiCommandMarkdownTest {
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);

    private TuiController controller(TuiState state, Path dir) {
        var themes = new ThemeManager("dark", dir.resolve("themes"), true, "light", "dark");
        var chat = new ChatViewport(new ConversationView(), ChatViewport.Scrollbar.AUTO, true);
        var editor = new Editor(new KeyBindings(Map.of()), null);
        return new TuiController(state, null, null, chat, editor, null, new KeyBindings(Map.of()),
                new CommandCatalog(dir), null, themes, event -> { }, () -> { }, null);
    }

    private static String body(TuiState state) {
        return state.transcript.entries().stream().map(entry -> entry.body()).reduce("", (a, b) -> a + "\n" + b);
    }

    private static String text(List<StyledLine> lines) {
        return lines.stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test void hotkeysAndMcpReachTranscriptAndFinalProjectionWithSemanticStyles(@TempDir Path dir) {
        var state = new TuiState(); state.installGeneration(0);
        var controller = controller(state, dir);
        assertTrue(controller.submit("/hotkeys"));
        String body = body(state);
        assertTrue(body.contains("## Keys and commands"), body);
        assertTrue(body.contains("` ctrl+x `") || body.contains("`ctrl+x`"), body);
        assertTrue(body.contains("fullscreen selection"), body);
        assertFalse(state.transcript.entries().getFirst().body().contains("Enter submit · Shift+Enter"), "old four-line help must not be command source");

        state.mcpServers = List.of(new com.jadecode.mcp.McpManager.ServerSnapshot("alpha`server", true, true,
                List.of("read — safe")));
        assertTrue(controller.submit("/mcp"));
        body = body(state);
        assertTrue(body.contains("## MCP servers"), body);
        assertTrue(body.contains("alpha`server"), body);
        assertTrue(body.contains("read — safe"), body);

        var context = new RenderContext(80, 30, theme);
        var conversation = new ConversationView();
        var lines = conversation.render(state, context, List.of(), false);
        String projected = text(lines);
        assertTrue(projected.contains("Keys and commands"), projected);
        assertTrue(projected.contains("MCP servers"), projected);
        var headingFg = theme.style("mdHeading").fg();
        assertTrue(lines.stream().flatMap(line -> line.spans().stream())
                .anyMatch(span -> span.style().fg().equals(headingFg)), "heading style lost through NOTICE");
        var codeFg = theme.style("mdCode").fg();
        assertTrue(lines.stream().flatMap(line -> line.spans().stream())
                .anyMatch(span -> span.style().fg().equals(codeFg)), "inline code style lost through NOTICE");
        // 120 列重排不得保留 80 列成品硬换行；样式仍在。
        var wide = conversation.render(state, new RenderContext(120, 30, theme), List.of(), false);
        assertTrue(wide.stream().allMatch(line -> line.width() <= 120));
        assertTrue(text(wide).contains("ctrl+x"));
    }

    @Test void modelAndSessionKeepKnownFieldsAndUsageGroups(@TempDir Path dir) {
        var state = new TuiState(); state.installGeneration(0);
        var total = new SessionUsage.Totals(1200, 300, 100, 50, 2, 1);
        var usage = new SessionUsage.Snapshot(total, Map.of("leader", total), Map.of("chat", total),
                Map.of("model-x", total));
        var metadata = new ModelMetadata(new ModelMetadata.Limits(200_000, 180_000, 8_000), "config");
        state.model = "model-x";
        state.sessionId = "11111111-1111-1111-1111-111111111111";
        state.session = new SessionRuntime.Display(state.sessionId, "Actual title", "model-x", metadata,
                ThinkingSettings.HIGH, "high", List.of(ThinkingSettings.HIGH), usage,
                new ContextUsage(1500, 200_000));
        var controller = controller(state, dir);
        assertTrue(controller.submit("/model"));
        assertTrue(controller.submit("/session"));
        String body = body(state);
        assertTrue(body.contains("Context window:** 200000"), body);
        assertTrue(body.contains("Max input:** 180000"), body);
        assertTrue(body.contains("Max output:** 8000"), body);
        assertTrue(body.contains("Actual title"), body);
        assertTrue(body.contains("By actor"), body);
        assertTrue(body.contains("By purpose"), body);
        assertTrue(body.contains("By model"), body);
        assertTrue(body.contains("Missing usage reports:** 1"), body);
        assertFalse(body.matches("(?s).*(?<!\\d)0 tokens.*"), "unknown values must not be fabricated as zero: " + body);
        var lines = new ConversationView().render(state, new RenderContext(110, 40, theme), List.of(), false);
        assertTrue(text(lines).contains("Context window: 200000"));
        assertTrue(text(lines).contains("By actor"));
        var headingFg = theme.style("mdHeading").fg();
        assertTrue(lines.stream().flatMap(line -> line.spans().stream())
                .anyMatch(span -> span.style().fg().equals(headingFg)), "model/session heading style lost");
    }
}
