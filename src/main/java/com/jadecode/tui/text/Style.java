package com.jadecode.tui.text;

/**
 * 可组合的文字样式：前景/背景颜色加若干独立属性。
 *
 * <p>与旧实现只保存一个样式名不同，嵌套 Markdown（粗体里的代码、链接里的斜体、引用里的
 * 删除线）可以逐层合并而不互相覆盖。样式不包含任何 ANSI 字符串，也不含可执行回调；
 * 最终 SGR 由 {@code AnsiEncoder} 生成。{@link #merge(Style)} 的语义是“子节点覆盖父节点
 * 明确设置的颜色和背景，属性取并集”，用于行内语法嵌套。</p>
 */
public record Style(Color fg, Color bg, boolean bold, boolean italic, boolean underline,
        boolean strike, boolean inverse, boolean dim) {
    /** 无颜色、无属性的正文样式。 */
    public static final Style PLAIN = new Style(Color.DEFAULT, Color.DEFAULT, false, false, false, false, false, false);

    public Style {
        if (fg == null) fg = Color.DEFAULT;
        if (bg == null) bg = Color.DEFAULT;
    }

    /** 只设置前景色。 */
    public static Style fg(Color color) { return new Style(color, Color.DEFAULT, false, false, false, false, false, false); }

    /** 只设置背景色。 */
    public static Style bg(Color color) { return new Style(Color.DEFAULT, color, false, false, false, false, false, false); }

    /** @return 替换前景色后的新样式。 */
    public Style withFg(Color color) { return new Style(color, bg, bold, italic, underline, strike, inverse, dim); }

    /** @return 替换背景色后的新样式。 */
    public Style withBg(Color color) { return new Style(fg, color, bold, italic, underline, strike, inverse, dim); }

    /** @return 追加粗体，保留其它属性。 */
    public Style withBold(boolean value) { return new Style(fg, bg, value, italic, underline, strike, inverse, dim); }

    /** @return 追加斜体，保留其它属性。 */
    public Style withItalic(boolean value) { return new Style(fg, bg, bold, value, underline, strike, inverse, dim); }

    /** @return 追加删除线，保留其它属性。 */
    public Style withStrike(boolean value) { return new Style(fg, bg, bold, italic, underline, value, inverse, dim); }

    /** @return 追加下划线，保留其它属性。 */
    public Style withUnderline(boolean value) { return new Style(fg, bg, bold, italic, value, strike, inverse, dim); }

    /** @return 追加暗淡属性，保留其它属性。 */
    public Style withDim(boolean value) { return new Style(fg, bg, bold, italic, underline, strike, inverse, value); }

    /** @return 追加反色属性，供假光标/选区使用。 */
    public Style withInverse(boolean value) { return new Style(fg, bg, bold, italic, underline, strike, value, dim); }

    /**
     * 合并父子样式：子样式明确的非默认颜色覆盖父样式，属性取并集。
     *
     * <p>例子：父样式为 `bold + 黄色`，子片段只设置 `italic`，合并结果是
     * `bold + italic + 黄色`；子片段若设置蓝色前景，则最终为 `bold + italic + 蓝色`。</p>
     */
    public Style merge(Style child) {
        if (child == null) return this;
        return new Style(child.fg.isDefault() ? fg : child.fg, child.bg.isDefault() ? bg : child.bg,
                bold || child.bold, italic || child.italic, underline || child.underline,
                strike || child.strike, inverse || child.inverse, dim || child.dim);
    }

    /** @return 是否没有任何可见颜色/属性，供渲染器跳过空 SGR。 */
    public boolean isPlain() { return this.equals(PLAIN); }
}
