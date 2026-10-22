package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.tui.components.Input;
import com.jadecode.tui.components.SelectList;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * 选择器面板的专用排版：搜索框、分隔边界、候选窗口、说明、状态和操作提示。
 *
 * <p>这是 Editor 原位替换的视觉面板，不是第二套 Overlay/事件循环：候选与过滤仍由
 * {@link SelectList} 持有，输入仍由 {@link Input} 持有，Controller 负责导航/确认/子菜单。
 * 面板只分配行、列和光标/命中，不访问 Host、不写配置、不执行动作。高度先扣除搜索、说明、
 * 状态和提示，再决定候选窗口；空间不足时按固定优先级降级，避免截断下边框或光标。</p>
 */
public final class SelectorPanelView {
    /** 一行候选的命中；row 是面板内相对行，id 是 SelectList 的稳定 ID。 */
    public record ItemHit(int row, String id) { }

    /**
     * @param lines 面板完整行
     * @param cursorRow 搜索框在面板内的行；不可见 0
     * @param cursorColumn 搜索光标列
     * @param cursorVisible 是否显示真实光标
     * @param itemHits 候选行命中
     */
    public record Layout(List<StyledLine> lines, int cursorRow, int cursorColumn, boolean cursorVisible,
            List<ItemHit> itemHits) {
        public Layout {
            lines = List.copyOf(lines);
            itemHits = List.copyOf(itemHits);
        }
    }

    private static final int MAX_VISIBLE = 10;

    /**
     * @param state 当前选择器状态（selector、selectorInput、专用元数据）
     * @param context 同帧主题/宽高
     * @param width 面板宽度
     * @param maxRows 面板可用高度；由 DockView 先扣除 status/footer/widget
     * @return 不超出 maxRows 的面板；极窄/矮屏仍保留搜索与至少一个候选（若有）
     */
    public Layout render(TuiState state, RenderContext context, int width, int maxRows) {
        int columns = Math.max(1, width);
        int rows = Math.max(1, maxRows);
        SelectList list = state.selector;
        Input input = state.selectorInput;
        if (list == null) return new Layout(List.of(), 0, 0, false, List.of());

        boolean settings = state.selectorKind.equals("Settings");
        boolean showTitle = !settings && state.selectorTitle != null && !state.selectorTitle.isBlank();
        boolean showBorder = rows >= 4;
        boolean showGap = rows >= 5;
        boolean showDescription = !state.selectorDescription.isBlank();
        boolean showStatus = !state.selectorStatus.isBlank();
        boolean showHint = !state.hint.isBlank();
        boolean showFooter = !state.selectorFooter.isBlank();
        boolean describe = showDescription;
        boolean status = showStatus;
        boolean hint = showHint;
        boolean footer = showFooter;
        boolean border = showBorder;
        boolean title = showTitle;
        boolean gap = showGap;

        // 先构造非列表部分，再按优先级移除可选行，确保列表和搜索框有空间。
        List<StyledLine> head = new ArrayList<>();
        List<StyledLine> tail = new ArrayList<>();
        while (true) {
            head.clear(); tail.clear();
            if (border) head.add(border(columns, context));
            if (title) head.add(StyledLine.of(DisplayWidth.ellipsize(state.selectorTitle, columns),
                    context.theme().style("accent")));
            StyledLine search = searchLine(input, context, columns);
            head.add(search);
            if (gap) head.add(StyledLine.empty());

            if (gap && describe) tail.add(StyledLine.empty());
            if (describe) addWrapped(tail, state.selectorDescription, context, columns, "muted", 3);
            if (status) addWrapped(tail, state.selectorStatus, context, columns, "muted", 2);
            if (hint) addWrapped(tail, state.hint, context, columns, "warning", 2);
            if (gap && footer) tail.add(StyledLine.empty());
            if (footer) addWrapped(tail, state.selectorFooter, context, columns, "dim", 2);
            if (border) tail.add(border(columns, context));

            if (head.size() + tail.size() + 1 <= rows) break;
            // 固定优先级：先去掉装饰空行，再缩说明/状态/错误，最后才动边界和提示。
            if (gap) { gap = false; continue; }
            if (describe) { describe = false; continue; }
            if (status) { status = false; continue; }
            if (hint) { hint = false; continue; }
            if (footer) { footer = false; continue; }
            if (border) { border = false; continue; }
            if (title) { title = false; continue; }
            // 连搜索 + 一行候选都放不下时只能保留搜索；这是极窄屏的最后降级。
            break;
        }

        int listRows = Math.max(0, rows - head.size() - tail.size());
        int filtered = list.size();
        // 窗口上限是 10；即使 regular 预算很大，也要为被截掉的项显示计数。
        boolean count = filtered > Math.min(MAX_VISIBLE, listRows) && listRows > 1;
        if (count) listRows--;
        int visible = Math.min(MAX_VISIBLE, Math.max(0, listRows));
        int start = visible == 0 ? 0 : list.windowStart(visible);
        var body = new ArrayList<StyledLine>();
        var hits = new ArrayList<ItemHit>();
        List<SelectList.Item> items = list.items();
        if (filtered == 0) {
            body.add(StyledLine.of(DisplayWidth.ellipsize("  No matches", columns),
                    context.theme().style("muted")));
        } else {
            int labelWidth = settings ? settingsLabelWidth(items) : 0;
            for (int i = start; i < Math.min(filtered, start + visible); i++) {
                SelectList.Item item = items.get(i);
                boolean selected = i == list.selectedIndex();
                body.add(row(item, selected, settings, labelWidth, columns, context));
                hits.add(new ItemHit(head.size() + body.size() - 1, item.id()));
            }
            if (count) {
                String counter = "  (" + (list.selectedIndex() + 1) + "/" + filtered + ")";
                body.add(StyledLine.of(DisplayWidth.ellipsize(counter, columns), context.theme().style("dim")));
            }
        }

        var lines = new ArrayList<StyledLine>(head.size() + body.size() + tail.size());
        lines.addAll(head);
        lines.addAll(body);
        lines.addAll(tail);
        // 极端宽度/高度下也不能把面板撑破 Dock 预算；上面已按预算生成，这里只做安全收口。
        if (lines.size() > rows) lines = new ArrayList<>(lines.subList(0, rows));
        int cursorRow = Math.min(head.size() - (gap ? 2 : 1), lines.size() - 1);
        cursorRow = Math.max(0, cursorRow);
        int searchColumn = 2 + (input == null ? 0 : input.cursorColumn(Math.max(1, columns - 2)));
        return new Layout(lines, cursorRow, Math.max(0, Math.min(columns - 1, searchColumn)),
                input != null, hits);
    }

