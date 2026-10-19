package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TableCell;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.StyledLine;

/**
 * CommonMark 负责解析语法，本类把语法树排成终端行，不生成 HTML，也不执行原始 ANSI。
 * 每个消息条目按内容版本和宽度缓存结果；流式未闭合代码围栏交给解析器自然处理。
 */
public final class Markdown {
    private final Parser parser = Parser.builder().extensions(List.of(TablesExtension.create())).build();
    private static final Pattern TOKENS = Pattern.compile("(//.*|#.*|/\\*.*?\\*/)|([\"'](?:\\\\.|[^\"'\\\\])*[\"'])|\\b(public|private|class|record|return|if|else|for|while|new|static|final|void|int|boolean|def|import|from|const|let|function|true|false|null)\\b");

    /** 解析完整的当前显示副本；即使正在生成，也不会修改存入模型历史的原文。 */
    public List<StyledLine> render(String source, int width) {
        var lines = new ArrayList<StyledLine>();
        blocks(parser.parse(DisplayWidth.clean(source)), "", width, lines);
        while (!lines.isEmpty() && lines.getLast().text().isBlank()) lines.removeLast();
        return List.copyOf(lines);
    }

    /** 递归处理块结构。列表前缀也计入可用宽度，不让缩进后的正文越过终端边界。 */
    private void blocks(Node parent, String prefix, int width, List<StyledLine> out) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Paragraph || node instanceof Heading) {
                var spans = new ArrayList<Span>(); spans.add(new Span(prefix, "muted"));
                inline(node, node instanceof Heading ? "heading" : "normal", spans);
                out.addAll(StyledLine.wrap(spans, width)); out.add(StyledLine.of("", "normal"));
            } else if (node instanceof TableBlock) table(node, prefix, width, out);
            else if (node instanceof FencedCodeBlock code) code(code.getLiteral(), code.getInfo(), prefix, width, out);
            else if (node instanceof IndentedCodeBlock code) code(code.getLiteral(), "", prefix, width, out);
            else if (node instanceof BlockQuote) blocks(node, prefix + "│ ", width, out);
            else if (node instanceof BulletList || node instanceof OrderedList) {
                int number = node instanceof OrderedList ordered ? ordered.getStartNumber() : 0;
                for (Node item = node.getFirstChild(); item != null; item = item.getNext()) {
                    blocks(item, prefix + (node instanceof OrderedList ? number++ + ". " : "• "), width, out);
                }
            } else if (node instanceof ThematicBreak) out.add(StyledLine.of("─".repeat(Math.max(1, width)), "muted"));
            else if (node instanceof HtmlBlock html) out.addAll(StyledLine.wrap(List.of(new Span(prefix + html.getLiteral(), "normal")), width));
            else blocks(node, prefix, width, out);
        }
    }

    /**
     * 把 GFM 表格排成终端网格，参考 Pi 的“先分配列宽，再分别换行单元格”。
     * 解析转义管道符和行内代码仍由 CommonMark 完成，不能用 split("|") 重新猜列。
     * width 是整行可用列数，prefix 的引用／列表缩进和边框也占空间。
     * 本方法只读取语法树，不修改回复正文，也不向终端直接写入任何控制符。
     */
    private void table(Node table, String prefix, int width, List<StyledLine> out) {
        var rows = new ArrayList<List<TableCell>>();
        // TableBlock 下先是表头／表体容器，容器下才是每一行。
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) continue;
                var cells = new ArrayList<TableCell>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext())
                    if (cell instanceof TableCell c) cells.add(c);
                rows.add(cells);
            }
        }
        if (rows.isEmpty() || rows.getFirst().isEmpty()) return;
        int columns = rows.getFirst().size();
        int available = Math.max(0, width - DisplayWidth.width(prefix));
        // n 列网格的边框和左右内边距共占 3*n+1 列；每列至少需要一格正文。
        // 极窄终端不裁掉后几列，改为按顺序显示可换行的文本行。
        if ((long) columns * 4 + 1 > available) {
            for (var row : rows) {
                var spans = new ArrayList<Span>(); spans.add(new Span(prefix + "| ", "muted"));
                for (TableCell cell : row) { inline(cell, "normal", spans); spans.add(new Span(" | ", "muted")); }
                out.addAll(StyledLine.wrap(spans, width));
            }
            out.add(StyledLine.of("", "normal")); return;
        }

        // 1. 计算无约束时各列所需宽度。使用显示列宽，不把中文、emoji 或样式当一个 char。
        int[] natural = new int[columns]; java.util.Arrays.fill(natural, 1);
        for (var row : rows) for (int i = 0; i < Math.min(columns, row.size()); i++) {
            var spans = new ArrayList<Span>(); inline(row.get(i), "normal", spans);
            String text = new StyledLine(spans).text();
            for (String line : text.split("\n", -1)) natural[i] = Math.max(natural[i], DisplayWidth.width(line));
        }
        int cellsWidth = available - 3 * columns - 1;
        int[] widths = natural.clone();
        long total = java.util.Arrays.stream(natural).asLongStream().sum();
        if (total > cellsWidth) {
            // 2. 先给每列一格，再按自然宽度分配剩余空间。取整剩下的格子逐列补回。
            // 极长的路径会在单元格中换行，不能挤掉其它列或写到窗口外。
            int extra = cellsWidth - columns, allocated = 0;
            for (int i = 0; i < columns; i++) {
                widths[i] = 1 + (int) ((long) (natural[i] - 1) * extra / (total - columns));
                allocated += widths[i];
            }
            for (int i = 0; allocated < cellsWidth; i = (i + 1) % columns)
                if (widths[i] < natural[i]) { widths[i]++; allocated++; }
        }

        // 3. 先画表头，再画数据行；每行高度是其中最高单元格的换行数。
        tableBorder(prefix, widths, "┌", "┬", "┐", out);
        for (int index = 0; index < rows.size(); index++) {
            List<TableCell> row = rows.get(index);
            var wrapped = new ArrayList<List<StyledLine>>(); int height = 1;
            for (int i = 0; i < columns; i++) {
                var spans = new ArrayList<Span>();
                if (i < row.size()) inline(row.get(i), index == 0 ? "heading" : "normal", spans);
                List<StyledLine> lines = StyledLine.wrap(spans, widths[i]); wrapped.add(lines);
                height = Math.max(height, lines.size());
            }
            for (int line = 0; line < height; line++) {
                var spans = new ArrayList<Span>(); spans.add(new Span(prefix + "│ ", "muted"));
                for (int i = 0; i < columns; i++) {
                    StyledLine cell = line < wrapped.get(i).size() ? wrapped.get(i).get(line) : StyledLine.of("", "normal");
                    int padding = Math.max(0, widths[i] - DisplayWidth.width(cell.text()));
                    String align = String.valueOf(rows.getFirst().get(i).getAlignment());
                    int before = align.equals("RIGHT") ? padding : align.equals("CENTER") ? padding / 2 : 0;
                    spans.add(new Span(" ".repeat(before), "normal")); spans.addAll(cell.spans());
                    spans.add(new Span(" ".repeat(padding - before) + (i + 1 < columns ? " │ " : " │"), "muted"));
                }
                out.add(new StyledLine(spans));
            }
            if (index + 1 < rows.size()) tableBorder(prefix, widths, "├", "┼", "┤", out);
        }
        tableBorder(prefix, widths, "└", "┴", "┘", out); out.add(StyledLine.of("", "normal"));
    }

    /** 边框复用正文的列宽；每格两侧各有一格空白，所以横线比正文宽两格。 */
    private void tableBorder(String prefix, int[] widths, String left, String middle, String right, List<StyledLine> out) {
        String line = java.util.Arrays.stream(widths).mapToObj(w -> "─".repeat(w + 2))
                .collect(java.util.stream.Collectors.joining(middle));
        out.add(StyledLine.of(prefix + left + line + right, "muted"));
    }

    /** 内联节点保留样式，不把链接、粗体或行内代码的 Markdown 标记原样堆给终端。 */
    private void inline(Node parent, String style, List<Span> out) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Text text) out.add(new Span(text.getLiteral(), style));
            else if (node instanceof Code code) out.add(new Span(code.getLiteral(), "code"));
            else if (node instanceof SoftLineBreak) out.add(new Span(" ", style));
            else if (node instanceof HardLineBreak) out.add(new Span("\n", style));
            else if (node instanceof HtmlInline html) out.add(new Span(html.getLiteral(), style));
            else if (node instanceof StrongEmphasis) inline(node, "heading", out);
            else if (node instanceof Emphasis) inline(node, "thinking", out);
            else if (node instanceof Link link) {
                inline(node, "accent", out); out.add(new Span(" (" + link.getDestination() + ")", "muted"));
            } else if (node instanceof Image image) {
                out.add(new Span("[image: ", "muted")); inline(node, "muted", out); out.add(new Span("] " + image.getDestination(), "muted"));
            } else inline(node, style, out);
        }
    }

    /** 基本词法配色，不冒充完整语言服务器。代码字符、空格和缩进均保留，只增加显示样式。 */
    private void code(String text, String language, String prefix, int width, List<StyledLine> out) {
        out.add(StyledLine.of(DisplayWidth.take(prefix + "╭─ " + language, width), "muted"));
        for (String line : text.split("\n", -1)) {
            var spans = new ArrayList<Span>(); spans.add(new Span(prefix + "│ ", "muted"));
            var matcher = TOKENS.matcher(line); int from = 0;
            while (matcher.find()) {
                spans.add(new Span(line.substring(from, matcher.start()), "code"));
                spans.add(new Span(matcher.group(), matcher.group(1) != null ? "comment" : matcher.group(2) != null ? "string" : "keyword"));
                from = matcher.end();
            }
            spans.add(new Span(line.substring(from), "code")); out.addAll(StyledLine.wrap(spans, width));
        }
        out.add(StyledLine.of(DisplayWidth.take(prefix + "╰─", width), "muted"));
    }
}
