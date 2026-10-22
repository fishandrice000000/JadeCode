package com.jadecode.tui.markdown;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.LinkTarget;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.text.TextSanitizer;
import com.jadecode.tui.theme.Theme;

/**
 * 把 Markdown 块/行内 token 排成终端行：Java 拥有布局，解析结果来自纯 DTO。
 *
 * <p>覆盖 Pi 的基本规则：h1 下划线、h3+ 保留 `#`、段落空行、hr 最多 80 列、列表每层
 * 4 空格与任务项、引用竖线+斜体、strong/em/code/del 嵌套恢复、链接 OSC8、代码原始围栏和
 * 两个空格缩进、表格 `3*n+1` 开销与最长词最小列宽、按词/CJK 折行、HTML 原样显示。超窄
 * 表格退回源码，不裁剪内容冒充完整表格。所有正文先经 {@link TextSanitizer} 净化。</p>
 *
 * <p>解析器接受 {@link TextRenderResult.Token}；{@link JavaMarkdownParser} 产生同结构 token，
 * 以便固定 JS 引擎接入后布局不变。样式只来自 {@link Theme} token，不硬编码 ANSI。</p>
 */
public final class MarkdownLayout {
    private final Theme theme;

    /** @param theme 当前主题快照；布局结果可直接缓存到下一帧。 */
    public MarkdownLayout(Theme theme) { this.theme = theme; }

    /**
     * @param markdown 原始 Markdown（可含换行/不可信控制字符）
     * @param width 可用列数
     * @param baseStyle 额外基础样式（thinking 斜体等）；null 为普通正文
     * @return 完整显示行；末尾不附加多余空行
     */
    public List<StyledLine> render(String markdown, int width, Style baseStyle) {
        String source = TextSanitizer.display(markdown == null ? "" : markdown);
        TextRenderResult result = new JavaMarkdownParser(source).parse();
        return layout(result, Math.max(1, width), baseStyle == null ? Style.PLAIN : baseStyle);
    }

    /** 渲染已解析 token；供 JS 结果复用。 */
    public List<StyledLine> layout(TextRenderResult result, int width, Style baseStyle) {
        return layout(result, width, baseStyle, false);
    }

    /** 渲染已解析 token；thinking 时跳过 Mermaid 图形但仍按 Markdown 排版。 */
    public List<StyledLine> layout(TextRenderResult result, int width, Style baseStyle, boolean thinking) {
        var out = new ArrayList<StyledLine>();
        // Java 回退把块包在 Document 里，固定 JS 适配器返回平铺顶层块。两者必须先展开成
        // 同一块序列，再统一插入块间距；否则同源 alpha\n\nbeta 会出现 2 行/3 行跳变。
        renderBlocks(result.tokens(), width, baseStyle, out, 0, thinking);
        while (!out.isEmpty() && out.getLast().isBlank()) out.removeLast();
        return out;
    }

    /**
     * 按顺序排版一组块，并在相邻块之间插入一个空行。Document 只作包装，不参与间距；
     * 顶层和引用内部使用同一规则，保证回退与固定引擎的等价结构一致。
     */
    private void renderBlocks(List<TextRenderResult.Token> tokens, int width, Style style,
            List<StyledLine> out, int indent, boolean thinking) {
        var blocks = new ArrayList<TextRenderResult.Token>();
        flatten(tokens, blocks);
        for (int i = 0; i < blocks.size(); i++) {
            renderBlock(blocks.get(i), width, style, out, indent, thinking);
            if (i + 1 < blocks.size()) out.add(StyledLine.empty());
        }
    }

    /** 递归去掉 Document 包装；其它 token 保持原顺序。 */
    private static void flatten(List<TextRenderResult.Token> tokens, List<TextRenderResult.Token> out) {
        for (TextRenderResult.Token token : tokens) {
            if (token instanceof TextRenderResult.Document document) flatten(document.items(), out);
            else out.add(token);
        }
    }

    private void renderBlock(TextRenderResult.Token token, int width, Style style, List<StyledLine> out, int indent) {
        renderBlock(token, width, style, out, indent, false);
    }

