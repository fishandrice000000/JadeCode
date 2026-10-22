package com.jadecode.tui.geometry;

/**
 * 四边内边距，顺序与 CSS 一致：上、右、下、左。所有值非负，0 表示不占空间。
 *
 * <p>Box/Overlay/布局用它表达 padding/margin，避免产品组件在每处手写空行和空格数量。
 * 它不携带主题或屏幕模式；只描述空间，实际裁剪由 {@link Rect#inset(Insets)} 完成。</p>
 */
public record Insets(int top, int right, int bottom, int left) {
    public static final Insets ZERO = new Insets(0, 0, 0, 0);

    public Insets {
        if (top < 0 || right < 0 || bottom < 0 || left < 0)
            throw new IllegalArgumentException("Insets cannot be negative");
    }

    /** 四边相同的内边距。 */
    public static Insets all(int value) { return new Insets(value, value, value, value); }

    /** 水平与垂直分别指定；例如对称(x=1, y=0)表示左右各 1 列。 */
    public static Insets symmetric(int horizontal, int vertical) {
        return new Insets(vertical, horizontal, vertical, horizontal);
    }

    /** @return 左右合计占用的列数。 */
    public int horizontal() { return left + right; }

    /** @return 上下合计占用的行数。 */
    public int vertical() { return top + bottom; }
}
