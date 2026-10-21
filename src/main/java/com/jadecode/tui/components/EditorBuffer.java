package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.jadecode.tui.text.DisplayWidth;

/**
 * 编辑器的纯逻辑状态：逻辑文本、UTF-16 光标、字素边界移动和所有编辑操作。
 *
 * <p>不持有屏幕坐标、终端 writer 或补全请求；排版由 {@link EditorLayout} 负责。光标保存为
 * UTF-16 下标，但左右移动/删除都以完整字素为单位，避免把 emoji、组合符或折叠的粘贴标记
 * 拆成两半。撤销/粘贴登记/历史分别由 {@link UndoHistory}/{@link PasteRegistry}/
 * {@link EditorHistory} 管理；本类只协调顺序，并在提交时展开粘贴原文。</p>
 *
 * <p>当前实现用不可变字符串 + 快照撤销，文档规模（个人会话输入）足够；没有放入共享线程，
 * 所有方法只在 UI 线程调用。</p>
 */
public final class EditorBuffer {
    /** 编辑操作方向。 */
    public enum Direction { BACKWARD, FORWARD }

    /** 单行输入模式：拒绝插入换行，用于搜索/设置/选择器过滤。 */
    private final boolean singleLine;
    private final UndoHistory undo = new UndoHistory();
    private final KillRing killRing = new KillRing();
    private final PasteRegistry pastes = new PasteRegistry();
    private String text = "";
    private int caret;
    /** 上下移动时的目标显示列；横向/输入操作会清除它。 */
    private int preferredColumn = -1;
    /** 最近输入/删除的合并键，供撤销按词分组。 */
    private String lastEditKey = "";
    /** 最近一次 yank 的插入范围，用于 yank-pop 替换。 */
    private int lastYankStart = -1, lastYankEnd = -1;

    /** 多行编辑器。 */
    public EditorBuffer() { this(false); }

    /**
     * @param singleLine true 时换行被拒绝，Enter 由宿主当提交/确认；Input 组件使用
     */
    public EditorBuffer(boolean singleLine) { this.singleLine = singleLine; }

    /** @return 当前逻辑正文（粘贴标记已折叠）。 */
    public String text() { return text; }

    /** @return UTF-16 光标位置，始终位于字素边界。 */
    public int caret() { return caret; }

    /** @return 文本是否为空。 */
    public boolean empty() { return text.isEmpty(); }

    /** @return 单行模式。 */
    public boolean singleLine() { return singleLine; }

    /** 把光标放到最近的合法字素边界；历史恢复/外部程序定位使用。 */
    public void setCaret(int offset) {
        int value = Math.max(0, Math.min(offset, text.length()));
        // 若落在字素/粘贴标记中间，逐步向前取整。
        while (value > 0 && value < text.length()
                && DisplayWidth.previousOffset(text, value + 1) != value) value = DisplayWidth.previousOffset(text, value);
        caret = value; preferredColumn = -1;
    }

    /**
     * 用替换范围 [from,to) 写入候选文本；用于补全应用。
     * 范围越界/反向时不做任何修改，避免把命令拼到错误位置。
     */
    public void replaceRange(int from, int to, String replacement) {
        int left = Math.max(0, Math.min(from, text.length()));
        int right = Math.max(left, Math.min(to, text.length()));
        String value = replacement == null ? "" : replacement;
        pushUndo("complete");
        text = text.substring(0, left) + value + text.substring(right);
        caret = left + value.length(); preferredColumn = -1; lastEditKey = "";
    }

    /** @return 当前粘贴登记表只读快照，供撤销/测试。 */
    public PasteRegistry pastes() { return pastes; }

    /** @return 撤销历史，供宿主诊断/测试。 */
    public UndoHistory undoHistory() { return undo; }

    /** @return 删除环，供宿主诊断/测试。 */
    public KillRing killRing() { return killRing; }

