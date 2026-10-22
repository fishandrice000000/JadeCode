package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.model.CommandPanelModel;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.layout.CardFrame;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.text.TextSanitizer;

/**
 * 命令信息卡片：真实分组/字段/服务列表、局部焦点和滚动，多列随宽度重排。
 *
 * <p>只消费 {@link CommandPanelModel}，不读 Host、不写终端、不把 Markdown 当页面。所有外部
 * 名称/值先经 {@link TextSanitizer#display} 净化；行宽严格限制在给定宽度内。外框/留白/面板
 * 底色统一由 {@link CardFrame} 生成，本类只负责正文内容和局部滚动，因此与审批/选择/搜索
 * 使用同一套闭合几何。标题嵌入顶边并使用 accent+bold；宽度/高度不足时先缩 padding，再滚动
 * 正文，四角和底边始终完整。</p>
 */
public final class CommandPanelView implements Component {
    private final CommandPanelModel model;
    private int scroll;
    private int section;
    private int service;
    /** 每个快捷键分类保留自己的阅读位置；其它三种信息页仍使用原多列布局。 */
    private final java.util.Map<String, Integer> categoryScroll = new java.util.HashMap<>();
    /** 最近一次布局中每个分组的正文起始行（含滚动前坐标），Tab 用它定位。 */
    private List<Integer> sectionStarts = List.of();

    public CommandPanelView(CommandPanelModel model) {
        this.model = model;
    }

    public CommandPanelModel model() { return model; }
    public int scroll() { return scroll; }
    public int section() { return section; }
    public int service() { return service; }

    /** 上下/翻页滚动正文；不改变分组或服务选择。 */
    public void scrollBy(int delta) { scroll = Math.max(0, scroll + delta); }

    public void page(int direction, int pageSize) {
        scrollBy(direction * Math.max(1, pageSize));
    }

    public void top() { scroll = 0; }

    public void bottom(int bodyRows) {
        scroll = Math.max(0, lastBodySize - Math.max(1, bodyRows));
    }

    /** 跳到正文末尾；使用最近一次渲染的可见行数。 */
    public void end() { scroll = Math.max(0, lastBodySize - Math.max(1, lastBodyRows)); }

    /** Tab/Shift+Tab 切换分组；不跳出弹窗。 */
    public void moveSection(int direction) {
        if (model.groups().isEmpty() && model.fields().isEmpty() && model.services().isEmpty()) return;
        if (model.kind() == CommandPanelModel.Kind.HOTKEYS && !model.groups().isEmpty()) {
            categoryScroll.put(model.groups().get(section).id(), scroll);
            section = Math.floorMod(section + direction, model.groups().size());
            scroll = categoryScroll.getOrDefault(model.groups().get(section).id(), 0);
            return;
        }
        int count = Math.max(1, sectionStarts.size());
        section = Math.floorMod(section + direction, count);
        if (section < sectionStarts.size()) scroll = sectionStarts.get(section);
    }

    /** MCP 服务选择；其它页面退化为滚动。 */
    public void moveService(int direction) {
        if (model.kind() != CommandPanelModel.Kind.MCP || model.services().isEmpty()) {
            scrollBy(direction);
            return;
        }
        service = Math.floorMod(service + direction, model.services().size());
        scroll = 0;
    }

    /** 当前可见行数；渲染时记录，供 PageUp/Down 使用。 */
    private int lastBodyRows = 1;
    private int lastBodySize;

    /** 以实际面板正文高度翻页，regular 不再依赖为零的聊天视口高度。 */
    public void page(int direction) { page(direction, lastBodyRows); }

    @Override public List<StyledLine> render(RenderContext context, int width) {
        return render(context, width, Math.max(1, context.height()));
    }