    private void renderBlock(TextRenderResult.Token token, int width, Style style, List<StyledLine> out, int indent,
            boolean thinking) {
        String prefix = " ".repeat(Math.max(0, indent));
        switch (token) {
            case TextRenderResult.Heading heading -> {
                Style headingStyle = style.merge(theme.style("mdHeading"));
                String hashes = heading.depth() >= 3 ? "#".repeat(heading.depth()) + " " : "";
                var spans = inline(heading.items(), headingStyle);
                var lines = wrapWithPrefix(spans, width, prefix + hashes, prefix);
                out.addAll(lines);
                if (heading.depth() == 1) out.add(StyledLine.of(
                        DisplayWidth.take("─".repeat(Math.max(1, width)), width), theme.style("mdHr")));
            }
            case TextRenderResult.Paragraph paragraph ->
                    out.addAll(wrapWithPrefix(inline(paragraph.items(), style), width, prefix, prefix));
            case TextRenderResult.Code code -> {
                Style codeStyle = style.merge(theme.style("mdCodeBlock"));
                Style borderStyle = style.merge(theme.style("mdCodeBlockBorder"));
                List<String> codeLines = DisplayWidth.lines(code.text());
                // thinking 内容跳过 Mermaid 图形，按普通代码块显示原文（Pi 行为）。
                boolean mermaidArt = !thinking && code.language().equals("mermaid") && !code.highlight().isEmpty();
                if (!mermaidArt) {
                    String fence = "```" + (code.language() == null ? "" : code.language());
                    out.add(StyledLine.of(DisplayWidth.ellipsize(prefix + fence, width), borderStyle));
                }
                for (int i = 0; i < codeLines.size(); i++) {
                    List<TextRenderResult.Scope> scopes = i < code.highlight().size()
                            ? code.highlight().get(i) : List.of();
                    StyledLine line;
                    if (scopes.isEmpty()) line = StyledLine.of(prefix + (mermaidArt ? "" : "  ") + codeLines.get(i), codeStyle);
                    else line = scopedLine(prefix + (mermaidArt ? "" : "  "), scopes, codeStyle);
                    out.add(line.crop(width));
                }
                if (!mermaidArt) out.add(StyledLine.of(DisplayWidth.ellipsize(prefix + "```", width), borderStyle));
            }
            case TextRenderResult.ListBlock list -> {
                int index = list.start();
                for (TextRenderResult.ListItem item : list.items()) {
                    String itemPrefix = prefix + " ".repeat(Math.max(0, item.depth()) * 4)
                            + (item.task() ? (item.checked() ? "[x] " : "[ ] ") : list.ordered() ? (index++) + ". " : "- ");
                    List<Span> spans = inline(item.items(), style);
                    // 列表项内容中的嵌套块（少见）递归排在后面。
                    out.addAll(wrapWithPrefix(spans, width, itemPrefix,
                            prefix + " ".repeat(Math.max(0, item.depth()) * 4 + 2)));
                }
            }
            case TextRenderResult.Blockquote quote -> {
                Style quoteStyle = style.merge(theme.style("mdQuote"));
                var inner = new ArrayList<StyledLine>();
                // 引用内同样展开 Document 并保留块间空行，避免 Java/JS 的包装差异。
                renderBlocks(quote.items(), Math.max(1, width - 2), quoteStyle, inner, 0, thinking);
                while (!inner.isEmpty() && inner.getLast().isBlank()) inner.removeLast();
                for (StyledLine line : inner) {
                    var spans = new ArrayList<Span>();
                    spans.add(new Span("│ ", style.merge(theme.style("mdQuoteBorder")), null));
                    spans.addAll(line.spans());
                    out.add(new StyledLine(spans).crop(width));
                }
            }
            case TextRenderResult.Rule ignored ->
                    out.add(StyledLine.of("─".repeat(Math.min(80, Math.max(1, width))), theme.style("mdHr")));
            case TextRenderResult.Table table -> renderTable(table, width, style, out, prefix);
            case TextRenderResult.Latex latex -> {
                String text = prefix + (latex.display() ? "$$\n" + latex.text() + "\n$$" : "$" + latex.text() + "$");
                out.addAll(wrapWithPrefix(List.of(new Span(text, style, null)), width, "", prefix));
            }
            case TextRenderResult.Html html -> {
                for (String line : DisplayWidth.lines(html.raw()))
                    out.addAll(wrapWithPrefix(List.of(new Span(line, style, null)), width, prefix, prefix));
            }
            default -> {
                if (!token.children().isEmpty()) {
                    for (TextRenderResult.Token child : token.children())
                        renderBlock(child, width, style, out, indent, thinking);
                } else if (token instanceof TextRenderResult.Text text) {
                    out.addAll(wrapWithPrefix(List.of(new Span(text.value(), style, null)), width, prefix, prefix));
                } else if (token instanceof TextRenderResult.Break) out.add(StyledLine.empty());
            }
        }
    }

