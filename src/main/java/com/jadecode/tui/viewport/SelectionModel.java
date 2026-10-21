package com.jadecode.tui.viewport;

import java.util.List;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;

/**
 * 全屏转录的字符/词/行选区，坐标使用“文档行 + 显示列”。
 *
 * <p>鼠标按下/拖动/释放由 ChatViewport 换算成文档坐标后调用；选区文本通过源行纯文字和
 * {@link DisplayWidth#offsetAtColumn(String, int)} 提取，宽字符和 emoji 不会切半。隐藏的
 * 图片/光标标记不属于正文，不会被复制。单击与拖选互斥：点击链接时宿主先判断命中，不启动
 * 选区。选区在 resize/内容重排后由宿主清空，不能用旧列号复制另一段文字。</p>
 */
public final class SelectionModel {
    /** 选择粒度；双击/三击切换。 */
    public enum Mode { CHARACTER, WORD, LINE }

    /** 文档中的一个位置：row 是完整转录行号，column 是显示列。 */
    public record Position(int row, int column) {
        public Position {
            row = Math.max(0, row);
            column = Math.max(0, column);
        }
    }

    /** 一行内的选中列区间 [from, to)；空区间表示该行没有选中。 */
    public record Range(int row, int fromColumn, int toColumn) {
        public boolean empty() { return toColumn <= fromColumn; }
    }

    private Position anchor;
    private Position focus;
    private Mode mode = Mode.CHARACTER;
    private boolean dragging;

    /** @return 是否已有非空选区。 */
    public boolean active() { return anchor != null && focus != null && !(anchor.equals(focus)); }

    /** @return 选区粒度。 */
    public Mode mode() { return mode; }

    /** @return 是否正在拖动（用于边缘自动滚动）。 */
    public boolean dragging() { return dragging; }

    /** 开始选择；点击次数决定字符/词/行模式。 */
    public void start(Position position, int clickCount) {
        anchor = position; focus = position; dragging = true;
        mode = clickCount >= 3 ? Mode.LINE : clickCount == 2 ? Mode.WORD : Mode.CHARACTER;
    }

    /** 拖动到新位置；顺序可逆。 */
    public void drag(Position position) {
        if (anchor == null) return;
        focus = position; dragging = true;
    }

    /** 结束拖动；保持选区可见。 */
    public void finish() { dragging = false; }

    /** 清空选区；点击空白/模式切换/resize 使用。 */
    public void clear() { anchor = null; focus = null; dragging = false; }

    /**
     * @param row 文档行号
     * @param plain 该行纯文字
     * @return 该行的选中列区间；无选区返回 empty range
     */
    public Range rangeFor(int row, String plain) {
        if (anchor == null || focus == null) return new Range(row, 0, 0);
        Position start = before(anchor, focus) ? anchor : focus;
        Position end = before(anchor, focus) ? focus : anchor;
        if (row < start.row() || row > end.row()) return new Range(row, 0, 0);
        int lineWidth = DisplayWidth.width(plain);
        int from = row == start.row() ? Math.min(start.column(), lineWidth) : 0;
        int to = row == end.row() ? Math.min(end.column(), lineWidth) : lineWidth;
        if (mode == Mode.LINE) { from = 0; to = lineWidth; }
        if (mode == Mode.WORD) {
            int[] word = wordRange(plain, row == start.row() ? from : 0);
            if (row == start.row()) from = word[0];
            if (row == end.row()) to = Math.max(from, word[1]);
        }
        return new Range(row, Math.max(0, from), Math.max(0, to));
    }

    /** @return 选区是否包含该位置。 */
    public boolean contains(int row, int column) {
        if (anchor == null || focus == null) return false;
        Position start = before(anchor, focus) ? anchor : focus;
        Position end = before(anchor, focus) ? focus : anchor;
        if (row < start.row() || row > end.row()) return false;
        if (row == start.row() && column < start.column()) return false;
        if (row == end.row() && column >= end.column()) return false;
        return true;
    }

    /**
     * 提取纯文字；行间用 `\n` 连接，行尾空白保留原文。图片/光标标记不在 StyledLine 正文中，
     * 因此不会进入剪贴板。
     */
    public String text(List<StyledLine> document) {
        if (!active()) return "";
        Position start = before(anchor, focus) ? anchor : focus;
        Position end = before(anchor, focus) ? focus : anchor;
        var out = new StringBuilder();
        for (int row = start.row(); row <= end.row() && row < document.size(); row++) {
            String plain = document.get(row).text();
            Range range = rangeFor(row, plain);
            int from = DisplayWidth.offsetAtColumn(plain, range.fromColumn());
            int to = DisplayWidth.offsetAtColumn(plain, range.toColumn());
            if (row > start.row()) out.append('\n');
            if (to > from) out.append(plain, from, to);
        }
        return out.toString();
    }

    private static boolean before(Position first, Position second) {
        return first.row() < second.row() || first.row() == second.row() && first.column() <= second.column();
    }

    /** 以列为中心的连续词范围；找不到词时退化为单字符范围。 */
    private static int[] wordRange(String plain, int column) {
        int offset = DisplayWidth.offsetAtColumn(plain, column);
        int start = offset, end = offset;
        while (start > 0 && isWordChar(plain.codePointBefore(start))) start--;
        while (end < plain.length() && isWordChar(plain.codePointAt(end))) end++;
        if (start == end && !plain.isEmpty()) {
            end = DisplayWidth.nextOffset(plain, offset);
            if (end == offset) start = Math.max(0, DisplayWidth.previousOffset(plain, offset));
        }
        return new int[]{DisplayWidth.columnAtOffset(plain, start), DisplayWidth.columnAtOffset(plain, end)};
    }

    private static boolean isWordChar(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-';
    }
}
