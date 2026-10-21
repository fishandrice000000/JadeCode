package com.jadecode.tui.geometry;

/**
 * 终端坐标中的整数矩形，原点是左上角，单位为“列/行”。
 *
 * <p>布局、命中测试、滚动和裁剪都使用同一个矩形类型，避免各组件用“行偏移 + 字符下标”
 * 各自推算坐标而出现文本、光标和点击区域错位。宽或高为 0 的矩形表示空区域，仍然合法，
 * 极窄窗口不需要用负数或特殊值分支。x/y 可以暂时超出屏幕，由 {@link #intersect(Rect)}
 * 与实际可视区相交后再绘制；命中区域同样必须使用裁剪后的矩形。</p>
 */
public record Rect(int x, int y, int width, int height) {
    /** 空矩形，宽高都为 0；用于“没有内容”而不是 null。 */
    public static final Rect EMPTY = new Rect(0, 0, 0, 0);

    public Rect {
        if (width < 0 || height < 0) throw new IllegalArgumentException("矩形宽高不能为负: " + width + "x" + height);
    }

    /** @return 右边界（不含），等于 x + width。 */
    public int right() { return x + width; }

    /** @return 下边界（不含），等于 y + height。 */
    public int bottom() { return y + height; }

    /** @return 是否没有任何可绘制格。 */
    public boolean isEmpty() { return width == 0 || height == 0; }

    /** @return 指定屏幕格是否落在矩形内；右/下边界本身不算。 */
    public boolean contains(int column, int row) {
        return column >= x && column < right() && row >= y && row < bottom();
    }

    /** @return 平移到新位置，尺寸不变；用于父布局分配区域。 */
    public Rect translate(int dx, int dy) { return new Rect(x + dx, y + dy, width, height); }

    /** @return 在四边各缩进后的区域，可能为空，但不会产生负宽高。 */
    public Rect inset(Insets insets) {
        int left = Math.min(width, insets.left());
        int right = Math.min(width - left, insets.right());
        int top = Math.min(height, insets.top());
        int bottom = Math.min(height - top, insets.bottom());
        return new Rect(x + left, y + top, Math.max(0, width - left - right), Math.max(0, height - top - bottom));
    }

    /**
     * 求两个矩形的交集。完全不相交时返回 {@link #EMPTY}，其位置为 0,0，
     * 调用方不应再从中反推原区域。
     */
    public Rect intersect(Rect other) {
        int left = Math.max(x, other.x);
        int top = Math.max(y, other.y);
        int right = Math.min(right(), other.right());
        int bottom = Math.min(bottom(), other.bottom());
        return right <= left || bottom <= top ? EMPTY : new Rect(left, top, right - left, bottom - top);
    }

    /** @return 把本矩形限制在 bounds 内；用于屏幕和父容器裁剪。 */
    public Rect clampTo(Rect bounds) { return intersect(bounds); }

    /** 便捷构造：从左上角到右下角（不含）。 */
    public static Rect of(int x, int y, int right, int bottom) {
        return new Rect(x, y, Math.max(0, right - x), Math.max(0, bottom - y));
    }
}
