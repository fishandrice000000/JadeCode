package com.jadecode.cli.tui.tools;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.cli.tui.model.ToolDisplayState;
import com.jadecode.execution.ToolIdentity;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** 工具专用 renderer 的显示契约测试：收起/展开/真实状态/不伪造执行。 */
class TuiToolRendererTest {
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
    private final RenderContext context = new RenderContext(60, 20, theme);

    private ToolDisplayState state(String name, String args) {
        var state = new ToolDisplayState("leader", "r", "c", name, ToolIdentity.builtin(name));
        state.previewArguments(args);
        return state;
    }

    private static String text(List<StyledLine> lines) {
        return lines.stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test void bashCollapsedShowsLastFiveVisualLinesAndExpandedShowsAll() {
        var state = state("bash", "{\"command\":\"printf test\"}");
        state.executing(ToolDisplayState.Status.RUNNING, "{\"command\":\"printf test\"}");
        state.finished(ToolDisplayState.Status.RETURNED, "1\n2\n3\n4\n5\n6\n7", 5_000_000L);
        var renderer = new ShellToolRenderer();
        String collapsed = text(renderer.render(state, context, 60));
        assertTrue(collapsed.contains("$ printf test"));
        assertTrue(collapsed.contains("3"));
        assertFalse(collapsed.contains("1"));
        assertTrue(collapsed.contains("Ctrl+O"));
        state.toggleExpanded();
        String expanded = text(renderer.render(state, context, 60));
        assertTrue(expanded.contains("1"));
        assertTrue(expanded.contains("7"));
    }

    @Test void readCollapsedHasNoBodyButExpandedShowsRealResult() {
        var state = state("read_file", "{\"path\":\"a.txt\",\"offset\":2,\"limit\":3}");
        state.executing(ToolDisplayState.Status.RUNNING, state.arguments());
        state.finished(ToolDisplayState.Status.RETURNED, "real content", 0);
        var renderer = new FileToolRenderer();
        String collapsed = text(renderer.render(state, context, 60));
        assertTrue(collapsed.contains("a.txt"));
        assertTrue(collapsed.contains("from line 2"));
        assertFalse(collapsed.contains("real content"));
        state.toggleExpanded();
        assertTrue(text(renderer.render(state, context, 60)).contains("real content"));
    }

    @Test void writeShowsPreviewAndExplicitNotExecuted() {
        var state = state("write_file", "{\"path\":\"a.txt\",\"content\":\"one\\ntwo\"}");
        String rendered = text(new FileToolRenderer().render(state, context, 60));
        assertTrue(rendered.contains("Preview · not executed"));
        assertTrue(rendered.contains("one"));
        assertTrue(rendered.contains("two"));
    }

    @Test void editShowsFragmentDiffAndNotExecuted() {
        var state = state("edit_file", "{\"path\":\"a.txt\",\"old_text\":\"old line\",\"new_text\":\"new line\"}");
        state.toggleExpanded();
        String rendered = text(new FileToolRenderer().render(state, context, 60));
        assertTrue(rendered.contains("Preview · not executed"));
        assertTrue(rendered.contains("- old line"));
        assertTrue(rendered.contains("+ new line"));
    }

    @Test void fallbackShowsTenLinesAndMcpIdentity() {
        var state = new ToolDisplayState("leader", "r", "c", "mcp_tool",
                new ToolIdentity(ToolIdentity.Kind.MCP, "fetch", "remote", false));
        state.previewArguments("{\"url\":\"x\"}");
        state.executing(ToolDisplayState.Status.RUNNING, state.arguments());
        state.finished(ToolDisplayState.Status.RETURNED, "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12", 0);
        String rendered = text(new FallbackToolRenderer().render(state, context, 60));
        assertTrue(rendered.contains("MCP · fetch"));
        assertTrue(rendered.contains("10"));
        assertFalse(rendered.lines().anyMatch(line -> line.strip().equals("12")), rendered);
        state.toggleExpanded();
        assertTrue(text(new FallbackToolRenderer().render(state, context, 60)).contains("12"));
    }

    @Test void registryUsesFallbackForUnknownAndNeverClaimsSuccess() {
        var state = state("unknown_tool", "{}");
        state.finished(ToolDisplayState.Status.RETURNED, "neutral output", 0);
        String rendered = text(new ToolRendererRegistry().render(state, context, 60));
        assertTrue(rendered.contains("unknown_tool"));
        assertTrue(rendered.contains("neutral output"));
        assertFalse(rendered.contains("success"));
    }
}
