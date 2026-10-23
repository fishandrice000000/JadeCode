package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.tui.components.Input;
import com.jadecode.tui.components.SelectList;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.layout.CardFrame;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * 选择器卡片：Pi 式上下横线、右上标题、查询/箭头从 0 列、标签与说明两列缩进。
 *
 * <p>这是 Editor 原位替换的输入区卡片，不是第二套 Overlay/事件循环：候选与过滤仍由
 * {@link SelectList} 持有，输入仍由 {@link Input} 持有，Controller 负责导航/确认/子菜单。
 * 外框由 {@link CardFrame} 的 {@link CardFrame.Surface#PI_SECTION} 生成；本类只分配卡片内
 * 行、列和光标/命中，不访问 Host、不写配置、不执行动作。</p>
 *
 * <p>S93 的列坐标（相对卡片左边界，外层只做一次 dock/屏幕平移）：</p>
 * <ul>
 *   <li>上/下横线 x=0、宽 W；</li>
 *   <li>查询行：{@code > } 从 x=0 开始，Input 从 x=2，窗口宽 max(1, W-2)；</li>
 *   <li>选择箭头/占位从 x=0 开始（选中 {@code → }，未选中两空格）；</li>
 *   <li>Settings 标签 x=2，标签列宽取完整候选标签的最大显示宽（最多 36 列），过滤时不抖动；
 *       右值从 2+标签列宽+2 开始，末尾至少留两列；</li>
 *   <li>项目计数/说明/提示 x=2，换行宽度 W-4。</li>
 * </ul>
 *
 * <p>高度先扣除查询、空白和说明/状态/提示，再决定候选窗口（最多 10 项）；空间不足时按
 * 固定优先级先去掉装饰空行，再缩说明/状态/错误，最后才动底部提示。光标行包含上横线，
 * 候选命中覆盖真实候选区域（x=0 到 W-2，不含末尾留白）。</p>
 */
public final class SelectorPanelView {
    /** 一行候选的命中；row 是卡片内相对行，id 是 SelectList 的稳定 ID。 */
    public record ItemHit(int row, String id) { }

    /**
     * @param lines 卡片完整行
     * @param cursorRow 查询框在卡片内的行；不可见 0
     * @param cursorColumn 查询光标列（卡片坐标）
     * @param cursorVisible 是否显示真实光标
     * @param itemHits 候选行命中；row 为卡片内行
     * @param contentX 正文起始列（Pi 外观为 0），宿主用于构造真实列界限的命中
     * @param contentWidth 正文列数（Pi 外观等于卡片总宽）
     */
    public record Layout(List<StyledLine> lines, int cursorRow, int cursorColumn, boolean cursorVisible,
            List<ItemHit> itemHits, int contentX, int contentWidth) {
        public Layout {
            lines = List.copyOf(lines);
            itemHits = List.copyOf(itemHits);
        }

        /** 兼容旧调用：无命中/坐标时使用 0。 */
        public Layout(List<StyledLine> lines, int cursorRow, int cursorColumn, boolean cursorVisible,
                List<ItemHit> itemHits) {
            this(lines, cursorRow, cursorColumn, cursorVisible, itemHits, 0, 0);
        }
    }

    private static final int MAX_VISIBLE = 10;
    /** 说明/状态/提示相对卡片左边界的缩进列数（与标签同列）。 */
    private static final int INDENT = 2;

    /**
     * @param state 当前选择器状态（selector、selectorInput、专用元数据）
     * @param context 同帧主题/宽高
     * @param width 卡片宽度
     * @param maxRows 卡片可用高度；由 DockView 先扣除 status/footer/widget
     * @return 不超出 maxRows 的卡片；极窄/矮屏保留标题与关闭提示
     */
    public Layout render(TuiState state, RenderContext context, int width, int maxRows) {
        int columns = Math.max(1, width);
        int rows = Math.max(1, maxRows);
        SelectList list = state.selector;
        Input input = state.selectorInput;
        if (list == null) return new Layout(List.of(), 0, 0, false, List.of(), 0, 0);

        String title = state.selectorTitle == null || state.selectorTitle.isBlank()
                ? (state.selectorKind.isBlank() ? "Select" : state.selectorKind) : state.selectorTitle;
        CardFrame.Metrics frame = CardFrame.measure(columns, rows, 0, false, CardFrame.Surface.PI_SECTION);
        if (frame.minimal()) {
            return new Layout(CardFrame.compose(title, "Esc close", List.of(), List.of(), null, frame, context),
                    0, 0, false, List.of(), frame.contentX(), frame.contentWidth());
        }
        int contentWidth = frame.contentWidth();
        // 查询/箭头在 0 列，说明与计数用 INDENT；换行再留出右侧两列空白。
        int innerWidth = Math.max(1, contentWidth - INDENT * 2);

        boolean settings = state.selectorKind.equals("Settings");
        boolean showDescription = !state.selectorDescription.isBlank();
        boolean showStatus = !state.selectorStatus.isBlank();
        boolean showHint = !state.hint.isBlank();
        boolean showFooter = !state.selectorFooter.isBlank();
        boolean describe = showDescription;
        boolean status = showStatus;
        boolean hint = showHint;
        boolean footer = showFooter;
        boolean gap = frame.bodyRows() >= 5;

        // 先按优先级决定哪些可选说明还放得下；查询框和至少一行候选优先保留。
        int headRows, tailRows;
        while (true) {
            headRows = 1 + (gap ? 1 : 0);
            tailRows = (gap && (describe || status || hint || footer) ? 1 : 0)
                    + (describe ? wrappedRows(state.selectorDescription, innerWidth, 3) : 0)
                    + (status ? wrappedRows(state.selectorStatus, innerWidth, 2) : 0)
                    + (hint ? wrappedRows(state.hint, innerWidth, 2) : 0)
                    + (footer ? wrappedRows(state.selectorFooter, innerWidth, 2) : 0);
            if (headRows + tailRows + 1 <= frame.bodyRows()) break;
            // 固定优先级：先去掉装饰空行，再缩说明/状态/错误，最后才动底部提示。
            if (gap) { gap = false; continue; }
            if (describe) { describe = false; continue; }
            if (status) { status = false; continue; }
            if (hint) { hint = false; continue; }
            if (footer) { footer = false; continue; }
            break;
        }

        int listRows = Math.max(0, frame.bodyRows() - headRows - tailRows);
        int filtered = list.size();
        // 窗口上限是 10；即使 regular 预算很大，也要为被截掉的项显示计数。
        boolean count = filtered > Math.min(MAX_VISIBLE, listRows) && listRows > 1;
        if (count) listRows--;
        int visible = Math.min(MAX_VISIBLE, Math.max(0, listRows));
        int start = visible == 0 ? 0 : list.windowStart(visible);

        var body = new ArrayList<StyledLine>();
        body.add(searchLine(input, context, contentWidth));
        if (gap) body.add(StyledLine.empty());
        var hits = new ArrayList<ItemHit>();
        List<SelectList.Item> items = list.items();
        if (filtered == 0) {
            body.add(indentLine("No matches", context, contentWidth, "muted"));
        } else {
            // 列宽取完整候选（不受过滤影响），搜索时右值列不会反复跳动。
            int labelWidth = settings ? settingsLabelWidth(list.allItems()) : 0;
            for (int i = start; i < Math.min(filtered, start + visible); i++) {
                SelectList.Item item = items.get(i);
                boolean selected = i == list.selectedIndex();
                body.add(row(item, selected, settings, labelWidth, contentWidth, context));
                // 命中行是卡片内坐标：正文索引 + 1（上横线）。
                hits.add(new ItemHit(body.size(), item.id()));
            }
            if (count) {
                String counter = "(" + (list.selectedIndex() + 1) + "/" + filtered + ")";
                body.add(indentLine(counter, context, contentWidth, "dim"));
            }
        }
        if (gap && (describe || status || hint || footer)) body.add(StyledLine.empty());
        if (describe) addWrapped(body, state.selectorDescription, context, contentWidth, "muted", 3);
        if (status) addWrapped(body, state.selectorStatus, context, contentWidth, "muted", 2);
        if (hint) addWrapped(body, state.hint, context, contentWidth, "warning", 2);
        if (footer) addWrapped(body, state.selectorFooter, context, contentWidth, "dim", 2);

        // 查询行固定在正文第一行（上横线之后）；`> ` 占两列，Input 窗口宽 W-2。
        int queryWidth = Math.max(1, contentWidth - 2);
        int cursorColumn = 2 + (input == null ? 0 : input.cursorColumn(queryWidth));
        return new Layout(CardFrame.compose(title, "Esc close", List.of(), body, null, frame, context),
                1, Math.max(0, Math.min(columns - 1, cursorColumn)), input != null,
                hits, frame.contentX(), contentWidth);
    }

    /** 查询行：`> ` 从 x=0 开始，Input 从 x=2；只画真实 Input，不伪造光标。 */
    private static StyledLine searchLine(Input input, RenderContext context, int width) {
        int content = Math.max(1, width - 2);
        var spans = new ArrayList<Span>();
        spans.add(new Span("> ", context.theme().style("accent"), null));
        if (input != null) spans.addAll(input.render(context, content).getFirst().spans());
        return new StyledLine(spans).crop(width);
    }

    /**
     * 一行候选：箭头从 0 列；Settings 标签从 2 列并按完整标签列宽对齐右值，
     * 其它页面标签后接两列与说明。选中只用 accent，不铺整行 selectedBg。
     */
    private static StyledLine row(SelectList.Item item, boolean selected, boolean settings, int labelWidth,
            int width, RenderContext context) {
        Style labelStyle = context.theme().style(selected ? "accent" : "text");
        Style valueStyle = context.theme().style(selected ? "accent" : "muted");
        String prefix = selected ? "→ " : "  ";
        if (settings) {
            int labelColumns = Math.max(1, Math.min(36, labelWidth));
            String label = DisplayWidth.ellipsize(item.label(), labelColumns);
            String padded = label + " ".repeat(Math.max(0, labelColumns - DisplayWidth.width(label)));
            // 右值列 = 2(箭头) + 标签列宽 + 2 列间隔；末尾至少留两列。
            int valueWidth = Math.max(0, width - INDENT - labelColumns - INDENT - INDENT);
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
            if (used + INDENT < width) spans.add(new Span("  " + DisplayWidth.ellipsize(item.description(),
                    width - used - INDENT), context.theme().style("muted"), null));
        }
        return new StyledLine(spans).crop(width);
    }

    /** 单行缩进文本（计数/无匹配）：x=2，宽 W-2，裁到卡片宽。 */
    private static StyledLine indentLine(String text, RenderContext context, int width, String token) {
        var spans = new ArrayList<Span>();
        spans.add(new Span(" ".repeat(INDENT), context.theme().style("dim"), null));
        spans.add(new Span(DisplayWidth.ellipsize(text, Math.max(1, width - INDENT)),
                context.theme().style(token), null));
        return new StyledLine(spans).crop(width);
    }

    private static int settingsLabelWidth(List<SelectList.Item> items) {
        int max = 1;
        for (SelectList.Item item : items) max = Math.max(max, DisplayWidth.width(item.label()));
        return Math.min(36, max);
    }

    /** 估算一段说明在给定换行宽下占几行；与 addWrapped 的换行宽一致。 */
    private static int wrappedRows(String text, int width, int maxLines) {
        if (text == null || text.isBlank() || maxLines <= 0) return 0;
        int count = StyledLine.wrap(List.of(new Span(text, Style.PLAIN, null)),
                Math.max(1, width)).size();
        return Math.min(maxLines, count);
    }

    /** 说明/状态/提示：每行同样从 x=2 起，换行宽 W-4。 */
    private static void addWrapped(List<StyledLine> out, String text, RenderContext context, int width,
            String token, int maxLines) {
        if (text == null || text.isBlank() || maxLines <= 0) return;
        List<StyledLine> wrapped = StyledLine.wrap(List.of(new Span(text, context.theme().style(token), null)),
                Math.max(1, width - INDENT * 2));
        for (int i = 0; i < Math.min(maxLines, wrapped.size()); i++) {
            StyledLine line = wrapped.get(i);
            var spans = new ArrayList<Span>();
            spans.add(new Span(" ".repeat(INDENT), context.theme().style("dim"), null));
            spans.addAll(line.spans());
            out.add(new StyledLine(spans).crop(width));
        }
    }
}
