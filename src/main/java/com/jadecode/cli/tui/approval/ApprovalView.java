package com.jadecode.cli.tui.approval;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.text.TextSanitizer;

/**
 * S52 审批输入区：直接替换底部 Editor 的完整有边界面板。
 *
 * <p>标题嵌入顶边（accent+bold），正文每行闭合侧边、左右各 2 列 padding、整块矩形铺
 * {@code panelBg}；TUI 不再绘制请求 reason（请求对象、Gate、plain/wire 原数据不受影响）。
 * 未选中按钮中性，Deny 不因语义 token 默认红色加粗；选中项完整标签反色。初始 choice=-1；
 * 按钮横/竖排先按实际标签（含 (n/a)）计算，再决定详情行数；真实高度不足时只显示安全提示
 * 且不生成任何批准动作/hit。返回的 Button/choice 坐标都是完成外框后的面板坐标，宿主只平移。</p>
 *
 * <p>本类只读 {@link ApprovalViewState} 并更新其页大小/偏移；不完成 future、不判断权限，
 * 动作证据仍由状态类与 PresentedFrame/水位校验。</p>
 */
public final class ApprovalView {
    /** 一个按钮的帧内行/列范围及动作种类。 */
    public record Button(int row, int from, int to, com.jadecode.tui.core.ActionToken.Kind kind, boolean enabled) { }

    /** 排版结果；行号相对审批区起点，宿主平移后写入 Frame hit。 */
    public record Rendered(List<StyledLine> lines, int choiceRow, int choiceColumn, List<Button> buttons) {
        public Rendered { lines = List.copyOf(lines); buttons = List.copyOf(buttons); }
    }

    private static final String[] LABELS = {"Allow once", "Allow similar", "Deny"};
    /** 安全提示至少需要上下边界+标题+按钮+提示；更小窗口不生成批准动作。 */
    private static final int MIN_SAFE_ROWS = 6;

