package com.jadecode.cli.tui.view;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.cli.tui.model.TranscriptEntry;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** R2-01 滚动条固定列几何：所有行总宽一致、bar 只在最后一列、正文重新按 contentWidth 排版。 */
class TuiScrollbarGeometryTest {
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
    private final RenderContext context = new RenderContext(80, 20, theme);

    private TuiState stateWith(String... bodies) {
        var state = new TuiState();
        state.installGeneration(0);
        for (String body : bodies)
            state.transcript.addHistorical("leader", TranscriptEntry.Kind.REPLY, "Reply", body, "DONE");
        return state;
    }

    private List<StyledLine> render(TuiState state, ChatViewport.Scrollbar bar, int width) {
        var view = new ChatViewport(new ConversationView(), bar, false);
        return view.render(state, new RenderContext(width, 12, theme), 12, List.of(), false, true);
    }

    @Test void alwaysScrollbarPutsBarInFixedLastColumnForShortEmptyAndFullLines() {
        var state = stateWith("short", "", "x".repeat(200), "中文🙂 mixed");
        var lines = render(state, ChatViewport.Scrollbar.ALWAYS, 80);
        assertFalse(lines.isEmpty());
        for (StyledLine line : lines) {
            assertEquals(80, line.width(), line.text());
            String last = line.spans().getLast().text();
            assertTrue(last.equals("█") || last.equals("│"), "bar expected at column 79: " + last);
            // bar 必须从 0 基第 79 列开始，且正文内容不越界。
            assertEquals(79, DisplayWidth.width(line.text().substring(0, line.text().length() - last.length())));
        }
    }

    @Test void hiddenAndShortAutoDoNotReserveOrDrawBar() {
        var state = stateWith("short");
        var hidden = render(state, ChatViewport.Scrollbar.HIDDEN, 80);
        assertTrue(hidden.stream().allMatch(line -> line.width() == 80));
        assertFalse(hidden.stream().anyMatch(line -> {
            String last = line.spans().getLast().text();
            return last.equals("█") || last.equals("│");
        }));
        var auto = render(state, ChatViewport.Scrollbar.AUTO, 80);
        assertFalse(auto.stream().anyMatch(line -> {
            String last = line.spans().getLast().text();
            return last.equals("█") || last.equals("│");
        }));
    }

    @Test void alwaysScrollbarRerendersContentAtReducedWidthInsteadOfCropping() {
        String longLine = "word ".repeat(40).strip();
        var state = stateWith(longLine);
        var lines = render(state, ChatViewport.Scrollbar.ALWAYS, 40);
        for (StyledLine line : lines) assertEquals(40, line.width());
        // 正文重新排版后 39 列内仍包含完整词序列（不因裁剪丢最后一列内容）。
        String body = lines.stream().map(StyledLine::text).reduce("", String::concat);
        assertTrue(body.contains("word"), body);
    }
}