    /** 表格：3*n+1 开销、每列最小宽度取最长词（上限 30），余量均分；过窄退回源码。 */
    private void renderTable(TextRenderResult.Table table, int width, Style style, List<StyledLine> out, String prefix) {
        int columns = table.header().size();
        if (columns == 0) return;
        int[] widths = new int[columns];
        for (int i = 0; i < columns; i++) widths[i] = 1;
        for (int i = 0; i < columns; i++) widths[i] = Math.max(widths[i], longestWord(table.header().get(i)));
        for (var row : table.rows()) for (int i = 0; i < Math.min(columns, row.size()); i++)
            widths[i] = Math.max(widths[i], longestWord(row.get(i)));
        for (int i = 0; i < columns; i++) widths[i] = Math.min(30, widths[i]);
        int overhead = 3 * columns + 1;
        int content = Math.max(0, width - DisplayWidth.width(prefix) - overhead);
        int required = java.util.Arrays.stream(widths).sum();
        if (content < required) {
            // 极窄：退回带竖线的源码行，保留表头和全部单元格内容而不是裁掉数据。
            for (var cell : table.header())
                out.addAll(wrapWithPrefix(inline(cell, style), width, "| " + prefix, "| " + prefix));
            for (var row : table.rows()) {
                for (var cell : row) out.addAll(wrapWithPrefix(inline(cell, style), width, "| " + prefix, "| " + prefix));
            }
            return;
        }
        int extra = content - required;
        for (int i = 0; i < columns; i++) widths[i] += extra / columns + (i < extra % columns ? 1 : 0);
        String border = border('┌', '┬', '┐', widths);
        out.add(StyledLine.of(DisplayWidth.take(prefix + border, width), theme.style("mdHr")));
        out.add(tableRow(table.header(), widths, style, prefix, true));
        out.add(StyledLine.of(DisplayWidth.take(prefix + border('├', '┼', '┤', widths), width), theme.style("mdHr")));
        for (var row : table.rows()) {
            out.add(tableRow(row, widths, style, prefix, false));
            out.add(StyledLine.of(DisplayWidth.take(prefix + border('├', '┼', '┤', widths), width), theme.style("mdHr")));
        }
    }

    private StyledLine tableRow(List<List<TextRenderResult.Token>> cells, int[] widths, Style style,
            String prefix, boolean header) {
        var spans = new ArrayList<Span>();
        spans.add(new Span(prefix + "│ ", theme.style("mdHr"), null));
        for (int i = 0; i < widths.length; i++) {
            Style cellStyle = header ? style.merge(theme.style("mdHeading")) : style;
            String text = i < cells.size() ? plain(inline(cells.get(i), cellStyle)) : "";
            String cropped = DisplayWidth.ellipsize(text, widths[i]);
            spans.add(new Span(cropped + " ".repeat(Math.max(0, widths[i] - DisplayWidth.width(cropped))), cellStyle, null));
            spans.add(new Span(" │ ", theme.style("mdHr"), null));
        }
        return new StyledLine(spans);
    }

    /** 按语义作用域构建一行；cls 映射主题 token，未知类回退代码块样式。 */
    private StyledLine scopedLine(String prefix, List<TextRenderResult.Scope> scopes, Style base) {
        var spans = new ArrayList<Span>();
        spans.add(new Span(prefix, base, null));
        for (TextRenderResult.Scope scope : scopes) {
            if (scope.text().isEmpty()) continue;
            spans.add(new Span(scope.text(), scopeStyle(scope.cls(), base), null));
        }
        return new StyledLine(spans);
    }

    private Style scopeStyle(String cls, Style base) {
        if (cls == null || cls.isEmpty()) return base;
        return switch (cls) {
            case "hljs-keyword" -> theme.style("syntaxKeyword");
            case "hljs-string" -> theme.style("syntaxString");
            case "hljs-comment" -> theme.style("syntaxComment");
            case "hljs-number", "hljs-literal" -> theme.style("syntaxNumber");
            case "hljs-function", "hljs-title" -> theme.style("syntaxFunction");
            case "hljs-variable", "hljs-params", "hljs-attr", "hljs-attribute", "hljs-property" -> theme.style("syntaxVariable");
            case "hljs-type", "hljs-built_in", "hljs-class" -> theme.style("syntaxType");
            case "hljs-operator" -> theme.style("syntaxOperator");
            case "hljs-punctuation" -> theme.style("syntaxPunctuation");
            // grok-mermaid 的语义类
            case "border" -> theme.style("borderMuted");
            case "text" -> theme.style("text");
            case "edge" -> theme.style("accent");
            case "edgeLabel" -> theme.style("muted");
            case "title" -> theme.style("accent").withBold(true);
            case "none" -> base;
            default -> base;
        };
    }

    private static String border(char left, char middle, char right, int[] widths) {
        var builder = new StringBuilder();
        builder.append(left);
        for (int i = 0; i < widths.length; i++) {
            builder.append("─".repeat(widths[i] + 2));
            builder.append(i + 1 < widths.length ? middle : right);
        }
        return builder.toString();
    }

    private static int longestWord(List<TextRenderResult.Token> tokens) {
        String text = plain(inlineTokens(tokens));
        int max = 0;
        for (String word : text.split("\\s+")) max = Math.max(max, DisplayWidth.width(word));
        return max;
    }

