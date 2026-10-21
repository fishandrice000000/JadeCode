package com.jadecode.tui.text;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.geometry.Insets;
import com.jadecode.tui.geometry.Rect;

/** 终端文本几何/宽表/净化/链接的单元测试；不依赖真实终端。 */
class TuiTextGeometryTest {
    @Test void rectIntersectsAndInsetsClamp() {
        Rect a = new Rect(2, 3, 10, 5);
        assertEquals(new Rect(5, 5, 7, 3), a.intersect(new Rect(5, 5, 20, 20)));
        assertEquals(Rect.EMPTY, a.intersect(new Rect(20, 20, 1, 1)));
        assertTrue(a.inset(Insets.all(100)).isEmpty());
        assertTrue(a.contains(2, 3));
        assertFalse(a.contains(12, 3));
    }

    @Test void graphemesKeepEmojiAndCombiningMarksWhole() {
        String text = "a👩‍👩‍👧‍👦e\u0301中";
        assertEquals(4, DisplayWidth.clusters(text).size());
        assertEquals(2, DisplayWidth.clusterWidth("👩‍👩‍👧‍👦"));
        assertEquals(1, DisplayWidth.clusterWidth("e\u0301"));
        assertEquals(2, DisplayWidth.clusterWidth("中"));
        assertEquals(6, DisplayWidth.width(text));
        assertEquals("a👩‍👩‍👧‍👦", DisplayWidth.take(text, 3));
        assertEquals("a…", DisplayWidth.ellipsize("a👩‍👩‍👧‍👦", 2));
    }

    @Test void offsetAndColumnConversionsRoundTripAtBoundaries() {
        String text = "👩‍👩‍👧‍👦x中";
        assertEquals(0, DisplayWidth.columnAtOffset(text, 0));
        assertEquals(2, DisplayWidth.columnAtOffset(text, 11));
        assertEquals(3, DisplayWidth.columnAtOffset(text, 12));
        assertEquals(0, DisplayWidth.offsetAtColumn(text, 1));
        assertEquals(11, DisplayWidth.offsetAtColumn(text, 2));
        assertEquals(12, DisplayWidth.offsetAtColumn(text, 3));
        assertEquals(12, DisplayWidth.offsetAtColumn(text, 4));
        assertEquals(13, DisplayWidth.offsetAtColumn(text, 5));
        assertEquals(12, DisplayWidth.previousOffset(text, 13));
    }

    @Test void styledLineWrapPreservesStylesAndLinksAndDoesNotSplitWideChars() {
        LinkTarget link = new LinkTarget("https://example.com/x");
        Span span = new Span("ab中文", Style.fg(Color.rgb(1, 2, 3)).withBold(true), link);
        List<StyledLine> lines = StyledLine.wrap(List.of(span), 4);
        assertEquals(2, lines.size());
        assertEquals("ab中", lines.get(0).text());
        assertEquals("文", lines.get(1).text());
        assertEquals(link, lines.get(0).spans().getFirst().link());
        assertTrue(lines.get(0).spans().getFirst().style().bold());
        assertEquals("ab", new StyledLine(List.of(span)).crop(2).text());
        assertEquals(4, lines.get(0).width());
    }

    @Test void sanitizerRemovesEscapesButApprovalVisibleKeepsThemReadable() {
        String hostile = "ok\u001b]52;c;cGF5bG9hZA==\u0007\u001b[31mred\u001b[0m\u009b2J";
        assertEquals("okred", TextSanitizer.display(hostile));
        String visible = TextSanitizer.visible(hostile);
        assertTrue(visible.contains("\\x1b]52;"));
        assertTrue(visible.contains("\\x1b[31m"));
        assertFalse(visible.contains("\u001b"));
        assertEquals("ok", TextSanitizer.display("ok\u001b]0;title\u0007"));
    }

    @Test void linksRejectControlsAndJavascript() {
        assertNull(LinkTarget.of("java\nscript:alert(1)"));
        assertNull(LinkTarget.of("javascript:alert(1)"));
        assertNotNull(LinkTarget.of("https://example.com/a"));
        assertEquals(1, DisplayWidth.width("¢"));
    }

    @Test void widthTableIsLoadedWithFixedVersion() {
        assertEquals("1.6.0", TerminalWidthTable.version());
        assertEquals(2, TerminalWidthTable.eastAsianWidth('中'));
        assertEquals(1, TerminalWidthTable.eastAsianWidth('a'));
    }
}
