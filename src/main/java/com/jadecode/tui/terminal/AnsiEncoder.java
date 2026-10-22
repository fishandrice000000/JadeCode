package com.jadecode.tui.terminal;

import com.jadecode.tui.text.Color;
import com.jadecode.tui.text.LinkTarget;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * 唯一受信终端编码器：把结构化样式/链接编码为 SGR/OSC 序列。
 *
 * <p>模型、工具和文件正文只存在于 {@link Span} 的纯文字里；本类不会把正文当转义序列透传。
 * 颜色按能力降级：真彩色输出 24 位，256 色终端把 RGB 近似到调色板，{@code NO_COLOR}
 * 时不输出任何 SGR 颜色/属性。每行结束主动 reset 并关闭 OSC 8，避免样式或链接泄漏到下一行
 * 或 shell 提示符。</p>
 *
 * <p>本类只生成字符串，不执行 I/O；renderer 决定何时写入/flush，确保 PresentedFrame 的
 * 证据只在 flush 成功后发布。</p>
 */
public final class AnsiEncoder {
    private AnsiEncoder() { }

    /**
     * 编码一行。
     * @param line 已净化的结构化行
     * @param trueColor 是否允许 24 位颜色
     * @param colorsEnabled 是否允许任何颜色/属性（NO_COLOR 为 false）
     * @param hyperlinks 是否允许 OSC 8
     * @return 可写入终端的字符串；不含换行
     */
    public static String encode(StyledLine line, boolean trueColor, boolean colorsEnabled, boolean hyperlinks) {
        var out = new StringBuilder(Math.max(16, line.width() + 16));
        LinkTarget activeLink = null;
        Style activeStyle = Style.PLAIN;
        for (Span span : line.spans()) {
            String text = span.text();
            if (text.isEmpty()) continue;
            if (hyperlinks && !sameLink(activeLink, span.link())) {
                if (activeLink != null) out.append("\u001b]8;;\u001b\\");
                activeLink = span.link();
                if (activeLink != null) out.append("\u001b]8;;").append(activeLink.uri()).append("\u001b\\");
            }
            if (colorsEnabled && !span.style().equals(activeStyle)) {
                out.append(sgr(span.style(), trueColor));
                activeStyle = span.style();
            }
            out.append(safe(text));
        }
        if (activeLink != null) out.append("\u001b]8;;\u001b\\");
        if (colorsEnabled && !activeStyle.isPlain()) out.append("\u001b[0m");
        return out.toString();
    }

    private static boolean sameLink(LinkTarget first, LinkTarget second) {
        return first == null ? second == null : first.sameAs(second);
    }

    /** 生成完整 SGR：先 reset，再设置颜色与属性，避免上一段的反色/粗体残留。 */
    public static String sgr(Style style, boolean trueColor) {
        var codes = new java.util.ArrayList<String>();
        if (!style.fg().isDefault()) codes.add(color(style.fg(), true, trueColor));
        if (!style.bg().isDefault()) codes.add(color(style.bg(), false, trueColor));
        if (style.bold()) codes.add("1");
        if (style.dim()) codes.add("2");
        if (style.italic()) codes.add("3");
        if (style.underline()) codes.add("4");
        if (style.inverse()) codes.add("7");
        if (style.strike()) codes.add("9");
        return codes.isEmpty() ? "\u001b[0m" : "\u001b[0;" + String.join(";", codes) + "m";
    }

    private static String color(Color color, boolean foreground, boolean trueColor) {
        if (color.kind() == Color.Kind.DEFAULT) return foreground ? "39" : "49";
        if (color.kind() == Color.Kind.INDEXED) return (foreground ? "38;5;" : "48;5;") + color.index();
        if (trueColor) return (foreground ? "38;2;" : "48;2;") + color.red() + ";" + color.green() + ";" + color.blue();
        int index = to256(color.red(), color.green(), color.blue());
        return (foreground ? "38;5;" : "48;5;") + index;
    }

    /** RGB→xterm 256 色近似：6x6x6 色立方 + 24 级灰阶，取最小平方误差。 */
    public static int to256(int red, int green, int blue) {
        int[] levels = {0, 95, 135, 175, 215, 255};
        int bestIndex = 16, bestDistance = Integer.MAX_VALUE;
        for (int r = 0; r < 6; r++) for (int g = 0; g < 6; g++) for (int b = 0; b < 6; b++) {
            int distance = square(red - levels[r]) + square(green - levels[g]) + square(blue - levels[b]);
            if (distance < bestDistance) { bestDistance = distance; bestIndex = 16 + 36 * r + 6 * g + b; }
        }
        int gray = Math.round((red + green + blue) / 3f);
        int grayIndex = 232 + Math.min(23, Math.max(0, Math.round((gray - 8) / 10f)));
        int grayValue = 8 + (grayIndex - 232) * 10;
        if (square(red - grayValue) + square(green - grayValue) + square(blue - grayValue) < bestDistance) return grayIndex;
        return bestIndex;
    }

    private static int square(int value) { return value * value; }

    /** 防御性清理：正文不应含 ESC，但若上游遗漏，这里也不让它进入协议通道。 */
    private static String safe(String text) {
        return text.indexOf('\u001b') < 0 ? text : text.replace("\u001b", "\u241b");
    }

    /** @return 同步输出开始标记；包围一整帧以减少撕裂。 */
    public static String syncStart() { return "\u001b[?2026h"; }

    /** @return 同步输出结束标记。 */
    public static String syncEnd() { return "\u001b[?2026l"; }

    /** @return 进入备用屏并关闭自动换行。 */
    public static String enterFullscreen() { return "\u001b[?1049h\u001b[?7l"; }

    /** @return 离开备用屏并恢复自动换行。 */
    public static String exitFullscreen() { return "\u001b[?7h\u001b[?1049l"; }

    /** @return 隐藏光标。 */
    public static String hideCursor() { return "\u001b[?25l"; }

    /** @return 显示光标。 */
    public static String showCursor() { return "\u001b[?25h"; }

    /** @return 移动光标到 0 基行列。 */
    public static String cursorTo(int row, int column) {
        return "\u001b[" + (Math.max(0, row) + 1) + ";" + (Math.max(0, column) + 1) + "H";
    }

    /** @return 清整行并回到列首。 */
    public static String clearLine() { return "\r\u001b[2K"; }

    /** @return 清屏到末尾。 */
    public static String clearBelow() { return "\u001b[J"; }

    /** @return 清屏并清 scrollback；只在 Pi 规则要求时由 renderer 发出。 */
    public static String clearAll() { return "\u001b[H\u001b[2J\u001b[3J"; }

    /**
     * @return 应用鼠标捕获：1000 点击 + 1002 按钮拖动报告 + 1006 SGR 坐标。
     *         只开 1000 时终端不会发送普通拖动事件，全屏选区无法工作。
     */
    public static String enableMouse() { return "\u001b[?1000h\u001b[?1002h\u001b[?1006h"; }

    /** @return 关闭鼠标捕获，恢复终端原生选择/滚动。 */
    public static String disableMouse() { return "\u001b[?1000l\u001b[?1002l\u001b[?1003l\u001b[?1006l"; }

    /** @return 打开 bracketed paste。 */
    public static String enablePaste() { return "\u001b[?2004h"; }

    /** @return 关闭 bracketed paste。 */
    public static String disablePaste() { return "\u001b[?2004l"; }
}
