package com.jadecode.tui.markdown;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.components.Markdown;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** R3-06：Java 回退 Document 与固定 JS 平铺块必须产生相同块间距，流式前缀不得反复变矮。 */
class MarkdownGeometryTest {
    private static JsTextEngine engine;
    private static final Theme THEME = Theme.from(ThemePalette.builtin("dark"), true, 1);

    @BeforeAll static void start() throws Exception { engine = JsTextEngine.create(); }
    @AfterAll static void stop() { if (engine != null) engine.close(); }

    private static List<String> texts(List<StyledLine> lines) {
        return lines.stream().map(StyledLine::text).toList();
    }

    private static void awaitParsed(Markdown markdown, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (markdown.parsedCount() < count) {
            if (System.nanoTime() > deadline) fail("fixed engine did not cache result " + count);
            Thread.sleep(10);
        }
    }

    @Test void javaFallbackAndFixedEngineAgreeOnParagraphSpacing() throws Exception {
        String source = "alpha\n\nbeta";
        var fallback = texts(new MarkdownLayout(THEME).render(source, 80, Style.PLAIN));
        var fixed = texts(new MarkdownLayout(THEME).layout(
                engine.lex(source, 1).get(30, TimeUnit.SECONDS), 80, Style.PLAIN));
        assertEquals(List.of("alpha", "", "beta"), fallback);
        assertEquals(fallback, fixed);
        for (String streaming : List.of("alpha\n\nbeta", "alpha\n\nbeta!")) {
            var javaLines = texts(new MarkdownLayout(THEME).render(streaming, 80, Style.PLAIN));
            var jsLines = texts(new MarkdownLayout(THEME).layout(
                    engine.lex(streaming, 2).get(30, TimeUnit.SECONDS), 80, Style.PLAIN));
            assertEquals(javaLines, jsLines, streaming);
            assertEquals(3, javaLines.size(), streaming);
        }
    }

    @Test void documentWrapperAndFlatBlocksHaveSameSpacingForListsAndCode() throws Exception {
        String source = "first\n\n- one\n- two\n\n```java\nint x = 1;\n```\n\nlast";
        var javaLines = texts(new MarkdownLayout(THEME).render(source, 80, Style.PLAIN));
        var jsLines = texts(new MarkdownLayout(THEME).layout(
                engine.lex(source, 1).get(30, TimeUnit.SECONDS), 80, Style.PLAIN));
        assertEquals(javaLines, jsLines);
        assertTrue(javaLines.contains(""), javaLines.toString());
    }

    @Test void componentKeepsGeometryAcrossFallbackFixedAppendAndNewFixedResult() throws Exception {
        var markdown = new Markdown(() -> engine, () -> { });
        String source = "alpha\n\nbeta";
        var fallback = texts(markdown.render(source, 80, THEME, false));
        assertEquals(List.of("alpha", "", "beta"), fallback);
        awaitParsed(markdown, 1);
        var fixed = texts(markdown.render(source, 80, THEME, false));
        assertEquals(fallback, fixed, "fixed result must not introduce a taller document");

        markdown.text(source + "!");
        var nextFallback = texts(markdown.render(source + "!", 80, THEME, false));
        assertEquals(3, nextFallback.size(), "new streaming prefix must not fall back to 2 lines");
        awaitParsed(markdown, 2);
        var nextFixed = texts(markdown.render(source + "!", 80, THEME, false));
        assertEquals(nextFallback, nextFixed, "new fixed result must keep same block spacing");
    }
}
