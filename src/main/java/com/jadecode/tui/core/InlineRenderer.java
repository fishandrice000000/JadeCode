package com.jadecode.tui.core;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;

/**
 * Pi regular 风格主屏差分渲染。只在界面线程使用，不认识消息或工具。
 * 已滚出视口的行无法被 ANSI 寻址；尺寸变化或旧行改变时必须清屏／scrollback 后重放。
 */
public final class InlineRenderer {
    private final PrintWriter output;
    private List<StyledLine> previous = List.of();
    private int viewportTop, cursorRow, width, height;
    private Theme lastTheme;
    private Frame visible;
    /** 终端改变尺寸后，旧坐标即使仍在旧 Frame 中，也不能继续用于审批确认。 */
    private boolean actionsValid;
    public InlineRenderer(PrintWriter output) { this.output = output; }
    /** 结束时移到实际内容下方，避免返回 shell 后提示符覆盖编辑器或页脚。 */
    public void finish() {
        if (visible != null) move(visible.lines().size() - 1);
        output.print("\r\n"); output.flush();
    }

    /** 根据最后真正画出的帧处理点击，不能使用尚未绘制的新状态。 */
    public String hit(int column, int row) {
        if (visible == null || !actionsValid) return null;
        int documentRow = viewportTop + row;
        return visible.hits().stream().filter(h -> h.row() == documentRow && column >= h.fromColumn()
                && column < h.toColumn()).map(Frame.Hit::action).findFirst().orElse(null);
    }
    /** 确认选项确实已经画在可见帧上，防止上一份审批的排队按键作用于尚未显示的新申请。 */
    public boolean hasAction(String action) {
        return visible != null && actionsValid && visible.hits().stream().anyMatch(h -> h.action().equals(action)
                && h.row() >= viewportTop && h.row() < viewportTop + height);
    }

    /**
     * 收到窗口尺寸变化后立即停止使用旧画面的操作位置，防止同一批事件中的 Enter 盲批。
     * 不清屏、不删除显示记录；下一次 render 真正写完新画面后才重新启用确认位置。
     * 只由界面线程调用，与 render、hit 和 hasAction 保持相同的线程使用约定。
     */
    public void invalidateActions() { actionsValid = false; }

    /**
     * 比较样式行后只重写变化的尾部。最后一列由布局保留，避免终端自动折行破坏行号。
     * 本方法唯一产生光标控制序列，写入的正文必须已经清理；不修改输入帧。
     */
    public void render(Frame frame, RenderContext context) {
        if (frame.lines().isEmpty()) return;
        boolean layoutChanged = previous.isEmpty() || width != context.width() || height != context.height()
                || !context.theme().equals(lastTheme);
        // 候选栏收起后，已滚动过的终端不会自动把较短画面向下挪。
        // 保留当前可见区的底部行号，把多出来的空行放在输入区上方，而不是页脚下方。
        Frame painted = anchorBottom(frame, (layoutChanged ? 0 : viewportTop) + context.height());
        List<StyledLine> lines = painted.lines();
        int first = 0;
        while (first < Math.min(lines.size(), previous.size()) && lines.get(first).equals(previous.get(first))) first++;
        boolean reset = layoutChanged || first < viewportTop;
        if (reset) {
            // 真正重放整篇内容时会重新建立可见区，不沿用上一份画面的滚动行号补空行。
            painted = anchorBottom(frame, context.height()); lines = painted.lines();
        }
        output.print("\033[?2026h\033[?25l");
        if (reset) {
            if (previous.isEmpty()) {
                // 首帧尚不知道 shell 留下的物理行号。把原屏滚入历史，建立可寻址视口，
                // 不在启动时清空 scrollback；后续真正重排旧内容才使用 Pi 的清除策略。
                output.print("\r\n".repeat(context.height()) + "\033[H\033[2J");
            } else output.print("\033[H\033[2J\033[3J");
            viewportTop = 0; cursorRow = 0; first = 0;
        }
        if (reset || first < lines.size() || lines.size() < previous.size()) {
            int start = Math.min(first, lines.size() - 1);
            // 若是在旧末行后追加，先从旧末行换行；不能用向下移动制造一个不存在的文档行。
            if (!reset && start >= previous.size()) {
                move(previous.size() - 1); newline(previous.size(), context.height());
            } else move(start);
            for (int row = start; row < lines.size(); row++) {
                output.print("\r\033[2K"); output.print(context.theme().encode(lines.get(row))); cursorRow = row;
                if (row + 1 < lines.size()) newline(row + 1, context.height());
            }
            output.print("\033[J"); // 新内容变短时清掉旧尾部，而不是留下重复的输入框。
        }
        int target = Math.max(viewportTop, Math.min(painted.cursorRow(), lines.size() - 1));
        move(target); output.print("\033[" + (Math.max(0, painted.cursorColumn()) + 1) + "G\033[?25h\033[?2026l");
        output.flush();
        if (output.checkError()) throw new IllegalStateException("终端写入失败，停止交互会话");
        previous = lines; visible = painted; actionsValid = true;
        width = context.width(); height = context.height(); lastTheme = context.theme();
    }

    /**
     * 在底部输入区域之前补齐显示空行，使页脚落在当前窗口最后一行。
     * minimumRows 是当前可见区末尾的下一行；新内容更长时不补，让正常换行推进滚动。
     * 空行只属于这次显示，不写入聊天历史；后续消息增长时会逐步占用这些空位。
     * 返回新帧并同步平移输入区的光标与点击位置，避免审批按钮看到的位置与可确认的位置不一致。
     * 普通四参数帧没有底部标记，原样返回，不改变独立组件的渲染方式。
     */
    private Frame anchorBottom(Frame frame, int minimumRows) {
        int start = frame.bottomStartRow(), padding = minimumRows - frame.lines().size();
        if (start < 0 || padding <= 0) return frame;
        var lines = new ArrayList<StyledLine>(minimumRows);
        lines.addAll(frame.lines().subList(0, start));
        for (int row = 0; row < padding; row++) lines.add(StyledLine.of("", "normal"));
        lines.addAll(frame.lines().subList(start, frame.lines().size()));
        // 消息卡仍在原位置；只有输入区域及其后的行需要平移，不能给所有 hit 无差别加偏移。
        var hits = frame.hits().stream().map(hit -> hit.row() < start ? hit
                : new Frame.Hit(hit.row() + padding, hit.fromColumn(), hit.toColumn(), hit.action())).toList();
        int cursor = frame.cursorRow() < start ? frame.cursorRow() : frame.cursorRow() + padding;
        return new Frame(lines, cursor, frame.cursorColumn(), hits, start + padding);
    }
    private void move(int row) {
        int delta = row - cursorRow;
        if (delta != 0) output.print("\033[" + Math.abs(delta) + (delta > 0 ? "B" : "A"));
        output.print('\r'); cursorRow = row;
    }
    private void newline(int nextRow, int rows) {
        output.print("\r\n"); cursorRow = nextRow;
        if (nextRow >= viewportTop + rows) viewportTop = nextRow - rows + 1;
    }
}
