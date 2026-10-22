package com.jadecode.tui.markdown;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.components.Markdown;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** 固定 JS 引擎的封闭性/同源 DTO/高亮/Mermaid/LaTeX 与 Markdown 接线测试。 */
class JsTextEngineParityTest {
    private static JsTextEngine engine;

    @BeforeAll static void start() throws Exception {
        engine = JsTextEngine.create();
    }

    @AfterAll static void stop() {
        if (engine != null) engine.close();
    }

    @Test void sandboxHasNoHostObjectsOrProcess() throws Exception {
        String probe = engine.sandboxProbe().get(20, TimeUnit.SECONDS);
        assertTrue(probe.contains("\"javaType\":\"blocked\""), probe);
        assertTrue(probe.contains("\"polyglot\":\"undefined\""), probe);
        assertTrue(probe.contains("\"require\":\"undefined\""), probe);
        assertTrue(probe.contains("\"process\":\"undefined\""), probe);
    }

    @Test void fixedBundleMatchesManifestHash() {
        assertEquals(64, engine.bundleHash().length());
        assertTrue(engine.ready());
    }

    @Test void lexMapsBlocksAndAttachesHighlightAndMermaid() throws Exception {
        String source = "# Title\n\n- a\n- b\n\n```java\nint x = 1; // c\n```\n\n"
                + "| A | B |\n|---|---|\n| 1 | 2 |\n\n$x^2$ and $\\alpha$\n\n"
                + "```mermaid\ngraph TD; A-->B;\n```\n";
        TextRenderResult result = engine.lex(source, 7).get(30, TimeUnit.SECONDS);
        assertEquals(7, result.sourceVersion());
        var types = result.tokens().stream().map(t -> t.getClass().getSimpleName()).toList();
        assertTrue(types.contains("Heading"), types.toString());
        assertTrue(types.contains("ListBlock"));
        assertTrue(types.contains("Table"));
        var code = result.tokens().stream().filter(t -> t instanceof TextRenderResult.Code)
                .map(t -> (TextRenderResult.Code) t).filter(c -> c.language().equals("java")).findFirst().orElseThrow();
        assertFalse(code.highlight().isEmpty());
        assertTrue(code.highlight().getFirst().stream().anyMatch(s -> s.cls().equals("hljs-keyword")), code.highlight().toString());
        var mermaid = result.tokens().stream().filter(t -> t instanceof TextRenderResult.Code)
                .map(t -> (TextRenderResult.Code) t).filter(c -> c.language().equals("mermaid")).findFirst().orElseThrow();
        assertFalse(mermaid.text().isBlank());
        assertTrue(mermaid.highlight().stream().flatMap(List::stream).anyMatch(s -> s.cls().equals("border")));
        String text = result.tokens().stream().filter(t -> t instanceof TextRenderResult.Paragraph)
                .flatMap(t -> t.children().stream()).filter(t -> t instanceof TextRenderResult.Text)
                .map(t -> ((TextRenderResult.Text) t).value()).reduce("", String::concat);
        assertTrue(text.contains("x²"), text);
        assertTrue(text.contains("α"), text);
    }

    @Test void unknownLanguageHasNoHighlightAndMermaidRejectsNonDiagram() throws Exception {
        assertNull(engine.highlight("x", "definitely-not-a-language").get(20, TimeUnit.SECONDS));
        assertNotNull(engine.highlight("int x = 1;", "java").get(20, TimeUnit.SECONDS));
        assertNull(engine.mermaid("this is not a diagram").get(20, TimeUnit.SECONDS));
        assertEquals("α", engine.latex("\\alpha", false).get(20, TimeUnit.SECONDS));
    }

    @Test void tableRendersAtNormalWidthAndFallsBackToSourceWhenNarrow() throws Exception {
        var theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
        String source = "| Name | Value |\n|---|---:|\n| alpha | 1 |\n| beta | 2 |\n";
        var result = engine.lex(source, 1).get(30, TimeUnit.SECONDS);
        String wide = text(new MarkdownLayout(theme).layout(result, 80, Style.PLAIN));
        assertTrue(wide.contains("Name") && wide.contains("Value") && wide.contains("alpha") && wide.contains("2"), wide);
        assertTrue(wide.contains("│") || wide.contains("|"), wide);
        String narrow = text(new MarkdownLayout(theme).layout(result, 8, Style.PLAIN));
        // 8 列不足以容纳 2 个单元格列（3n+1 开销后），退回原始源码；每个 token 必须完整可见。
        String narrowJoined = narrow.replaceAll("[\\s│┌┐└┘├┤┬┴┼─]+", "");
        for (String token : List.of("Name", "Value", "alpha", "beta", "1", "2"))
            assertTrue(narrowJoined.contains(token), "narrow table dropped " + token + ": " + narrow);
    }

    @Test void codeFenceKeepsRawSourceAndFence() throws Exception {
        var theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
        var result = engine.lex("```java\nint x = 1;\n```\n", 1).get(30, TimeUnit.SECONDS);
        String rendered = text(new MarkdownLayout(theme).layout(result, 80, Style.PLAIN));
        assertTrue(rendered.contains("```java"), rendered);
        assertTrue(rendered.contains("int x = 1;"), rendered);
        assertTrue(rendered.contains("```"), rendered);
    }

    private static String text(List<StyledLine> lines) {
        return lines.stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test void markdownComponentUsesEngineResultAfterAsyncParse() throws Exception {
        var repainted = new CountDownLatch(1);
        var markdown = new Markdown(() -> engine, repainted::countDown);
        var theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
        var context = new RenderContext(80, 24, theme);
        String source = "```java\nint value = 1; // note\n```";
        List<StyledLine> fallback = markdown.render(source, 80, theme, false);
        assertFalse(fallback.isEmpty());
        assertTrue(repainted.await(30, TimeUnit.SECONDS), "engine result must trigger a repaint");
        List<StyledLine> parsed = markdown.render(source, 80, theme, false);
        var keywordStyle = theme.style("syntaxKeyword");
        assertTrue(parsed.stream().flatMap(line -> line.spans().stream())
                .anyMatch(span -> span.style().fg().equals(keywordStyle.fg())), "highlight scope must use syntax theme");
        assertTrue(parsed.stream().anyMatch(line -> line.text().contains("int")));
    }
}
