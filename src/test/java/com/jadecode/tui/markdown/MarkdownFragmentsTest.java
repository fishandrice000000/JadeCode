package com.jadecode.tui.markdown;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;
import com.jadecode.tui.text.Style;

/** 对照完整解析器逐前缀验语义；工作量用实际送入字符数衡量，不依赖计时断言。 */
class MarkdownFragmentsTest {
    private static JsTextEngine engine;
    private static final Theme THEME = Theme.from(ThemePalette.builtin("dark"), true, 1);
    @BeforeAll static void start() throws Exception { engine = JsTextEngine.create(); }
    @AfterAll static void stop() { engine.close(); }

    static List<String> corpus() {
        return List.of(
            "alpha\n\nbeta\n\n# heading\n\n**bold** and `code`",
            "first\n\n```java\nint[] x = {1};\n\n// [not a reference]\n```\n\nlast",
            "first\n\n````java\n```\n\nint x;\n````\n\nlast",
            "first\n\n```java\n    ```\n\nint x;\n```\n\nlast",
            "first\n\n```java\n\t```\n\nint x;\n```\n\nlast",
            "first\n\n```java\n```\u2003\n\nint x;\n```\n\nlast",
            "first\n\n~~~text\n\nbody\n~~~\n\nlast",
            "first\n\n[x][id]\n\n[id]: https://example.com",
            "first\n\n- one\n\n- two\n  continuation\n\nlast",
            "first\n\n1. one\n\n2. two",
            "first\n\n> quote\n\n> continued",
            "first\n\n<div>\n\ninside\n</div>\n\nlast",
            "first\n\na | b\n- | -\nlong value | tail",
            "first\n\nheading\n===\n\nlast",
            "first\n\nheading\n---\n\nlast",
            "first\n\n    indented\n\n    more",
            "first\n\n ```java\ntext\n ```\n\nlast",
            "first\n\n$$x + y$$\n\nlast",
            "first\n\n```mermaid\ngraph LR\nA-->B\n```\n\nlast",
            "first\n\nescaped\\\nline\n\nlast",
            "中文👩‍👩‍👧‍👦\n\ne\u0301 and https://example.com\n\nlast",
            "first\r\n\r\nsecond\r\n\r\nlast",
            "first\n\u2003\nsecond\n\nlast",
            "first\n\f\nsecond\n\nlast",
            "first\n\n\n\nlast\n\n\n");
    }

    @ParameterizedTest @MethodSource("corpus")
    void everyPrefixAgreesWithTheFullJavaAndFixedParsers(String source) throws Exception {
        var java = new MarkdownFragments();
        var javaLayout = new MarkdownLayout(THEME);
        var fixedLayout = new MarkdownLayout(THEME);
        for (int end = 0; end <= source.length(); end++) {
            String prefix = source.substring(0, end);
            var actualJava = java.parseJava(prefix);
            var fullJava = new JavaMarkdownParser(prefix).parse();
            var layout = new MarkdownLayout(THEME);
            assertEquals(layout.layout(fullJava, 40, Style.PLAIN), layout.layout(actualJava, 40, Style.PLAIN),
                    "Java prefix " + end + " of " + source);
            assertEquals(layout.layout(fullJava, 40, Style.PLAIN),
                    javaLayout.layoutCached(actualJava, 40, Style.PLAIN, false), "cached Java prefix " + end);
            var actualFixed = engine.lex(prefix, end).get(20, TimeUnit.SECONDS);
            var fullFixed = MarkedTokenAdapter.adapt(engine.lexJson(prefix).get(20, TimeUnit.SECONDS), end);
            assertEquals(fullFixed.tokens(), actualFixed.tokens(), "fixed prefix " + end + " of " + source);
            assertEquals(layout.layout(fullFixed, 40, Style.PLAIN),
                    fixedLayout.layoutCached(actualFixed, 40, Style.PLAIN, false), "cached fixed prefix " + end);
        }
    }

    @Test void stableUniqueParagraphsAndClosedCodeAreParsedOnceAndReusedInLayout() throws Exception {
        var java = new MarkdownFragments();
        long before = engine.lexedCharacters(), fullWork = 0;
        StringBuilder source = new StringBuilder();
        TextRenderResult last = null;
        for (int i = 0; i < 60; i++) {
            source.append("Paragraph ").append(i).append(" **bold** and `code`\n\n");
            fullWork += source.length();
            java.parseJava(source.toString());
            last = engine.lex(source.toString(), i).get(20, TimeUnit.SECONDS);
        }
        assertTrue(java.parsedCharacters() < fullWork / 10, "Java must reuse all completed unique blocks");
        assertTrue(engine.lexedCharacters() - before < fullWork / 10, "JS must avoid repeated full lex");
        var layout = new MarkdownLayout(THEME);
        var firstLines = layout.layoutCached(last, 60, THEME.style("text"), false);
        source.append("tail");
        var next = engine.lex(source.toString(), 61).get(20, TimeUnit.SECONDS);
        var nextLines = layout.layoutCached(next, 60, THEME.style("text"), false);
        assertSame(firstLines.getFirst(), nextLines.getFirst(), "stable block lines must be reused");
        assertEquals(new MarkdownLayout(THEME).layout(next, 60, THEME.style("text")), nextLines);
        // resize 与 thinking 改变布局参数，必须按原完整路径重新排；不冻结旧列宽或样式。
        assertEquals(new MarkdownLayout(THEME).layout(next, 17, THEME.style("thinkingText"), true),
                layout.layoutCached(next, 17, THEME.style("thinkingText"), true));
    }

    @Test void replacementAndLateReferenceDefinitionsInvalidateSafePrefix() throws Exception {
        var fragments = new MarkdownFragments();
        fragments.parseJava("prefix\n\nold");
        var replaced = fragments.parseJava("other\n\nnew");
        assertEquals(new MarkdownLayout(THEME).layout(new JavaMarkdownParser("other\n\nnew").parse(), 60, Style.PLAIN),
                new MarkdownLayout(THEME).layout(replaced, 60, Style.PLAIN));
        engine.lex("before\n\nplain\n\n", 1).get(20, TimeUnit.SECONDS);
        String source = "before\n\nplain\n\n[x][id]\n\n[id]: /actual";
        var actual = engine.lex(source, 2).get(20, TimeUnit.SECONDS);
        assertEquals(MarkedTokenAdapter.adapt(engine.lexJson(source).get(20, TimeUnit.SECONDS), 2).tokens(), actual.tokens());
    }

    @Test void completedCodeWithInternalBlankLinesIsNotHighlightedAgainWhenTextIsAppended() throws Exception {
        String code = "```java\nint[] values = {1, 2};\n\n// ordinary comment\n```\n\n";
        var first = engine.lex(code, 1).get(20, TimeUnit.SECONDS);
        long processed = engine.lexedCharacters();
        var next = engine.lex(code + "new paragraph", 2).get(20, TimeUnit.SECONDS);
        assertSame(first.tokens().getFirst(), next.tokens().getFirst());
        assertEquals("new paragraph".length(), engine.lexedCharacters() - processed);
        assertEquals(MarkedTokenAdapter.adapt(engine.lexJson(code + "new paragraph")
                .get(20, TimeUnit.SECONDS), 2).tokens(), next.tokens());
    }
}
