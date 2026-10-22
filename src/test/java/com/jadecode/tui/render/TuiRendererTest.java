package com.jadecode.tui.render;

import static org.junit.jupiter.api.Assertions.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.PresentedFrame;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** regular/fullscreen renderer 的写屏/证据/失效/首帧行为测试（内存 writer）。 */
class TuiRendererTest {
    @Test void regularLayerUsesBlankWorkingAreaAndRestoresWithoutGrowingDocument() throws Exception {
        for (int count : List.of(0, 2)) {
            output.getBuffer().setLength(0);
            var renderer = new RegularRenderer(port, true, true, true);
            var context = new RenderContext(40, 10, theme);
            var document = java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> StyledLine.of("short-" + i, Style.PLAIN)).toList();
            var layer = new Frame.ScreenLayer(new com.jadecode.tui.geometry.Rect(4, 5, 20, 2),
                    List.of(StyledLine.of("PANEL", Style.PLAIN), StyledLine.of("BOTTOM", Style.PLAIN)),
                    Frame.NO_CURSOR, List.of(), 1);
            var frame = new Frame(1, 1, 1, document, Frame.NO_CURSOR, List.of(), null, layer);
            renderer.present(frame, context);
            assertTrue(output.toString().contains("    PANEL"), output.toString());
            assertTrue(output.toString().contains("BOTTOM"));
            assertEquals(count, frame.lines().size(), "temporary blanks must not extend the document");
            output.getBuffer().setLength(0);
            renderer.present(new Frame(2, 1, 2, document, Frame.NO_CURSOR, List.of()), context);
            assertFalse(output.toString().contains("PANEL"));
            assertTrue(output.toString().contains("\u001b[J"), "closing must clear the temporary working area");
            assertFalse(output.toString().contains("\u001b[3J"), "opening/closing must not clear native history");
            renderer.close();
        }
    }
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
    private final StringWriter output = new StringWriter();
    private final ScreenRenderer.Port port = new ScreenRenderer.Port() {
        @Override public int columns() { return 40; }
        @Override public int rows() { return 10; }
        @Override public long epoch() { return 1; }
        @Override public PrintWriter writer() { return new PrintWriter(output, true); }
        @Override public boolean outputFailed() { return false; }
        @Override public boolean colorsEnabled() { return true; }
    };

    private Frame frame(long revision, String text) {
        return new Frame(1, 1, revision, List.of(StyledLine.of(text, Style.PLAIN)), new Frame.Cursor(0, 0, true), List.of());
    }

    @Test void fullscreenPublishesOnlyAfterSuccessfulFlushAndInvalidates() throws Exception {
        var renderer = new FullscreenRenderer(port, true, true, true, true);
        var context = new RenderContext(40, 10, theme);
        PresentedFrame presented = renderer.present(frame(1, "hello"), context);
        assertNotNull(presented);
        assertTrue(output.toString().contains("hello"));
        assertTrue(output.toString().contains("1049h"));
        renderer.invalidate();
        assertNull(renderer.presented());
        assertEquals(2, renderer.present(frame(2, "world"), context).frameId());
        renderer.finish();
        assertTrue(output.toString().contains("1049l"));
        renderer.close();
    }

    @Test void regularFirstFrameDoesNotClearScrollbackButResizeDoes() throws Exception {
        var renderer = new RegularRenderer(port, true, true, true);
        renderer.present(frame(1, "first"), new RenderContext(40, 10, theme));
        assertFalse(output.toString().contains("\u001b[3J"));
        output.getBuffer().setLength(0);
        renderer.present(frame(2, "second"), new RenderContext(41, 10, theme));
        assertTrue(output.toString().contains("\u001b[3J"));
        renderer.close();
    }

    @Test void regularWritesCompleteDocumentBeyondScreenHeight() throws Exception {
        var renderer = new RegularRenderer(port, true, true, true);
        var lines = new java.util.ArrayList<StyledLine>();
        for (int i = 0; i < 100; i++) lines.add(StyledLine.of("row-" + i, Style.PLAIN));
        var document = new Frame(1, 1, 1, lines, Frame.NO_CURSOR, List.of());
        renderer.present(document, new RenderContext(40, 10, theme));
        assertTrue(output.toString().contains("row-99"), "regular must hand the full document to the renderer");
        assertTrue(output.toString().contains("row-10"));
        renderer.close();
    }

    @Test void regularFlashIsScreenLayerAndExpiryRestoresDocumentLine() throws Exception {
        var renderer = new RegularRenderer(port, true, true, true);
        var lines = new java.util.ArrayList<StyledLine>();
        for (int i = 0; i < 12; i++) lines.add(StyledLine.of("row-" + i, Style.PLAIN));
        var flash = new Frame.Flash("Copied!", theme.style("success"));
        var withFlash = new Frame(1, 1, 1, lines, Frame.NO_CURSOR, List.of(), flash);
        renderer.present(withFlash, new RenderContext(40, 10, theme));
        assertTrue(output.toString().contains("Copied!"), output.toString());
        assertEquals("row-0", withFlash.lines().getFirst().text(), "flash must not mutate document lines");
        output.getBuffer().setLength(0);
        var withoutFlash = new Frame(2, 1, 2, lines, Frame.NO_CURSOR, List.of());
        renderer.present(withoutFlash, new RenderContext(40, 10, theme));
        assertFalse(output.toString().contains("Copied!"), "expiry must clear the overlay");
        assertTrue(output.toString().contains("row-2"), output.toString());
        assertFalse(output.toString().contains("\u001b[3J"));
        renderer.close();
    }

    @Test void regularScreenLayerDrawsOverVisibleRowsAndRestoresThem() throws Exception {
        var renderer = new RegularRenderer(port, true, true, true);
        var lines = new java.util.ArrayList<StyledLine>();
        for (int i = 0; i < 30; i++) lines.add(StyledLine.of("doc-" + i, Style.PLAIN));
        var layerLines = java.util.List.of(StyledLine.of("PANEL-A", Style.PLAIN), StyledLine.of("PANEL-B", Style.PLAIN));
        var layer = new Frame.ScreenLayer(new com.jadecode.tui.geometry.Rect(2, 3, 20, 2),
                layerLines, Frame.NO_CURSOR, java.util.List.of(), 1);
        var withLayer = new Frame(1, 1, 1, lines, Frame.NO_CURSOR, java.util.List.of(), null, layer);
        renderer.present(withLayer, new RenderContext(40, 10, theme));
        assertTrue(output.toString().contains("PANEL-A"), output.toString());
        assertTrue(output.toString().contains("doc"), "layer must preserve document content outside its rectangle");
        output.getBuffer().setLength(0);
        var withoutLayer = new Frame(2, 1, 2, lines, Frame.NO_CURSOR, java.util.List.of());
        renderer.present(withoutLayer, new RenderContext(40, 10, theme));
        assertFalse(output.toString().contains("PANEL-A"), "layer must be removed from the visible area");
        assertTrue(output.toString().contains("doc-24") || output.toString().contains("doc-25"), output.toString());
        renderer.close();
    }

    @Test void regularAppendsWithoutFullClearWhenTailChanges() throws Exception {
        var renderer = new RegularRenderer(port, true, true, true);
        renderer.present(frame(1, "one"), new RenderContext(40, 10, theme));
        output.getBuffer().setLength(0);
        renderer.present(frame(2, "one\ntwo"), new RenderContext(40, 10, theme));
        assertFalse(output.toString().contains("\u001b[3J"));
        assertTrue(output.toString().contains("two"));
        renderer.close();
    }
}
