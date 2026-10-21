package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.cli.tui.UsageFormatter;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;

/**
 * /session：当前身份、模型/思考/权限、真实 usage 分组、context 估计与 MCP 数量。
 *
 * <p>只读 SessionRuntime.Display/SessionUsage/ContextUsage，不修改账本、不把缺报告当 0。
 * 缓存读写不重复加到输入/输出；近因分母缺失时省略 CH。分组保持 actor/用途/模型原键，
 * 不做跨类累计。没有费用/订阅数据不画。</p>
 */
public final class SessionInfoView {
    public List<StyledLine> render(TuiState state, RenderContext context, int width) {
        var out = new ArrayList<StyledLine>();
        out.add(StyledLine.of(DisplayWidth.ellipsize(UiMessages.SESSION_LABEL + " · " + (state.session == null
                ? "New session" : state.session.title()), width), context.theme().style("accent")));
        if (state.sessionId != null && !state.sessionId.isBlank())
            out.addAll(wrap("ID · " + state.sessionId, context, width, "muted"));
        if (state.model != null && !state.model.isBlank())
            out.addAll(wrap("Model · " + state.model, context, width, "text"));
        if (state.session != null) {
            out.addAll(wrap("Thinking · " + state.session.thinkingLabel(), context, width, "text"));
            out.addAll(wrap("Permission · " + UiMessages.permission(state.permissionProfile), context, width, "text"));
            var total = state.session.usage().total();
            out.addAll(wrap("Tokens · " + notEmpty(UsageFormatter.tokenCounts(total), "none reported"),
                    context, width, "text"));
            out.addAll(wrap("Cache · " + notEmpty(UsageFormatter.cacheCounts(total), "none reported")
                    + (UsageFormatter.cacheHit(total).isBlank() ? "" : " · " + UsageFormatter.cacheHit(total)),
                    context, width, "text"));
            if (total.missingRequests() > 0)
                out.addAll(wrap("Missing usage reports · " + total.missingRequests(), context, width, "warning"));
            String contextLine = UsageFormatter.context(state.session.context());
            out.addAll(wrap("Context · " + (contextLine.isBlank() ? "unknown" : "estimated " + contextLine),
                    context, width, "text"));
            var byActor = state.session.usage().byActor();
            if (!byActor.isEmpty()) {
                out.add(StyledLine.of(DisplayWidth.ellipsize("By actor", width), context.theme().style("muted")));
                byActor.forEach((actor, value) -> out.addAll(wrap("  " + actor + " · "
                        + UsageFormatter.tokenCounts(value), context, width, "text")));
            }
        }
        return out;
    }

    private static List<StyledLine> wrap(String text, RenderContext context, int width, String token) {
        return StyledLine.wrap(List.of(new com.jadecode.tui.text.Span(text, context.theme().style(token), null)),
                Math.max(1, width));
    }

    private static String notEmpty(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
