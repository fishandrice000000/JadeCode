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
    /**
     * /model 的语义 Markdown：只使用 Display.metadata/thinking 与真实 model 名，不反解析
     * describe 字符串、不联网、不把未知限额补成 0。
     */
    public String modelMarkdown(TuiState state) {
        var out = new StringBuilder();
        out.append("## Model\n\n");
        String model = state == null ? "" : state.model;
        var metadata = state == null || state.session == null ? null : state.session.metadata();
        String thinking = state == null || state.session == null || state.session.thinking() == null
                ? "" : state.session.thinking().value();
        out.append("- **Model:** ").append(model == null || model.isBlank() ? "unknown" : MarkdownSafe.code(model)).append('\n');
        out.append("- **Thinking:** ").append(thinking == null || thinking.isBlank() ? "unknown" : thinking).append('\n');
        var limits = metadata == null ? null : metadata.limits();
        boolean known = limits != null && limits.known();
        if (known) {
            out.append("\n### Limits\n\n");
            if (limits.contextWindowTokens() != null)
                out.append("- **Context window:** ").append(limits.contextWindowTokens()).append(" tokens\n");
            if (limits.maxInputTokens() != null)
                out.append("- **Max input:** ").append(limits.maxInputTokens()).append(" tokens\n");
            if (limits.maxOutputTokens() != null)
                out.append("- **Max output:** ").append(limits.maxOutputTokens()).append(" tokens\n");
        } else {
            out.append("\n### Limits\n\n- No context/input/output limits reported.\n");
        }
        out.append("\n").append(UiMessages.NO_MODEL_SWITCH).append(".\n");
        return out.toString();
    }

    /**
     * /session 的语义 Markdown：保留身份、真实 usage 分组、cache/context 与缺报告语义；
     * 字段缺失时省略或写 unknown，不补 0、不合并计数。
     */
    public String markdown(TuiState state) {
        var out = new StringBuilder();
        out.append("## Session\n\n");
        String title = state.session == null || state.session.title() == null || state.session.title().isBlank()
                ? "New session" : state.session.title();
        out.append("- **Title:** ").append(MarkdownSafe.code(title)).append('\n');
        if (state.sessionId != null && !state.sessionId.isBlank())
            out.append("- **ID:** ").append(MarkdownSafe.code(state.sessionId)).append('\n');
        if (state.model != null && !state.model.isBlank())
            out.append("- **Model:** ").append(MarkdownSafe.code(state.model)).append('\n');
        if (state.session == null) {
            out.append("\nNo active session data reported.\n");
            return out.toString();
        }
        out.append("- **Thinking:** ").append(state.session.thinking() == null ? "unknown"
                : state.session.thinking().value()).append('\n');
        out.append("- **Permission:** ").append(UiMessages.permission(state.permissionProfile)).append('\n');
        var usage = state.session.usage();
        var total = usage == null ? null : usage.total();
        out.append("\n### Usage\n\n");
        out.append("- **Tokens:** ").append(total == null ? "none reported" : notEmpty(
                UsageFormatter.tokenCounts(total), "none reported")).append('\n');
        String cache = total == null ? "" : UsageFormatter.cacheCounts(total);
        String hit = total == null ? "" : UsageFormatter.cacheHit(total);
        out.append("- **Cache:** ").append(cache.isBlank() && hit.isBlank() ? "none reported"
                : (cache + (hit.isBlank() ? "" : " · " + hit)).strip()).append('\n');
        if (total != null && total.missingRequests() > 0)
            out.append("- **Missing usage reports:** ").append(total.missingRequests()).append('\n');
        String context = state.session.context() == null ? "" : UsageFormatter.context(state.session.context());
        out.append("- **Context:** ").append(context.isBlank() ? "unknown" : "estimated " + context).append('\n');
        if (usage != null) {
            usageGroup(out, "By actor", usage.byActor());
            usageGroup(out, "By purpose", usage.byPurpose());
            usageGroup(out, "By model", usage.byModel());
        }
        return out.toString();
    }

    private static void usageGroup(StringBuilder out, String label, java.util.Map<String, ?> values) {
        if (values == null || values.isEmpty()) return;
        out.append("\n### ").append(label).append("\n\n");
        values.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            String key = String.valueOf(entry.getKey());
            String value = entry.getValue() instanceof com.jadecode.session.SessionUsage.Totals total
                    ? notEmpty(UsageFormatter.tokenCounts(total), "none reported") : String.valueOf(entry.getValue());
            out.append("- ").append(MarkdownSafe.code(key)).append(" — ").append(value).append('\n');
        });
    }

    /** 旧 StyledLine 渲染保留给组件级调用；Controller 已使用语义 Markdown。 */
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
