package com.jadecode.cli.tui.view;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.markdown.JsTextEngine;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** 产品投影到固定引擎的宿主接线：Repaint 后 ConversationView 必须重排并显示高亮作用域。 */
class TuiMarkdownProjectionTest {
    private static JsTextEngine engine;

    @BeforeAll static void start() throws Exception { engine = JsTextEngine.create(); }

    @AfterAll static void stop() { if (engine != null) engine.close(); }

    @Test void conversationViewRendersEngineHighlightAfterRepaint() throws Exception {
        var repainted = new CountDownLatch(1);
        var view = new ConversationView(() -> engine, repainted::countDown);
        var state = new TuiState();
        state.installGeneration(0);
        state.transcript.addHistorical("leader", com.jadecode.cli.tui.model.TranscriptEntry.Kind.REPLY,
                "Reply", "```java\nint value = 1; // note\n```", "DONE");
        var theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
        var context = new RenderContext(80, 24, theme);
        view.render(state, context, List.of(), false);
        assertTrue(repainted.await(30, TimeUnit.SECONDS), "engine result must trigger repaint");
        // 普通 Repaint 不清会话缓存：消息自身 parsedRevision 必须使固定结果可见。
        List<StyledLine> lines = view.render(state, context, List.of(), false);
        var keywordFg = theme.style("syntaxKeyword").fg();
        assertTrue(lines.stream().flatMap(line -> line.spans().stream())
                .anyMatch(span -> span.style().fg().equals(keywordFg)), "expected keyword highlight");
        assertTrue(lines.stream().anyMatch(line -> line.text().contains("int value")));
    }
}
