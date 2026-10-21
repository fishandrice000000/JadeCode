package com.jadecode.cli.tui.tools;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.jadecode.cli.tui.model.ToolDisplayState;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;

/**
 * 列表/检索类工具卡：当前 Jade 只有 glob，按其真实语义显示 pattern 与前 15 行结果。
 *
 * <p>不把 glob 冒称 grep，也不为不存在的 find/ls 造入口；若未来注册了真实检索工具，可按
 * 名称复用同一布局。折叠截断只影响显示，原结果完整保留。</p>
 */
public final class ListingToolRenderer implements ToolRenderer {
    private static final java.util.Set<String> NAMES = java.util.Set.of("glob", "grep", "find", "ls");

    @Override public boolean supports(String name, com.jadecode.execution.ToolIdentity identity) {
        return NAMES.contains(name);
    }

    @Override public List<StyledLine> render(ToolDisplayState state, RenderContext context, int width) {
        var out = new ArrayList<StyledLine>();
        out.add(ToolPresentation.header(state, context, width));
        JsonNode args = ToolPresentation.arguments(state);
        String pattern = ToolPresentation.string(args, "pattern");
        if (pattern.isBlank()) pattern = ToolPresentation.string(args, "query");
        if (pattern.isBlank()) pattern = ToolPresentation.safe(state.arguments());
        out.add(StyledLine.of(DisplayWidth.ellipsize("  " + pattern.replace('\n', ' '), width),
                context.theme().style("muted")));
        int limit = state.expanded() ? Integer.MAX_VALUE : 15;
        var lines = ToolPresentation.body(state.result(), context, width, "normal");
        out.addAll(lines.subList(0, Math.min(limit, lines.size())));
        if (!state.expanded() && lines.size() > limit)
            out.add(ToolPresentation.truncated("Ctrl+O to expand · " + lines.size() + " lines", context, width));
        return out;
    }
}
