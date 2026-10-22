package com.jadecode.tui.markdown;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** R5-03：表格逐格换行、闭合底边、列宽预算与极窄原文回退。 */
class TableLayoutTest {
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);

    private List<StyledLine> layout(TextRenderResult.Table table, int width) {
        return new MarkdownLayout(theme).layout(new TextRenderResult(List.of(table), 1, List.of()), width, Style.PLAIN);
    }

    private static String text(List<StyledLine> lines) {
        return lines.stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test void longCellWrapsAndClosesBottomWithoutEllipsis() {
        String longText = "X".repeat(120) + "TAIL_SENTINEL";
        var table = new TextRenderResult.Table(
                List.of(List.of(new TextRenderResult.Text("A")), List.of(new TextRenderResult.Text("B"))),
                List.of(List.of(List.of(new TextRenderResult.Text(longText)), List.of(new TextRenderResult.Text("short")))),
                List.of("left", "left"), "| A | B |\n|---|---|\n| " + longText + " | short |");
        var lines = layout(table, 60);
        String all = text(lines);
        assertTrue(all.contains("TAIL_SENTINEL"), all);
        assertTrue(lines.getLast().text().startsWith("└"), lines.getLast().text());
        assertTrue(lines.stream().anyMatch(line -> line.text().startsWith("┌")));
        assertTrue(lines.stream().noneMatch(line -> line.text().contains("…")), "long cells must wrap, not ellipsize");
        for (StyledLine line : lines) assertTrue(line.width() <= 60, line.width() + " > 60: " + line.text());
        long separators = lines.stream().filter(line -> line.text().startsWith("├")).count();
        assertEquals(1, separators, "only the header separator is needed for one data row");
    }

    @Test void headerCjkEmojiAndInlineStylesWrapAndStayStyled() {
        var header = List.<List<TextRenderResult.Token>>of(
                List.<TextRenderResult.Token>of(new TextRenderResult.Text("很长的中文表头")),
                List.<TextRenderResult.Token>of(new TextRenderResult.Text("Emoji 🙂 header")));
        var row = List.<List<TextRenderResult.Token>>of(
                List.<TextRenderResult.Token>of(new TextRenderResult.Strong(List.of(new TextRenderResult.Text("粗体内容")))),
                List.<TextRenderResult.Token>of(
                        new TextRenderResult.Link("https://example.test", List.of(new TextRenderResult.Text("link target"))),
                        new TextRenderResult.Code("code", "", false)));
        var table = new TextRenderResult.Table(header, List.of(row), List.of("left", "left"), "");
        var lines = layout(table, 28);
        String all = text(lines);
        assertTrue(all.contains("很长的中文") || (all.contains("很长的") && all.contains("中文表")), all);
        assertTrue(all.contains("Emoji"), all);
        assertTrue(all.contains("link") && all.contains("target"), all);
        assertTrue(lines.stream().noneMatch(line -> line.text().contains("🙂") && line.text().contains("?")), all);
        assertTrue(lines.stream().flatMap(line -> line.spans().stream())
                .anyMatch(span -> span.style().bold()), "strong style must survive cell wrapping");
        assertTrue(lines.stream().flatMap(line -> line.spans().stream())
                .anyMatch(span -> span.link() != null), "link metadata must survive cell wrapping");
        for (StyledLine line : lines) assertTrue(line.width() <= 28, line.width() + ": " + line.text());
    }

    @Test void tableWithoutDataRowsStillClosesBottom() {
        var table = new TextRenderResult.Table(
                List.<List<TextRenderResult.Token>>of(List.<TextRenderResult.Token>of(new TextRenderResult.Text("Only"))), List.of(), List.of("left"), "");
        var lines = layout(table, 40);
        assertTrue(lines.stream().anyMatch(line -> line.text().startsWith("┌")));
        assertTrue(lines.stream().anyMatch(line -> line.text().startsWith("├")));
        assertTrue(lines.getLast().text().startsWith("└"), lines.getLast().text());
        assertTrue(text(lines).contains("Only"));
    }

    @Test void narrowTableFallsBackToPreservedRawSource() {
        String raw = "| left | right |\n|---|---|\n| " + "LONGVALUE".repeat(20) + " | short |";
        var table = new TextRenderResult.Table(
                List.of(List.of(new TextRenderResult.Text("left")), List.of(new TextRenderResult.Text("right"))),
                List.of(List.of(List.of(new TextRenderResult.Text("LONGVALUE".repeat(20))),
                        List.of(new TextRenderResult.Text("short")))),
                List.of("left", "left"), raw);
        var lines = layout(table, 8);
        String all = text(lines);
        assertTrue(all.contains("LONGVAL"), all);
        assertFalse(lines.stream().anyMatch(line -> line.text().startsWith("┌")), "too narrow must use raw fallback");
        for (StyledLine line : lines) assertTrue(line.width() <= 8, line.width() + ": " + line.text());
    }

    @Test void resizeRebuildsWithinBudgetAndKeepsTailVisible() {
        String longText = "prefix-" + "y".repeat(90) + "-END";
        var table = new TextRenderResult.Table(
                List.of(List.of(new TextRenderResult.Text("Head one")), List.of(new TextRenderResult.Text("Head two"))),
                List.of(List.of(List.of(new TextRenderResult.Text(longText)), List.of(new TextRenderResult.Text("x")))),
                List.of("left", "left"), "");
        for (int width : List.of(80, 40, 24)) {
            var lines = layout(table, width);
            assertTrue(text(lines).contains("-END"), "width " + width + " lost the tail: " + text(lines));
            for (StyledLine line : lines) assertTrue(line.width() <= width, width + ": " + line.text());
            assertTrue(lines.getLast().text().startsWith("└"));
        }
    }
}
