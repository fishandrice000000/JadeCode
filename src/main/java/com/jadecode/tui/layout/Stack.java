package com.jadecode.tui.layout;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * 垂直/水平顺序布局，按 Pi 的 min/shrink/flex 思路分配实际空间。
 *
 * <p>垂直模式只拼接行，gap 插入空行；水平模式先按子组件理想宽/权重分配列，再逐行拼接，
 * 子组件行数不足时用空白补齐。父容器可用更小高度裁剪，组件自身不偷偷加空行。这里不拥有
 * 焦点、业务状态或滚动；Dock/产品视图决定谁被显示。</p>
 */
public final class Stack implements Component {
    /** 布局方向；内部枚举避免公开两套类。 */
    public enum Direction { VERTICAL, HORIZONTAL }

    private final Direction direction;
    private final List<Component> children;
    private final int gap;

    private Stack(Direction direction, List<Component> children, int gap) {
        this.direction = direction; this.children = List.copyOf(children); this.gap = Math.max(0, gap);
    }

    /** 垂直栈；children 按顺序从上到下。 */
    public static Stack vertical(Component... children) { return new Stack(Direction.VERTICAL, List.of(children), 0); }

    /** 带间距的垂直栈。 */
    public static Stack vertical(int gap, Component... children) { return new Stack(Direction.VERTICAL, List.of(children), gap); }

    /** 水平栈；children 按顺序从左到右。 */
    public static Stack horizontal(Component... children) { return new Stack(Direction.HORIZONTAL, List.of(children), 0); }

    /** 带间距的水平栈。 */
    public static Stack horizontal(int gap, Component... children) { return new Stack(Direction.HORIZONTAL, List.of(children), gap); }

    public Direction direction() { return direction; }

    public List<Component> children() { return children; }

    @Override public List<StyledLine> render(RenderContext context, int width) {
        if (direction == Direction.VERTICAL) {
            var result = new ArrayList<StyledLine>();
            for (int i = 0; i < children.size(); i++) {
                if (i > 0 && gap > 0) for (int row = 0; row < gap; row++) result.add(StyledLine.empty());
                result.addAll(children.get(i).render(context, width));
            }
            return result;
        }
        int[] widths = allocate(context, width);
        var rendered = new ArrayList<List<StyledLine>>();
        int height = 0;
        for (int i = 0; i < children.size(); i++) {
            var lines = children.get(i).render(context, widths[i]);
            rendered.add(lines); height = Math.max(height, lines.size());
        }
        var result = new ArrayList<StyledLine>(height);
        for (int row = 0; row < height; row++) {
            var spans = new ArrayList<Span>();
            for (int i = 0; i < children.size(); i++) {
                if (i > 0 && gap > 0) spans.add(new Span(" ".repeat(gap), Style.PLAIN, null));
                List<StyledLine> lines = rendered.get(i);
                StyledLine line = row < lines.size() ? lines.get(row) : StyledLine.empty();
                spans.addAll(line.spans());
                int used = line.width();
                if (used < widths[i]) spans.add(new Span(" ".repeat(widths[i] - used), Style.PLAIN, null));
            }
            result.add(new StyledLine(spans));
        }
        return result;
    }

    /** 水平宽度分配：先满足最小 1 列，再按剩余平均分配；不足时逐个裁剪到 1 列。 */
    private int[] allocate(RenderContext context, int width) {
        int gaps = gap * Math.max(0, children.size() - 1);
        int available = Math.max(children.size(), width - gaps);
        int[] result = new int[children.size()];
        int remaining = available;
        for (int i = 0; i < result.length; i++) {
            int share = Math.max(1, remaining / (result.length - i));
            result[i] = share; remaining -= share;
        }
        return result;
    }

    @Override public boolean handle(com.jadecode.tui.input.InputEvent event) {
        for (Component child : children) if (child.handle(event)) return true;
        return false;
    }
}
