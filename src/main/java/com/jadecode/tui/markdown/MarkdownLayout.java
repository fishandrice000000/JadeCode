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
    /** 以 DTO 身份缓存顶层块，避免对完整 token 树做昂贵结构 hash；只在 layoutCached 使用。 */
    private java.util.IdentityHashMap<TextRenderResult.Token, List<StyledLine>> cachedBlocks;
    private int cachedWidth;
    private Style cachedBase;
    private boolean cachedThinking;

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
     * 同一消息的增量布局：复用稳定块的行，只重新绘制改变的尾块。
     * 宽度/基础样式/思考模式变化时清行缓存；主题由实例固定，调用方换主题需新实例。
     * 块间空行仍由本次装配插入，不冻结尾部间距或源文；旧尾块当帧回收，避免保留每个前缀。
     */
    public List<StyledLine> layoutCached(TextRenderResult result, int width, Style baseStyle, boolean thinking) {
        if (cachedBlocks == null) cachedBlocks = new java.util.IdentityHashMap<>();
        if (cachedWidth != width || !java.util.Objects.equals(cachedBase, baseStyle) || cachedThinking != thinking) {
            cachedBlocks.clear(); cachedWidth = width; cachedBase = baseStyle; cachedThinking = thinking;
        }
        var blocks = new ArrayList<TextRenderResult.Token>();
        flatten(result.tokens(), blocks);
        var live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<TextRenderResult.Token, Boolean>());
        var out = new ArrayList<StyledLine>();
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.get(i); live.add(block);
            List<StyledLine> lines = cachedBlocks.get(block);
            if (lines == null) {
                var built = new ArrayList<StyledLine>();
                renderBlock(block, width, baseStyle, built, 0, thinking);
                lines = List.copyOf(built); cachedBlocks.put(block, lines);
            }
            out.addAll(lines);
            if (i + 1 < blocks.size()) out.add(StyledLine.empty());
        }
        cachedBlocks.keySet().retainAll(live);
        while (!out.isEmpty() && out.getLast().isBlank()) out.removeLast();
        return List.copyOf(out);
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
                    // 缩进属于布局，列表符号属于主题：把 marker 单独着色为 mdListBullet，
                    // 续行缩进与 marker 等宽，避免长短编号（9. → 10.）错位。
                    String itemIndent = prefix + " ".repeat(Math.max(0, item.depth()) * 4);
                    String marker = item.task() ? (item.checked() ? "[x] " : "[ ] ")
                            : list.ordered() ? (index++) + ". " : "- ";
                    List<Span> spans = inline(item.items(), style);
                    // 列表项内容中的嵌套块（少见）递归排在后面。
                    out.addAll(wrapWithPrefix(spans, width,
                            new Span(itemIndent + marker, theme.style("mdListBullet"), null),
                            new Span(itemIndent + " ".repeat(DisplayWidth.width(marker)), Style.PLAIN, null)));
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

    /**
     * 表格布局：固定 Pi 的 3*n+1 开销、自然宽/最长词（上限 30）收缩与逐格富样式换行。
     * 表头与数据行按各自列宽换行，一条逻辑行取最大格高；只有数据行之间画中分隔，
     * 末尾画闭合底边。极窄且可用单元格列数不足列数时退回保留的原始源码，不整体裁剪。
     */
    private void renderTable(TextRenderResult.Table table, int width, Style style, List<StyledLine> out, String prefix) {
        int columns = table.header().size();
        if (columns == 0) return;
        int prefixWidth = DisplayWidth.width(prefix);
        int overhead = 3 * columns + 1;
        int innerWidth = Math.max(0, width - prefixWidth);
        int availableForCells = innerWidth - overhead;
        if (availableForCells < columns) {
            renderTableFallback(table, width, style, out, prefix);
            return;
        }
        int[] natural = new int[columns];
        int[] minWord = new int[columns];
        for (int i = 0; i < columns; i++) {
            String text = plain(inline(table.header().get(i), style));
            natural[i] = DisplayWidth.width(text);
            minWord[i] = Math.max(1, longestWordWidth(text, 30));
        }
        for (var row : table.rows()) {
            for (int i = 0; i < Math.min(columns, row.size()); i++) {
                String text = plain(inline(row.get(i), style));
                natural[i] = Math.max(natural[i], DisplayWidth.width(text));
                minWord[i] = Math.max(minWord[i], longestWordWidth(text, 30));
            }
        }
        int[] min = minWord.clone();
        int minCells = sum(min);
        if (minCells > availableForCells) {
            java.util.Arrays.fill(min, 1);
            int remaining = availableForCells - columns;
            if (remaining > 0) {
                int totalWeight = 0;
                for (int value : minWord) totalWeight += Math.max(0, value - 1);
                int[] growth = new int[columns];
                for (int i = 0; i < columns; i++) {
                    int weight = Math.max(0, minWord[i] - 1);
                    growth[i] = totalWeight > 0 ? (int) Math.floor(weight * (double) remaining / totalWeight) : 0;
                }
                int allocated = sum(growth);
                for (int i = 0; i < columns; i++) min[i] += growth[i];
                int leftover = remaining - allocated;
                for (int i = 0; leftover > 0 && i < columns; i++, leftover--) min[i]++;
            }
            minCells = sum(min);
        }
        int[] widths = new int[columns];
        if (sum(natural) + overhead <= innerWidth) {
            for (int i = 0; i < columns; i++) widths[i] = Math.max(natural[i], min[i]);
        } else {
            int totalGrowPotential = 0;
            for (int i = 0; i < columns; i++) totalGrowPotential += Math.max(0, natural[i] - min[i]);
            int extraWidth = Math.max(0, availableForCells - minCells);
            for (int i = 0; i < columns; i++) {
                int delta = Math.max(0, natural[i] - min[i]);
                int grow = totalGrowPotential > 0
                        ? (int) Math.floor(delta * (double) extraWidth / totalGrowPotential) : 0;
                widths[i] = min[i] + grow;
            }
            int remaining = availableForCells - sum(widths);
            while (remaining > 0) {
                boolean grew = false;
                for (int i = 0; i < columns && remaining > 0; i++) {
                    if (widths[i] < natural[i]) { widths[i]++; remaining--; grew = true; }
                }
                if (!grew) break;
            }
        }

        Style headerStyle = style.merge(theme.style("mdHeading"));
        out.add(StyledLine.of(DisplayWidth.take(prefix + border('┌', '┬', '┐', widths), width),
                theme.style("mdHr")));
        List<List<StyledLine>> headerCells = new ArrayList<>();
        for (int i = 0; i < columns; i++) headerCells.add(wrapCell(inline(table.header().get(i), headerStyle), widths[i]));
        int headerHeight = maxLines(headerCells);
        for (int line = 0; line < headerHeight; line++)
            out.add(tableLine(headerCells, widths, prefix, headerStyle, line));
        out.add(StyledLine.of(DisplayWidth.take(prefix + border('├', '┼', '┤', widths), width),
                theme.style("mdHr")));
        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            var row = table.rows().get(rowIndex);
            List<List<StyledLine>> cells = new ArrayList<>();
            for (int i = 0; i < columns; i++)
                cells.add(wrapCell(inline(i < row.size() ? row.get(i) : List.of(), style), widths[i]));
            int height = maxLines(cells);
            for (int line = 0; line < height; line++)
                out.add(tableLine(cells, widths, prefix, style, line));
            if (rowIndex + 1 < table.rows().size())
                out.add(StyledLine.of(DisplayWidth.take(prefix + border('├', '┼', '┤', widths), width),
                        theme.style("mdHr")));
        }
        out.add(StyledLine.of(DisplayWidth.take(prefix + border('└', '┴', '┘', widths), width),
                theme.style("mdHr")));
    }

    /** 极窄：优先用解析器保留的原始源码；没有 raw 的旧构造才重建近似源码行。 */
    private void renderTableFallback(TextRenderResult.Table table, int width, Style style,
            List<StyledLine> out, String prefix) {
        String raw = table.raw();
        if (raw == null || raw.isBlank()) {
            var builder = new StringBuilder();
            for (var cell : table.header()) builder.append("| ").append(plain(inline(cell, style))).append(' ');
            builder.append('|');
            for (var row : table.rows()) {
                builder.append('\n');
                for (var cell : row) builder.append("| ").append(plain(inline(cell, style))).append(' ');
                builder.append('|');
            }
            raw = builder.toString();
        }
        List<StyledLine> wrapped = wrapCell(List.of(new Span(raw, style, null)),
                Math.max(1, width - DisplayWidth.width(prefix)));
        for (StyledLine line : wrapped) {
            var spans = new ArrayList<Span>();
            spans.add(new Span(prefix, style, null));
            spans.addAll(line.spans());
            out.add(new StyledLine(spans));
        }
    }

    private StyledLine tableLine(List<List<StyledLine>> cells, int[] widths, String prefix,
            Style paddingStyle, int lineIndex) {
        var spans = new ArrayList<Span>();
        spans.add(new Span(prefix + "│ ", theme.style("mdHr"), null));
        for (int i = 0; i < widths.length; i++) {
            List<Span> cell = i < cells.size() && lineIndex < cells.get(i).size()
                    ? cells.get(i).get(lineIndex).spans() : List.of();
            spans.addAll(cell);
            int used = widthOf(cell);
            if (used < widths[i]) spans.add(new Span(" ".repeat(widths[i] - used), paddingStyle, null));
            spans.add(new Span(i + 1 < widths.length ? " │ " : " │", theme.style("mdHr"), null));
        }
        return new StyledLine(spans);
    }

    /** 单元格按词/CJK 断点折行；保留 Span 样式与链接，长词按字素拆行。 */
    private List<StyledLine> wrapCell(List<Span> spans, int width) {
        int limit = Math.max(1, width);
        var tokens = new ArrayList<WordToken>();
        for (Span span : spans) {
            for (String cluster : DisplayWidth.clusters(span.text())) {
                if (cluster.equals("\n")) { tokens.add(new WordToken(cluster, span.style(), span.link(), TokenKind.NEWLINE)); continue; }
                if (cluster.equals(" ")) { tokens.add(new WordToken(cluster, span.style(), span.link(), TokenKind.SPACE)); continue; }
                TokenKind kind = cjkBreak(cluster) ? TokenKind.CJK : TokenKind.WORD;
                if (kind == TokenKind.WORD && !tokens.isEmpty()) {
                    WordToken last = tokens.getLast();
                    if (last.kind() == TokenKind.WORD && last.style().equals(span.style())
                            && java.util.Objects.equals(last.link(), span.link())) {
                        tokens.set(tokens.size() - 1, new WordToken(last.text() + cluster, last.style(), last.link(), TokenKind.WORD));
                        continue;
                    }
                }
                tokens.add(new WordToken(cluster, span.style(), span.link(), kind));
            }
        }
        var lines = new ArrayList<StyledLine>();
        var current = new ArrayList<Span>();
        int used = 0;
        for (WordToken token : tokens) {
            if (token.kind() == TokenKind.NEWLINE) {
                lines.add(trimTrailing(current)); current = new ArrayList<>(); used = 0; continue;
            }
            int cells = DisplayWidth.width(token.text());
            if (token.kind() == TokenKind.SPACE) {
                if (used == 0) continue;
                if (used + cells > limit) { lines.add(trimTrailing(current)); current = new ArrayList<>(); used = 0; continue; }
                append(current, token.text(), token.style(), token.link()); used += cells; continue;
            }
            if (used + cells > limit && used > 0) { lines.add(trimTrailing(current)); current = new ArrayList<>(); used = 0; }
            if (cells > limit) {
                for (String cluster : DisplayWidth.clusters(token.text())) {
                    int clusterWidth = DisplayWidth.clusterWidth(cluster);
                    if (used + clusterWidth > limit) { lines.add(trimTrailing(current)); current = new ArrayList<>(); used = 0; }
                    append(current, cluster, token.style(), token.link()); used += clusterWidth;
                }
            } else {
                append(current, token.text(), token.style(), token.link()); used += cells;
            }
        }
        lines.add(trimTrailing(current));
        if (lines.isEmpty()) lines.add(new StyledLine(List.of()));
        return lines;
    }

    private enum TokenKind { WORD, SPACE, CJK, NEWLINE }

    private record WordToken(String text, Style style, com.jadecode.tui.text.LinkTarget link, TokenKind kind) { }

    private static void append(List<Span> line, String text, Style style, com.jadecode.tui.text.LinkTarget link) {
        if (!line.isEmpty()) {
            Span last = line.getLast();
            if (last.style().equals(style) && java.util.Objects.equals(last.link(), link)) {
                line.removeLast(); line.add(last.withText(last.text() + text)); return;
            }
        }
        line.add(new Span(text, style, link));
    }

    private static StyledLine trimTrailing(List<Span> spans) {
        var result = new ArrayList<>(spans);
        while (!result.isEmpty()) {
            Span last = result.getLast();
            String trimmed = last.text().replaceAll(" +$", "");
            if (trimmed.isEmpty()) { result.removeLast(); continue; }
            result.set(result.size() - 1, last.withText(trimmed));
            break;
        }
        return new StyledLine(result);
    }

    private static int widthOf(List<Span> spans) {
        int total = 0;
        for (Span span : spans) total += DisplayWidth.width(span.text());
        return total;
    }

    private static int maxLines(List<List<StyledLine>> cells) {
        int max = 1;
        for (List<StyledLine> cell : cells) max = Math.max(max, cell.size());
        return max;
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }

    private static int longestWordWidth(String text, int cap) {
        int max = 0;
        for (String word : text.split("\\s+")) max = Math.max(max, DisplayWidth.width(word));
        return Math.min(max, cap);
    }

    /** CJK/全角字符允许逐字断行；西文按词处理。 */
    private static boolean cjkBreak(String cluster) {
        int codePoint = cluster.codePointAt(0);
        return switch (Character.UnicodeScript.of(codePoint)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL -> true;
            default -> (codePoint >= 0x3000 && codePoint <= 0x303F)
                    || (codePoint >= 0xFF00 && codePoint <= 0xFFEF);
        };
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

    /** 按列换行并给每行加纯布局前缀/续行前缀；保留链接和样式。 */
    private static List<StyledLine> wrapWithPrefix(List<Span> spans, int width, String firstPrefix,
            String continuationPrefix) {
        return wrapWithPrefix(spans, width, new Span(firstPrefix, Style.PLAIN, null),
                new Span(continuationPrefix, Style.PLAIN, null));
    }

    /**
     * 带样式的首行/续行前缀版本：列表符号使用主题角色，缩进与空白保持纯布局样式。
     */
    private static List<StyledLine> wrapWithPrefix(List<Span> spans, int width, Span firstPrefix,
            Span continuationPrefix) {
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
        if (result.isEmpty()) result.add(new StyledLine(List.of(firstPrefix)));
        return result;
    }

    private static List<StyledLine> wrapSingle(List<Span> spans, int width, Span firstPrefix,
            Span continuationPrefix, boolean first) {
        if (spans.isEmpty()) return List.of(new StyledLine(List.of(firstPrefix)));
        Span prefix = first ? firstPrefix : continuationPrefix;
        int available = Math.max(1, width - DisplayWidth.width(prefix.text()));
        var lines = new ArrayList<StyledLine>();
        List<StyledLine> wrapped = StyledLine.wrap(spans, available);
        for (int i = 0; i < wrapped.size(); i++) {
            var lineSpans = new ArrayList<Span>();
            lineSpans.add(i == 0 ? firstPrefix : continuationPrefix);
            lineSpans.addAll(wrapped.get(i).spans());
            lines.add(new StyledLine(lineSpans));
        }
        return lines;
    }
}
