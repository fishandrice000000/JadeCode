package com.jadecode.tui.layout;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** 第八轮共享卡片几何：边框/padding/正文坐标/最小降级/显式背景保留。 */
class CardFrameTest {
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
    private final RenderContext context = new RenderContext(80, 24, theme);

    @Test void measureUsesDisplayColumnsAndReservesHeaderFooter() {
        var wide = CardFrame.measure(80, 24, 1, true);
        assertEquals(2, wide.padding());
        assertEquals(74, wide.contentWidth());
        assertEquals(3, wide.contentX());
        assertEquals(24 - 2 - 1 - 1, wide.bodyRows());
        assertFalse(wide.minimal());

        var narrow = CardFrame.measure(12, 5, 0, false);
        assertEquals(1, narrow.padding());
        assertEquals(8, narrow.contentWidth());
        assertEquals(2, narrow.contentX());
        assertEquals(3, narrow.bodyRows());

        assertTrue(CardFrame.measure(9, 20, 0, false).minimal(), "too narrow must degrade safely");
        assertTrue(CardFrame.measure(40, 2, 0, false).minimal(), "too short must degrade safely");
    }

    @Test void composeProducesClosedFrameWithOpaquePanelBackground() {
        var metrics = CardFrame.measure(80, 12, 1, true);
        var body = List.of(StyledLine.of("body", theme.style("text")), StyledLine.empty());
        var header = List.of(StyledLine.of("header", theme.style("accent")));
        var footer = StyledLine.of("hint", theme.style("dim"));
        var lines = CardFrame.compose("Title", "Esc close", header, body, footer, metrics, context);
        assertEquals(2 + 1 + 2 + 1, lines.size(), "短页按内容收缩，不补到预算上限");
        assertTrue(lines.stream().allMatch(line -> line.width() == 80));
        assertTrue(lines.getFirst().text().startsWith("┌─ Title"), lines.getFirst().text());
        assertTrue(lines.getLast().text().startsWith("└") && lines.getLast().text().endsWith("┘"));
        var panel = theme.color("panelBg");
        assertTrue(lines.stream().flatMap(line -> line.spans().stream())
                .allMatch(span -> span.style().bg().equals(panel)), "padding and blank cells share panelBg");
        var title = lines.getFirst().spans().stream().filter(span -> span.text().contains("Title"))
                .findFirst().orElseThrow();
        assertTrue(title.style().bold());
        assertEquals(theme.style("accent").fg(), title.style().fg());
        assertFalse(lines.getFirst().spans().getFirst().style().bold(), "border stays normal weight");
    }

    @Test void explicitButtonBackgroundSurvivesPanelFill() {
        var metrics = CardFrame.measure(60, 10, 0, true);
        var green = new Span("Allow once", Style.PLAIN.withBg(theme.color("success")),
                null);
        var lines = CardFrame.compose("Approval", "Esc deny", List.of(),
                List.of(new StyledLine(List.of(green))), null, metrics, context);
        var span = lines.stream().flatMap(line -> line.spans().stream())
                .filter(value -> value.text().contains("Allow once")).findFirst().orElseThrow();
        assertEquals(theme.color("success"), span.style().bg(), "explicit selection color must not be overwritten");
    }

    @Test void minimalModeKeepsOneReadableLine() {
        var metrics = CardFrame.measure(8, 3, 1, true);
        assertTrue(metrics.minimal());
        var lines = CardFrame.compose("Model", "Esc close", List.of(), List.of(), null, metrics, context);
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().width() <= 8);
        assertTrue(lines.getFirst().text().contains("Model"));
    }

    @Test void piSectionUsesTopRuleWithRightAlignedTitleAndNoSideBorders() {
        var metrics = CardFrame.measure(80, 12, 1, true, CardFrame.Surface.PI_SECTION);
        assertEquals(0, metrics.contentX());
        assertEquals(80, metrics.contentWidth());
        assertEquals(0, metrics.padding());
        assertEquals(1, metrics.headerRows());
        assertEquals(12 - 2 - 1 - 1, metrics.bodyRows());
        assertEquals(CardFrame.Surface.PI_SECTION, metrics.surface());
        var lines = CardFrame.compose("Settings", "Esc close",
                List.of(StyledLine.of("nav", theme.style("accent"))),
                List.of(StyledLine.of("row", theme.style("text"))),
                StyledLine.of("hint", theme.style("dim")), metrics, context);
        // 上横线：左横线 + 右上标题 + 末尾一列横线；总宽恰为 80。
        String top = lines.getFirst().text();
        assertEquals(80, lines.getFirst().width());
        assertTrue(top.startsWith("──"), top);
        assertTrue(top.endsWith("─"), top);
        assertTrue(top.contains(" Settings "), top);
        assertTrue(top.indexOf("Settings") > 40, "title is right-aligned: " + top);
        assertEquals("─".repeat(80), lines.getLast().text());
        assertTrue(lines.stream().noneMatch(line -> line.text().startsWith("│")), "no side borders");
        assertTrue(lines.stream().flatMap(line -> line.spans().stream())
                .allMatch(span -> span.style().bg().isDefault()), "no panel background fill");
        var title = lines.getFirst().spans().stream().filter(span -> span.text().contains("Settings"))
                .findFirst().orElseThrow();
        assertTrue(title.style().bold());
        assertEquals(theme.style("accent").fg(), title.style().fg());
        assertFalse(lines.getFirst().spans().getFirst().style().bold(), "rule stays normal weight");
    }

    @Test void piSectionDegradesToASingleReadableLineOnTinyWidth() {
        var metrics = CardFrame.measure(3, 8, 0, false, CardFrame.Surface.PI_SECTION);
        assertTrue(metrics.minimal());
        var lines = CardFrame.compose("Model", "Esc close", List.of(), List.of(), null, metrics, context);
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().width() <= 3);
    }

    @Test void approvalSurfaceStillPaintsClosedBoxWithExplicitButtonBackground() {
        var metrics = CardFrame.measure(60, 10, 0, true, CardFrame.Surface.APPROVAL_BOX);
        var green = new Span("Allow once", Style.PLAIN.withBg(theme.color("approvalOnceBg")), null);
        var lines = CardFrame.compose("Approval", "Esc deny", List.of(),
                List.of(new StyledLine(List.of(green))), null, metrics, context);
        assertTrue(lines.getFirst().text().startsWith("┌─ Approval"), lines.getFirst().text());
        assertTrue(lines.getLast().text().startsWith("└") && lines.getLast().text().endsWith("┘"));
        var span = lines.stream().flatMap(line -> line.spans().stream())
                .filter(value -> value.text().contains("Allow once")).findFirst().orElseThrow();
        assertEquals(theme.color("approvalOnceBg"), span.style().bg(),
                "explicit button background must survive the closed-box fill");
    }

    @Test void noColorMakesPanelBackgroundPlain() {
        var plain = new Theme(theme.name(), theme.appearance(), theme.colors(), false, 1);
        var lines = CardFrame.compose("Title", "Esc close", List.of(), List.of(StyledLine.of("x", Style.PLAIN)),
                null, CardFrame.measure(40, 8, 0, false), new RenderContext(40, 8, plain));
        assertTrue(lines.stream().flatMap(line -> line.spans().stream())
                .allMatch(span -> span.style().isPlain()), "NO_COLOR must not emit color/attribute escapes");
    }
}
