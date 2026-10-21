package com.jadecode.tui.markdown;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地 Markdown 词法/块解析，产生与固定 JS 引擎一致的 {@link TextRenderResult.Token}。
 *
 * <p>这一实现用于 JS bundle 尚未打包/加载失败时的可读回退，并与 JS 结果共享同一布局路径；
 * 它不执行 HTML、不下载图片、不访问文件。支持的块：标题、段落、围栏代码、列表/任务项、
 * 引用、分隔线、表格、行内代码、强调/斜体/删除线、链接、行内/块数学（原样保留）与 HTML
 * （纯文字）。解析只做纯计算，输入已经由调用方净化。</p>
 */
public final class JavaMarkdownParser {
    private final String source;

    public JavaMarkdownParser(String source) { this.source = source == null ? "" : source; }

    /** @return 顶层文档 token。 */
    public TextRenderResult parse() {
        List<String> lines = new ArrayList<>();
        for (String line : source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) lines.add(line);
        return new TextRenderResult(List.of(new TextRenderResult.Document(blocks(lines))), 0, List.of());
    }

    private List<TextRenderResult.Token> blocks(List<String> lines) {
        var result = new ArrayList<TextRenderResult.Token>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.isBlank()) { i++; continue; }
            if (fence(line) != null) { i = parseFence(lines, i, result); continue; }
            if (heading(line) != null) {
                String[] heading = heading(line);
                result.add(new TextRenderResult.Heading(heading[0].length(), inline(heading[1])));
                i++; continue;
            }
            if (isRule(line)) { result.add(new TextRenderResult.Rule()); i++; continue; }
            if (line.stripLeading().startsWith(">")) { i = parseQuote(lines, i, result); continue; }
            if (listItem(line) != null) { i = parseList(lines, i, result); continue; }
            if (isTableStart(lines, i)) { i = parseTable(lines, i, result); continue; }
            i = parseParagraph(lines, i, result);
        }
        // 顶层 Document 的子节点即块；MarkdownLayout 直接遍历 Document.items()。
        if (result.size() == 1 && result.getFirst() instanceof TextRenderResult.Document document)
            return document.items();
        return result;
    }

    /** 解包 Document（parse 返回的顶层只有一个 Document）。 */
    private List<TextRenderResult.Token> unwrap(TextRenderResult.Document document) { return document.items(); }

    private String fence(String line) {
        String stripped = line.stripLeading();
        if (stripped.startsWith("```")) return "`";
        if (stripped.startsWith("~~~")) return "~";
        return null;
    }

    private int parseFence(List<String> lines, int start, List<TextRenderResult.Token> result) {
        String line = lines.get(start);
        char marker = fence(line) == null ? '`' : fence(line).charAt(0);
        int count = 0;
        while (count < line.length() && line.charAt(count) == marker) count++;
        String info = line.substring(line.indexOf(marker) + count).trim();
        String language = info.isEmpty() ? "" : info.split("\\s+")[0];
        var body = new StringBuilder();
        int i = start + 1;
        while (i < lines.size()) {
            String candidate = lines.get(i);
            String stripped = candidate.stripLeading();
            int closing = 0;
            while (closing < stripped.length() && stripped.charAt(closing) == marker) closing++;
            if (closing >= count && stripped.substring(closing).isBlank()) {
                i++;
                break;
            }
            if (body.length() > 0 || i > start + 1) body.append('\n');
            body.append(candidate);
            i++;
        }
        result.add(new TextRenderResult.Code(body.toString(), language, true));
        return i;
    }

    private String[] heading(String line) {
        int hashes = 0;
        while (hashes < line.length() && line.charAt(hashes) == '#') hashes++;
        if (hashes < 1 || hashes > 6 || hashes >= line.length() || !Character.isWhitespace(line.charAt(hashes)))
            return null;
        return new String[]{"#".repeat(hashes), line.substring(hashes).strip()};
    }

    private boolean isRule(String line) {
        String stripped = line.strip();
        if (stripped.length() < 3) return false;
        char first = stripped.charAt(0);
        if (first != '-' && first != '*' && first != '_') return false;
        for (char c : stripped.toCharArray()) if (c != first && !Character.isWhitespace(c)) return false;
        return stripped.chars().filter(c -> c == first).count() >= 3;
    }

    private int parseQuote(List<String> lines, int start, List<TextRenderResult.Token> result) {
        var inner = new ArrayList<String>();
        int i = start;
        while (i < lines.size() && lines.get(i).stripLeading().startsWith(">")) {
            String line = lines.get(i).stripLeading().substring(1);
            inner.add(line.startsWith(" ") ? line.substring(1) : line);
            i++;
        }
        result.add(new TextRenderResult.Blockquote(blocks(inner)));
        return i;
    }

    /** 列表行：返回 [缩进, 标记, 内容, 序号]；不匹配 null。 */
    private String[] listItem(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') indent++;
        if (indent >= line.length()) return null;
        String rest = line.substring(indent);
        if (rest.startsWith("- ") || rest.startsWith("* ") || rest.startsWith("+ "))
            return new String[]{Integer.toString(indent), rest.substring(0, 1), rest.substring(2), "0"};
        int digits = 0;
        while (digits < rest.length() && Character.isDigit(rest.charAt(digits))) digits++;
        if (digits > 0 && digits + 1 < rest.length()
                && (rest.charAt(digits) == '.' || rest.charAt(digits) == ')') && rest.charAt(digits + 1) == ' ')
            return new String[]{Integer.toString(indent), rest.substring(0, digits + 1), rest.substring(digits + 2),
                    rest.substring(0, digits)};
        return null;
    }

    private int parseList(List<String> lines, int start, List<TextRenderResult.Token> result) {
        var items = new ArrayList<TextRenderResult.ListItem>();
        boolean ordered = false;
        int firstIndex = 1;
        int i = start;
        while (i < lines.size()) {
            String[] item = listItem(lines.get(i));
            if (item == null) break;
            int indent = Integer.parseInt(item[0]);
            boolean itemOrdered = Character.isDigit(item[1].charAt(0));
            int index;
            try { index = Integer.parseInt(item[3]); } catch (NumberFormatException invalid) { index = 0; }
            if (items.isEmpty()) { ordered = itemOrdered; firstIndex = itemOrdered ? Math.max(1, index) : 1; }
            int depth = Math.min(6, indent / 2);
            String content = item[2];
            boolean task = false, checked = false;
            if (content.startsWith("[ ] ") || content.startsWith("[x] ") || content.startsWith("[X] ")) {
                task = true; checked = !content.startsWith("[ ] "); content = content.substring(4);
            }
            items.add(new TextRenderResult.ListItem(depth, itemOrdered, Math.max(1, index), task, checked,
                    inline(content)));
            i++;
        }
        result.add(new TextRenderResult.ListBlock(ordered, firstIndex, items));
        return i;
    }

    private boolean isTableStart(List<String> lines, int index) {
        if (index + 1 >= lines.size()) return false;
        String header = lines.get(index), separator = lines.get(index + 1);
        if (!header.contains("|")) return false;
        return separator.strip().matches("\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)+\\|?");
    }

    private int parseTable(List<String> lines, int start, List<TextRenderResult.Token> result) {
        List<String> headerCells = cells(lines.get(start));
        int columns = headerCells.size();
        var header = new ArrayList<List<TextRenderResult.Token>>();
        for (String cell : headerCells) header.add(inline(cell));
        var rows = new ArrayList<List<List<TextRenderResult.Token>>>();
        int i = start + 2;
        while (i < lines.size() && lines.get(i).contains("|") && !lines.get(i).isBlank()) {
            List<String> cells = cells(lines.get(i));
            var row = new ArrayList<List<TextRenderResult.Token>>();
            for (int c = 0; c < columns; c++) row.add(inline(c < cells.size() ? cells.get(c) : ""));
            rows.add(row);
            i++;
        }
        result.add(new TextRenderResult.Table(header, rows, List.of()));
        return i;
    }

    private static List<String> cells(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (escaped) { current.append(c); escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '|') { result.add(current.toString().strip()); current.setLength(0); continue; }
            current.append(c);
        }
        result.add(current.toString().strip());
        return result;
    }

    private int parseParagraph(List<String> lines, int start, List<TextRenderResult.Token> result) {
        var text = new StringBuilder();
        int i = start;
        while (i < lines.size() && !lines.get(i).isBlank()) {
            String line = lines.get(i);
            if (i > start && (fence(line) != null || heading(line) != null || isRule(line)
                    || line.stripLeading().startsWith(">") || listItem(line) != null || isTableStart(lines, i)))
                break;
            if (i > start) text.append('\n');
            text.append(line);
            i++;
        }
        result.add(new TextRenderResult.Paragraph(inlineParagraph(text.toString())));
        return i;
    }

    /** 段落内转换软换行/硬换行，再走行内解析。 */
    private List<TextRenderResult.Token> inlineParagraph(String text) {
        var result = new ArrayList<TextRenderResult.Token>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean hard = line.endsWith("  ") || line.endsWith("\\");
            String stripped = hard ? line.replaceFirst("(\\\\|\\s{2,})$", "") : line;
            if (!stripped.isEmpty()) result.addAll(inline(stripped));
            if (i + 1 < lines.length) result.add(hard ? new TextRenderResult.Break() : new TextRenderResult.Space());
        }
        return result;
    }

    /** 行内解析：代码、strong/em/del、链接、autolink、数学、HTML、转义。 */
    List<TextRenderResult.Token> inline(String text) {
        var result = new ArrayList<TextRenderResult.Token>();
        var plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) { plain.append(text.charAt(i + 1)); i += 2; continue; }
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i) { flush(plain, result); result.add(new TextRenderResult.Code(text.substring(i + 1, end), "", false)); i = end + 1; continue; }
            }
            if (text.startsWith("**", i) || text.startsWith("__", i)) {
                String marker = text.substring(i, i + 2);
                int end = text.indexOf(marker, i + 2);
                if (end > i + 2) {
                    flush(plain, result);
                    result.add(new TextRenderResult.Strong(inline(text.substring(i + 2, end))));
                    i = end + 2; continue;
                }
            }
            if (text.startsWith("~~", i)) {
                int end = text.indexOf("~~", i + 2);
                if (end > i + 2) {
                    flush(plain, result);
                    result.add(new TextRenderResult.Strikethrough(inline(text.substring(i + 2, end))));
                    i = end + 2; continue;
                }
            }
            if (c == '*' || c == '_') {
                int end = text.indexOf(c, i + 1);
                if (end > i + 1 && !Character.isWhitespace(text.charAt(i + 1))) {
                    flush(plain, result);
                    result.add(new TextRenderResult.Emphasis(inline(text.substring(i + 1, end))));
                    i = end + 1; continue;
                }
            }
            if (c == '[') {
                int close = text.indexOf(']', i + 1);
                if (close > i && close + 1 < text.length() && text.charAt(close + 1) == '(') {
                    int end = text.indexOf(')', close + 2);
                    if (end > close) {
                        flush(plain, result);
                        String href = text.substring(close + 2, end).trim();
                        if (href.startsWith("<") && href.endsWith(">")) href = href.substring(1, href.length() - 1);
                        String target = com.jadecode.tui.text.LinkTarget.of(href) == null ? null : href;
                        result.add(new TextRenderResult.Link(target, inline(text.substring(i + 1, close))));
                        i = end + 1; continue;
                    }
                }
            }
            if (c == '<') {
                int close = text.indexOf('>', i + 1);
                if (close > i) {
                    String inside = text.substring(i + 1, close);
                    if (inside.matches("(?i)(?:https?|mailto):[^\\s]+")) {
                        flush(plain, result);
                        result.add(new TextRenderResult.Link(inside, List.of(new TextRenderResult.Text(inside))));
                        i = close + 1; continue;
                    }
                    if (inside.startsWith("/") || inside.matches("[A-Za-z][^>]*")) {
                        flush(plain, result);
                        result.add(new TextRenderResult.Html(text.substring(i, close + 1)));
                        i = close + 1; continue;
                    }
                }
            }
            if (c == '$') {
                int end = text.indexOf('$', i + 1);
                if (end > i + 1 && !Character.isWhitespace(text.charAt(i + 1))) {
                    flush(plain, result);
                    result.add(new TextRenderResult.Latex(text.substring(i + 1, end), false));
                    i = end + 1; continue;
                }
            }
            plain.append(c); i++;
        }
        flush(plain, result);
        return result;
    }

    private static void flush(StringBuilder plain, List<TextRenderResult.Token> result) {
        if (plain.isEmpty()) return;
        result.add(new TextRenderResult.Text(plain.toString()));
        plain.setLength(0);
    }
}