    private static StyledLine searchLine(Input input, RenderContext context, int width) {
        int content = Math.max(1, width - 2);
        var spans = new ArrayList<Span>();
        spans.add(new Span("> ", context.theme().style("accent"), null));
        if (input != null) spans.addAll(input.render(context, content).getFirst().spans());
        return new StyledLine(spans).crop(width);
    }

    private static StyledLine border(int width, RenderContext context) {
        return StyledLine.of("─".repeat(Math.max(1, width)), context.theme().style("borderMuted"));
    }

    /** 设置页专用：标签列对齐、右值；选中项只用 accent，不铺整行背景。 */
    private static StyledLine row(SelectList.Item item, boolean selected, boolean settings, int labelWidth,
            int width, RenderContext context) {
        Style labelStyle = context.theme().style(selected ? "accent" : "text");
        Style valueStyle = context.theme().style(selected ? "accent" : "muted");
        String prefix = selected ? "  → " : "    ";
        if (settings) {
            int labelColumns = Math.max(1, Math.min(36, labelWidth));
            String label = DisplayWidth.ellipsize(item.label(), Math.max(1, labelColumns));
            String padded = label + " ".repeat(Math.max(0, labelColumns - DisplayWidth.width(label)));
            int valueWidth = Math.max(0, width - DisplayWidth.width(prefix) - labelColumns - 2);
            String value = DisplayWidth.ellipsize(item.description(), valueWidth);
            var spans = new ArrayList<Span>();
            spans.add(new Span(prefix + padded, labelStyle, null));
            if (!value.isEmpty()) spans.add(new Span("  " + value, valueStyle, null));
            return new StyledLine(spans).crop(width);
        }
        var spans = new ArrayList<Span>();
        spans.add(new Span(prefix + item.label(), labelStyle, null));
        if (!item.description().isEmpty()) {
            int used = DisplayWidth.width(prefix + item.label());
            if (used + 2 < width) spans.add(new Span("  " + DisplayWidth.ellipsize(item.description(),
                    width - used - 2), context.theme().style("muted"), null));
        }
        return new StyledLine(spans).crop(width);
    }

    private static int settingsLabelWidth(List<SelectList.Item> items) {
        int max = 1;
        for (SelectList.Item item : items) max = Math.max(max, DisplayWidth.width(item.label()));
        return Math.min(36, max);
    }

    private static void addWrapped(List<StyledLine> out, String text, RenderContext context, int width,
            String token, int maxLines) {
        if (text == null || text.isBlank() || maxLines <= 0) return;
        List<StyledLine> wrapped = StyledLine.wrap(List.of(new Span(text, context.theme().style(token), null)),
                Math.max(1, width - 4));
        for (int i = 0; i < Math.min(maxLines, wrapped.size()); i++) {
            StyledLine line = wrapped.get(i);
            var spans = new ArrayList<Span>();
            spans.add(new Span("  ", context.theme().style("dim"), null));
            spans.addAll(line.spans());
            out.add(new StyledLine(spans).crop(width));
        }
    }
}
