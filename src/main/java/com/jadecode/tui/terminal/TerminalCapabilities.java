package com.jadecode.tui.terminal;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一次启动内不变的终端能力快照。探测只读取环境/只读终端信息，不访问 Agent 或凭据。
 *
 * <p>环境显式覆盖优先：`JADE_TUI_IMAGES`、`JADE_TUI_TRUE_COLOR`、`JADE_TUI_HYPERLINKS`、
 * `JADE_HARDWARE_CURSOR`；未覆盖时按 Pi 的 TERM/NO_COLOR/tmux 规则推测。能力只决定
 * “可以尝试什么”，不是安全权限来源；比如图片能力不代表可以读取任意本地文件。</p>
 *
 * @param term TERM 原始值（仅用于诊断/分支，不直接显示给用户）
 * @param terminalProgram TERM_PROGRAM 或等价标识
 * @param colorCount 终端颜色数量（0/16/256/16_777_216）
 * @param trueColor 是否允许 24 位颜色
 * @param hyperlinks 是否允许 OSC 8
 * @param images 终端图片协议
 * @param keyboard 键盘协议能力（legacy/kitty/modifyOtherKeys）
 * @param cellWidthPixels 终端单元格像素宽；0 表示未知
 * @param cellHeightPixels 单元格像素高；0 表示未知
 * @param escapeTimeoutMillis 孤立 ESC 的等待期限
 * @param showHardwareCursor 是否显示真实硬件光标（IME 定位）
 * @param tmux 是否运行在 tmux/screen 内
 * @param ssh 是否通过 SSH
 */
