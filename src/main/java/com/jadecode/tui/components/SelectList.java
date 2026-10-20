package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;

/** 可过滤的通用列表；主题和帮助复用，不负责保存主题或执行被选命令。 */
public final class SelectList {
    private final List<String> choices;
    /** 选择值可以是内部编号，显示和筛选则只用用户可读的标签。 */
    private final Function<String, String> label;
    private String filter = "";
    private int selected;
    /** 普通命令、主题等列表的选择值就是显示文字，保持原有调用方式。 */
    public SelectList(List<String> choices) { this(choices, Function.identity()); }
    /**
     * 分开保存选择值与显示文字，例如同名会话显示相同标题，但仍用各自 ID 恢复。
     * label 在界面线程调用，应只查已有资料，不读磁盘或请求模型；返回非空文字。
     * 本组件复制选项，不修改传入列表。selected 返回原选择值，render/filter 使用标签。
     */
    public SelectList(List<String> choices, Function<String, String> label) {
        this.choices = List.copyOf(choices); this.label = Objects.requireNonNull(label);
        for (String choice : this.choices) Objects.requireNonNull(label.apply(choice), "列表标签不能为 null");
    }
    public void filter(String value) { filter = value; selected = 0; }
    public String filter() { return filter; }
    public void move(int delta) { int n = visible().size(); if (n > 0) selected = Math.floorMod(selected + delta, n); }
    public String selected() { var values = visible(); return values.isEmpty() ? null : values.get(Math.min(selected, values.size() - 1)); }
    /** 按显示标签过滤，保留原选择值；隐藏的 UUID 不参与会话名称筛选。 */
    public List<String> visible() {
        String query = filter.toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> label.apply(s).toLowerCase(Locale.ROOT).contains(query)).toList();
    }
    /** 窗口只显示选中项附近，不把整个列表塞进输入区。 */
    public List<StyledLine> render(int width, int max) {
        var out = new ArrayList<StyledLine>(); var values = visible();
        int top = Math.max(0, selected - Math.max(1, max) + 1);
        for (int i = top; i < Math.min(values.size(), top + max); i++)
            out.add(StyledLine.of(DisplayWidth.take((i == selected ? "› " : "  ") + label.apply(values.get(i)), width), i == selected ? "selected" : "normal"));
        if (values.isEmpty()) out.add(StyledLine.of("无匹配项", "muted"));
        return out;
    }
}
