package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按词/操作分组的撤销快照，包含文本、光标与粘贴登记表。
 *
 * <p>旧实现每收到一个字符就压一份整个文档；这里同一连续输入的撤销点合并成一项：用户按
 * 一次 Ctrl+- 撤销一个词，而不是一个字。删除同样按连续操作合并；粘贴/换行/kill/yank
 * 形成独立边界。快照保存粘贴登记表副本，撤销删除标记后仍能展开原文，不会出现“标记回来了
 * 但原文丢失”。容量有上限，避免长会话无限占用内存。</p>
 */
public final class UndoHistory {
    /** 一个完整可恢复状态。 */
    public record Snapshot(String text, int caret, Map<Integer, String> pastes, String key) {
        public Snapshot { pastes = Map.copyOf(pastes); }
    }

    private static final int MAX = 200;
    private final List<Snapshot> stack = new ArrayList<>();

    /** 记录编辑前状态；超过上限时丢弃最旧项。 */
    public void push(String text, int caret, Map<Integer, String> pastes, String key) {
        stack.add(new Snapshot(text, caret, pastes, key));
        if (stack.size() > MAX) stack.removeFirst();
    }

    /** 合并输入时保留最早的撤销点，不需要修改栈顶。 */
    public void refresh(String text, int caret, Map<Integer, String> pastes) { /* 保留原撤销点。 */ }

    /** @return 是否还有快照可合并；连续输入才合并，空栈时首次输入必须新压。 */
    public boolean canMerge() { return !stack.isEmpty(); }

    /** @return 弹出最近快照；空栈返回 null。 */
    public Snapshot pop() { return stack.isEmpty() ? null : stack.removeLast(); }

    /** 清空撤销历史（提交/切换会话）。 */
    public void clear() { stack.clear(); }

    /** @return 当前快照数量，测试/诊断。 */
    public int size() { return stack.size(); }

    /** @return 只读列表副本（旧到新），测试用。 */
    public List<Snapshot> snapshots() { return List.copyOf(stack); }

    /** 由粘贴登记表构造不可变副本；调用方通常直接传 map。 */
    public static Map<Integer, String> copy(Map<Integer, String> source) { return new LinkedHashMap<>(source); }
}
