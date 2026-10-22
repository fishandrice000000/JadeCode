package com.jadecode.tui.markdown;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把固定 JS 引擎返回的 marked token JSON 转成 Java 不可变 {@link TextRenderResult}。
 *
 * <p>Java 不解析 Markdown 语法，只做结构映射；转换后的 DTO 可跨线程交给布局缓存。
 * 未知 token 类型保留其文字或子节点，不静默丢内容。输入来自封闭 Context 的 JSON 字符串，
 * 不携带 JS Value/函数。
 */
public final class MarkedTokenAdapter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MarkedTokenAdapter() { }

    /**
     * @param json `JsTextEngine.lex` 返回的 JSON
     * @param version 源文版本，用于缓存失效
     * @return 解析后的 DTO；JSON 非法时抛 IOException，由调用方保留原文
     */
    public static TextRenderResult adapt(String json, long version) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode tokens = root.path("tokens");
        if (!tokens.isArray()) throw new IOException("text engine lex result has no tokens array");
        return new TextRenderResult(blocks(tokens, 0), version, List.of());
    }

    /** 顶层块；depth 只用于列表嵌套。 */
    private static List<TextRenderResult.Token> blocks(JsonNode tokens, int depth) {
        var result = new ArrayList<TextRenderResult.Token>();
        if (!tokens.isArray()) return result;
        for (JsonNode token : tokens) {
            switch (token.path("type").asText("")) {
                case "space" -> { /* 块间距由布局统一插入，不重复计空格。 */ }
                case "heading" -> result.add(new TextRenderResult.Heading(
                        Math.max(1, Math.min(6, token.path("depth").asInt(1))), inline(token.path("tokens"))));
                case "paragraph" -> result.add(new TextRenderResult.Paragraph(inline(token.path("tokens"))));
                case "code" -> result.add(code(token));
                case "blockquote" -> result.add(new TextRenderResult.Blockquote(blocks(token.path("tokens"), depth)));
                case "hr" -> result.add(new TextRenderResult.Rule());
                case "html" -> result.add(new TextRenderResult.Html(token.path("text").asText("")));
                case "list" -> result.add(list(token, depth));
                case "table" -> result.add(table(token));
                case "text" -> {
                    if (token.path("tokens").isArray() && !token.path("tokens").isEmpty())
                        result.add(new TextRenderResult.Paragraph(inline(token.path("tokens"))));
                    else if (!token.path("text").asText("").isEmpty())
                        result.add(new TextRenderResult.Paragraph(List.of(new TextRenderResult.Text(token.path("text").asText("")))));
                }
                default -> {
                    // 未知块：优先保留子节点，再退回 raw/text，避免内容消失。
                    if (token.path("tokens").isArray() && !token.path("tokens").isEmpty())
                        result.addAll(blocks(token.path("tokens"), depth));
                    else {
                        String text = token.path("text").asText(token.path("raw").asText(""));
                        if (!text.isEmpty()) result.add(new TextRenderResult.Paragraph(List.of(new TextRenderResult.Text(text))));
                    }
                }
            }
        }
        return result;
    }

    private static TextRenderResult.Code code(JsonNode token) {
        String language = token.path("lang").asText("").trim();
        int space = language.indexOf(' ');
        if (space > 0) language = language.substring(0, space);
        language = language.toLowerCase(java.util.Locale.ROOT);
        var highlight = new ArrayList<List<TextRenderResult.Scope>>();
        JsonNode scopes = token.path("highlight");
        if (scopes.isArray()) {
            for (JsonNode line : scopes) {
                var spans = new ArrayList<TextRenderResult.Scope>();
                if (line.isArray()) for (JsonNode scope : line)
                    spans.add(new TextRenderResult.Scope(scope.path("text").asText(""),
                            scope.path("cls").isTextual() ? scope.path("cls").asText("") : ""));
                highlight.add(List.copyOf(spans));
            }
        }
        JsonNode mermaid = token.path("mermaid");
        if (mermaid.isObject()) {
            // Mermaid 图形复用 Code 通道：plain 文本 + styled 作用域，布局按 cls 映射主题。
            String plain = String.join("\n", texts(mermaid.path("plain")));
            var styled = new ArrayList<List<TextRenderResult.Scope>>();
            for (JsonNode row : mermaid.path("styled")) {
                var spans = new ArrayList<TextRenderResult.Scope>();
                if (row.isArray()) for (JsonNode span : row)
                    spans.add(new TextRenderResult.Scope(span.path("text").asText(""),
                            span.path("cls").asText("none")));
                styled.add(List.copyOf(spans));
            }
            return new TextRenderResult.Code(plain, "mermaid", true, styled);
        }
        return new TextRenderResult.Code(token.path("text").asText(""), language, true, highlight);
    }

    private static List<String> texts(JsonNode array) {
        var result = new ArrayList<String>();
        if (array.isArray()) for (JsonNode node : array) result.add(node.asText(""));
        return result;
    }

    private static TextRenderResult.ListBlock list(JsonNode token, int depth) {
        var items = new ArrayList<TextRenderResult.ListItem>();
        boolean ordered = token.path("ordered").asBoolean(false);
        int start = Math.max(1, token.path("start").asInt(1));
        int index = start;
        for (JsonNode item : token.path("items")) {
            boolean task = item.path("task").asBoolean(false);
            boolean checked = item.path("checked").asBoolean(false);
            items.add(new TextRenderResult.ListItem(depth, ordered, index++, task, checked,
                    itemBlocks(item.path("tokens"), depth + 1)));
        }
        return new TextRenderResult.ListBlock(ordered, start, items);
    }

    /**
     * 列表项内容：行内 token 展开成行内节点；嵌套 list 作为子块保留。
     * 布局当前把 ListItem.items 按行内处理，因此嵌套块转成段落文本，不丢内容。
     */
    private static List<TextRenderResult.Token> itemBlocks(JsonNode tokens, int depth) {
        var result = new ArrayList<TextRenderResult.Token>();
        if (!tokens.isArray()) return result;
        for (JsonNode token : tokens) {
            String type = token.path("type").asText("");
            if (type.equals("text") || type.equals("paragraph")) result.addAll(inline(token.path("tokens")));
            else if (type.equals("list")) {
                var nested = list(token, depth);
                for (var item : nested.items()) result.addAll(item.items());
            } else if (token.path("tokens").isArray() && !token.path("tokens").isEmpty())
                result.addAll(inline(token.path("tokens")));
            else if (!token.path("text").asText("").isEmpty())
                result.add(new TextRenderResult.Text(token.path("text").asText("")));
        }
        return result;
    }

    private static TextRenderResult.Table table(JsonNode token) {
        var header = new ArrayList<List<TextRenderResult.Token>>();
        for (JsonNode cell : token.path("header")) header.add(inline(cell.path("tokens")));
        var rows = new ArrayList<List<List<TextRenderResult.Token>>>();
        for (JsonNode row : token.path("rows")) {
            var cells = new ArrayList<List<TextRenderResult.Token>>();
            for (JsonNode cell : row) cells.add(inline(cell.path("tokens")));
            rows.add(List.copyOf(cells));
        }
        var align = new ArrayList<String>();
        for (JsonNode value : token.path("align")) align.add(value.asText(""));
        return new TextRenderResult.Table(header, rows, align);
    }

    /** 行内 token 递归映射；有子 tokens 的优先递归，保持嵌套样式。 */
    private static List<TextRenderResult.Token> inline(JsonNode tokens) {
        var result = new ArrayList<TextRenderResult.Token>();
        if (!tokens.isArray()) return result;
        for (JsonNode token : tokens) {
            String type = token.path("type").asText("");
            List<TextRenderResult.Token> children = inline(token.path("tokens"));
            switch (type) {
                case "text" -> {
                    if (!children.isEmpty()) result.addAll(children);
                    else if (!token.path("text").asText("").isEmpty())
                        result.add(new TextRenderResult.Text(token.path("text").asText("")));
                }
                case "escape" -> result.add(new TextRenderResult.Text(token.path("text").asText("")));
                case "strong" -> result.add(new TextRenderResult.Strong(children));
                case "em" -> result.add(new TextRenderResult.Emphasis(children));
                case "del" -> result.add(new TextRenderResult.Strikethrough(children));
                case "codespan" -> result.add(new TextRenderResult.Code(token.path("text").asText(""), "", false));
                case "br" -> result.add(new TextRenderResult.Break());
                case "link" -> result.add(new TextRenderResult.Link(token.path("href").asText(""), children));
                case "html" -> result.add(new TextRenderResult.Html(token.path("text").asText("")));
                case "image" -> result.add(new TextRenderResult.Text(token.path("text").asText("")));
                default -> {
                    if (!children.isEmpty()) result.addAll(children);
                    else if (!token.path("text").asText("").isEmpty())
                        result.add(new TextRenderResult.Text(token.path("text").asText("")));
                }
            }
        }
        return result;
    }
}
