package com.jadecode.tui.text;

/**
 * 不可变颜色值，只表达“默认色 / RGB / 256 色索引”，不读取环境也不输出 ANSI。
 *
 * <p>颜色来源可以是主题 JSON、终端 OSC 回复或语义 token。真正写终端前，由
 * {@code AnsiEncoder} 根据当前终端能力把 RGB 降级为索引色或默认色；这样同一份
 * 富样式既能用于真彩色终端，也能在 256 色或 {@code NO_COLOR} 下安全显示。</p>
 */
public record Color(Kind kind, int red, int green, int blue, int index) {
    /** 颜色的表达方式。DEFAULT 表示交给终端默认前景/背景。 */
    public enum Kind { DEFAULT, RGB, INDEXED }

    /** 终端默认色（前景或背景由使用位置决定）。 */
    public static final Color DEFAULT = new Color(Kind.DEFAULT, 0, 0, 0, -1);

    public Color {
        if (kind == null) throw new IllegalArgumentException("Color kind cannot be empty");
        if (kind == Kind.RGB && (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255))
            throw new IllegalArgumentException("RGB components must be between 0 and 255: " + red + "," + green + "," + blue);
        if (kind == Kind.INDEXED && (index < 0 || index > 255))
            throw new IllegalArgumentException("Indexed color must be between 0 and 255: " + index);
    }

    /** 由 0-255 的 RGB 分量构造颜色。 */
    public static Color rgb(int red, int green, int blue) { return new Color(Kind.RGB, red, green, blue, -1); }

    /** 由 256 色索引构造颜色。 */
    public static Color indexed(int index) { return new Color(Kind.INDEXED, 0, 0, 0, index); }

    /** @return 是否为终端默认色；主题中空字符串颜色使用它。 */
    public boolean isDefault() { return kind == Kind.DEFAULT; }

    /**
     * @return 用于比较/缓存的稳定整数；不同种类即使视觉接近也不会误判相等。
     */
    public int packed() {
        return switch (kind) {
            case DEFAULT -> 0;
            case RGB -> 0x0100_0000 | red << 16 | green << 8 | blue;
            case INDEXED -> 0x0200_0000 | index;
        };
    }
}
