package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * Pi 风格候选窗口：fuzzy 过滤、`→` 选中前缀、双列描述、居中滚动窗口和 `(i/n)`。
 *
 * <p>候选 ID 与显示标签分离，同名会话/重复标题不会丢项；显示标签过宽时按列裁切。列表只辅助
 * 选择，不执行命令；确认动作由宿主根据选中 ID 决定。过滤使用与路径补全一致的 fuzzy 评分，
 * 无匹配时显示明确文案，不静默变成空列表。组件不维护终端 writer 或第二套编辑语义。</p>
 */
public final class SelectList implements Component {
    /**
     * 一个候选。
     * @param id 稳定选择值（可能是 UUID 或命令名）
     * @param label 显示文字
     * @param description 右侧说明；可为空
     */
    public record Item(String id, String label, String description) {
        public Item {
            if (id == null) throw new IllegalArgumentException("候选 ID 不能为空");
            if (label == null) label = "";
            if (description == null) description = "";
        }
    }

    private final List<Item> items;
    private List<Item> filtered;
    private String filter = "";
    private int selected;

    /** 用字符串标签构造，ID 与标签相同。 */
    public SelectList(List<String> labels) {
        var converted = new ArrayList<Item>();
        for (String label : labels) converted.add(new Item(label, label, ""));
        this.items = List.copyOf(converted);
        this.filtered = this.items;
    }

    /**
     * 用带描述的候选构造。第二个参数只为与 List&lt;String&gt; 构造区分擦除，无业务含义。
     */
    public SelectList(List<Item> items, boolean typedItems) {
        this.items = List.copyOf(items);
        this.filtered = this.items;
    }

    /** @return 当前过滤文字。 */
    public String filter() { return filter; }

    /** 设置过滤并重置选中到第一个匹配项。 */
    public void filter(String value) {
        filter = value == null ? "" : value;
        filtered = items.stream().filter(item -> matches(filter, item))
                .sorted(java.util.Comparator.comparingInt((Item item) -> score(filter, item)).reversed())
                .toList();
        selected = 0;
    }

    /** 移动到上/下一项，循环。 */
    public void move(int delta) {
        if (filtered.isEmpty()) return;
        selected = Math.floorMod(selected + delta, filtered.size());
    }

    /** 翻页；pageSize 由调用方按可视行数提供。 */
    public void page(int direction, int pageSize) {
        if (filtered.isEmpty()) return;
        selected = Math.max(0, Math.min(filtered.size() - 1, selected + direction * Math.max(1, pageSize)));
    }

    /** @return 当前选中 ID；无候选 null。 */
    public String selected() { return filtered.isEmpty() ? null : filtered.get(selected).id(); }

    /** @return 当前选中项；无候选 null。 */
    public Item selectedItem() { return filtered.isEmpty() ? null : filtered.get(selected); }

    /** @return 选中下标；无候选 -1。 */
    public int selectedIndex() { return filtered.isEmpty() ? -1 : selected; }

    /** @return 过滤后候选数量。 */
    public int size() { return filtered.size(); }

    /** @return 过滤后候选快照。 */
    public List<Item> items() { return filtered; }

    /** @return 按 ID 查找当前过滤后的候选；不存在 null。 */
    public Item find(String id) {
        return filtered.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
    }

    /** @return 选中项在渲染窗口中的相对行；无候选 0。 */
    public int selectedRow(int maxVisible) {
        int window = Math.max(1, maxVisible);
        int start = windowStart(window);
        return Math.max(0, Math.min(window - 1, selected - start));
    }

    /**
     * 计算居中窗口的起点：让选中尽量位于中间。
     * @param window 可见行数
     * @return 过滤后列表的起始下标
     */
    public int windowStart(int window) {
        if (filtered.size() <= window) return 0;
        return Math.max(0, Math.min(filtered.size() - window, selected - window / 2));
    }

