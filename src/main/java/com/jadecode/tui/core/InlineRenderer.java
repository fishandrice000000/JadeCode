package com.jadecode.tui.core;

import java.io.PrintWriter;
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
    public InlineRenderer(PrintWriter output) { this.output = output; }
    /** 结束时移到实际内容下方，避免返回 shell 后提示符覆盖编辑器或页脚。 */
    public void finish() {
        if (visible != null) move(visible.lines().size() - 1);
        output.print("\r\n"); output.flush();
    }

    /** 根据最后真正画出的帧处理点击，不能使用尚未绘制的新状态。 */
    public String hit(int column, int row) {
        if (visible == null) return null;
        int documentRow = viewportTop + row;
        return visible.hits().stream().filter(h -> h.row() == documentRow && column >= h.fromColumn()
                && column < h.toColumn()).map(Frame.Hit::action).findFirst().orElse(null);
    }
    /** 确认选项确实已经画在可见帧上，防止上一份审批的排队按键作用于尚未显示的新申请。 */
    public boolean hasAction(String action) {
        return visible != null && visible.hits().stream().anyMatch(h -> h.action().equals(action)
                && h.row() >= viewportTop && h.row() < viewportTop + height);
    }

    /**
     * 比较样式行后只重写变化的尾部。最后一列由布局保留，避免终端自动折行破坏行号。
     * 本方法唯一产生光标控制序列，写入的正文必须已经清理；不修改输入帧。
     */
    public void render(Frame frame, RenderContext context) {
        List<StyledLine> lines = frame.lines();
        if (lines.isEmpty()) return;
        int first = 0;
        while (first < Math.min(lines.size(), previous.size()) && lines.get(first).equals(previous.get(first))) first++;
        boolean reset = previous.isEmpty() || width != context.width() || height != context.height()
                || !context.theme().equals(lastTheme) || first < viewportTop;
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
        int target = Math.max(viewportTop, Math.min(frame.cursorRow(), lines.size() - 1));
        move(target); output.print("\033[" + (Math.max(0, frame.cursorColumn()) + 1) + "G\033[?25h\033[?2026l");
        output.flush();
        if (output.checkError()) throw new IllegalStateException("终端写入失败，停止交互会话");
        previous = lines; visible = frame;
        width = context.width(); height = context.height(); lastTheme = context.theme();
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
