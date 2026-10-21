package com.jadecode.cli.tui.view;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.cli.tui.model.TranscriptEntry;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** C13 卡片/28.0s?/思考折叠/状态/页脚的显示测试。 */
class TuiMessageStatusFrameTest {
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
    private final RenderContext context = new RenderContext(72, 24, theme);

    private static String text(List<StyledLine> lines) {
        return lines.stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test void userRequestCardKeepsHeaderAndFullWidthBackground() {
        var state = new TuiState();
        var entry = state.transcript.addUser("hello [x](https://example.com)");
        var lines = new MessageCard().render(entry, context, 60, true, "");
        assertTrue(lines.getFirst().text().contains("Request"));
        assertTrue(text(lines).contains("hello"));
        var body = lines.stream().filter(line -> line.text().contains("hello")).findFirst().orElseThrow();
        assertEquals(60, body.width());
    }

    @Test void replyCardKeepsStatusAndDoesNotInventZeroTiming() {
        var state = new TuiState();
        var entry = state.transcript.addHistorical("leader", TranscriptEntry.Kind.REPLY, "Reply", "answer", "DONE");
        String header = new MessageCard().render(entry, context, 60, true, "").getFirst().text();
        assertTrue(header.contains("Reply") && header.contains("Done"));
        assertFalse(header.contains("0.0s"));
        entry.elapsed(29_000_000_000L);
        assertTrue(new MessageCard().render(entry, context, 60, true, "").getFirst().text().contains("29.0s"));
    }

    @Test void thinkingCollapsesButKeepsHeaderAndMarkdownBody() {
        var state = new TuiState();
        var entry = state.transcript.addHistorical("leader", TranscriptEntry.Kind.THINKING, "Thinking",
                "**deep** thought", "DONE");
        var visible = new MessageCard().render(entry, context, 60, true, "");
        assertTrue(text(visible).contains("deep thought"));
        var hidden = new MessageCard().render(entry, context, 60, false, "");
        assertEquals(1, hidden.size());
        assertTrue(hidden.getFirst().text().contains("Thinking…"));
    }

    @Test void incompleteReplyAddsHonestStatusAfterPartialText() {
        var state = new TuiState();
        var entry = state.transcript.addHistorical("leader", TranscriptEntry.Kind.REPLY, "Reply", "partial", "INCOMPLETE");
        String rendered = text(new MessageCard().render(entry, context, 60, true, ""));
        assertTrue(rendered.contains("partial"));
        assertTrue(rendered.contains("not a complete reply"));
    }

    @Test void statusShowsRealTurnTimeAndFreezesAtEnd() {
        var state = new TuiState();
        long now = System.nanoTime();
        state.execution(new ExecutionNotice("leader", ExecutionNotice.Kind.TURN_START,
                "turn", "Reply", "", "RUNNING", 0, 0, 0, now - 29_000_000_000L));
        var contextNow = new RenderContext(80, 24, theme,
                com.jadecode.tui.terminal.TerminalCapabilities.detect(java.util.Map.of()), now, 1);
        String running = text(new StatusView().render(state, contextNow, 80));
        assertTrue(running.contains("29.0s"), running);
        state.execution(new ExecutionNotice("leader", ExecutionNotice.Kind.TURN_END,
                "turn", "Reply", "", "DONE", 0, 0, 29_000_000_000L, now - 29_000_000_000L));
        String frozen = text(new StatusView().render(state, new RenderContext(80, 24, theme,
                com.jadecode.tui.terminal.TerminalCapabilities.detect(java.util.Map.of()), now + 5_000_000_000L, 2), 80));
        assertTrue(frozen.contains("29.0s"), frozen);
    }

    @Test void footerUses100KAndEstimatedContext() {
        var state = new TuiState();
        state.directory = "/home/user/project";
        state.branch = "main";
        state.model = "model-x";
        state.permissionProfile = "workspace";
        state.sessionId = "id";
        var usage = new com.jadecode.session.SessionUsage.Snapshot(
                new com.jadecode.session.SessionUsage.Totals(100_000, 2000, 5000, 100, 3, 0),
                java.util.Map.of("leader", new com.jadecode.session.SessionUsage.Totals(100_000, 2000, 5000, 100, 3, 0)),
                java.util.Map.of(), java.util.Map.of());
        state.session = new com.jadecode.session.SessionRuntime.Display("id", "My session", "model-x",
                new com.jadecode.llm.ModelMetadata(com.jadecode.llm.ModelMetadata.Limits.unknown(), ""),
                com.jadecode.llm.ThinkingSettings.DEFAULT, "default",
                java.util.List.of(com.jadecode.llm.ThinkingSettings.DEFAULT), usage,
                new com.jadecode.session.ContextUsage(32000, 128000));
        var lines = new FooterView().render(state, context, 100);
        String rendered = text(lines);
        assertTrue(rendered.contains("100K"), rendered);
        assertTrue(rendered.contains("25.0%/128K"), rendered);
        assertTrue(rendered.contains("workspace"));
        assertTrue(lines.get(1).text().trim().endsWith("model-x"));
    }
}
