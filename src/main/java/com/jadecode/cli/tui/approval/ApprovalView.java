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
 * Pi 式审批 dock：完整参数/理由/长期授权范围、三个显式选项与阅读滚动。
 *
 * <p>参数使用 {@link TextSanitizer#visible} 展示，控制字符可见化但不改变真实请求对象；不把
 * 多行命令缩成一行摘要。无默认选择；窗口太小或无法安全显示审阅区与按钮时不发布任何批准
 * 动作，只给英文提示。视图不完成 future、不判断权限；动作证据由 ApprovalViewState 与
 * PresentedFrame 校验。</p>
 */
public final class ApprovalView {
    /** 一个按钮的帧内行/列范围及动作种类。 */
    public record Button(int row, int from, int to, com.jadecode.tui.core.ActionToken.Kind kind, boolean enabled) { }

    /** 排版结果；行号相对审批区起点，宿主平移后写入 Frame hit。 */
    public record Rendered(List<StyledLine> lines, int choiceRow, int choiceColumn, List<Button> buttons) {
        public Rendered { lines = List.copyOf(lines); buttons = List.copyOf(buttons); }
    }

    private static final String[] LABELS = {"Allow once", "Allow similar", "Deny"};

    /**
     * @param state 当前审批状态（只读；会更新页大小/偏移夹取）
     * @param context 同帧上下文
     * @param width 可用列
     * @param maxRows 审批区最多行数（dock 空间）
     */
    public Rendered render(ApprovalViewState state, RenderContext context, int width, int maxRows) {
        var out = new ArrayList<StyledLine>();
        if (!state.active()) return new Rendered(out, 0, 0, List.of());
        ApprovalViewState.Request request = state.request();
        String actor = request.actor() == null || request.actor().isBlank() ? ""
                : com.jadecode.cli.tui.TuiState.actorName(request.actor()) + " · ";
        out.add(StyledLine.of(DisplayWidth.ellipsize(UiMessages.APPROVAL + " · " + actor + request.tool(), width),
                context.theme().style("warning")));
        String source = (request.reason().isBlank() ? "" : request.reason() + "\n") + "Parameters:\n"
                + TextSanitizer.visible(request.arguments())
                + (request.suggestion().isBlank() ? "" : "\nLong-term scope:\n" + request.suggestion());
        List<StyledLine> details = StyledLine.wrap(List.of(new Span(source, context.theme().style("text"), null)),
                Math.max(1, width));
        boolean stacked = width < 46;
        // 固定开销：标题 1 + 阅读提示 1 + 按钮（横排 1/竖排 3）+ 说明 1；maxRows 不足时优先保证按钮。
        int fixed = 1 + (stacked ? 3 : 1) + 2;
        int page = Math.max(0, maxRows - fixed);
        state.page(page, details.size());
        if (page < 1 || width < 20) {
            out.add(StyledLine.of(DisplayWidth.ellipsize("Window too small to review safely; enlarge to approve.", width),
                    context.theme().style("warning")));
            return new Rendered(out, 0, 0, List.of());
        }
        out.add(StyledLine.of(DisplayWidth.ellipsize(
                "Details " + (state.offset() + 1) + "–" + Math.min(details.size(), state.offset() + page)
                        + " / " + details.size() + " lines · PgUp/PgDn to read", width), context.theme().style("muted")));
        out.addAll(details.subList(state.offset(), Math.min(details.size(), state.offset() + page)));

        var buttons = new ArrayList<Button>();
        int choice = state.choice();
        if (stacked) {
            for (int i = 0; i < LABELS.length; i++) {
                boolean enabled = i != 1 || !request.suggestion().isBlank();
                String label = buttonLabel(i, choice, enabled);
                int row = out.size();
                out.add(StyledLine.of(label, buttonStyle(context, i, choice, enabled)));
                if (enabled) buttons.add(new Button(row, 0, DisplayWidth.width(label), token(i), true));
            }
        } else {
            var spans = new ArrayList<Span>();
            int column = 0, selectedColumn = 0;
            int row = out.size();
            for (int i = 0; i < LABELS.length; i++) {
                boolean enabled = i != 1 || !request.suggestion().isBlank();
                String label = buttonLabel(i, choice, enabled);
                if (i > 0) { spans.add(new Span("  ", Style.PLAIN, null)); column += 2; }
                if (choice == i) selectedColumn = column;
                spans.add(new Span(label, buttonStyle(context, i, choice, enabled), null));
                if (enabled) buttons.add(new Button(row, column, column + DisplayWidth.width(label), token(i), true));
                column += DisplayWidth.width(label);
            }
            out.add(new StyledLine(spans));
            out.add(StyledLine.of(DisplayWidth.ellipsize("↑↓ read · ←/→ or Tab select · Enter confirm · Esc does not answer", width),
                    context.theme().style("muted")));
            return new Rendered(out, row, selectedColumn, buttons);
        }
        out.add(StyledLine.of(DisplayWidth.ellipsize("Tab select · Enter confirm · Esc does not answer", width),
                context.theme().style("muted")));
        int choiceRow = Math.max(1, out.size() - LABELS.length - 1) + Math.max(0, choice);
        return new Rendered(out, choiceRow, 0, buttons);
    }

    private static String buttonLabel(int index, int choice, boolean enabled) {
        String marker = choice == index ? "›" : " ";
        return "[" + marker + " " + LABELS[index] + (enabled ? "" : " (n/a)") + " ]";
    }

    private static Style buttonStyle(RenderContext context, int index, int choice, boolean enabled) {
        if (!enabled) return context.theme().style("dim");
        if (choice != index) return context.theme().style(index == 2 ? "approvalDeny" : "approval");
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
