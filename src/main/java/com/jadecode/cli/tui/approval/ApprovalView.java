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
 * <p>上边界内显示 actor/tool、可滚动的完整参数与长期授权范围、三个显式选项和操作提示，
 * 下边界闭合；TUI 不再绘制请求 reason（请求对象、Gate、plain/wire 原数据不受影响）。
 * 未选中的可用按钮使用中性样式，Deny 不因语义 token 默认红色加粗；只有显式选中项强调。
 * 初始 choice=-1；真实可用高度不足时只显示安全提示且不生成任何批准动作/hit。</p>
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
        Style borderStyle = context.theme().style("approval");
        Style muted = context.theme().style("muted");
        String actor = request.actor() == null || request.actor().isBlank() ? ""
                : com.jadecode.cli.tui.TuiState.actorName(request.actor()) + " · ";
        String title = UiMessages.APPROVAL + " · " + actor + request.tool();

        // 详情：只显示完整参数和必要长期范围；reason 不进入 TUI 显示。
        var details = new ArrayList<StyledLine>();
        details.add(StyledLine.of("Parameters:", muted));
        details.addAll(StyledLine.wrap(List.of(new Span("  " + TextSanitizer.visible(request.arguments()),
                context.theme().style("text"), null)), Math.max(1, columns - 2)));
        if (!request.suggestion().isBlank()) {
            details.add(StyledLine.empty());
            details.add(StyledLine.of("Long-term scope:", muted));
            details.addAll(StyledLine.wrap(List.of(new Span("  " + TextSanitizer.visible(request.suggestion()),
                    context.theme().style("text"), null)), Math.max(1, columns - 2)));
        }

        boolean stacked = columns < 46;
        int buttonRows = stacked ? LABELS.length : 1;
        int hintRows = 1;
        // 边界 2 + 标题 1 + 按钮 + 提示；详情至少 1 行。
        int fixed = 2 + 1 + buttonRows + hintRows;
        if (budget < Math.max(MIN_SAFE_ROWS, fixed + 1) || columns < 20) {
            out.add(StyledLine.of(DisplayWidth.ellipsize(
                    "Window too small to review safely; enlarge to approve.", columns),
                    context.theme().style("warning")));
            return new Rendered(out, 0, 0, List.of());
        }
        int detailRows = Math.max(1, budget - fixed);
        state.page(detailRows, details.size());
        int from = Math.min(state.offset(), Math.max(0, details.size() - 1));
        int to = Math.min(details.size(), from + detailRows);

        out.add(topBorder(title, columns, borderStyle));
        out.addAll(details.subList(from, to));

        int choice = state.choice();
        var buttons = new ArrayList<Button>();
        int choiceRow = 0, choiceColumn = 0;
        if (stacked || buttonLineWidth() > columns - 2) {
            for (int i = 0; i < LABELS.length; i++) {
                boolean enabled = i != 1 || !request.suggestion().isBlank();
                String label = buttonLabel(i, choice, enabled);
                int row = out.size();
                out.add(StyledLine.of(DisplayWidth.ellipsize("  " + label, columns),
                        buttonStyle(context, i, choice, enabled)));
                if (enabled) buttons.add(new Button(row, 2, 2 + DisplayWidth.width(label), token(i), true));
                if (choice == i) { choiceRow = row; choiceColumn = 2; }
            }
        } else {
            var spans = new ArrayList<Span>();
            int column = 0;
            int row = out.size();
            for (int i = 0; i < LABELS.length; i++) {
                boolean enabled = i != 1 || !request.suggestion().isBlank();
                String label = buttonLabel(i, choice, enabled);
                if (i > 0) { spans.add(new Span("  ", Style.PLAIN, null)); column += 2; }
                if (choice == i) { choiceRow = row; choiceColumn = column; }
                spans.add(new Span(label, buttonStyle(context, i, choice, enabled), null));
                if (enabled) buttons.add(new Button(row, column, column + DisplayWidth.width(label), token(i), true));
                column += DisplayWidth.width(label);
            }
            out.add(new StyledLine(spans));
        }
        out.add(StyledLine.of(DisplayWidth.ellipsize(
                "Details " + (from + 1) + "–" + to + " / " + details.size()
                        + " · PgUp/PgDn read · Tab select · Enter confirm · Esc does not answer", columns), muted));
        out.add(bottomBorder(columns, borderStyle));
        return new Rendered(out, choiceRow, choiceColumn, buttons);
    }

    private static int buttonLineWidth() {
        int width = 0;
        for (int i = 0; i < LABELS.length; i++) {
            width += DisplayWidth.width(buttonLabel(i, -1, true));
            if (i > 0) width += 2;
        }
        return width;
    }

    private static StyledLine topBorder(String title, int width, Style style) {
        int columns = Math.max(1, width);
        String label = DisplayWidth.ellipsize(title, Math.max(1, columns - 4));
        int fill = Math.max(0, columns - DisplayWidth.width(label) - 4);
        return StyledLine.of(DisplayWidth.take("┌─ " + label + " " + "─".repeat(fill) + "┐", columns), style);
    }

    private static StyledLine bottomBorder(int width, Style style) {
        int columns = Math.max(1, width);
        return StyledLine.of("└" + "─".repeat(Math.max(0, columns - 2)) + "┘", style);
    }

    private static String buttonLabel(int index, int choice, boolean enabled) {
        String marker = choice == index ? "›" : " ";
        return "[" + marker + " " + LABELS[index] + (enabled ? "" : " (n/a)") + " ]";
    }

    private static Style buttonStyle(RenderContext context, int index, int choice, boolean enabled) {
        if (!enabled) return context.theme().style("dim");
        // 未选中一律中性；Deny 只有被显式选中才用错误色强调。
        if (choice != index) return context.theme().style("text");
        return context.theme().style(index == 2 ? "error" : "accent").withBold(true);
    }

    private static com.jadecode.tui.core.ActionToken.Kind token(int index) {
        return switch (index) {
            case 0 -> com.jadecode.tui.core.ActionToken.Kind.APPROVE;
            case 1 -> com.jadecode.tui.core.ActionToken.Kind.ALLOW_SIMILAR;
            default -> com.jadecode.tui.core.ActionToken.Kind.DENY;
        };
    }
}
