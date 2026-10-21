package com.jadecode.cli.tui.tools;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.model.ToolDisplayState;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;

/**
 * 其他现有工具/MCP/子代理的 fallback 卡：真实名称/来源/参数与前 10 行结果，展开无静默丢失。
 *
 * <p>不把结果正文解析成命令/成功标签；未知工具仍显示真实 ID/状态，错误与空结果可读。
 * MCP 身份只从宿主 ToolIdentity 展示，不从模型参数或名称前缀推断。</p>
 */
public final class FallbackToolRenderer implements ToolRenderer {
    @Override public List<StyledLine> render(ToolDisplayState state, RenderContext context, int width) {
        var out = new ArrayList<StyledLine>();
        out.add(ToolPresentation.header(state, context, width));
        if (state.identity() != null) {
            String source = state.identity().kind() == com.jadecode.execution.ToolIdentity.Kind.MCP
                    ? "MCP · " + state.identity().serverName() : "Built-in";
            out.add(StyledLine.of(DisplayWidth.ellipsize("  " + source, width), context.theme().style("dim")));
        }
        out.add(ToolPresentation.argumentLine(state, context, width));
        var body = ToolPresentation.body(state.result(), context, width, "normal");
        int limit = state.expanded() ? Integer.MAX_VALUE : 10;
        out.addAll(body.subList(0, Math.min(limit, body.size())));
        if (!state.expanded() && body.size() > limit)
            out.add(ToolPresentation.truncated("Ctrl+O to expand · " + body.size() + " lines", context, width));
        if (state.result().isEmpty() && !state.active())
            out.add(StyledLine.of("  (no output)", context.theme().style("dim")));
        return out;
    }
}
