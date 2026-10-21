package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.List;

/**
 * 输入历史：最多 100 项、相邻重复合并，并保存进入历史前的草稿/光标。
 *
 * <p>只在用户真正提交后被宿主追加（用户消息/显式输入），不把系统、工具或压缩摘要当输入。
 * 上下键浏览到边界时 Editor 才调用本类；离开历史时恢复原草稿与光标，而不是清空或跳到末尾。
 * 历史不持久化，也不读取会话日志（恢复会话的用户输入由 HistoryViewMapper 明确填入）。</p>
 */
public final class EditorHistory {
    private static final int CAPACITY = 100;
    private final List<String> entries = new ArrayList<>();
    private int index;
    private String draft = "";
    private int draftCaret;

    /** @return 是否正在浏览历史（不是当前草稿位置）。 */
    public boolean browsing() { return index < entries.size(); }

    /** 追加一条已提交输入；空/与相邻项相同则忽略。 */
    public void add(String text) {
        if (text == null || text.isBlank()) return;
        if (!entries.isEmpty() && entries.getLast().equals(text)) { end(text); return; }
        entries.add(text);
        if (entries.size() > CAPACITY) entries.removeFirst();
        end(text);
    }

    /** 浏览前保存当前草稿；只在第一次进入历史时记录。 */
    public void begin(String currentText, int caret) {
        if (index == entries.size()) { draft = currentText == null ? "" : currentText; draftCaret = caret; }
    }

    /** @return 上一条历史；没有更早项返回 null。调用前应 {@link #begin}。 */
    public String previous() {
        if (entries.isEmpty() || index == 0) return null;
        index--;
        return entries.get(index);
    }

    /** @return 下一条历史；越过最后一项返回原草稿。 */
    public String next() {
        if (entries.isEmpty() || index >= entries.size()) return null;
        index++;
        return index == entries.size() ? draft : entries.get(index);
    }

    /** @return 导航前的草稿（非历史项）。 */
    public String draft() { return draft; }

    /** @return 导航前的光标，恢复草稿时使用。 */
    public int draftCaret() { return draftCaret; }

    /** 离开历史/提交后回到末尾。 */
    public void end(String currentText) {
        index = entries.size(); draft = currentText == null ? "" : currentText; draftCaret = draft.length();
    }

    /** 清空历史（切换会话时由宿主决定是否保留；当前历史属于进程内 UI）。 */
    public void clear() { entries.clear(); index = 0; draft = ""; draftCaret = 0; }

    /** @return 历史条目快照，旧到新。 */
    public List<String> entries() { return List.copyOf(entries); }

    /** @return 当前浏览下标；等于 size 表示草稿。 */
    public int index() { return index; }
}
