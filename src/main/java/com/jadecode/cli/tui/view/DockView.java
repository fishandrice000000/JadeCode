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
    private final SelectorPanelView selectorPanel = new SelectorPanelView();
    private final StatusView statusView = new StatusView();
    private final FooterView footerView = new FooterView();

    /** 组装 dock（无外部高度限制；regular/测试使用）。 */
    public Layout render(TuiState state, RenderContext context, int width) {
        return render(state, context, width, Integer.MAX_VALUE);
    }

    /**
     * 组装 dock；fullscreen 传 rows-1 作为上限，先扣除状态/footer/widget，再给选择器面板
     * 分配剩余高度，避免 Application 最后截断底边、提示或 footer。
     */
    public Layout render(TuiState state, RenderContext context, int width, int maxTotalRows) {
        int maxRows = Math.max(1, maxTotalRows);
        var lines = new ArrayList<StyledLine>();
        var hits = new ArrayList<Frame.Hit>();
        // 扩展 widget 上限 10 行；超出不显示，不静默塞进状态行。
        var upper = new ArrayList<StyledLine>();
        if (state.upperWidgets.size() > TuiState.MAX_WIDGET_ROWS)
            upper.add(StyledLine.of(DisplayWidth.ellipsize("Widget slot capped at 10 rows", width),
                    context.theme().style("warning")));
        upper.addAll(state.upperWidgets.subList(0, Math.min(TuiState.MAX_WIDGET_ROWS, state.upperWidgets.size())));

        // 先构造底部固定区域，避免选择器把 footer/status 挤出屏幕。
        var tail = new ArrayList<StyledLine>();
        tail.addAll(statusView.render(state, context, width));
        tail.addAll(footerView.render(state, context, width));
        if (state.closing) tail.add(StyledLine.of(DisplayWidth.ellipsize(UiMessages.CLOSING, width),
                context.theme().style("warning")));
        if (!state.hint.isBlank() && state.selector == null && state.approvals.isEmpty())
            tail.add(StyledLine.of(DisplayWidth.ellipsize(state.hint, width), context.theme().style("warning")));
        if (state.lowerWidgets.size() > TuiState.MAX_WIDGET_ROWS)
            tail.add(StyledLine.of(DisplayWidth.ellipsize("Widget slot capped at 10 rows", width),
                    context.theme().style("warning")));
        tail.addAll(state.lowerWidgets.subList(0, Math.min(TuiState.MAX_WIDGET_ROWS, state.lowerWidgets.size())));

        // 极矮屏优先保护输入/footer：widget 超过剩余空间时只保留能放下的前缀。
        int upperBudget = Math.max(0, maxRows - tail.size() - 3);
        if (upper.size() > upperBudget) upper = new ArrayList<>(upper.subList(0, upperBudget));
        int inputBudget = Math.max(1, maxRows - upper.size() - tail.size());

        int cursorRow = 0, cursorColumn = 0;
        boolean cursorVisible = false;
        if (!state.approvals.isEmpty() && state.approval.active()) {
            var rendered = approvalView.render(state.approval, context, width,
                    Math.max(6, Math.min(inputBudget, context.height() - 5)));
            int start = upper.size();
            lines.addAll(upper); lines.addAll(rendered.lines());
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
            var panel = selectorPanel.render(state, context, width, inputBudget);
            int start = upper.size();
            lines.addAll(upper); lines.addAll(panel.lines());
            for (var item : panel.itemHits()) {
                hits.add(new Frame.Hit(new Rect(0, start + item.row(), Math.max(1, width), 1),
                        new ActionToken(ActionToken.Kind.SELECT_ITEM, item.id(), context.revision())));
            }
            cursorRow = start + panel.cursorRow();
            cursorColumn = panel.cursorColumn();
            cursorVisible = panel.cursorVisible();
        } else if (state.editor != null) {
            lines.addAll(upper);
            var rendered = state.editor.render(context, width);
            lines.addAll(rendered);
            var cursor = state.editor.cursor(context);
            cursorRow = upper.size() + cursor.row(); cursorColumn = cursor.column(); cursorVisible = cursor.visible();
        } else {
            lines.addAll(upper);
        }

        lines.addAll(tail);
        // 仅 selector/审批使用严格预算；普通编辑器沿用原布局，避免改变既有键盘行为。
        return new Layout(lines, cursorRow, cursorColumn, cursorVisible, hits);
    }
}
