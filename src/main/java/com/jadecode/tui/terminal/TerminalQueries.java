package com.jadecode.tui.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.text.Color;

/**
 * 终端能力协商与查询回复匹配：DA、Kitty 键盘标记、单元格尺寸、OSC 10/11/4 色板和明暗报告。
 *
 * <p>查询只能由 UI/宿主在明确的生命周期点发起（启动、恢复、模式切换），通过唯一终端 writer
 * 排队输出；worker 不能直接 print。回复由输入解码器识别后交给这里，晚回复/分片/重复回复
 * 只更新能力快照，绝不进入编辑器。终端没有回复时保留 Pi 回退值，不阻塞输入。</p>
 *
 * <p>epoch 与请求绑定：暂停/恢复或 resize 后旧回复按 epoch 丢弃，防止把上一屏的色板
 * 或鼠标坐标应用到新画面。</p>
 */
public final class TerminalQueries {
    /**
     * 协商结果快照；全部字段可空，表示尚未获得该信息。
     * @param keyboard 键盘协议
     * @param cellWidth cell 像素宽
     * @param cellHeight cell 像素高
     * @param foreground OSC 10 前景色
     * @param background OSC 11 背景色
     * @param ansi OSC 4 上报的 16 色（按索引放置，缺失为 null）
     * @param colorScheme 终端明暗报告
     * @param colorReplyComplete 是否至少收到前景/背景/16 色之一
     */
    public record State(TerminalCapabilities.KeyboardSupport keyboard, Integer cellWidth, Integer cellHeight,
            Color foreground, Color background, List<Color> ansi, Boolean darkBackground, boolean colorReplyComplete) {
        public State {
            // 16 色回复可能只到一部分，未到达项为 null；这里不能使用 List.copyOf（拒绝 null）。
            ansi = ansi == null ? List.of()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(ansi));
        }
    }

    private final Consumer<String> output;
    private final List<String> pending = new ArrayList<>();
    private Stage stage = Stage.NEW;
    private long epoch;
    private TerminalCapabilities.KeyboardSupport keyboard = TerminalCapabilities.KeyboardSupport.LEGACY;
    private Integer cellWidth, cellHeight;
    private Color foreground, background;
    private final List<Color> ansi = new ArrayList<>(java.util.Collections.nCopies(16, null));
    private Boolean darkBackground;
    private boolean colorReplyComplete;

    /** 查询阶段；用于诊断/测试，不直接展示给用户。 */
    public enum Stage { NEW, SENT, DA_RECEIVED, READY }

    /** @param output 唯一终端写入口（通常是 renderer/terminal session 的 writer）。 */
    public TerminalQueries(Consumer<String> output) { this.output = output; }

    /** 恢复/模式切换后重新协商：清空未完成查询并重发，不创建第二读取者。 */
    public void restart() { stage = Stage.NEW; pending.clear(); start(); }

    /** @return 当前协商快照。 */
    public State snapshot() {
        return new State(keyboard, cellWidth, cellHeight, foreground, background, ansi, darkBackground, colorReplyComplete);
    }

    /** @return 当前 epoch；宿主在恢复/resize 时更新。 */
    public long epoch() { return epoch; }

    /** 递增 epoch 并使未完成查询失效；旧回复随后按 epoch 丢弃。 */
    public void newEpoch(long value) {
        epoch = value; pending.clear(); stage = Stage.NEW;
    }

    /**
     * 发起 Pi 对应的一组查询：Kitty 键盘标记、DA1 哨兵、单元格尺寸、明暗报告和色板。
     * 只发送一次；再次调用会重新协商但不会创建另一条读取线程。
     */
    public void start() {
        if (stage != Stage.NEW) return;
        stage = Stage.SENT;
        // 顺序与 Pi 接近：先键盘能力与 DA 哨兵，再尺寸/明暗/颜色；回复可能乱序。
        output.accept("\u001b[?u\u001b[>0;0;0c\u001b[c\u001b[16t\u001b[?996n");
        output.accept("\u001b]10;?\u0007\u001b]11;?\u0007");
        StringBuilder palette = new StringBuilder();
        for (int i = 0; i < 16; i++) palette.append("\u001b]4;").append(i).append(";?\u0007");
        output.accept(palette.toString());
    }

    /**
     * 处理一个终端回复。返回 true 表示能力/色板发生变化，调用方应重建 Theme 并重绘。
     * 非法/迟到回复返回 false，不抛出；不把 payload 当输入。
     */
    public boolean accept(InputEvent.TerminalReply reply, long replyEpoch) {
        if (reply == null) return false;
        if (replyEpoch != 0 && epoch != 0 && replyEpoch != epoch) return false;
        boolean changed = false;
        switch (reply.kind()) {
            case "KEYBOARD_FLAGS" -> {
                // Kitty 键盘协议回复形如 `?3u`（flags=3）或空；只有包含 `?` 才是我们的回复。
                if (reply.payload().startsWith("?") && reply.payload().endsWith("u")) {
                    String flags = reply.payload().substring(1, reply.payload().length() - 1);
                    if (!flags.isEmpty() && flags.chars().allMatch(Character::isDigit)) {
                        keyboard = TerminalCapabilities.KeyboardSupport.KITTY; changed = true;
                    }
                }
            }
            case "DA" -> { stage = Stage.DA_RECEIVED; changed = true; }
            case "CELL_SIZE" -> {
                String[] parts = reply.payload().split(";");
                try {
                    if (parts.length >= 2) {
                        int height = Integer.parseInt(parts[0]), width = Integer.parseInt(parts[1]);
                        if (width > 0 && height > 0) { cellWidth = width; cellHeight = height; changed = true; }
                    }
                } catch (NumberFormatException ignored) { }
            }
            case "COLOR_SCHEME" -> {
                if (reply.payload().endsWith(";1n") || reply.payload().endsWith(";1")) { darkBackground = true; changed = true; }
                else if (reply.payload().endsWith(";2n") || reply.payload().endsWith(";2")) { darkBackground = false; changed = true; }
            }
            case "COLOR" -> changed = acceptColor(reply.payload());
            default -> { }
        }
        if (changed && stage == Stage.SENT) stage = Stage.DA_RECEIVED;
        return changed;
    }

    /** 解析 OSC 10/11/4 颜色回复。颜色值格式在 Pi 与常见终端间有 hex/rgb:/16 位差异。 */
    private boolean acceptColor(String payload) {
        String[] parts = payload.split(";", 3);
        if (parts.length < 3) return false;
        Color color = parseColor(parts[2]);
        if (parts[0].equals("10")) { foreground = color; colorReplyComplete = true; return true; }
        if (parts[0].equals("11")) {
            background = color;
            if (color != null && color.kind() == Color.Kind.RGB) {
                darkBackground = com.jadecode.tui.theme.ThemePalette.lightness(color) < 0.5;
            }
            colorReplyComplete = true; return true;
        }
        if (parts[0].equals("4")) {
            try {
                int index = Integer.parseInt(parts[1]);
                if (index >= 0 && index < 16) { ansi.set(index, color); colorReplyComplete = true; return true; }
            } catch (NumberFormatException ignored) { }
        }
        return false;
    }

    private static Color parseColor(String value) {
        String text = value.trim();
        if (text.isEmpty() || text.equals("?")) return null;
        try {
            if (text.startsWith("#")) return com.jadecode.tui.theme.ThemePalette.parseColor(text);
            String body = text.replaceFirst("(?i)^rgba?:", "").replace("/", "");
            if (body.matches("[0-9a-fA-F]{12}")) {
                return Color.rgb(hexChannel(body.substring(0, 4)), hexChannel(body.substring(4, 8)),
                        hexChannel(body.substring(8, 12)));
            }
            if (body.matches("[0-9a-fA-F]{6}")) return com.jadecode.tui.theme.ThemePalette.parseColor("#" + body);
            String[] rgb = text.replaceFirst("(?i)^rgba?:", "").split("[/,]");
            if (rgb.length >= 3) return Color.rgb(channel(rgb[0]), channel(rgb[1]), channel(rgb[2]));
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static int hexChannel(String value) {
        int max = (int) Math.pow(16, value.length()) - 1;
        return Math.round(Integer.parseInt(value, 16) * 255f / max);
    }

    private static int channel(String value) {
        String text = value.trim();
        if (text.startsWith("#")) return (int) Math.round(Integer.parseInt(text.substring(1), 16) * 255.0 / 255);
        int parsed = Integer.parseInt(text);
        // 终端可能上报 0-255 或 16 位 0-65535；按数值范围选择比例。
        return parsed <= 255 ? parsed : Math.round(parsed * 255f / 65535);
    }

    /** @return 尚未收到的查询说明；用于诊断而不是用户界面。 */
    public List<String> missing() {
        var result = new ArrayList<String>();
        if (foreground == null) result.add("foreground");
        if (background == null) result.add("background");
        if (ansi.stream().anyMatch(java.util.Objects::isNull)) result.add("palette");
        if (cellWidth == null) result.add("cell-size");
        if (keyboard == TerminalCapabilities.KeyboardSupport.LEGACY) result.add("keyboard-flags");
        return List.copyOf(result);
    }
}