    /** 用外部文本替换全部内容（外部编辑器返回）；保留撤销点和光标到末尾。 */
    public void setText(String value) {
        pushUndo("set");
        text = normalize(value == null ? "" : value);
        if (singleLine) text = text.replace('\n', ' ');
        caret = text.length(); preferredColumn = -1;
    }

    /** 清空正文；Ctrl+C 使用，仍可撤销。 */
    public void clear() {
        if (text.isEmpty()) return;
        pushUndo("clear");
        text = ""; caret = 0; preferredColumn = -1; pastes.clear();
    }

    /**
     * 插入普通文字或整段粘贴。
     *
     * <p>粘贴会先规范化换行、展开 Tab、过滤不可打印字符；大段内容（>10 行或 >1000 字符）
     * 折叠成顺序编号标记（例如 `[paste #1 +12 lines]`），提交时展开。路径粘贴在光标前是
     * 单词字符时自动补一个空格。普通文字插入按字素边界推进光标，不触发提交。</p>
     *
     * @param value 要插入的正文
     * @param paste 是否来自 bracketed paste
     * @return 实际插入的文本（可能被折叠/过滤）
     */
    public String insert(String value, boolean paste) {
        if (value == null || value.isEmpty()) return "";
        String prepared = paste ? preparePaste(value) : normalizeInput(value);
        if (singleLine) prepared = prepared.replace("\n", " ");
        if (prepared.isEmpty()) return "";
        String key = prepared.codePoints().allMatch(Character::isWhitespace) ? "space" : "type";
        pushUndo(key);
        int at = snapForward(caret);
        text = text.substring(0, at) + prepared + text.substring(at);
        caret = at + prepared.length(); preferredColumn = -1; lastYankStart = lastYankEnd = -1;
        lastEditKey = key;
        return prepared;
    }

    /** 粘贴预处理：CRLF/CR 归一、Tab→4 空格、过滤控制字符；大段折叠。 */
    private String preparePaste(String value) {
        String clean = normalize(value);
        var filtered = new StringBuilder(clean.length());
        clean.codePoints().forEach(cp -> {
            if (cp == '\n' || cp >= 32) filtered.appendCodePoint(cp);
        });
        String result = filtered.toString();
        // 路径粘贴：光标前是单词字符时补空格，避免把路径粘到单词中间。
        if (!result.isEmpty() && (result.charAt(0) == '/' || result.charAt(0) == '~' || result.charAt(0) == '.')
                && caret > 0 && isWordChar(text.codePointBefore(caret))) result = " " + result;
        long lines = result.chars().filter(c -> c == '\n').count() + 1;
        if (lines > 10 || result.length() > 1000) {
            int id = pastes.register(result);
            return lines > 10 ? "[paste #" + id + " +" + lines + " lines]" : "[paste #" + id + " " + result.length() + " chars]";
        }
        return result;
    }

    /** 普通按键文字只规范化换行；Tab 按键由宿主显式插入 4 空格。 */
    private String normalizeInput(String value) {
        return normalize(value);
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
    }

    /** 在光标处插入换行；单行模式拒绝。 */
    public boolean newline() {
        if (singleLine) return false;
        pushUndo("newline");
        text = text.substring(0, caret) + "\n" + text.substring(caret);
        caret++; preferredColumn = -1; lastEditKey = "newline"; return true;
    }

    /** 向左/右移动一个字素。 */
    public void moveHorizontal(int direction) {
        caret = direction < 0 ? DisplayWidth.previousOffset(text, caret) : DisplayWidth.nextOffset(text, caret);
        preferredColumn = -1; lastEditKey = "";
    }