    /**
     * @param context 同帧主题/上下文
     * @param width 面板总列数
     * @param maxRows 面板总行数
     * @return 严格不超过 maxRows 的面板行
     */
    public List<StyledLine> render(RenderContext context, int width, int maxRows) {
        int columns = Math.max(1, width);
        int budget = Math.max(1, maxRows);
        String title = oneLine(model.title());
        boolean footer = budget >= 5;
        boolean categories = model.kind() == CommandPanelModel.Kind.HOTKEYS && !model.groups().isEmpty()
                && budget >= 5;
        // 固定头部只有分类导航；正文滚动不能把它带走。footer 单独占一行，避免长分组挤掉提示。
        CardFrame.Metrics frame = CardFrame.measure(columns, budget, categories ? 1 : 0, footer);
        if (frame.minimal()) return CardFrame.compose(title, "Esc close", List.of(), List.of(), null, frame, context);
        int contentWidth = frame.contentWidth();
        int bodyRows = frame.bodyRows();

        List<Section> sections = buildSections(contentWidth);
        int columnCount = Math.min(3, Math.max(1, (contentWidth + 2) / 32));
        if (model.kind() == CommandPanelModel.Kind.HOTKEYS) columnCount = 1;
        if (model.kind() == CommandPanelModel.Kind.MCP) columnCount = contentWidth >= 60 ? 2 : 1;
        int columnWidth = Math.max(8, (contentWidth - (columnCount - 1) * 2) / columnCount);
        if (columnWidth * columnCount + (columnCount - 1) * 2 > contentWidth) columnCount = 1;
        if (columnCount == 1) columnWidth = contentWidth;
        List<List<StyledLine>> columnLines = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) columnLines.add(new ArrayList<>());
        int[] heights = new int[columnCount];
        var sectionShapes = new ArrayList<int[]>();
        for (Section value : sections) {
            int target = 0;
            for (int i = 1; i < columnCount; i++) if (heights[i] < heights[target]) target = i;
            if (heights[target] > 0) { columnLines.get(target).add(StyledLine.empty()); heights[target]++; }
            var lines = renderSection(value, context, columnWidth, value.selected());
            sectionShapes.add(new int[]{target, lines.size()});
            columnLines.get(target).addAll(lines);
            heights[target] += lines.size();
        }
        int bodySize = 0;
        for (var column : columnLines) bodySize = Math.max(bodySize, column.size());
        // 高度紧张时先去掉分组间的纯装饰空行，再滚动正文；标题/底边/侧边不截断。
        while (bodySize > bodyRows && removeDecorativeBlank(columnLines)) {
            bodySize = 0;
            for (var column : columnLines) bodySize = Math.max(bodySize, column.size());
        }
        // 去掉装饰空行后按原分组顺序重算 Tab 起点，不能沿用删除前的行号。
        var starts = new ArrayList<Integer>();
        int[] rebuilt = new int[columnCount];
        for (int[] shape : sectionShapes) {
            starts.add(rebuilt[shape[0]]);
            rebuilt[shape[0]] += shape[1];
        }
        sectionStarts = List.copyOf(starts);
        lastBodySize = bodySize;
        lastBodyRows = Math.max(1, bodyRows);
        scroll = Math.min(scroll, Math.max(0, bodySize - lastBodyRows));

