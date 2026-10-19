package com.jadecode.tui.components;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;

/**
 * 多行编辑状态，由界面线程独占。cursor 是 UTF-16 偏移，但移动／删除以完整字素为单位。
 * 大段粘贴以不可拆的占位编辑，提交时还原正文；草稿、撤销和历史都不由模型线程修改。
 */
public final class Editor {
    private String text = "";
    private int cursor;
    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>();
    private final List<String> history = new ArrayList<>();
    private int historyIndex;
    private String historyDraft = "";
    /** 最近一次排版的正文列数；上下键按屏幕软换行移动，不把首个逻辑行误当历史入口。 */
    private int visualColumns = 78;
    private final LinkedHashMap<String, String> pastes = new LinkedHashMap<>();
    private record Snapshot(String text, int cursor) { }
    /** 文本行和相对于这些行的光标，供外层布局一起移动。 */
    public record Layout(List<StyledLine> lines, int row, int column) { }
    public String text() { return text; }
    public boolean empty() { return text.isEmpty(); }

    /** 插入按键或粘贴。只有粘贴的大块正文折叠，换行绝不在这里触发提交。 */
    public void insert(String value, boolean paste) {
        save();
        value = value.replace("\r\n", "\n").replace('\r', '\n');
        if (paste && (value.length() > 1000 || value.lines().count() > 10)) {
            String marker = "[Paste " + java.util.UUID.randomUUID().toString().substring(0, 8) + " · " + value.lines().count() + " lines]";
            pastes.put(marker, value); value = marker;
        }
        text = text.substring(0, cursor) + value + text.substring(cursor); cursor += value.length();
    }
    /** 提交时展开完整原文，并把展开后的输入放进本进程历史；不持久化草稿。 */
    public String submit() {
        String result = text;
        for (var paste : pastes.entrySet()) result = result.replace(paste.getKey(), paste.getValue());
        if (!result.isBlank()) { history.add(result); if (history.size() > 200) history.removeFirst(); }
        text = ""; cursor = 0; undo.clear(); pastes.clear(); historyIndex = history.size(); historyDraft = "";
        return result;
    }
    /** 清空也可撤销；Ctrl+C 不负责取消 Agent。 */
    public void clear() { save(); text = ""; cursor = 0; }
    /** 按完整字素和完整粘贴标记移动，不能把一个 emoji 或折叠正文拆碎。 */
    public void move(int direction) {
        int next = direction < 0 ? previous(cursor) : following(cursor);
        cursor = snap(next, direction);
    }
    /** Home/End 只移动到当前逻辑行头尾。 */
    public void home() { cursor = text.lastIndexOf('\n', Math.max(-1, cursor - 1)) + 1; }
    public void end() { int next = text.indexOf('\n', cursor); cursor = next < 0 ? text.length() : next; }
    /** 删除一个字素／整块粘贴，backward 决定删除光标前还是后。 */
    public void delete(boolean backward) {
        int other = snap(backward ? previous(cursor) : following(cursor), backward ? -1 : 1);
        if (cursor == other) return;
        save(); int left = Math.min(cursor, other), right = Math.max(cursor, other);
        text = text.substring(0, left) + text.substring(right); cursor = left;
    }
    public void killEnd() { save(); int old = cursor; end(); text = text.substring(0, old) + text.substring(cursor); cursor = old; }
    public void deleteWord() {
        save(); int old = cursor;
        while (cursor > 0 && Character.isWhitespace(text.charAt(cursor - 1))) cursor = previous(cursor);
        while (cursor > 0 && !Character.isWhitespace(text.charAt(cursor - 1))) cursor = previous(cursor);
        cursor = snap(cursor, -1); text = text.substring(0, cursor) + text.substring(old);
    }
    public void undo() { if (!undo.isEmpty()) { Snapshot s = undo.removeLast(); text = s.text(); cursor = s.cursor(); } }

