package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.text.DisplayWidth;

/**
 * 把逻辑正文排成视觉行，并给出 UTF-16 偏移 ↔（视觉行, 列）映射。
 *
 * <p>编辑器绘制、上下移动、硬件光标/IME 定位和鼠标点击都使用同一份布局；换行按完整字素
 * 和终端列宽进行，宽字符不会跨行拆开。行文本保存原始片段（含空格），不重新解释提交内容。
 * 换行优先在空白后断开以减少词中间断裂，但不会丢失/增加字符；超长无空白词仍按列硬切。</p>
 */
public final class EditorLayout {
    /** 一条视觉行：源文区间 [start, end)，显示文本（不含换行符）。 */
    public record Line(int start, int end, String text) { }

    /** 一个光标位置：源文偏移、视觉行、行内显示列。 */
    public record Position(int offset, int row, int column) { }

    private final List<Line> lines;
    private final List<Position> positions;

    private EditorLayout(List<Line> lines, List<Position> positions) {
        this.lines = List.copyOf(lines); this.positions = List.copyOf(positions);
    }

    /** @return 视觉行快照。 */
    public List<Line> lines() { return lines; }

    /** @return 所有可放置光标的位置，按源文顺序。 */
    public List<Position> positions() { return positions; }

    /**
     * 按给定列宽排版。宽度至少 1；空文本也产生一条空行，保证光标有位置。
     * 换行符属于上一行的结束位置，下一行从换行符之后开始。
     */
    public static EditorLayout of(String text, int columns) {
        int width = Math.max(1, columns);
        var lines = new ArrayList<Line>();
        var positions = new ArrayList<Position>();
        int lineStart = 0, lineWidth = 0, offset = 0;
        var lineText = new StringBuilder();
        for (String cluster : DisplayWidth.clusters(text)) {
            int cells = DisplayWidth.clusterWidth(cluster);
            if (cluster.equals("\n")) {
                positions.add(new Position(offset, lines.size(), lineWidth));
                lines.add(new Line(lineStart, offset, lineText.toString()));
                lineStart = offset + 1; lineText.setLength(0); lineWidth = 0; offset++;
                continue;
            }
            boolean wrap = lineWidth > 0 && lineWidth + cells > width;
            if (wrap) {
                // 在最近空白后断行，保留该空白在上一行；找不到则硬切。
                int breakAt = lastBreak(lineText);
                if (breakAt > 0 && breakAt < lineText.length()) {
                    int breakOffset = lineStart + breakAt;
                    lines.add(new Line(lineStart, breakOffset, lineText.substring(0, breakAt)));
                    positions.add(new Position(breakOffset, lines.size() - 1, DisplayWidth.width(lineText.substring(0, breakAt))));
                    lineStart = breakOffset;
                    lineText.delete(0, breakAt);
                    lineWidth = DisplayWidth.width(lineText.toString());
                } else {
                    lines.add(new Line(lineStart, offset, lineText.toString()));
                    lineStart = offset; lineText.setLength(0); lineWidth = 0;
                }
            }
            positions.add(new Position(offset, lines.size(), lineWidth));
            lineText.append(cluster); lineWidth += cells; offset += cluster.length();
        }
        positions.add(new Position(offset, lines.size(), lineWidth));
        lines.add(new Line(lineStart, offset, lineText.toString()));
        return new EditorLayout(lines, positions);
    }

    /** 在视觉行文本中寻找最近的空白断点（其后断行），返回切点字符下标。 */
    private static int lastBreak(StringBuilder line) {
        for (int i = line.length() - 1; i > 0; i--) if (Character.isWhitespace(line.charAt(i))) return i + 1;
        return -1;
    }

    /** @return 指定偏移的光标位置；偏移不在边界时向后取整。 */
    public Position positionOf(int offset) {
        for (Position position : positions) if (position.offset() >= offset) return position;
        return positions.getLast();
    }

    /** @return 指定视觉行列的最近源文偏移；用于上下移动与点击。 */
    public int offsetAt(int row, int column) {
        if (lines.isEmpty()) return 0;
        int target = Math.max(0, Math.min(row, lines.size() - 1));
        Line line = lines.get(target);
        String text = line.text();
        int used = 0;
        for (String cluster : DisplayWidth.clusters(text)) {
            int cells = DisplayWidth.clusterWidth(cluster);
            if (used + cells > column) return line.start() + offsetWithin(text, used);
            used += cells;
        }
        return line.end();
    }

    /** 在显示列 used 处找到行内 UTF-16 偏移；需要重新按字素累计。 */
    private static int offsetWithin(String text, int targetColumn) {
        int used = 0, offset = 0;
        for (String cluster : DisplayWidth.clusters(text)) {
            if (used >= targetColumn) return offset;
            used += DisplayWidth.clusterWidth(cluster);
            offset += cluster.length();
        }
        return text.length();
    }

    /** @return 光标所在视觉行。 */
    public int rowOf(int offset) { return positionOf(offset).row(); }

    /** @return 光标所在显示列。 */
    public int columnOf(int offset) { return positionOf(offset).column(); }

    /**
     * 选择可见窗口顶部，使光标始终可见。
     * @param caretRow 光标视觉行
     * @param visibleRows 可显示内容行数（不含边框/候选）
     */
    public int topFor(int caretRow, int visibleRows) {
        int rows = Math.max(1, visibleRows);
        return Math.max(0, Math.min(caretRow - rows + 1, Math.max(0, lines.size() - rows)));
    }
}
