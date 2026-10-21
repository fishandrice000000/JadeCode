package com.jadecode.tui.text;

/**
 * 一段带样式（可选带链接）的正文。正文已经过净化，不包含终端控制序列。
 *
 * <p>Span 是布局/渲染的最小样式单位，不携带源文下标或可变状态。链接只能通过
 * {@link LinkTarget} 构造，模型正文无法伪造 OSC 8。相邻 Span 只有在样式和链接完全相同时
 * 才可合并；跨 Span 的字素必须由 {@link StyledLine} 的换行/裁切逻辑先分组再切分，
 * 不能在颜色边界把一个 emoji 或组合字符拆开。</p>
 *
 * @param text 已净化的正文片段
 * @param style 完整可组合样式
 * @param link 可点击目标；普通文字为 null
 */
public record Span(String text, Style style, LinkTarget link) {
    public Span {
        if (text == null) text = "";
        if (style == null) style = Style.PLAIN;
    }

    /** 纯正文 Span。 */
    public Span(String text, Style style) { this(text, style, null); }

    /** 纯文字、无样式 Span。 */
    public static Span plain(String text) { return new Span(text, Style.PLAIN, null); }

    /** @return 纯文字，不含光标/图片标记。 */
    public String text() { return text; }

    /** @return 宽度（终端列），不是 UTF-16 长度。 */
    public int width() { return DisplayWidth.width(text); }

    /** @return 是否为空片段；布局可跳过但通常保留以维持样式缓存一致。 */
    public boolean isEmpty() { return text.isEmpty(); }

    /** @return 替换文字后的新片段；样式与链接不变，便于裁切/换行。 */
    public Span withText(String value) { return new Span(value, style, link); }

    /**
     * 按给定父样式解析后的实际样式；子片段明确的颜色覆盖父级，属性合并。
     * 用于 Markdown 嵌套作用域，不会修改 Span 自身。
     */
    public Style resolve(Style parent) { return parent == null ? style : parent.merge(style); }
}
