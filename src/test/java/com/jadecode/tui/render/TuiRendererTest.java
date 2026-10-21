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
