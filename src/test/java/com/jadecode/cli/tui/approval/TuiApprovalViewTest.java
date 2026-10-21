package com.jadecode.cli.tui.approval;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.cli.tui.UiEvent;
import com.jadecode.tui.core.ActionToken;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.PresentedFrame;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.geometry.Rect;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** 审批显示证据/水位/无默认/小窗不发布批准动作的测试。 */
class TuiApprovalViewTest {
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
    private final UiEvent.ApprovalRequested request = new UiEvent.ApprovalRequested("id-1", "leader",
            "bash", "printf 'x'\nrm -rf /tmp/demo", "Needs confirmation", "bash: rm", 7L);

    private PresentedFrame frame(ActionToken.Kind kind, long revision) {
        var token = new ActionToken(kind, "id-1", revision);
        var frame = new Frame(3, 1, 1, List.of(StyledLine.empty()), Frame.NO_CURSOR,
                List.of(new Frame.Hit(new Rect(0, 0, 10, 1), token)));
        return new PresentedFrame(3, 1, 80, 24, 1, List.of(token));
    }

    @Test void noDefaultChoiceAndSuggestionGating() {
        var state = new ApprovalViewState();
        state.show(request, 0, 1);
        assertEquals(-1, state.choice());
        assertNull(state.selectedAnswer());
        state.move(1, true);
        assertEquals(0, state.choice());
        state.move(1, false);
        assertEquals(2, state.choice());
        state.move(-1, false);
        assertEquals(0, state.choice());
    }

    @Test void approvalRequiresPresentedFrameAndLaterInputWatermark() {
        var state = new ApprovalViewState();
        state.show(request, 0, 1);
        var accept = frame(ActionToken.Kind.APPROVE, 7);
        assertFalse(state.canAnswer(11, 1, accept, ActionToken.Kind.APPROVE));
        state.presented(accept, 1, 10);
        assertTrue(state.presented());
        assertTrue(state.canAnswer(11, 1, accept, ActionToken.Kind.APPROVE));
        assertFalse(state.canAnswer(10, 1, accept, ActionToken.Kind.APPROVE));
        assertFalse(state.canAnswer(11, 2, accept, ActionToken.Kind.APPROVE));
        assertFalse(state.canAnswer(11, 1, accept, ActionToken.Kind.DENY));
    }

    @Test void resizeInvalidatesEvidenceAndSmallWindowHasNoButtons() {
        var state = new ApprovalViewState();
        state.show(request, 0, 1);
        state.presented(frame(ActionToken.Kind.APPROVE, 7), 1, 4);
        assertTrue(state.presented());
        state.invalidate();
        assertFalse(state.presented());
        var view = new ApprovalView();
        var small = view.render(state, new RenderContext(12, 6, theme), 12, 4);
        assertTrue(small.buttons().isEmpty());
        assertTrue(small.lines().stream().anyMatch(line -> line.text().contains("Window")));
    }

    @Test void fullParametersAreVisibleAndScrollable() {
        var state = new ApprovalViewState();
        state.show(request, 0, 1);
        var rendered = new ApprovalView().render(state, new RenderContext(60, 20, theme), 60, 20);
        String all = rendered.lines().stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("Parameters:"));
        assertTrue(all.contains("rm -rf /tmp/demo"));
        assertTrue(all.contains("Long-term scope:"));
        assertTrue(state.totalLines() > 0);
        state.page(2, 100);
        state.scroll(1);
        assertEquals(1, state.offset());
    }
}
