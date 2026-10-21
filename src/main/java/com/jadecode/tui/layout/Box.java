package com.jadecode.tui.layout;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.geometry.Insets;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * 给子组件加 padding/背景/宽度约束的容器。
 *
 * <p>背景会填满整个分配宽度（含 padding 与右侧空位），文字样式叠在背景上；不添加 Pi 没有的
 * 左右竖边。极窄宽度下 insets 会被夹取，不产生负内容宽。所有行在同一宽度下重新 pad，
 * 避免调用方再手拼空格导致末列/命中错位。</p>
 *
 * @param child 子组件
 * @param insets 内边距
 * @param backgroundToken 主题背景 token；null 表示透明
 * @param padToWidth 是否用背景/普通样式补满整行
 */
public record Box(Component child, Insets insets, String backgroundToken, boolean padToWidth) implements Component {
    public Box {
        if (child == null) throw new IllegalArgumentException("Box 子组件不能为空");
        if (insets == null) insets = Insets.ZERO;
    }

    /** 无背景的 padding 容器。 */
    public static Box padded(Component child, Insets insets) { return new Box(child, insets, null, false); }

    /** 带整行背景的容器。 */
    public static Box background(Component child, Insets insets, String token) {
        return new Box(child, insets, token, true);
    }

    @Override public List<StyledLine> render(RenderContext context, int width) {
        // 极窄窗口必须夹取 padding，至少给内容留 1 列；否则 1 列宽时仍会输出 6+ 列，
        // 父布局/命中坐标随之越界。
        int left = Math.min(insets.left(), Math.max(0, width - 1));
        int right = Math.min(insets.right(), Math.max(0, width - left - 1));
        int inner = Math.max(1, width - left - right);
        var result = new ArrayList<StyledLine>();
        Style background = backgroundToken == null ? Style.PLAIN
                : context.theme().background(backgroundToken);
        for (int row = 0; row < insets.top(); row++) result.add(fill("", width, background));
        for (StyledLine line : child.render(context, inner)) {
            var spans = new ArrayList<Span>();
            if (left > 0) spans.add(new Span(" ".repeat(left), background, null));
            for (Span span : line.spans())
                spans.add(background.isPlain() ? span : new Span(span.text(), span.style().withBg(background.bg()), span.link()));
            int used = left + Math.min(inner, line.width());
            if (used < width) {
                // 右 padding 与剩余背景必须一起补；不能把 insets.right 先算进 used 后
                // 又只在 used<width 时补，否则整行会少一列，背景/hit 与文字错位。
                spans.add(new Span(" ".repeat(width - used), backgroundToken == null ? Style.PLAIN : background, null));
            }
            result.add(new StyledLine(spans, line.annotation()));
        }
        for (int row = 0; row < insets.bottom(); row++) result.add(fill("", width, background));
        return result;
    }

    @Override public int height(RenderContext context, int width) {
        int left = Math.min(insets.left(), Math.max(0, width - 1));
        int right = Math.min(insets.right(), Math.max(0, width - left - 1));
        int inner = Math.max(1, width - left - right);
        return insets.vertical() + child.height(context, inner);
    }

    @Override public boolean handle(com.jadecode.tui.input.InputEvent event) { return child.handle(event); }

    private static StyledLine fill(String text, int width, Style style) {
        String padded = text + " ".repeat(Math.max(0, width - text.length()));
        return new StyledLine(List.of(new Span(padded, style, null)));
    }
}
