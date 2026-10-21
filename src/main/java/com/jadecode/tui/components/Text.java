package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * 富文本组件：把给定行按可用宽度显示，支持左/中/右对齐与可选背景。
 *
 * <p>不做 Markdown 解析、不折行（调用方用 {@link StyledLine#wrap} 生成行），只负责对齐和
 * 裁剪；这样标题、状态、菜单可以复用同一几何逻辑。背景填充到完整宽度，窄屏时先裁正文再
 * 补背景，不产生负宽度。</p>
 */
public final class Text implements Component {
    /** 文本对齐方式。 */
    public enum Align { LEFT, CENTER, RIGHT }

    private final List<StyledLine> lines;
    private final Align align;
    private final String backgroundToken;

    /** 用已排版行构造文本组件。 */
    public Text(List<StyledLine> lines, Align align, String backgroundToken) {
        this.lines = List.copyOf(lines); this.align = align; this.backgroundToken = backgroundToken;
    }

    /** 用单一样式多行文本构造左对齐组件。 */
    public static Text of(String text, Style style) {
        var lines = new ArrayList<StyledLine>();
        for (String line : DisplayWidth.lines(text)) lines.add(StyledLine.of(line, style));
        return new Text(lines, Align.LEFT, null);
    }

    /** 单行文本。 */
    public static Text line(String text, Style style) { return new Text(List.of(StyledLine.of(text, style)), Align.LEFT, null); }

    /** @return 替换对齐方式的新组件。 */
    public Text aligned(Align value) { return new Text(lines, value, backgroundToken); }

    /** @return 替换背景 token 的新组件。 */
    public Text withBackground(String token) { return new Text(lines, align, token); }

    @Override public List<StyledLine> render(RenderContext context, int width) {
        Style background = backgroundToken == null ? Style.PLAIN : context.theme().background(backgroundToken);
        var result = new ArrayList<StyledLine>(lines.size());
        for (StyledLine source : lines) {
            StyledLine cropped = source.crop(width);
            int missing = Math.max(0, width - cropped.width());
            int left = switch (align) {
                case LEFT -> 0;
                case CENTER -> missing / 2;
                case RIGHT -> missing;
            };
            int right = missing - left;
            var spans = new ArrayList<Span>();
            if (left > 0) spans.add(new Span(" ".repeat(left), background, null));
            for (Span span : cropped.spans())
                spans.add(background.isPlain() ? span : new Span(span.text(), span.style().withBg(background.bg()), span.link()));
            if (right > 0) spans.add(new Span(" ".repeat(right), background, null));
            result.add(new StyledLine(spans, cropped.annotation()));
        }
        return result;
    }

    @Override public int height(RenderContext context, int width) { return lines.size(); }

    /** @return 原始行快照，供测试/组合。 */
    public List<StyledLine> lines() { return lines; }
}
