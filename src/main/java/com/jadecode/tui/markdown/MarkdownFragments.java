package com.jadecode.tui.markdown;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 保守的 Markdown 稳定块复用：只切开可以独立解析的顶层段落/标题/围栏代码。
 *
 * <p>空行不一定是 Markdown 边界：列表可跨空行，HTML 可吞后续行，引用定义能改变前文。
 * 所以遇到这些语法时整篇交回原解析器；此类不代替 marked，也不自行解释行内语法。
 * 追加时保留已完成块，重扫并解析尚在增长的尾部；改写正文则重建。实例只用于一个解析
 * 语义，并由所属 UI 或 JS worker 单线程访问，不混用 Java 与 JS 结果。</p>
 */
public final class MarkdownFragments {
    /** 解析器可返回原来的不可变 DTO；JS 适配错误仍按 IOException 传播。 */
    @FunctionalInterface public interface Parser {
        TextRenderResult parse(String source) throws IOException;
    }

    private static final class Part {
        final String source;
        TextRenderResult parsed;
        Part(String source) { this.source = source; }
    }

    private String previous = "", tailSource = "";
    private int stableEnd;
    private boolean independent = true;
    private final List<Part> stable = new ArrayList<>();
    /** 同一篇文档中重复段落可共用 DTO；容量随当前稳定块数，换文档时清除。 */
    private final Map<String, TextRenderResult> repeated = new HashMap<>();
    private TextRenderResult tailResult;
    private long parsedCharacters;

    /**
     * 解析最新完整源文；只对安全且完整的块复用结果，返回平铺顶层 token。
     * @param source 最新源文，不被修改；调用方负责原有净化策略
     * @param version 返回 DTO 的版本，不影响块内容
     * @param parser 同一实例始终使用相同语义的纯解析器
     * @return 与整篇解析等价的块序列；复杂/不确定语法直接整篇调用 parser
     * @throws IOException 底层固定解析失败；不把失败缓存为成功
     */
    public TextRenderResult parse(String source, long version, Parser parser) throws IOException {
        String current = source == null ? "" : source;
        if (!current.startsWith(previous)) reset();
        // Windows/旧 Mac 换行先交给原解析器规范化，不在此重定义换行边界。
        if (current.indexOf('\r') >= 0) independent = false;
        if (independent) scan(current);
        if (!independent) {
            stable.clear(); repeated.clear(); stableEnd = 0;
        }
        previous = current;
        var tokens = new ArrayList<TextRenderResult.Token>();
        var warnings = new ArrayList<String>();
        for (Part part : stable) {
            if (part.parsed == null) {
                part.parsed = repeated.get(part.source);
                if (part.parsed == null) {
                    part.parsed = evaluate(part.source, parser);
                    repeated.put(part.source, part.parsed);
                }
            }
            append(part.parsed.tokens(), tokens); warnings.addAll(part.parsed.warnings());
        }
        String tail = current.substring(stableEnd);
        if (!tail.isEmpty()) {
            if (tailResult == null || !tail.equals(tailSource)) {
                tailResult = evaluate(tail, parser); tailSource = tail;
            }
            append(tailResult.tokens(), tokens); warnings.addAll(tailResult.warnings());
        } else {
            // 尾部提交成稳定块后不再保留上一个不完整前缀及其大代码高亮结果。
            tailSource = ""; tailResult = null;
        }
        return new TextRenderResult(tokens, version, warnings);
    }

    /** Java 回退同样复用稳定块；解析器无 IO，IOException 在此只能是内部契约错误。 */
    public TextRenderResult parseJava(String source) {
        try { return parse(source, 0, value -> new JavaMarkdownParser(value).parse()); }
        catch (IOException impossible) { throw new java.io.UncheckedIOException(impossible); }
    }

    /** @return 实际送入解析器的累计 UTF-16 字符数，用于无需计时的工作量回归。 */
    public long parsedCharacters() { return parsedCharacters; }

    private TextRenderResult evaluate(String source, Parser parser) throws IOException {
        parsedCharacters += source.length();
        return parser.parse(source);
    }

    /** 源文改写时丢弃之前的边界/结果；累计工作量计数继续保留用于诊断。 */
    private void reset() {
        stable.clear(); repeated.clear(); stableEnd = 0; independent = true;
        tailSource = ""; tailResult = null;
    }

    /**
     * 从最后一个稳定边界重扫尾部；围栏内部空行不是边界，关闭后再遇到空行才提交。
     * 未以换行终止的最后一行永远留在尾部，追加字符可能改变它的语法。
     */
    private void scan(String source) {
        char fence = 0;
        int fenceLength = 0, cursor = stableEnd;
        while (cursor < source.length()) {
            int newline = source.indexOf('\n', cursor);
            int end = newline < 0 ? source.length() : newline;
            String line = source.substring(cursor, end);
            if (fence != 0) {
                // 固定库只允许至多三个前导空格，现有 Java 回退会 trim 更多空白。
                // 两者可能分歧时各自整篇解析，不能用同一分块边界改写任一路径的语义。
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                String stripped = indent <= 3 ? line.substring(indent) : "";
                if (run(stripped, fence) == 0 && run(line.stripLeading(), fence) >= fenceLength) {
                    independent = false; return;
                }
                int count = run(stripped, fence);
                if (count >= fenceLength && stripped.substring(count).isBlank()) {
                    if (!blank(stripped.substring(count))) { independent = false; return; }
                    fence = 0;
                }
            } else if (blank(line)) {
                if (newline >= 0) {
                    stable.add(new Part(source.substring(stableEnd, newline + 1)));
                    stableEnd = newline + 1;
                }
            } else {
                char first = line.charAt(0);
                int count = first == '`' || first == '~' ? run(line, first) : 0;
                if (count >= 3) {
                    // 反引号围栏的 info 不允许反引号；不确定时让原解析器决定。
                    if (first == '`' && line.substring(count).indexOf('`') >= 0) { independent = false; return; }
                    fence = first; fenceLength = count;
                } else if (unsafe(line)) { independent = false; return; }
            }
            if (newline < 0) break;
            cursor = newline + 1;
        }
    }

    /** 保守排除可能跨块或影响前文的构造；拒绝优化不改变原解析器对它的支持。 */
    private static boolean unsafe(String line) {
        char first = line.charAt(0);
        if (Character.isWhitespace(first) || first == '>' || first == '-' || first == '+') return true;
        if ((first == '*' && (line.length() == 1 || Character.isWhitespace(line.charAt(1)) || line.startsWith("***")))
                || line.startsWith("___")) return true;
        int digits = 0;
        while (digits < line.length() && Character.isDigit(line.charAt(digits))) digits++;
        if (digits > 0 && digits < line.length() && (line.charAt(digits) == '.' || line.charAt(digits) == ')')
                && (digits + 1 == line.length() || Character.isWhitespace(line.charAt(digits + 1)))) return true;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '[' || c == '<' || c == '|' || c == '$' || c == '\\' || c < 0x20) return true;
        }
        return false;
    }

    private static int run(String line, char marker) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == marker) count++;
        return count;
    }

    /** Markdown 空行仅含空格或 Tab；Unicode 空白不能擅自作为固定库的段落边界。 */
    private static boolean blank(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') return false;
        }
        return true;
    }

    /** Java 回退有 Document 包装，固定引擎是平铺结果；只展开包装，不丢任何真实块。 */
    private static void append(List<TextRenderResult.Token> source, List<TextRenderResult.Token> target) {
        for (var token : source) {
            if (token instanceof TextRenderResult.Document document) append(document.items(), target);
            else target.add(token);
        }
    }
}
