package com.jadecode.cli.tui.tools;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.cli.tui.UsageFormatter;
import com.jadecode.cli.tui.model.ToolDisplayState;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.text.TextSanitizer;
import com.jadecode.tui.theme.Theme;

/**
 * 工具卡公共排版：标题/状态/耗时、参数解析、缩进结果、展开提示与截断。
 *
 * <p>仅供 tools 包内 renderer 复用；不执行工具、不读文件。JSON 参数解析失败时原样显示
 * 安全文本，不猜字段。状态色来自真实 ToolDisplayState；RETURNED 使用中性色，ERROR/BLOCKED/
 * NOT_RUN 保留各自提示。结果总是完整保存在 state 中，折叠只影响显示行数。</p>
 */
final class ToolPresentation {
    private ToolPresentation() { }

    /** @return 解析后的参数对象；非法/空返回 null，由调用方显示原文。 */
    static JsonNode arguments(ToolDisplayState state) {
        if (state.arguments() == null || state.arguments().isBlank()) return null;
        try {
            JsonNode node = com.jadecode.util.Json.mapper().readTree(state.arguments());
            return node != null && node.isObject() ? node : null;
        } catch (Exception invalid) { return null; }
    }

    /** @return 安全显示的单行摘要（控制字符可见化）。 */
    static String safe(String value) {
        return TextSanitizer.singleLine(TextSanitizer.visible(value == null ? "" : value));
    }

    /** @return 工具卡标题行：`◍ name · status · duration`，超宽裁切。 */
    static StyledLine header(ToolDisplayState state, RenderContext context, int width) {
        String actor = state.actor().isBlank() ? "" : com.jadecode.cli.tui.TuiState.actorName(state.actor()) + " · ";
        var text = new StringBuilder("◍ ").append(actor).append(state.name());
        String status = UiMessages.status(state.status().name());
        if (!status.isBlank() && state.status() != ToolDisplayState.Status.PREVIEW) text.append(" · ").append(status);
        else if (state.status() == ToolDisplayState.Status.PREVIEW) text.append(" · Preview");
        if (state.elapsedNanos() > 0) text.append(" · ").append(UsageFormatter.toolDuration(state.elapsedNanos()));
        if (state.argumentsComplete() && state.status() != ToolDisplayState.Status.PREVIEW) {
            // 参数已通过 JSON 校验、但整响应可能失败：不额外加成功标记。
        }
        Style style = switch (state.status()) {
            case ERROR, INTERRUPTED -> context.theme().style("error");
            case BLOCKED, NOT_RUN -> context.theme().style("warning");
            case PREVIEW, QUEUED, RUNNING -> context.theme().style("dim");
            default -> context.theme().style("toolTitle");
        };
        return StyledLine.of(DisplayWidth.ellipsize(text.toString(), width), style);
    }

    /** @return 参数摘要行（2 空格缩进），已净化；null 参数显示 `{}`。 */
    static StyledLine argumentLine(ToolDisplayState state, RenderContext context, int width) {
        String text = safe(state.arguments()).replace('\n', ' ');
        if (text.isBlank()) text = "{}";
        return new StyledLine(List.of(new Span("  " + DisplayWidth.ellipsize(text, Math.max(1, width - 2)),
                context.theme().style("muted"), null)));
    }

    /** @return 把文本按宽度折行并加 2 空格缩进；为空返回空列表。 */
    static List<StyledLine> body(String text, RenderContext context, int width, String token) {
        if (text == null || text.isEmpty()) return List.of();
        var spans = new ArrayList<Span>();
        spans.add(new Span(TextSanitizer.display(text), context.theme().style(token), null));
        List<StyledLine> wrapped = StyledLine.wrap(spans, Math.max(1, width - 2));
        var result = new ArrayList<StyledLine>(wrapped.size());
        for (StyledLine line : wrapped) {
            var prefixed = new ArrayList<Span>();
            prefixed.add(new Span("  ", Style.PLAIN, null));
            prefixed.addAll(line.spans());
            result.add(new StyledLine(prefixed, line.annotation()));
        }
        return result;
    }

    /** @return 结果的前/后若干视觉行；truncated 标志由调用方决定是否加提示。 */
    static List<StyledLine> head(String text, int visualLines, RenderContext context, int width, String token) {
        List<StyledLine> all = body(text, context, width, token);
        if (all.size() <= visualLines) return all;
        return new ArrayList<>(all.subList(0, Math.max(0, visualLines)));
    }

    /** @return 结果最后 N 个视觉行（bash 默认折叠）。 */
    static List<StyledLine> tail(String text, int visualLines, RenderContext context, int width, String token) {
        List<StyledLine> all = body(text, context, width, token);
        if (all.size() <= visualLines) return all;
        return new ArrayList<>(all.subList(all.size() - visualLines, all.size()));
    }

    /** @return 截断提示；完整内容仍保存在状态中。 */
    static StyledLine truncated(String hint, RenderContext context, int width) {
        return StyledLine.of(DisplayWidth.ellipsize("  … " + hint, width), context.theme().style("dim"));
    }

    /** @return 从参数中取字符串字段；缺失返回空串。 */
    static String string(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return "";
        JsonNode value = node.get(field);
        return value.isTextual() ? value.asText() : value.toString();
    }

    /** @return 从参数中取整数字段；缺失返回 null。 */
    static Integer integer(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        JsonNode value = node.get(field);
        return value.isNumber() ? value.asInt() : null;
    }

    /** 通用“原始参数”回退：解析失败时显示真实 JSON 文本，不吞掉。 */
    static List<StyledLine> rawArguments(ToolDisplayState state, RenderContext context, int width) {
        if (state.arguments() == null || state.arguments().isBlank()) return List.of();
        return head(state.arguments(), 10, context, width, "muted");
    }

    /** @return 主题背景 token 对应的辅助样式（未使用背景时保持正文样式）。 */
    static Style toolBackground(ToolDisplayState state, RenderContext context) {
        String token = switch (state.status()) {
            case ERROR, INTERRUPTED -> "toolErrorBg";
            case RETURNED -> "toolSuccessBg";
            case PREVIEW, QUEUED, RUNNING -> "toolPendingBg";
            default -> "toolPendingBg";
        };
        return context.theme().style("toolOutput").withBg(context.theme().color(token));
    }
}