    /** 向左/右按词移动：先跳过空白，再跳过同类字符。 */
    public void moveWord(int direction) {
        if (direction < 0) {
            while (caret > 0 && isWhitespace(text.codePointBefore(caret))) caret = DisplayWidth.previousOffset(text, caret);
            if (caret > 0) {
                int kind = charKind(text.codePointBefore(caret));
                while (caret > 0 && charKind(text.codePointBefore(caret)) == kind)
                    caret = DisplayWidth.previousOffset(text, caret);
            }
        } else {
            while (caret < text.length() && isWhitespace(text.codePointAt(caret))) caret = DisplayWidth.nextOffset(text, caret);
            if (caret < text.length()) {
                int kind = charKind(text.codePointAt(caret));
                while (caret < text.length() && charKind(text.codePointAt(caret)) == kind)
                    caret = DisplayWidth.nextOffset(text, caret);
            }
        }
        caret = snapPasteMarker(caret, direction);
        preferredColumn = -1; lastEditKey = "";
    }

    /** 移到当前逻辑行首/行尾。 */
    public void lineStart() {
        caret = text.lastIndexOf('\n', Math.max(-1, caret - 1)) + 1; preferredColumn = -1; lastEditKey = "";
    }

    public void lineEnd() {
        int next = text.indexOf('\n', caret); caret = next < 0 ? text.length() : next;
        preferredColumn = -1; lastEditKey = "";
    }

    /**
     * 按视觉行上下移动。返回 false 表示已经越过文档首/末视觉行，调用方可以进入历史。
     * @param columns 当前排版宽度；使用与绘制相同的换行计算
     * @param pageRows 翻页行数；上下键传 1
     */
    public boolean vertical(int direction, int columns, int pageRows) {
        EditorLayout layout = EditorLayout.of(text, Math.max(1, columns));
        EditorLayout.Position position = layout.positionOf(caret);
        int targetRow = position.row() + direction * Math.max(1, pageRows);
        if (targetRow < 0 || targetRow >= layout.lines().size()) return false;
        if (preferredColumn < 0) preferredColumn = position.column();
        int target = layout.offsetAt(targetRow, preferredColumn);
        caret = target; lastEditKey = "";
        return true;
    }

    /** 删除前/后一个字素；位于粘贴标记内部时整块删除。 */
    public void delete(Direction direction) {
        int other = direction == Direction.BACKWARD ? DisplayWidth.previousOffset(text, caret)
                : DisplayWidth.nextOffset(text, caret);
        other = snapPasteMarker(other, direction == Direction.BACKWARD ? -1 : 1);
        if (other == caret) return;
        pushUndo("delete");
        int left = Math.min(caret, other), right = Math.max(caret, other);
        text = text.substring(0, left) + text.substring(right);
        caret = left; preferredColumn = -1; lastYankStart = lastYankEnd = -1; lastEditKey = "delete";
    }

    /** 删除前/后一个词。 */
    public void deleteWord(Direction direction) {
        int start = caret;
        moveWord(direction == Direction.BACKWARD ? -1 : 1);
        int end = caret; caret = start;
        if (start == end) return;
        pushUndo("deleteWord");
        int left = Math.min(start, end), right = Math.max(start, end);
        text = text.substring(0, left) + text.substring(right);
        caret = left; preferredColumn = -1; lastYankStart = lastYankEnd = -1; lastEditKey = "deleteWord";
    }

    /** Ctrl+U：删除光标到逻辑行首，并放入 kill ring。 */
    public void killToLineStart() { killRange(lineStartOffset(), caret, KillRing.Direction.BACKWARD); }

    /** Ctrl+K：删除光标到逻辑行尾（有内容）或当前换行（行尾）并放入 kill ring。 */
    public void killToLineEnd() {
        int end = text.indexOf('\n', caret);
        if (end < 0) end = text.length(); else if (end == caret) end++;
        killRange(caret, end, KillRing.Direction.FORWARD);
    }

    private void killRange(int start, int end, KillRing.Direction direction) {
        if (start == end) return;
        pushUndo("kill");
        String killed = text.substring(start, end);
        text = text.substring(0, start) + text.substring(end);
        caret = start; preferredColumn = -1; lastYankStart = lastYankEnd = -1; lastEditKey = "kill";
        killRing.kill(killed, direction);
    }