public record TerminalCapabilities(String term, String terminalProgram, int colorCount, boolean trueColor,
        boolean hyperlinks, ImageSupport images, KeyboardSupport keyboard, int cellWidthPixels,
        int cellHeightPixels, long escapeTimeoutMillis, boolean showHardwareCursor, boolean tmux, boolean ssh) {
    /** 终端图片协议。NONE 时所有图片显示回退为文字占位。 */
    public enum ImageSupport { NONE, KITTY, ITERM2 }

    /** 键盘协议；KITTY 由 CSI-u 协商确定，MODIFY_OTHER_KEYS 是 xterm 回退。 */
    public enum KeyboardSupport { LEGACY, KITTY, MODIFY_OTHER_KEYS }

    public TerminalCapabilities {
        if (term == null) term = "";
        if (terminalProgram == null) terminalProgram = "";
        if (escapeTimeoutMillis <= 0) escapeTimeoutMillis = 10;
        if (colorCount < 0) colorCount = 0;
        if (cellWidthPixels < 0) cellWidthPixels = 0;
        if (cellHeightPixels < 0) cellHeightPixels = 0;
    }

    /** @return 是否允许输出任何颜色/视觉属性。 */
    public boolean colorsEnabled() { return colorCount > 0; }

    /**
     * 根据环境与显式覆盖探测能力。unknown/空环境按保守值处理；不在这里发送终端查询，
     * 查询结果由 {@link TerminalQueries} 以同一 epoch 合入新的快照。
     */
    public static TerminalCapabilities detect(Map<String, String> env) {
        String term = env.getOrDefault("TERM", "").toLowerCase(Locale.ROOT);
        String program = env.getOrDefault("TERM_PROGRAM", "");
        boolean tmux = !env.getOrDefault("TMUX", "").isBlank()
                || term.startsWith("tmux") || term.startsWith("screen");
        boolean ssh = !env.getOrDefault("SSH_CONNECTION", "").isBlank()
                || !env.getOrDefault("SSH_TTY", "").isBlank();
        boolean dumb = term.equals("dumb") || term.isEmpty();
        boolean noColor = env.containsKey("NO_COLOR");
        String colorTerm = env.getOrDefault("COLORTERM", "").toLowerCase(Locale.ROOT);
        boolean trueColor = !noColor && !dumb && (colorTerm.contains("truecolor") || colorTerm.contains("24bit")
                || env.getOrDefault("JADE_TUI_TRUE_COLOR", "").equals("1"));
        if (env.getOrDefault("JADE_TUI_TRUE_COLOR", "").equals("0")) trueColor = false;
        int colors = dumb || noColor ? 0 : trueColor ? 16_777_216 : 256;
        String colorOverride = env.getOrDefault("JADE_TERM_COLORS", "");
        if (!colorOverride.isBlank()) {
            try { colors = Math.max(0, Integer.parseInt(colorOverride)); } catch (NumberFormatException ignored) { }
        }
        boolean hyperlinks = !noColor && !dumb && !term.contains("linux")
                && ("1".equals(env.get("JADE_TUI_HYPERLINKS"))
                || env.containsKey("WT_SESSION") || program.contains("WezTerm") || program.contains("iTerm")
                || term.contains("kitty") || term.contains("wezterm") || term.contains("ghostty"));
        if ("0".equals(env.get("JADE_TUI_HYPERLINKS"))) hyperlinks = false;

        ImageSupport images = ImageSupport.NONE;
        String imageOverride = env.getOrDefault("JADE_TUI_IMAGES", "").toLowerCase(Locale.ROOT);
        boolean kitty = term.contains("kitty") || "1".equals(env.get("KITTY_WINDOW_ID"));
        boolean iterm2 = program.contains("iTerm") || "1".equals(env.get("ITERM_SESSION_ID"))
                || program.contains("WezTerm");
        if (tmux || program.toLowerCase(Locale.ROOT).contains("vscode")) { kitty = false; iterm2 = false; }
        if (imageOverride.equals("kitty")) images = ImageSupport.KITTY;
        else if (imageOverride.equals("iterm2")) images = ImageSupport.ITERM2;
        else if (imageOverride.equals("none")) images = ImageSupport.NONE;
        else if (kitty) images = ImageSupport.KITTY;
        else if (iterm2) images = ImageSupport.ITERM2;

        long escape = ssh ? 100 : 10;
        String override = env.getOrDefault("JADE_TUI_ESC_TIMEOUT", "");
        if (!override.isBlank()) {
            try {
                long value = Long.parseLong(override);
                if (value > 0) escape = value;
            } catch (NumberFormatException ignored) { }
        }
        boolean hardware = "1".equals(env.get("JADE_HARDWARE_CURSOR"));
        return new TerminalCapabilities(term, program, colors, trueColor, hyperlinks, images,
                KeyboardSupport.LEGACY, 0, 0, escape, hardware, tmux, ssh);
    }

    /** @return 合并终端查询结果的副本；不修改原快照。 */
    public TerminalCapabilities withQueries(Integer cellWidth, Integer cellHeight, KeyboardSupport protocol,
            Integer colors, Boolean trueColor, Boolean links, ImageSupport imageSupport) {
        return new TerminalCapabilities(term, terminalProgram, colors == null ? colorCount : colors,
                trueColor == null ? this.trueColor : trueColor,
                links == null ? hyperlinks : links, imageSupport == null ? images : imageSupport,
                protocol == null ? keyboard : protocol,
                cellWidth == null ? cellWidthPixels : cellWidth,
                cellHeight == null ? cellHeightPixels : cellHeight,
                escapeTimeoutMillis, showHardwareCursor, tmux, ssh);
    }

    /** @return 与 Pi 相同的 16 色回退列表（用于 system 主题在无 OSC 回复时取色相）。 */
    public List<com.jadecode.tui.text.Color> assumedPalette() {
        var result = new java.util.ArrayList<com.jadecode.tui.text.Color>(16);
        for (int i = 0; i < 16; i++) result.add(com.jadecode.tui.theme.ThemePalette.indexedApprox(i));
        return List.copyOf(result);
    }

    /** @return 图像能力是否明确为可用；false 时组件应显示文字回退。 */
    public boolean imagesAvailable() { return images != ImageSupport.NONE; }
}
