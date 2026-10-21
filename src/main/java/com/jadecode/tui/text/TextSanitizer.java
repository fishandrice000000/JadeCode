package com.jadecode.tui.text;

/**
 * 把不可信正文转换成可安全显示的文本，同时保留“审批要看真实参数”的边界。
 *
 * <p>模型、工具或文件内容里的 ESC/CSI/OSC/DCS/APC 既不能直接写终端（会改变颜色、标题、
 * 剪贴板甚至执行协议），也不能在审批时被悄悄删除成另一个看似无害的命令。因此这里提供三种
 * 用途：{@link #display(String)} 移除控制序列得到普通显示正文；{@link #visible(String)}
 * 把控制字节变成可见转义文本供审批阅读；{@link #plain(String)} 只用于复制，保留换行/制表符。
 * 源字符串本身从不被修改。</p>
 */
public final class TextSanitizer {
    private TextSanitizer() { }

    /**
     * 生成普通显示正文：删除 CSI/OSC/DCS/APC 等控制序列、C0/C1 控制字符与 DEL；
     * 保留换行和制表符，由上层决定展开或换行。孤立的高/低代理项替换为可见占位，避免编码崩溃。
     */
    public static String display(String value) {
        if (value == null) return "";
        var out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == 0x1b) { i = skipEscape(value, i); continue; }
            if (c == '\n' || c == '\t') { out.append(c); i++; continue; }
            if (c == 0x9b || c == 0x9d || c == 0x90 || c == 0x9f || c == 0x98 || c == 0x9e) {
                // C1 CSI/OSC/DCS/APC 与 ESC 版本一样可能携带参数/正文；必须连同整段移除，
                // 不能只删控制字节而把 "2J"/"0;title" 留在可见正文里。
                i = skipC1(value, i, c);
                continue;
            }
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f)) { i++; continue; }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                    out.append(c).append(value.charAt(i + 1)); i += 2;
                } else { out.append('\uFFFD'); i++; }
                continue;
            }
            if (Character.isLowSurrogate(c)) { out.append('\uFFFD'); i++; continue; }
            out.append(c); i++;
        }
        return out.toString();
    }

    /**
     * 生成单行正文：先按显示规则净化，再把换行/制表符替换为空格。
     * 用于标题、页脚、候选标签等不允许出现第二行的位置。
     */
    public static String singleLine(String value) {
        return display(value).replace("\r\n", "\n").replace('\r', '\n').replace('\n', ' ').replace('\t', ' ');
    }

    /**
     * 把控制字符显示成可读转义文本，供审批参数/诊断阅读。
     *
     * <p>与 {@link #display(String)} 不同，这里不丢弃内容：ESC 显示为 `\x1b`，CSI/OSC
     * 的控制字节逐个可见化，换行仍然保留。这样用户能看出工具实际收到的参数里是否夹带了
     * 终端控制序列，同时不会让这些序列生效。展示文本不再等于源参数，因此审批提交仍使用
     * 原始请求对象，而不是这段转义后的字符串。</p>
     */
    public static String visible(String value) {
        if (value == null) return "";
        var out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n') { out.append('\n'); continue; }
            if (c == '\t') { out.append("\\t"); continue; }
            if (c == 0x1b) { out.append("\\x1b"); continue; }
            if (c < 0x20 || c == 0x7f) { out.append(String.format("\\x%02x", (int) c)); continue; }
            if (c >= 0x80 && c <= 0x9f) { out.append(String.format("\\u%04x", (int) c)); continue; }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 复制/搜索用的纯文字：移除所有控制序列，保留换行与制表符。
     * 不包含图片占位、假光标或 OSC 8 标记，因为这些从来不是 Span 正文的一部分。
     */
    public static String plain(String value) { return display(value); }

    /**
     * 从 {@code start}（指向 ESC）跳到该控制序列之后。无法识别的孤立 ESC 只跳过一个字符，
     * 后续普通文字仍会保留，不因畸形输入吞掉整段回答。
     */
    /** C1 控制序列：CSI 到最终字节，字符串型到 BEL/ST；其它只跳过控制字节。 */
    private static int skipC1(String value, int start, char kind) {
        int i = start + 1;
        if (kind == 0x9b) {
            while (i < value.length()) {
                char c = value.charAt(i++);
                if (c >= 0x40 && c <= 0x7e) return i;
            }
            return i;
        }
        if (kind == 0x9d || kind == 0x90 || kind == 0x9f || kind == 0x98 || kind == 0x9e) {
            while (i < value.length()) {
                char c = value.charAt(i);
                if (c == 0x07) return i + 1;
                if (c == 0x1b && i + 1 < value.length() && value.charAt(i + 1) == '\\') return i + 2;
                i++;
            }
            return i;
        }
        return i;
    }

    private static int skipEscape(String value, int start) {
        int i = start + 1;
        if (i >= value.length()) return i;
        char kind = value.charAt(i);
        if (kind == '[') {
            i++;
            while (i < value.length()) {
                char c = value.charAt(i++);
                if (c >= 0x40 && c <= 0x7e) return i;
            }
            return i;
        }
        if (kind == ']' || kind == 'P' || kind == '_' || kind == '^' || kind == 'X') {
            i++;
            while (i < value.length()) {
                char c = value.charAt(i);
                if (c == 0x07) return i + 1;
                if (c == 0x1b && i + 1 < value.length() && value.charAt(i + 1) == '\\') return i + 2;
                i++;
            }
            return i;
        }
        return Math.min(value.length(), i + 1);
    }
}
