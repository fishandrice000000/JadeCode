package com.jadecode.cli.tui.view;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.cli.tui.model.TranscriptEntry;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** R2-05：缓存冷/热/失效路径必须产生完全相同的文档几何与阅读锚点。 */
class TuiConversationCacheTest {
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
    private final RenderContext context = new RenderContext(80, 20, theme);

    private TuiState state(int replies) {
        var state = new TuiState();
        state.installGeneration(0);
        for (int i = 0; i < replies; i++)
            state.transcript.addHistorical("leader", TranscriptEntry.Kind.REPLY, "Reply",
                    "reply " + i + "\n\nsecond paragraph " + i, "DONE");
        return state;
    }

    private static List<String> texts(List<StyledLine> lines) {
        return lines.stream().map(StyledLine::text).toList();
    }

    @Test void coldCachedAndInvalidatedDocumentsAreLineForLineIdentical() {
        var state = state(3);
        var view = new ConversationView();
        var cold = view.render(state, context, List.of(), false);
        var cached = view.render(state, context, List.of(), false);
        view.invalidateLayout();
        var invalidated = view.render(state, context, List.of(), false);
        assertEquals(texts(cold), texts(cached), "cache hit must keep separator lines");
        assertEquals(texts(cold), texts(invalidated), "invalidation must rebuild identical geometry");
        assertEquals(cold.size(), cached.size());
    }

    @Test void appendingAtEndDoesNotMoveHistoryViewport() {
        var state = state(12);
        var view = new ChatViewport(new ConversationView(), ChatViewport.Scrollbar.AUTO, false);
        view.render(state, context, 10, List.of(), false, true);
        view.scrollLines(-5);
        String topBefore = view.render(state, context, 10, List.of(), false, true).getFirst().text();
        state.transcript.addHistorical("leader", TranscriptEntry.Kind.REPLY, "Reply", "new tail reply", "DONE");
        List<StyledLine> after = view.render(state, context, 10, List.of(), false, true);
        assertEquals(topBefore, after.getFirst().text(), "history top must stay stable when tail grows");
        assertFalse(view.scrollState().following());
    }

    @Test void markdownRepaintAboveViewportKeepsAnchor() {
        var state = state(8);
        var view = new ChatViewport(new ConversationView(), ChatViewport.Scrollbar.AUTO, false);
        view.render(state, context, 10, List.of(), false, true);
        view.scrollLines(-3);
        String topBefore = view.render(state, context, 10, List.of(), false, true).getFirst().text();
        // 模拟引擎异步结果到达：只清组件排版缓存，保留 DTO，并让一个更早条目重排。
        var first = state.transcript.entries().getFirst();
        first.append("\n\nasynchronously appended paragraph");
        view.invalidate();
        view.render(state, context, 10, List.of(), false, true);
        List<StyledLine> after = view.render(state, context, 10, List.of(), false, true);
        assertFalse(after.isEmpty());
        assertTrue(after.stream().limit(3).anyMatch(line -> line.text().contains("Reply")),
                "viewport must stay near the anchored history entry: " + topBefore + " / " + texts(after));
    }

    @Test void returningToBottomResumesFollow() {
        var state = state(12);
        var view = new ChatViewport(new ConversationView(), ChatViewport.Scrollbar.AUTO, false);
        view.render(state, context, 10, List.of(), false, true);
        view.scrollLines(-6);
        assertFalse(view.scrollState().following());
        view.bottom();
        assertTrue(view.scrollState().following());
        state.transcript.addHistorical("leader", TranscriptEntry.Kind.REPLY, "Reply", "tail", "DONE");
        List<StyledLine> after = view.render(state, context, 10, List.of(), false, true);
        assertEquals(10, after.size());
    }
    @Test void pureScrollingReusesBothWidthsEvenBeyondTheOldEntryLimit() {
        var state = state(600);
        var view = new ConversationView();
        var full = view.render(state, context, List.of(), false);
        var narrow = view.render(state, context.withWidth(79), List.of(), false);
        assertSame(full, view.render(state, context, List.of(), false));
        assertSame(narrow, view.render(state, context.withWidth(79), List.of(), false));
        // 末尾变化只替换末尾块；第一条消息的样式/元数据仍是同一份不可变行。
        state.transcript.entries().getLast().append(" appended");
        var changed = view.render(state, context, List.of(), false);
        int first = view.entryStartRows().get(state.transcript.entries().getFirst().id());
        assertSame(full.get(first), changed.get(first));
        assertNotEquals(texts(full), texts(changed));
    }

}
