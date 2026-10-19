package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;

/** 可过滤的通用列表；主题和帮助复用，不负责保存主题或执行被选命令。 */
public final class SelectList {
    private final List<String> choices;
    private String filter = "";
    private int selected;
    public SelectList(List<String> choices) { this.choices = List.copyOf(choices); }
    public void filter(String value) { filter = value; selected = 0; }
    public String filter() { return filter; }
    public void move(int delta) { int n = visible().size(); if (n > 0) selected = Math.floorMod(selected + delta, n); }
    public String selected() { var values = visible(); return values.isEmpty() ? null : values.get(Math.min(selected, values.size() - 1)); }
    public List<String> visible() { return choices.stream().filter(s -> s.toLowerCase().contains(filter.toLowerCase())).toList(); }
    /** 窗口只显示选中项附近，不把整个列表塞进输入区。 */
    public List<StyledLine> render(int width, int max) {
        var out = new ArrayList<StyledLine>(); var values = visible();
        int top = Math.max(0, selected - Math.max(1, max) + 1);
        for (int i = top; i < Math.min(values.size(), top + max); i++)
            out.add(StyledLine.of(DisplayWidth.take((i == selected ? "› " : "  ") + values.get(i), width), i == selected ? "selected" : "normal"));
        if (values.isEmpty()) out.add(StyledLine.of("无匹配项", "muted"));
        return out;
    }
}