    /** 仅用于统计列宽的行内 token 转 Span；不依赖实例主题。 */
    private static List<Span> inlineTokens(List<TextRenderResult.Token> tokens) {
        var result = new ArrayList<Span>();
        for (TextRenderResult.Token token : tokens) {
            if (token instanceof TextRenderResult.Text text) result.add(new Span(text.value(), Style.PLAIN, null));
            else if (token instanceof TextRenderResult.Space) result.add(new Span(" ", Style.PLAIN, null));
            else if (token instanceof TextRenderResult.Code code) result.add(new Span(code.text(), Style.PLAIN, null));
            else for (TextRenderResult.Token child : token.children()) result.addAll(inlineTokens(List.of(child)));
        }
        return result;
    }

    private static String plain(List<Span> spans) {
        var builder = new StringBuilder();
        for (Span span : spans) builder.append(span.text());
        return builder.toString();
    }

    /** 行内 token 转 Span，保留嵌套样式和链接。 */
    private List<Span> inline(List<TextRenderResult.Token> tokens, Style parent) {
        var result = new ArrayList<Span>();
        for (TextRenderResult.Token token : tokens) {
            switch (token) {
                case TextRenderResult.Text text -> result.add(new Span(text.value(), parent, null));
                case TextRenderResult.Space ignored -> result.add(new Span(" ", parent, null));
                case TextRenderResult.Break ignored -> result.add(new Span("\n", parent, null));
                case TextRenderResult.Code code -> result.add(new Span("`" + code.text() + "`",
                        parent.merge(theme.style("mdCode")), null));
                case TextRenderResult.Strong strong -> result.addAll(inline(strong.items(), parent.merge(Style.PLAIN.withBold(true))));
                case TextRenderResult.Emphasis emphasis -> result.addAll(inline(emphasis.items(), parent.merge(Style.PLAIN.withItalic(true))));
                case TextRenderResult.Strikethrough strike -> result.addAll(inline(strike.items(), parent.merge(Style.PLAIN.withStrike(true))));
                case TextRenderResult.Link link -> {
                    LinkTarget target = link.href() == null ? null : LinkTarget.of(link.href());
                    Style linkStyle = parent.merge(theme.style("mdLink"));
                    List<Span> children = inline(link.items(), linkStyle);
                    if (target == null) result.addAll(children);
                    else for (Span child : children) result.add(new Span(child.text(), child.style(), target));
                }
                case TextRenderResult.Latex latex -> result.add(new Span(
                        latex.display() ? "$$" + latex.text() + "$$" : "$" + latex.text() + "$", parent, null));
                case TextRenderResult.Html html -> result.add(new Span(html.raw(), parent, null));
                default -> {
                    if (!token.children().isEmpty()) result.addAll(inline(token.children(), parent));
                }
            }
        }
        return result;
    }

    /** 按列换行并给每行加前缀/续行前缀；保留链接和样式。 */
    private static List<StyledLine> wrapWithPrefix(List<Span> spans, int width, String firstPrefix,
            String continuationPrefix) {
        var result = new ArrayList<StyledLine>();
        // 先展开显式换行，再对每段按宽度折行；前缀计入宽度。
        var current = new ArrayList<Span>();
        for (Span span : spans) {
            var pieces = DisplayWidth.lines(span.text());
            for (int i = 0; i < pieces.size(); i++) {
                if (i > 0) { result.addAll(wrapSingle(current, width, firstPrefix, continuationPrefix, result.isEmpty())); current.clear(); }
                if (!pieces.get(i).isEmpty()) current.add(span.withText(pieces.get(i)));
            }
        }
        result.addAll(wrapSingle(current, width, firstPrefix, continuationPrefix, result.isEmpty()));
        if (result.isEmpty()) result.add(StyledLine.of(firstPrefix, Style.PLAIN));
        return result;
    }

    private static List<StyledLine> wrapSingle(List<Span> spans, int width, String firstPrefix,
            String continuationPrefix, boolean first) {
        if (spans.isEmpty()) return List.of(StyledLine.of(firstPrefix, Style.PLAIN));
        String prefix = first ? firstPrefix : continuationPrefix;
        int available = Math.max(1, width - DisplayWidth.width(prefix));
        var lines = new ArrayList<StyledLine>();
        List<StyledLine> wrapped = StyledLine.wrap(spans, available);
        for (int i = 0; i < wrapped.size(); i++) {
            var lineSpans = new ArrayList<Span>();
            lineSpans.add(new Span(i == 0 ? firstPrefix : continuationPrefix, Style.PLAIN, null));
            lineSpans.addAll(wrapped.get(i).spans());
            lines.add(new StyledLine(lineSpans));
        }
        return lines;
    }
}
