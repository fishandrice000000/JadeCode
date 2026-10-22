package com.jadecode.cli.tui.view;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** R4-04/S43：默认标题只留品牌/版本/tagline 与独立资源提示，不再铺四行固定帮助。 */
class TuiWelcomeTest {
    private final RenderContext context = new RenderContext(80, 24,
            Theme.from(ThemePalette.builtin("dark"), true, 1));

    @Test void defaultWelcomeHasNoFourLineQuickHelp() {
        var lines = new WelcomeView().render("1.2.3", "model-x", "/work", List.of(), false, context, 80);
        String text = lines.stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(text.contains("JadeCode v1.2.3"));
        assertTrue(text.contains("coding agent"));
        assertFalse(text.contains("Enter submit"));
        assertFalse(text.contains("Ctrl+T thinking"));
        assertFalse(text.contains("/settings · /session"));
        assertTrue(text.contains("Press Ctrl+O"));
    }

    @Test void conversationUsesConfiguredQuietStartupWithoutRestoringFixedHelp() {
        var state = new TuiState();
        state.installGeneration(0);
        state.directory = "/work";
        var normal = new ConversationView().render(state, context, List.of(), false);
        var quietState = new TuiState();
        quietState.installGeneration(0);
        quietState.directory = "/work";
        quietState.quietStartup = true;
        var quiet = new ConversationView().render(quietState, context, List.of(), false);
        assertTrue(normal.stream().noneMatch(line -> line.text().contains("Enter submit")));
        assertTrue(quiet.stream().noneMatch(line -> line.text().contains("Enter submit")));
        assertTrue(normal.size() > quiet.size(), "quietStartup should still hide the resource hint");
    }

    @Test void quietStartupHidesOnlyTheResourceHintAndExpandedResourcesRemainExplicit() {
        var quiet = new WelcomeView().render("1.2.3", "model-x", "/work", List.of("skill"), true, context, 80);
        assertEquals(2, quiet.size());
        assertTrue(quiet.stream().noneMatch(line -> line.text().contains("Press Ctrl+O")));
        var expanded = new WelcomeView().expanded("model-x", "/work", List.of("skill"), context, 80);
        assertTrue(expanded.stream().anyMatch(line -> line.text().contains("Model · model-x")));
        assertTrue(expanded.stream().anyMatch(line -> line.text().contains("Resource · skill")));
    }
}