    /**
     * @param state 当前审批状态（只读；会更新页大小/偏移夹取）
     * @param context 同帧上下文
     * @param width 可用列
     * @param maxRows 审批区真实可用行数（Dock 已扣除 status/footer/widget）
     */
    public Rendered render(ApprovalViewState state, RenderContext context, int width, int maxRows) {
        var out = new ArrayList<StyledLine>();
        if (!state.active()) return new Rendered(out, 0, 0, List.of());
        int columns = Math.max(1, width);
        int budget = Math.max(1, maxRows);
        ApprovalViewState.Request request = state.request();
        String actor = request.actor() == null || request.actor().isBlank() ? ""
                : com.jadecode.cli.tui.TuiState.actorName(request.actor()) + " · ";
        String title = UiMessages.APPROVAL + " · " + actor + request.tool();
        if (budget < 3 || columns < 10) {
            return new Rendered(List.of(panelLine(List.of(new Span(DisplayWidth.ellipsize(
                    "Window too small to review safely; enlarge to approve.", columns),
                    context.theme().style("warning"), null)), columns, context)), 0, 0, List.of());
        }
        int padding = columns >= 14 ? 2 : (columns >= 11 ? 1 : 0);
        if (budget < 6 && padding == 2) padding = 1;
        int contentWidth = Math.max(1, columns - 2 - padding * 2);

        // 详情：只显示完整参数和必要长期范围；reason 不进入 TUI 显示；每个续行同样缩进。
        var details = new ArrayList<StyledLine>();
        details.add(StyledLine.of("Parameters:", context.theme().style("muted")));
        details.addAll(indentWrap(TextSanitizer.visible(request.arguments()), contentWidth, context));
        if (!request.suggestion().isBlank()) {
            details.add(StyledLine.empty());
            details.add(StyledLine.of("Long-term scope:", context.theme().style("muted")));
            details.addAll(indentWrap(TextSanitizer.visible(request.suggestion()), contentWidth, context));
        }

        boolean hasSuggestion = !request.suggestion().isBlank();
        String[] labels = new String[LABELS.length];
        for (int i = 0; i < LABELS.length; i++) labels[i] = buttonLabel(i, state.choice(), i != 1 || hasSuggestion);
        int horizontalWidth = 0;
        for (int i = 0; i < labels.length; i++) {
            horizontalWidth += DisplayWidth.width(labels[i]);
            if (i > 0) horizontalWidth += 2;
        }
        boolean horizontal = horizontalWidth <= contentWidth;
        int buttonRows = horizontal ? 1 : LABELS.length;
        int hintRows = 1;
        // 上/下边框 + 按钮 + 提示；顶边已含标题，不额外算虚拟标题行。
        int fixed = 2 + buttonRows + hintRows;
        if (budget < fixed + 1 || columns < 20) {
            return new Rendered(List.of(panelLine(List.of(new Span(DisplayWidth.ellipsize(
                    "Window too small to review safely; enlarge to approve.", columns),
                    context.theme().style("warning"), null)), columns, context)), 0, 0, List.of());
        }
        int detailRows = Math.max(1, budget - fixed);
        state.page(detailRows, details.size());
        int from = Math.min(state.offset(), Math.max(0, details.size() - 1));
        int to = Math.min(details.size(), from + detailRows);

        out.add(topBorder(title, columns, context));
        for (int i = from; i < to; i++) out.add(bodyRow(details.get(i), columns, padding, contentWidth, context));

        int choice = state.choice();
        var buttons = new ArrayList<Button>();
        int choiceRow = 0, choiceColumn = 0;
        int startColumn = 1 + padding;
        if (horizontal) {
            var spans = new ArrayList<Span>();
            int column = startColumn;
            int row = out.size();
            for (int i = 0; i < LABELS.length; i++) {
                boolean enabled = i != 1 || hasSuggestion;
                if (i > 0) { spans.add(new Span("  ", context.theme().style("text"), null)); column += 2; }
                if (choice == i) { choiceRow = row; choiceColumn = column; }
                spans.add(new Span(labels[i], buttonStyle(context, i, choice, enabled), null));
                if (enabled) buttons.add(new Button(row, column, column + DisplayWidth.width(labels[i]), token(i), true));
                column += DisplayWidth.width(labels[i]);
            }
            out.add(bodyRow(spans, columns, padding, contentWidth, context));
        } else {
            for (int i = 0; i < LABELS.length; i++) {
                boolean enabled = i != 1 || hasSuggestion;
                int row = out.size();
                if (choice == i) { choiceRow = row; choiceColumn = startColumn; }
                out.add(bodyRow(List.of(new Span(labels[i], buttonStyle(context, i, choice, enabled), null)),
                        columns, padding, contentWidth, context));
                if (enabled) buttons.add(new Button(row, startColumn,
                        startColumn + DisplayWidth.width(labels[i]), token(i), true));
            }
        }
        String status = "Details " + (from + 1) + "–" + to + " / " + details.size()
                + " · PgUp/PgDn read · Tab select · Enter confirm · Esc does not answer";
        out.add(bodyRow(List.of(new Span(DisplayWidth.ellipsize(status, contentWidth),
                context.theme().style("muted"), null)), columns, padding, contentWidth, context));
        out.add(bottomBorder(columns, context));
        if (out.size() > budget) out = new ArrayList<>(out.subList(0, budget));
        return new Rendered(out, choiceRow, choiceColumn, buttons);
    }

    /** 每一行都带同样的缩进；参数/长期范围尾部内容不会因续行丢缩进。 */
    private static List<StyledLine> indentWrap(String text, int width, RenderContext context) {
        Style style = context.theme().style("text");
        var result = new ArrayList<StyledLine>();
        for (StyledLine line : StyledLine.wrap(List.of(new Span(text, style, null)), Math.max(1, width - 2))) {
            var spans = new ArrayList<Span>();
            spans.add(new Span("  ", style, null));
            spans.addAll(line.spans());
            result.add(new StyledLine(spans).crop(Math.max(1, width)));
        }
        return result;
    }

