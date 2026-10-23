package com.jadecode.tui.text;

import java.util.ArrayList;
import java.util.List;

/**
 * 一行结构化富文本，附带不属于正文的元数据（图片占位、源映射）。
 *
 * <p>行宽、裁切和换行全部按终端列与完整字素计算，不能按 Java char 下标。宽字符
 * （CJK/emoji）在边界处不会被切成两半；窄到放不下一个宽字素时由布局决定省略或留空，
 * 而不是让终端隐式折行破坏行号。纯文字（{@link #text()}）用于复制/搜索，不包含
 * 光标或图片控制标记。</p>
 *
 * @param spans 行内片段；可以为空，表示空行
 * @param annotation 非正文元数据；普通行为 null
 */
public record StyledLine(List<Span> spans, LineAnnotation annotation) {
    public StyledLine {
        spans = List.copyOf(spans);
    }

    /** 没有附加元数据的一行。 */
    public StyledLine(List<Span> spans) { this(spans, null); }

    /** 单样式一行；换行/控制字符由调用方保证已处理。 */
    public static StyledLine of(String text, Style style) {
        return new StyledLine(text.isEmpty() ? List.of() : List.of(new Span(text, style, null)));
    }

    /** 主题 token 版本的便捷构造，供布局层在拿到 Theme 后使用。 */
    public static StyledLine empty() { return new StyledLine(List.of(), null); }

    /** @return 所有可见正文拼接；不含 annotation 和任何控制序列。 */
    public String text() {
        var out = new StringBuilder();
        for (Span span : spans) out.append(span.text());
        return out.toString();
    }

    /** @return 终端显示列数。 */
    public int width() {
        int total = 0;
        for (Span span : spans) total += DisplayWidth.width(span.text());
        return total;
    }

    /** @return 这一行是否没有可见字符。 */
    public boolean isBlank() { return width() == 0; }

    /** @return 附带同一 annotation 的新行；用于图片占位等元数据迁移。 */
    public StyledLine withAnnotation(LineAnnotation value) { return new StyledLine(spans, value); }

    /**
     * 按列裁切，保留完整字素和样式。超宽尾部丢弃，不自动加省略号（表格/代码需要精确裁列）。
     * 宽字符边界处宁可少一列也不拆字素；调用方可先用 {@link DisplayWidth#ellipsize} 生成摘要。
     */
    public StyledLine crop(int columns) {
        if (columns <= 0) return new StyledLine(List.of(), annotation);
        if (width() <= columns) return this;
        var result = new ArrayList<Span>();
        int remaining = columns;
        for (Span span : spans) {
            String part = DisplayWidth.take(span.text(), remaining);
            if (!part.isEmpty()) result.add(span.withText(part));
            remaining -= DisplayWidth.width(part);
            if (remaining <= 0 || part.length() < span.text().length()) break;
        }
        return new StyledLine(result, annotation);
    }

    /** 用给定样式在右侧补空格到目标列数；已足够宽时原样返回。 */
    public StyledLine padTo(int columns, Style style) {
        int missing = columns - width();
        if (missing <= 0) return this;
        var result = new ArrayList<>(spans);
        result.add(new Span(" ".repeat(missing), style, null));
        return new StyledLine(result, annotation);
    }

    /**
     * 把一组片段按列换行。换行只发生在完整字素之后；样式和链接随片段保留。
     *
     * <p>算法先把每个 Span 拆成字素，再按累计列数切行；相邻同一样式/链接的字素会合并，
     * 避免每个字符都生成一个 Span。`\n` 强制换行；空行也会产生一条空 StyledLine。
     * 宽度小于 1 时按 1 列处理，极窄窗口不抛异常。</p>
     */
    public static List<StyledLine> wrap(List<Span> spans, int width) {
        int limit = Math.max(1, width);
        var result = new ArrayList<StyledLine>();
        var line = new ArrayList<Span>();
        int used = 0;
        for (Span span : spans) {
            // 同一行/样式先写入可变缓冲，换行或片段结束才创建 Span。
            // 避免每个字素都复制已经积累的整段字符串。
            var run = new StringBuilder();
            for (String cluster : DisplayWidth.clusters(span.text())) {
                if (cluster.equals("\n")) {
                    appendRun(line, span, run); result.add(new StyledLine(line));
                    line = new ArrayList<>(); used = 0; continue;
                }
                int cells = DisplayWidth.clusterWidth(cluster);
                if (cells > limit) {
                    // 一个宽字素比整行还宽：保留占位而不是输出终端会隐式折行的宽字符。
                    cluster = "?".repeat(Math.max(1, cells));
                    cells = Math.min(cells, limit);
                }
                if (used + cells > limit) {
                    appendRun(line, span, run); result.add(new StyledLine(line));
                    line = new ArrayList<>(); used = 0;
                }
                run.append(cluster);
                used += cells;
            }
            appendRun(line, span, run);
        }
        result.add(new StyledLine(line));
        return result;
    }

    /** 把同一样式/链接的相邻字符合并，减少 ANSI 切换；不改变正文顺序。 */
    private static void appendRun(List<Span> line, Span source, StringBuilder run) {
        if (run.isEmpty()) return;
        String cluster = run.toString();
        run.setLength(0);
        if (!line.isEmpty()) {
            Span last = line.getLast();
            if (last.style().equals(source.style()) && java.util.Objects.equals(last.link(), source.link())) {
                line.removeLast(); line.add(last.withText(last.text() + cluster)); return;
            }
        }
        line.add(new Span(cluster, source.style(), source.link()));
    }

    /** 便捷包装：单一样式正文按宽度换行。 */
    public static List<StyledLine> wrap(String text, Style style, int width) {
        return wrap(List.of(new Span(text, style, null)), width);
    }
}
