package com.jadecode.util;

/**
 * 无终端渲染依赖的文本安全净化。
 *
 * <p>从旧 {@code tui.text.TextSanitizer} 抽取出来，供 headless 输出与诊断使用；它不包含
 * 列宽、Markdown、主题或终端渲染，所以旧 Java TUI 退役后仍然可以保留。</p>
 *
 * <p>为什么 headless 也要净化：模型/工具正文属于不可信内容，直接写 stdout 可能携带
 * ESC/CSI/OSC 序列，改变用户终端颜色、标题甚至触发剪贴板写入。这里移除控制序列但保留
 * 换行与制表符，正文本身不被改写。</p>
 */
public final class TerminalText {
    private TerminalText() { }

    /** 删除 CSI/OSC/DCS/APC 等控制序列、C0/C1 控制字符与 DEL，保留换行/制表符。 */
    public static String display(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == 0x1b) { i = skipEscape(value, i); continue; }
            if (c == '\n' || c == '\t') { out.append(c); i++; continue; }
            if (c == 0x9b || c == 0x9d || c == 0x90 || c == 0x9f || c == 0x98 || c == 0x9e) {
                i = skipC1(value, i, c);
                continue;
            }
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f)) { i++; continue; }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                    out.append(c).append(value.charAt(i + 1));
                    i += 2;
                } else {
                    out.append('\uFFFD');
                    i++;
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) { out.append('\uFFFD'); i++; continue; }
            out.append(c);
            i++;
        }
        return out.toString();
    }

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