    /** 上下优先按可见软换行移动；仅在草稿的第一／最后显示行之外浏览历史。 */
    public void vertical(int direction) {
        Visual visual = visual(visualColumns);
        Position current = visual.positions().stream().filter(p -> p.offset() == cursor).findFirst().orElse(visual.positions().getLast());
        int target = current.row() + direction;
        if (target >= 0 && target < visual.lines().size()) {
            Position chosen = null;
            for (Position position : visual.positions()) {
                if (position.row() == target && position.column() <= current.column()) chosen = position;
            }
            if (chosen == null) chosen = visual.positions().stream().filter(p -> p.row() == target).findFirst().orElse(current);
            cursor = chosen.offset();
        } else if (!history.isEmpty()) {
            if (historyIndex == history.size()) historyDraft = text;
            historyIndex = Math.max(0, Math.min(history.size(), historyIndex + direction));
            text = historyIndex == history.size() ? historyDraft : history.get(historyIndex); cursor = text.length();
        }
        cursor = snap(cursor, direction);
    }

    /** 排版草稿并让光标附近可见。超出高度的部分只从编辑窗口隐藏，正文不会截断。 */
    public Layout layout(int width, int height) {
        int columns = Math.max(1, width - 2); visualColumns = columns;
        Visual visual = visual(columns); var raw = visual.lines();
        Position caret = visual.positions().stream().filter(p -> p.offset() == cursor).findFirst().orElse(visual.positions().getLast());
        int caretRow = caret.row(), caretColumn = caret.column();
        int count = Math.max(1, height), top = Math.max(0, caretRow - count + 1);
        var lines = new ArrayList<StyledLine>();
        for (int i = top; i < Math.min(raw.size(), top + count); i++)
            lines.add(StyledLine.of((i == 0 ? "› " : "  ") + DisplayWidth.take(DisplayWidth.clean(raw.get(i)), columns), "normal"));
        return new Layout(lines, caretRow - top, caretColumn + 2);
    }
    private record Position(int offset, int row, int column) { }
    private record Visual(List<String> lines, List<Position> positions) { }
    /** 排版和上下移动共用坐标计算，尤其区分“光标在换行前”与“换行后”。 */
    private Visual visual(int columns) {
        var lines = new ArrayList<String>(); var positions = new ArrayList<Position>();
        var line = new StringBuilder(); int row = 0, column = 0, offset = 0;
        for (String cluster : DisplayWidth.clusters(text)) {
            // 粘贴正文可能带 Tab。原文仍按 UTF-16 下标编辑和提交，显示时则展开为四个空格；
            // 必须按展开后的列宽算光标，否则“a\tb”的光标会少走四列，软换行也会算错。
            // 控制字符也在这里去掉，不等排完行后才清理，以免显示与坐标使用不同的文字。
            String displayed = DisplayWidth.clean(cluster);
            int cells = DisplayWidth.width(displayed);
            if (!cluster.equals("\n") && column + cells > columns) {
                lines.add(line.toString()); line.setLength(0); row++; column = 0;
            }
            positions.add(new Position(offset, row, column));
            if (cluster.equals("\n")) { lines.add(line.toString()); line.setLength(0); row++; column = 0; }
            else { line.append(displayed); column += cells; }
            offset += cluster.length();
        }
        lines.add(line.toString()); positions.add(new Position(offset, row, column));
        return new Visual(lines, positions);
    }
    private void save() { undo.addLast(new Snapshot(text, cursor)); if (undo.size() > 100) undo.removeFirst(); }
    private int previous(int offset) {
        if (offset == 0) return 0;
        var clusters = DisplayWidth.clusters(text.substring(0, offset)); return offset - clusters.getLast().length();
    }
    private int following(int offset) {
        return offset == text.length() ? offset : offset + DisplayWidth.clusters(text.substring(offset)).getFirst().length();
    }
    private int snap(int offset, int direction) {
        for (String marker : pastes.keySet()) {
            int start = text.indexOf(marker);
            if (start >= 0 && offset > start && offset < start + marker.length()) return direction < 0 ? start : start + marker.length();
        }
        return offset;
    }
}