    /** StyledLine 版本：复用同一行合成，保留既有 Span。 */
    private static StyledLine bodyRow(StyledLine content, int columns, int padding, int contentWidth,
            RenderContext context) {
        return bodyRow(content.spans(), columns, padding, contentWidth, context);
    }

    /** 面板正文行：左边界+padding+内容+补齐+padding+右边界；整行铺 panelBg。 */
    private static StyledLine bodyRow(List<Span> content, int columns, int padding, int contentWidth,
            RenderContext context) {
        var spans = new ArrayList<Span>();
        spans.add(new Span("│" + " ".repeat(padding), context.theme().style("border"), null));
        int used = 0;
        for (Span span : content) { spans.add(span); used += DisplayWidth.width(span.text()); }
        if (used < contentWidth)
            spans.add(new Span(" ".repeat(contentWidth - used), context.theme().background("panelBg"), null));
        spans.add(new Span(" ".repeat(padding) + "│", context.theme().style("border"), null));
        return panelLine(spans, columns, context);
    }

    /** 给整行叠加 panelBg 并补齐/裁到 columns，保留前景/链接/属性。 */
    private static StyledLine panelLine(List<Span> spans, int columns, RenderContext context) {
        Style panel = context.theme().background("panelBg");
        var result = new ArrayList<Span>();
        for (Span span : spans)
            result.add(new Span(span.text(), panel.isPlain() ? span.style() : span.style().withBg(panel.bg()), span.link()));
        StyledLine line = new StyledLine(result);
        return line.width() < columns ? line.padTo(columns, panel.isPlain() ? Style.PLAIN : panel) : line.crop(columns);
    }

    /** 顶边：标题嵌入边框；边框普通字重，标题 accent+bold；总宽恰为 columns。 */
    private static StyledLine topBorder(String title, int columns, RenderContext context) {
        String shown = DisplayWidth.ellipsize(title, Math.max(1, columns - 5));
        int fill = Math.max(0, columns - DisplayWidth.width(shown) - 5);
        Style border = context.theme().style("border");
        Style titleStyle = context.theme().style("accent");
        if (context.theme().enabled()) titleStyle = titleStyle.withBold(true);
        return panelLine(List.of(
                new Span("┌─ ", border, null),
                new Span(shown, titleStyle, null),
                new Span(" " + "─".repeat(fill) + "┐", border, null)), columns, context);
    }

    private static StyledLine bottomBorder(int columns, RenderContext context) {
        return panelLine(List.of(new Span("└" + "─".repeat(Math.max(0, columns - 2)) + "┘",
                context.theme().style("border"), null)), columns, context);
    }

    private static String buttonLabel(int index, int choice, boolean enabled) {
        String marker = choice == index ? "›" : " ";
        return "[" + marker + " " + LABELS[index] + (enabled ? "" : " (n/a)") + " ]";
    }

    private static Style buttonStyle(RenderContext context, int index, int choice, boolean enabled) {
        Style panel = context.theme().background("panelBg");
        Style base = context.theme().style("text");
        if (!panel.isPlain()) base = base.withBg(panel.bg());
        if (!enabled) {
            Style dim = context.theme().style("dim");
            return panel.isPlain() ? dim : dim.withBg(panel.bg());
        }
        if (choice != index) return base;
        // 选中项完整标签反色；NO_COLOR 下依赖已有 › 标记而不输出 inverse。
        return context.theme().enabled() ? base.withInverse(true).withBold(true) : base;
    }

    private static com.jadecode.tui.core.ActionToken.Kind token(int index) {
        return switch (index) {
            case 0 -> com.jadecode.tui.core.ActionToken.Kind.APPROVE;
            case 1 -> com.jadecode.tui.core.ActionToken.Kind.ALLOW_SIMILAR;
            default -> com.jadecode.tui.core.ActionToken.Kind.DENY;
        };
    }
}
