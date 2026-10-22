package com.jadecode.tui.text;

import java.util.Locale;

/**
 * 经过校验的超链接目标，只有它能让 {@code AnsiEncoder} 生成 OSC 8 序列。
 *
 * <p>模型或工具回复里的普通文字即使长得像 `https://...` 也不能自行产生控制序列；Markdown
 * 解析只把明确的链接语法转换成这个类型。构造时拒绝空白、控制字符、缺少 scheme 或异常长的
 * URI，避免把不可信内容拼进 OSC 8。打开链接仍需要用户点击，渲染本身不发起网络访问。</p>
 *
 * @param uri 已校验的绝对 URI 文本（UTF-16）。不包含 `ESC]8;;` 等终端控制片段。
 */
public record LinkTarget(String uri) {
    /** 限制长度，避免恶意模型输出制造超大 OSC 序列或内存压力。 */
    private static final int MAX_LENGTH = 4096;

    public LinkTarget {
        if (uri == null || uri.isBlank()) throw new IllegalArgumentException("Link cannot be empty");
        if (uri.length() > MAX_LENGTH) throw new IllegalArgumentException("Link is too long");
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c) || c == 0x7f)
                throw new IllegalArgumentException("Link contains whitespace or control characters");
        }
        int colon = uri.indexOf(':');
        if (colon <= 0 || !uri.substring(0, colon).matches("[A-Za-z][A-Za-z0-9+.\\-]*"))
            throw new IllegalArgumentException("Link is missing a valid scheme");
        if (uri.regionMatches(true, 0, "javascript:", 0, 11))
            throw new IllegalArgumentException("Executable javascript links are not allowed");
    }

    /**
     * 尝试构造链接；非法值返回 null，正文仍按普通文字显示。
     * 这样 Markdown 中畸形链接不会让整条回复渲染失败，也不会被静默升级为可点击目标。
     */
    public static LinkTarget of(String value) {
        try { return new LinkTarget(value); } catch (IllegalArgumentException invalid) { return null; }
    }

    /** @return 只用于比较/日志的缩短形式，不替代原始 URI。 */
    public String display() {
        return uri.length() <= 80 ? uri : uri.substring(0, 77) + "...";
    }

    /** @return 不区分 scheme 大小写的比较，避免 `HTTPS:` 被视为不同协议。 */
    public boolean sameAs(LinkTarget other) {
        return other != null && uri.toLowerCase(Locale.ROOT).equals(other.uri.toLowerCase(Locale.ROOT));
    }
}