        // 组装正文行：各列按列宽补齐后拼成一条内容行，外框和底色交给 CardFrame。
        var body = new ArrayList<StyledLine>();
        for (int row = scroll; row < Math.min(bodySize, scroll + lastBodyRows); row++) {
            var spans = new ArrayList<Span>();
            for (int i = 0; i < columnLines.size(); i++) {
                if (i > 0) spans.add(new Span("  ", Style.PLAIN, null));
                StyledLine line = row < columnLines.get(i).size() ? columnLines.get(i).get(row) : StyledLine.empty();
                spans.addAll(line.padTo(columnWidth, Style.PLAIN).crop(columnWidth).spans());
            }
            body.add(new StyledLine(spans));
        }
        List<StyledLine> header = List.of();
        if (categories) {
            String navigation = "‹ " + oneLine(model.groups().get(section).title()) + " ›  "
                    + (section + 1) + "/" + model.groups().size();
            header = List.of(StyledLine.of(DisplayWidth.ellipsize(navigation, contentWidth),
                    context.theme().style("accent")));
        }
        StyledLine footerLine = footer
                ? StyledLine.of(DisplayWidth.ellipsize(hint(), contentWidth), context.theme().style("dim"))
                : null;
        return CardFrame.compose(title, "Esc close", header, body, footerLine, frame, context);
    }

    /** 去掉一个装饰空行；返回是否发生变化。 */
    private static boolean removeDecorativeBlank(List<List<StyledLine>> columns) {
        for (List<StyledLine> column : columns) {
            for (int i = column.size() - 1; i >= 0; i--) {
                if (column.get(i).isBlank()) { column.remove(i); return true; }
            }
        }
        return false;
    }

    private String hint() {
        return switch (model.kind()) {
            case MCP -> "↑↓ Service · Tab Section · PgUp/PgDn Scroll · Esc Close";
            case HOTKEYS -> "←/→ Tab Category · ↑/↓ Scroll · Esc Close";
            default -> "↑↓ Scroll · PgUp/PgDn Scroll · Esc Close";
        };
    }

    private record Section(String title, List<CommandPanelModel.Entry> entries, List<CommandPanelModel.Field> fields,
            boolean selected) { }

    private List<Section> buildSections(int columns) {
        var sections = new ArrayList<Section>();
        if (model.kind() == CommandPanelModel.Kind.HOTKEYS && !model.groups().isEmpty()) {
            var group = model.groups().get(section);
            // 只把当前分类交给布局；分类导航保持在正文滚动区外。
            return List.of(new Section("", group.entries(), List.of(), true));
        }
        if (model.kind() == CommandPanelModel.Kind.MCP) {
            var services = new ArrayList<CommandPanelModel.Entry>();
            for (int i = 0; i < model.services().size(); i++) {
                var value = model.services().get(i);
                String status = !value.enabled() ? "disabled" : value.connected() ? "connected" : "configured";
                services.add(new CommandPanelModel.Entry((i == service ? "→ " : "  ") + oneLine(value.name()), status, ""));
            }
            sections.add(new Section("Servers", services, List.of(), section == 0));
            if (model.services().isEmpty()) {
                sections.add(new Section("Details", List.of(), List.of(
                        new CommandPanelModel.Field("Status", model.emptyText())), section == 1));
            } else {
                var value = model.services().get(Math.max(0, Math.min(service, model.services().size() - 1)));
                var details = new ArrayList<CommandPanelModel.Entry>();
                String status = !value.enabled() ? "disabled" : value.connected() ? "connected" : "configured";
                details.add(new CommandPanelModel.Entry("Status", status, ""));
                if (value.connected() && value.tools().isEmpty())
                    details.add(new CommandPanelModel.Entry("Tools", "connected; no tools advertised", ""));
                else for (String tool : value.tools())
                    details.add(new CommandPanelModel.Entry(oneLine(tool), "", ""));
                sections.add(new Section(oneLine(value.name()), details, List.of(), section == 1));
            }
            return sections;
        }
        if (!model.fields().isEmpty()) {
            sections.add(new Section("Overview", List.of(), model.fields(), section == 0));
        }
        for (int i = 0; i < model.groups().size(); i++) {
            var group = model.groups().get(i);
            sections.add(new Section(oneLine(group.title()), group.entries(), List.of(),
                    section == sections.size()));
        }
        if (sections.isEmpty()) {
            sections.add(new Section(model.title(), List.of(),
                    List.of(new CommandPanelModel.Field("Status", model.emptyText())), true));
        }
        return sections;
    }

    private List<StyledLine> renderSection(Section section, RenderContext context, int width, boolean selected) {
        var out = new ArrayList<StyledLine>();
        if (!section.title().isBlank())
            out.add(StyledLine.of(DisplayWidth.ellipsize(section.title(), width),
                    context.theme().style(selected ? "accent" : "mdHeading")));
        if (section.entries().isEmpty() && section.fields().isEmpty())
            out.add(StyledLine.of(DisplayWidth.ellipsize("  " + model.emptyText(), width), context.theme().style("muted")));
        int keyWidth = 0;
        for (var entry : section.entries()) keyWidth = Math.max(keyWidth, DisplayWidth.width(oneLine(entry.key())));
        keyWidth = Math.min(Math.max(8, keyWidth), Math.max(8, width / 2));
        for (var entry : section.entries()) {
            var spans = new ArrayList<Span>();
            if (!entry.key().isEmpty()) {
                // 快捷键正文必须完整可读：窄屏把组合键交给富文本换行，不先省略掉后半组绑定。
                String key = model.kind() == CommandPanelModel.Kind.HOTKEYS
                        ? oneLine(entry.key()) : DisplayWidth.ellipsize(oneLine(entry.key()), keyWidth);
                spans.add(new Span(key + " ".repeat(Math.max(0, keyWidth - DisplayWidth.width(key))),
                        context.theme().style("accent"), null));
                if (!entry.label().isEmpty()) spans.add(new Span("  ", Style.PLAIN, null));
            }
            if (!entry.label().isEmpty()) spans.add(new Span(oneLine(entry.label()), context.theme().style("text"), null));
            out.addAll(wrapSpans(spans, width));
            if (!entry.description().isEmpty())
                out.addAll(wrapSpans(List.of(new Span("  " + entry.description(),
                        context.theme().style("muted"), null)), width));
        }
        for (var field : section.fields()) {
            var spans = new ArrayList<Span>();
            spans.add(new Span(oneLine(field.label()) + ": ", context.theme().style("muted"), null));
            spans.add(new Span(oneLine(field.value()), context.theme().style("text"), null));
            out.addAll(wrapSpans(spans, width));
        }
        return out;
    }

    /** 外部数据一律压成单行可见文本；不作为 Markdown/ANSI/多行注入执行。 */
    private static String oneLine(String value) {
        return TextSanitizer.display(value).replace('\n', ' ').replace('\t', ' ');
    }

    private static List<StyledLine> wrapSpans(List<Span> spans, int width) {
        return StyledLine.wrap(spans, Math.max(1, width));
    }
}