    @Override public List<StyledLine> render(RenderContext context, int width) {
        // 默认最多 5 行；调用方可用 render(width,maxVisible) 指定。
        return render(width, 5, context);
    }

    /** 按可视行数渲染候选与 `(i/n)`；无匹配显示英文提示。 */
    public List<StyledLine> render(int width, int maxVisible, RenderContext context) {
        var result = new ArrayList<StyledLine>();
        int window = Math.max(1, maxVisible);
        if (filtered.isEmpty()) {
            result.add(StyledLine.of(DisplayWidth.ellipsize("  No matches", width), context.theme().style("muted")));
            return result;
        }
        int start = windowStart(window);
        int end = Math.min(filtered.size(), start + window);
        int labelWidth = Math.max(1, Math.min(40, width / 2));
        for (int i = start; i < end; i++) {
            Item item = filtered.get(i);
            boolean chosen = i == selected;
            var spans = new ArrayList<Span>();
            String prefix = chosen ? "  → " : "    ";
            String label = DisplayWidth.ellipsize(item.label(), Math.max(1, labelWidth - 4));
            spans.add(new Span(prefix + label, context.theme().style(chosen ? "accent" : "text"), null));
            int used = DisplayWidth.width(prefix + label);
            if (!item.description().isEmpty() && used + 2 < width) {
                String description = DisplayWidth.ellipsize(item.description(), Math.max(0, width - used - 2));
                spans.add(new Span("  " + description, context.theme().style("muted"), null));
            }
            StyledLine line = new StyledLine(spans);
            if (chosen) {
                // Pi 只给选中项的“文字范围”加 selectedText 背景（含前缀与描述），
                // 不把行尾 padding 也涂成背景条。旧实现 padTo(width, selectedBg) 会把
                // 说明之后的整段空白染成浅色长条。
                var selectedSpans = new ArrayList<Span>();
                for (Span span : line.spans())
                    selectedSpans.add(new Span(span.text(),
                            span.style().withBg(context.theme().color("selectedBg")), span.link()));
                line = new StyledLine(selectedSpans).padTo(width, context.theme().style("text"));
            }
            result.add(line);
        }
        String count = "  (" + (selected + 1) + "/" + filtered.size() + ")";
        result.add(StyledLine.of(DisplayWidth.ellipsize(count, width), context.theme().style("dim")));
        return result;
    }

    /** 过滤匹配：标签或描述包含全部查询字符（大小写不敏感）。 */
    private static boolean matches(String query, Item item) {
        return score(query, item) >= 0;
    }

    /** fuzzy 评分；-1 不匹配。空查询所有项同分。 */
    static int score(String query, Item item) {
        if (query.isBlank()) return 0;
        String haystack = (item.label() + " " + item.description()).toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        int score = 0, position = 0, streak = 0;
        for (int i = 0; i < needle.length(); i++) {
            if (Character.isWhitespace(needle.charAt(i))) continue;
            int found = haystack.indexOf(needle.charAt(i), position);
            if (found < 0) return -1;
            streak = found == position ? streak + 1 : 0;
            score += 10 + streak * 5 - Math.min(4, found - position);
            position = found + 1;
        }
        if (item.label().toLowerCase(Locale.ROOT).startsWith(needle)) score += 40;
        return score;
    }

    /** 用当前主题渲染；保留旧调用风格的便捷入口。 */
    public List<StyledLine> render(int width, int maxVisible) {
        return render(width, maxVisible, new RenderContext(width, maxVisible + 2,
                new com.jadecode.tui.theme.Theme("dark", "dark",
                        com.jadecode.tui.theme.ThemePalette.builtin("dark").colors(), true, 0)));
    }

    /** 按 ID 设置选中；找不到时保持不动。 */
    public void selectId(String id) {
        for (int i = 0; i < filtered.size(); i++) if (filtered.get(i).id().equals(id)) { selected = i; return; }
    }
}
