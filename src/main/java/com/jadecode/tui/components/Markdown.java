package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.StyledLine;

/**
 * CommonMark 负责解析语法，本类把语法树排成终端行，不生成 HTML，也不执行原始 ANSI。
 * 每个消息条目按内容版本和宽度缓存结果；流式未闭合代码围栏交给解析器自然处理。
 */
public final class Markdown {
    private final Parser parser = Parser.builder().build();
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
            } else if (node instanceof FencedCodeBlock code) code(code.getLiteral(), code.getInfo(), prefix, width, out);
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