    /** Ctrl+Y：插入最近 kill 内容，并记录范围供 yank-pop。 */
    public void yank() {
        if (killRing.empty()) return;
        pushUndo("yank");
        String value = killRing.current();
        int at = caret;
        text = text.substring(0, at) + value + text.substring(at);
        caret = at + value.length(); lastYankStart = at; lastYankEnd = caret;
        preferredColumn = -1; lastEditKey = "yank";
    }

    /** Alt+Y：用环中上一项替换最近插入范围；没有 yank 时无操作。 */
    public void yankPop() {
        if (lastYankStart < 0 || lastYankEnd < lastYankStart || lastYankEnd > text.length()) return;
        String replacement = killRing.rotate();
        text = text.substring(0, lastYankStart) + replacement + text.substring(lastYankEnd);
        lastYankEnd = lastYankStart + replacement.length(); caret = lastYankEnd;
    }

    /** Ctrl+- 撤销；恢复完整快照（含粘贴登记表）。 */
    public void undo() {
        UndoHistory.Snapshot snapshot = undo.pop();
        if (snapshot == null) return;
        text = snapshot.text(); caret = Math.min(snapshot.caret(), text.length()); pastes.restore(snapshot.pastes());
        preferredColumn = -1; lastYankStart = lastYankEnd = -1; lastEditKey = "";
    }

    /**
     * 提交：展开所有仍存在的粘贴标记，返回完整原文并清空编辑器/撤销/登记。
     * 只有宿主确认接受后才应调用；被拒时保留草稿。
     */
    public String submit() {
        String expanded = expandPastes(text);
        text = ""; caret = 0; preferredColumn = -1; undo.clear(); pastes.clear();
        lastYankStart = lastYankEnd = -1; lastEditKey = "";
        return expanded;
    }

    /** @return 展开粘贴标记后的完整正文，不修改状态；用于外部编辑器导出/检查。 */
    public String expandedText() { return expandPastes(text); }

    private String expandPastes(String value) {
        String result = value;
        // 标记可能带行数/字符数后缀（[paste #1 +12 lines] / [paste #1 1234 chars]），按 ID 匹配
        // 完整标记后替换；不能只替换无后缀形式，否则提交会漏掉原始内容。
        for (var entry : pastes.entries()) {
            result = result.replaceAll("\\[paste #" + entry.getKey() + "(?: \\+\\d+ lines| \\d+ chars)?\\]",
                    java.util.regex.Matcher.quoteReplacement(entry.getValue()));
        }
        return result;
    }

    /** 若光标落在粘贴标记内部，移到标记起点/终点外侧。 */
    private int snapPasteMarker(int offset, int direction) {
        for (int id : pastes.ids()) {
            String prefix = "[paste #" + id;
            int start = text.indexOf(prefix);
            if (start < 0) continue;
            int end = text.indexOf(']', start);
            if (end < 0) continue;
            end++;
            if (offset > start && offset < end) return direction < 0 ? start : end;
        }
        return offset;
    }

    /** 光标若落在字素中间（正常操作不会），向后取整。 */
    private int snapForward(int offset) { return offset; }

    private int lineStartOffset() { return text.lastIndexOf('\n', Math.max(-1, caret - 1)) + 1; }

    /** 插入/操作前记录撤销快照；同一次词输入合并成一项。 */
    private void pushUndo(String key) {
        boolean merge = key.equals(lastEditKey) && (key.equals("type") || key.equals("delete"))
                && undo.canMerge();
        if (!merge) undo.push(text, caret, pastes.snapshot(), key);
        else undo.refresh(text, caret, pastes.snapshot());
    }

    private static boolean isWordChar(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    private static boolean isWhitespace(int codePoint) { return Character.isWhitespace(codePoint); }

    private static int charKind(int codePoint) {
        return isWhitespace(codePoint) ? 0 : isWordChar(codePoint) ? 1 : 2;
    }

    /** @return 逻辑文本的只读快照，供测试/布局。 */
    public String snapshotText() { return text; }
}
