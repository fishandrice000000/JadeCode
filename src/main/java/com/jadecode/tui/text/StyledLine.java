package com.jadecode.tui.text;

import java.util.ArrayList;
import java.util.List;

/** 一行结构化文字。换行时保留片段样式，编码终端控制符留到最终渲染。 */
public record StyledLine(List<Span> spans) {
    public StyledLine { spans = List.copyOf(spans); }
    /** 创建已经清理的一行，调用者保证不含换行。 */
    public static StyledLine of(String text, String style) { return new StyledLine(List.of(new Span(text, style))); }
    /** @return 用于检查、复制和宽度计算的纯文字。 */
    public String text() { return spans.stream().map(Span::text).collect(java.util.stream.Collectors.joining()); }
    /** 不改变行数地裁去超宽尾部，供极窄窗口兜底；保留原有样式和完整字素。 */
    public StyledLine crop(int width) {
        var result = new ArrayList<Span>(); int remaining = Math.max(0, width);
        for (Span span : spans) {
            String part = DisplayWidth.take(DisplayWidth.clean(span.text()).replace('\n', ' '), remaining);
            result.add(new Span(part, span.style())); remaining -= DisplayWidth.width(part);
            if (!part.equals(span.text()) || remaining == 0) break;
        }
        return new StyledLine(result);
    }
    /** 将富文本按列换行。超窄终端装不下的宽字素显示为 ?，不让终端隐式折行。 */
    public static List<StyledLine> wrap(List<Span> spans, int width) {
        int limit = Math.max(1, width), used = 0;
        var result = new ArrayList<StyledLine>();
        var line = new ArrayList<Span>();
        for (Span span : spans) {
            for (String cluster : DisplayWidth.clusters(DisplayWidth.clean(span.text()))) {
                if (cluster.equals("\n")) { result.add(new StyledLine(line)); line = new ArrayList<>(); used = 0; continue; }
                int cells = DisplayWidth.clusterWidth(cluster);
                if (cells > limit) { cluster = "?"; cells = 1; }
                if (used + cells > limit) { result.add(new StyledLine(line)); line = new ArrayList<>(); used = 0; }
                // 合并相邻样式，避免每个字素都生成一条 ANSI 序列。
                if (!line.isEmpty() && line.getLast().style().equals(span.style())) {
                    Span last = line.removeLast(); line.add(new Span(last.text() + cluster, span.style()));
                } else line.add(new Span(cluster, span.style()));
                used += cells;
            }
        }
        result.add(new StyledLine(line));
        return result;
    }
}
