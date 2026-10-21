package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 顺序编号的原子粘贴登记表。
 *
 * <p>超过 10 行或 1000 字符的粘贴在编辑器里折叠成 `[paste #N +K lines]` / `[paste #N K chars]`，
 * 移动、删除、撤销都把整个标记当成一个单位；提交或外部编辑器导出时展开原文。ID 从 1 递增，
 * 不使用随机 UUID；快照可恢复 ID 与计数器，撤销后新粘贴不会与仍存在的旧标记冲突。
 * 本类不访问系统剪贴板，也不决定折叠阈值（由 EditorBuffer 决定）。</p>
 */
public final class PasteRegistry {
    private final LinkedHashMap<Integer, String> entries = new LinkedHashMap<>();
    private int counter;

    /** 登记一段正文，返回新 ID（从 1 开始）。 */
    public int register(String text) {
        int id = ++counter;
        entries.put(id, text);
        return id;
    }

    /** @return 指定 ID 的原文；不存在返回 null。 */
    public String get(int id) { return entries.get(id); }

    /** @return 所有有效 ID，按登记顺序。 */
    public Set<Integer> ids() { return Set.copyOf(entries.keySet()); }

    /** @return 条目快照（ID→原文），按登记顺序。 */
    public List<Map.Entry<Integer, String>> entries() { return List.copyOf(entries.entrySet()); }

    /** 计数单位；仅测试/诊断。 */
    public int size() { return entries.size(); }

    /** @return 下一个将分配的 ID（不改变状态），供测试。 */
    public int nextId() { return counter + 1; }

    /** 清空内容并重置 ID；新会话/提交使用。 */
    public void clear() { entries.clear(); counter = 0; }

    /** 不可变快照，用于撤销。 */
    public Map<Integer, String> snapshot() { return Map.copyOf(entries); }

    /** 从撤销快照恢复；计数器取最大 ID，避免与现存标记重复。 */
    public void restore(Map<Integer, String> snapshot) {
        entries.clear();
        if (snapshot != null) entries.putAll(snapshot);
        counter = entries.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /** @return 只读文本列表，测试/诊断。 */
    public List<String> texts() { return new ArrayList<>(entries.values()); }
}
