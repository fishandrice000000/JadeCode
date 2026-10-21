package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.cli.tui.approval.ApprovalView;
import com.jadecode.cli.tui.approval.ApprovalViewState;
import com.jadecode.tui.components.SelectList;
import com.jadecode.tui.core.ActionToken;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.geometry.Rect;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;

/**
 * 底部 dock 的优先组合：审批 > 菜单/选择器 > 编辑器；之后是状态、footer、widget 与提示。
 *
 * <p>同一时间只显示一个输入对象；被盖住的编辑器草稿/光标完整保留。审批按钮的命中区域
 * 与文本在同一次布局中生成，动作带当前 layout revision；窗口太小或审批无按钮时不会生成
 * 可批准 hit。所有自有提示英文；不在这里执行命令或修改业务状态。</p>
 */
public final class DockView {
    /**
     * 排版结果。
     * @param lines dock 完整行（从 dock 顶部计）
     * @param cursorRow 光标在 dock 内的行；不可见时为 0
     * @param cursorColumn 光标列
     * @param cursorVisible 是否显示硬件光标
     * @param hits 绝对 dock 行号的动作区域
     */
    public record Layout(List<StyledLine> lines, int cursorRow, int cursorColumn, boolean cursorVisible,
            List<Frame.Hit> hits) {
        public Layout { lines = List.copyOf(lines); hits = List.copyOf(hits); }
    }

    private final ApprovalView approvalView = new ApprovalView();
    private final StatusView statusView = new StatusView();
    private final FooterView footerView = new FooterView();

    /** 组装 dock；调用方把返回行接到视口下方，并按 dockTop 平移 hits。 */
    public Layout render(TuiState state, RenderContext context, int width) {
        var lines = new ArrayList<StyledLine>();
        var hits = new ArrayList<Frame.Hit>();
        // 扩展 widget 上限 10 行；超出不显示，不静默塞进状态行。
        if (state.upperWidgets.size() > TuiState.MAX_WIDGET_ROWS)
            lines.add(StyledLine.of(DisplayWidth.ellipsize("Widget slot capped at 10 rows", width),
                    context.theme().style("warning")));
        lines.addAll(state.upperWidgets.subList(0, Math.min(TuiState.MAX_WIDGET_ROWS, state.upperWidgets.size())));

        int cursorRow = 0, cursorColumn = 0;
        boolean cursorVisible = false;
        if (!state.approvals.isEmpty() && state.approval.active()) {
            int maxRows = Math.max(6, context.height() - 5);
            var rendered = approvalView.render(state.approval, context, width, maxRows);
            int start = lines.size();
            lines.addAll(rendered.lines());
            var request = state.approval.request();
            for (var button : rendered.buttons()) {
                if (!button.enabled()) continue;
                // 审批动作绑定权限 revision（RECHECK 会使旧按钮失效），不是普通布局 revision。
                var token = new ActionToken(button.kind(), request.id(), request.revision());
                hits.add(new Frame.Hit(new Rect(button.from(), start + button.row(),
                        Math.max(1, button.to() - button.from()), 1), token));
            }
            if (!rendered.buttons().isEmpty()) {
                cursorRow = start + Math.max(0, rendered.choiceRow());
                cursorColumn = rendered.choiceColumn();
            }
        } else if (state.selector != null) {
            SelectList list = state.selector;
            lines.add(StyledLine.of(DisplayWidth.ellipsize(selectorTitle(state), width),
                    context.theme().style("accent")));
            if (!state.approval.active() && state.selectorKind.equals("Permission"))
                lines.addAll(StyledLine.wrap(List.of(new com.jadecode.tui.text.Span(
                        PermissionDescription.of(list.selected()), context.theme().style("muted"), null)), width));
            var rendered = list.render(width, Math.max(1, Math.min(8, context.height() / 3)), context);
            lines.addAll(rendered);
            if (!state.hint.isBlank())
                lines.add(StyledLine.of(DisplayWidth.ellipsize(state.hint, width), context.theme().style("muted")));
        } else if (state.editor != null) {
            var rendered = state.editor.render(context, width);
            lines.addAll(rendered);
            var cursor = state.editor.cursor(context);
            cursorRow = cursor.row(); cursorColumn = cursor.column(); cursorVisible = cursor.visible();
        }

        lines.addAll(statusView.render(state, context, width));
        lines.addAll(footerView.render(state, context, width));
        // 状态/footer 不提供点击动作；光标仍属于输入区。
        if (state.closing) lines.add(StyledLine.of(DisplayWidth.ellipsize(UiMessages.CLOSING, width),
                context.theme().style("warning")));
        if (!state.hint.isBlank() && state.selector == null && state.approvals.isEmpty())
            lines.add(StyledLine.of(DisplayWidth.ellipsize(state.hint, width), context.theme().style("warning")));
        if (state.lowerWidgets.size() > TuiState.MAX_WIDGET_ROWS)
            lines.add(StyledLine.of(DisplayWidth.ellipsize("Widget slot capped at 10 rows", width),
                    context.theme().style("warning")));
        lines.addAll(state.lowerWidgets.subList(0, Math.min(TuiState.MAX_WIDGET_ROWS, state.lowerWidgets.size())));
        return new Layout(lines, cursorRow, cursorColumn, cursorVisible, hits);
    }

    private static String selectorTitle(TuiState state) {
        if (!state.selectorKind.isBlank()) return state.selectorKind + " · filter: " + state.selector.filter();
        return "Select";
    }

    /** 权限说明由 PermissionMenu 提供；这里只做安全回退，避免视图依赖具体菜单实现。 */
    private static final class PermissionDescription {
        static String of(String selected) {
            if (selected == null) return "";
            return switch (selected) {
                case "read-only" -> "Reads only; writes and commands need confirmation.";
                case "workspace" -> "Workspace reads/writes and builds run directly; deletes and outside paths ask.";
                case "auto-review" -> "Routine prompts go to the review model; uncertain or sensitive actions still ask.";
                case "full-access" -> "Routine actions skip prompts; explicit denies and hard protections remain.";
                default -> "";
            };
        }
    }
}
