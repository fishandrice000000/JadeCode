package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.model.CommandPanelModel;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.text.TextSanitizer;

/**
 * 命令信息弹窗组件：真实分组/字段/服务列表、局部焦点和滚动，多列随宽度重排。
 *
 * <p>只消费 {@link CommandPanelModel}，不读 Host、不写终端、不把 Markdown 当页面。所有外部
 * 名称/值先经 {@link TextSanitizer#display} 净化；行宽严格限制在给定宽度内。边框内先给
 * 标题/提示留高度，再按列宽渲染分组；内容超出时只滚动正文，不截断边界。</p>
 */
public final class CommandPanelView implements Component {
    private final CommandPanelModel model;
    private int scroll;
    private int section;
    private int service;
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
        if (budget < 4 || columns < 8) {
            return List.of(StyledLine.of(DisplayWidth.ellipsize(title + " · Esc close", columns),
                    context.theme().style("muted")));
        }
        boolean footer = budget >= 5;
        int bodyRows = Math.max(1, budget - 2 - (footer ? 1 : 0));
        List<Section> sections = buildSections(context, columns);
        int inner = Math.max(1, columns - 2);
        int columnCount = Math.min(3, Math.max(1, (inner + 2) / 32));
        if (model.kind() == CommandPanelModel.Kind.MCP) columnCount = inner >= 60 ? 2 : 1;
        int columnWidth = Math.max(8, (inner - (columnCount - 1) * 2) / columnCount);
        List<List<StyledLine>> columnLines = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) columnLines.add(new ArrayList<>());
        int[] heights = new int[columnCount];
        var starts = new ArrayList<Integer>();
        for (Section value : sections) {
            int target = 0;
            for (int i = 1; i < columnCount; i++) if (heights[i] < heights[target]) target = i;
            if (heights[target] > 0) { columnLines.get(target).add(StyledLine.empty()); heights[target]++; }
            starts.add(heights[target]);
            var lines = renderSection(value, context, columnWidth, value.selected());
            columnLines.get(target).addAll(lines);
            heights[target] += lines.size();
        }
        sectionStarts = List.copyOf(starts);
        if (model.kind() == CommandPanelModel.Kind.MCP && model.services().isEmpty()) {
            columnLines.get(0).add(StyledLine.of(DisplayWidth.ellipsize(model.emptyText(), columnWidth),
                    context.theme().style("muted")));
        }
        int bodySize = 0;
        for (var column : columnLines) bodySize = Math.max(bodySize, column.size());
        lastBodySize = bodySize;
        lastBodyRows = bodyRows;
        int maxScroll = Math.max(0, bodySize - bodyRows);
        scroll = Math.min(scroll, maxScroll);
        var body = new ArrayList<StyledLine>();
        for (int row = scroll; row < Math.min(bodySize, scroll + bodyRows); row++) {
            var spans = new ArrayList<Span>();
            for (int i = 0; i < columnLines.size(); i++) {
                if (i > 0) spans.add(new Span("  ", Style.PLAIN, null));
                StyledLine line = row < columnLines.get(i).size() ? columnLines.get(i).get(row) : StyledLine.empty();
                spans.addAll(line.padTo(columnWidth, Style.PLAIN).spans());
            }
            body.add(new StyledLine(spans).crop(columns));
        }
        var out = new ArrayList<StyledLine>();
        out.add(borderLine('┌', title, columns, context));
        out.addAll(body);
        if (footer) out.add(StyledLine.of(DisplayWidth.ellipsize(hint(), columns), context.theme().style("dim")));
        out.add(borderLine('└', "", columns, context));
        if (out.size() > budget) out = new ArrayList<>(out.subList(0, budget));
        return out;
    }

    private String hint() {
        return switch (model.kind()) {
            case MCP -> "↑↓ Service · Tab Section · PgUp/PgDn Scroll · Esc Close";
            case HOTKEYS -> "↑↓ Scroll · Tab Section · PgUp/PgDn Scroll · Esc Close";
            default -> "↑↓ Scroll · PgUp/PgDn Scroll · Esc Close";
        };
    }

    private StyledLine borderLine(char left, String label, int columns, RenderContext context) {
        int fill = Math.max(0, columns - 2);
        String line;
        if (left == '┌' && !label.isBlank()) {
            String shown = DisplayWidth.ellipsize(label, Math.max(1, columns - 5));
            int used = DisplayWidth.width(shown);
            // 1(┌) + 2("─ ") + label + 1(空格) + fill + 1(┐) = columns。
            fill = Math.max(0, columns - used - 5);
            line = "┌─ " + shown + " " + "─".repeat(fill) + "┐";
        } else {
            line = left + "─".repeat(fill) + (left == '┌' ? '┐' : '┘');
        }
        return StyledLine.of(DisplayWidth.take(line, columns), context.theme().style("borderMuted"));
    }

    private record Section(String title, List<CommandPanelModel.Entry> entries, List<CommandPanelModel.Field> fields,
            boolean selected) { }

    private List<Section> buildSections(RenderContext context, int columns) {
        var sections = new ArrayList<Section>();
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
                String key = DisplayWidth.ellipsize(oneLine(entry.key()), keyWidth);
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
