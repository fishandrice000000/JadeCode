package com.jadecode.tui.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.components.Spacer;
import com.jadecode.tui.components.Text;
import com.jadecode.tui.geometry.Insets;
import com.jadecode.tui.geometry.Rect;
import com.jadecode.tui.layout.Box;
import com.jadecode.tui.layout.Stack;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;
import com.jadecode.tui.viewport.ScrollView;
import com.jadecode.tui.viewport.SelectionModel;
import com.jadecode.tui.viewport.SearchModel;

/** 布局/Frame 证据/滚动/选择/搜索的单元测试。 */
class TuiLayoutFrameTest {
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);

    @Test void boxPadsAndFillsBackgroundToFullWidth() {
        var box = Box.background(new Text(List.of(StyledLine.of("hi", Style.PLAIN)), Text.Align.LEFT, null),
                Insets.symmetric(1, 1), "selectedBg");
        var lines = box.render(new RenderContext(10, 5, theme), 10);
        assertEquals(3, lines.size());
        assertEquals("", lines.getFirst().text().strip());
        assertEquals("hi", lines.get(1).text().strip());
        assertEquals(10, lines.get(1).width());
        assertEquals(10, lines.getLast().width());
    }

    @Test void horizontalStackAllocatesAllColumnsAndPadsShortChildren() {
        var stack = Stack.horizontal(new Text(List.of(StyledLine.of("ab", Style.PLAIN)), Text.Align.LEFT, null),
                new Spacer(0), new Text(List.of(StyledLine.of("cd", Style.PLAIN)), Text.Align.RIGHT, null));
        var lines = stack.render(new RenderContext(11, 1, theme), 11);
        assertEquals(1, lines.size());
        assertEquals(11, lines.getFirst().width());
        assertTrue(lines.getFirst().text().startsWith("ab"));
        assertTrue(lines.getFirst().text().endsWith("cd"));
    }

    @Test void frameHitAndPresentedEvidenceUseSameRevision() {
        var token = new ActionToken(ActionToken.Kind.APPROVE, "req-1", 4);
        var frame = new Frame(7, 2, 4, List.of(StyledLine.empty()),
                new Frame.Cursor(0, 0, true), List.of(new Frame.Hit(new Rect(1, 0, 5, 1), token)));
        assertEquals(token, frame.actionAt(2, 0));
        assertNull(frame.actionAt(0, 0));
        assertTrue(frame.contains(token));
        var presented = new PresentedFrame(7, 2, 80, 24, 4, List.of(token));
        assertTrue(presented.contains(token, 2, 4));
        assertFalse(presented.contains(token, 3, 4));
        assertFalse(presented.contains(token, 2, 5));
        assertTrue(new ActionToken(ActionToken.Kind.DENY, "x", 4).validAt(4));
        assertFalse(new ActionToken(ActionToken.Kind.DENY, "x", 4).validAt(5));
    }

    @Test void scrollViewFollowsEndUntilManualScrollThenJumpRestores() {
        var doc = new java.util.ArrayList<StyledLine>();
        for (int i = 0; i < 20; i++) doc.add(StyledLine.of("line " + i, Style.PLAIN));
        var view = new ScrollView();
        var visible = view.view(doc, 5);
        assertEquals(15, view.offset());
        assertEquals("line 15", visible.getFirst().text());
        view.scroll(-3, doc.size(), 5);
        assertFalse(view.following());
        assertEquals(12, view.offset());
        doc.add(StyledLine.of("line 20", Style.PLAIN));
        view.view(doc, 5);
        assertEquals(12, view.offset());
        view.bottom(doc.size(), 5);
        assertTrue(view.following());
        assertEquals(16, view.offset());
    }

    @Test void selectionExtractsWideCharTextWithoutMarkers() {
        var selection = new SelectionModel();
        selection.start(new SelectionModel.Position(0, 1), 1);
        selection.drag(new SelectionModel.Position(1, 2));
        selection.finish();
        var document = List.of(StyledLine.of("a中b", Style.PLAIN), StyledLine.of("xy中", Style.PLAIN));
        var range = selection.rangeFor(0, "a中b");
        assertEquals(1, range.fromColumn());
        assertEquals(4, range.toColumn());
        assertEquals("中b\nxy", selection.text(document));
    }

    @Test void searchFindsAcrossWholeDocumentAndCycles() {
        var model = new SearchModel();
        var document = List.of(StyledLine.of("alpha", Style.PLAIN), StyledLine.of("beta alpha", Style.PLAIN));
        model.search("alpha", document, 1);
        assertEquals(2, model.size());
        assertEquals(0, model.current().row());
        assertEquals(1, model.next().row());
        assertEquals(0, model.next().row());
        assertEquals(1, model.previous().row());
        assertTrue(model.isCurrent(1, 5));
    }

    @Test void extremeWidthsDoNotThrowOrOverflow() {
        var context = new RenderContext(1, 1, theme);
        var text = Text.line("中文🙂", Style.PLAIN).render(context, 1);
        assertEquals(1, text.size());
        assertTrue(text.getFirst().width() <= 1);
        assertTrue(new Box(Text.line("x", Style.PLAIN), Insets.all(5), null, true)
                .render(context, 1).stream().allMatch(line -> line.width() <= 1));
    }
}
