package com.jadecode.cli.tui.view;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** S85：原始字符画、宽高档位、真实版本、蓝橙语义与 NO_COLOR。 */
class BrandingViewTest {
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
    private final BrandingView view = new BrandingView();

    @Test void assetsKeepUserCharactersAndMeasuredColumns() {
        assertEquals(6, BrandingView.mascotLines().size());
        assertEquals(6, BrandingView.bannerLines().size());
        assertEquals(17, BrandingView.mascotWidth());
        assertEquals(69, BrandingView.bannerWidth());
        assertEquals(89, BrandingView.fullWidth());
        assertEquals(29, BrandingView.compactWidth());
        assertTrue(BrandingView.mascotLines().getFirst().startsWith("┏"));
        assertTrue(BrandingView.bannerLines().getFirst().contains("█"));
    }

    @Test void modeFollowsMeasuredWidthAndTerminalHeight() {
        assertEquals(BrandingView.Mode.FULL, BrandingView.mode(89, 40));
        assertEquals(BrandingView.Mode.COMPACT, BrandingView.mode(88, 40));
        assertEquals(BrandingView.Mode.COMPACT, BrandingView.mode(29, 30));
        assertEquals(BrandingView.Mode.LINE, BrandingView.mode(28, 30));
        // 高度不足 22 行时不输出六行图案，优先保障聊天/输入区。
        assertEquals(BrandingView.Mode.LINE, BrandingView.mode(120, 21));
        assertEquals(BrandingView.Mode.COMPACT, BrandingView.mode(80, 24));
    }

    @Test void fullModeAlignsSixRowsAndPutsVersionBelow() {
        var lines = view.render("9.9.9", new RenderContext(120, 40, theme), 120, 40);
        assertEquals(8, lines.size());
        assertTrue(lines.subList(0, 6).stream().allMatch(line -> line.width() == 89),
                "short banner rows are right-padded to the measured width");
        assertEquals("JadeCode v9.9.9", lines.get(6).text());
        assertTrue(lines.get(7).text().startsWith("coding agent · Java "));
        assertTrue(lines.get(0).spans().stream().anyMatch(span -> span.text().contains("┏")
                && span.style().fg().equals(theme.color("brandFrame"))), "mascot frame uses brandFrame");
        assertTrue(lines.get(0).spans().stream().anyMatch(span -> span.text().contains("█")
                && span.style().fg().equals(theme.color("brandPrimary"))), "wordmark uses brandPrimary");
    }

    @Test void mascotUsesOrangeOnlyForDotsPromptAndCursor() {
        var lines = view.render("1.0.0", new RenderContext(80, 24, theme), 80, 24);
        var first = lines.getFirst();
        assertTrue(first.text().contains("JADE CODE"), "80x24 keeps the original mascot plus a plain wordmark");
        var row = lines.get(1);
        assertTrue(row.spans().stream().anyMatch(span -> span.text().contains("●")
                && span.style().fg().equals(theme.color("brandAccent"))));
        assertTrue(row.spans().stream().anyMatch(span -> span.text().contains("JADE")
                && span.style().fg().equals(theme.color("brandPrimary"))));
        var ready = lines.get(4);
        assertTrue(ready.spans().stream().anyMatch(span -> span.text().contains("ready")
                && span.style().fg().equals(theme.color("text"))));
        assertTrue(ready.spans().stream().anyMatch(span -> span.text().contains("_")
                && span.style().fg().equals(theme.color("brandAccent"))));
    }

    @Test void narrowAndShortScreensDegradeWithoutHalfArt() {
        var narrow = view.render("1.0.0", new RenderContext(40, 12, theme), 40, 12);
        assertEquals(2, narrow.size());
        assertEquals("JadeCode v1.0.0", narrow.getFirst().text());
        assertTrue(narrow.get(1).text().startsWith("coding agent · Java "));
        var tiny = view.render("1.0.0", new RenderContext(20, 8, theme), 20, 8);
        assertTrue(tiny.stream().allMatch(line -> line.width() <= 20));
        assertFalse(tiny.stream().anyMatch(line -> line.text().contains("┏")), "no half mascot");
    }

    @Test void noColorKeepsCharactersButDropsAttributes() {
        var plain = new Theme(theme.name(), theme.appearance(), theme.colors(), false, 1);
        var lines = view.render("1.0.0", new RenderContext(120, 40, plain), 120, 40);
        assertTrue(lines.stream().flatMap(line -> line.spans().stream())
                .allMatch(span -> span.style().equals(Style.PLAIN)));
        assertTrue(lines.getFirst().text().contains("┏"));
        assertTrue(lines.getFirst().text().contains("█"));
        assertTrue(lines.get(6).text().contains("v1.0.0"));
    }

    @Test void welcomeQuietKeepsBrandAndOnlyHidesResourceHint() {
        var context = new RenderContext(80, 24, theme);
        var normal = new WelcomeView().render("2.0.0", "m", "/w", List.of("skill"), false, context, 80);
        var quiet = new WelcomeView().render("2.0.0", "m", "/w", List.of("skill"), true, context, 80);
        assertTrue(normal.size() > quiet.size());
        assertTrue(quiet.stream().anyMatch(line -> line.text().contains("JadeCode v2.0.0")));
        assertTrue(quiet.stream().noneMatch(line -> line.text().contains("Press Ctrl+O")));
        assertTrue(normal.stream().anyMatch(line -> line.text().contains("Press Ctrl+O")));
    }

    @Test void expandedResourcesRemainRealValues() {
        var expanded = new WelcomeView().expanded("model-x", "/work", List.of("skill"), themeContext(), 80);
        assertTrue(expanded.stream().anyMatch(line -> line.text().contains("Model · model-x")));
        assertTrue(expanded.stream().anyMatch(line -> line.text().contains("Workspace · /work")));
        assertTrue(expanded.stream().anyMatch(line -> line.text().contains("Resource · skill")));
    }

    private RenderContext themeContext() { return new RenderContext(80, 24, theme); }
}
