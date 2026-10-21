package com.jadecode.tui.theme;

import java.util.Map;
import com.jadecode.tui.text.Color;
import com.jadecode.tui.text.Style;

/**
 * 不可变主题快照：语义 token 到完整 {@link Style} 的映射，以及是否允许颜色。
 *
 * <p>组件只请求语义 token（`muted`、`mdHeading`、`toolErrorBg` 等），不硬编码 ANSI 或
 * RGB。主题切换只替换快照并增加 revision，不要求重新解析消息。{@code colors=false}
 * 时（NO_COLOR/dumb 终端）所有 token 返回 {@link Style#PLAIN}，但定位、光标和屏幕模式
 * 仍由渲染器正常工作，不会输出可见转义乱码。</p>
 *
 * @param name 主题名（system/dark/light/自定义）
 * @param appearance 设计外观（dark/light），用于图片/对比度决定
 * @param colors token 颜色表
 * @param enabled 是否输出颜色和视觉属性
 * @param revision 主题代次；渲染器用它判断是否需要重画
 */
public record Theme(String name, String appearance, Map<String, Color> colors, boolean enabled, long revision) {
    /** 深色/浅色外观的统一大小写。 */
    public String appearance() { return appearance == null ? "dark" : appearance.toLowerCase(java.util.Locale.ROOT); }

    /** @return 是否为浅色主题，供图片/选择背景微调。 */
    public boolean isLight() { return appearance().equals("light"); }

    /** @return 指定 token 的颜色；未知 token 返回终端默认色。 */
    public Color color(String token) {
        Color color = colors.get(token);
        return color == null ? Color.DEFAULT : color;
    }

    /** @return 指定 token 的前景样式；包含该 token 的字体属性。 */
    public Style style(String token) {
        if (!enabled) return Style.PLAIN;
        Style base = Style.fg(color(token));
        return switch (token) {
            case "thinkingText", "thinkingOff", "thinkingMinimal", "thinkingLow", "thinkingMedium",
                    "thinkingHigh", "thinkingXhigh", "mdQuote" -> base.withItalic(true);
            case "dim", "toolDiffContext" -> base.withDim(true);
            case "mdHeading", "toolTitle", "requestTitle", "replyTitle", "thinkingTitle" -> base.withBold(true);
            case "mdLink" -> base.withUnderline(true);
            case "mdCode", "syntaxKeyword" -> base.withBold(true);
            case "error", "approvalDeny" -> base.withBold(true);
            default -> base;
        };
    }

    /** @return 指定 token 的整行背景样式；文本颜色保持默认。 */
    public Style background(String token) {
        return enabled ? Style.bg(color(token)) : Style.PLAIN;
    }

    /** @return 在前景样式上叠加背景 token 的样式。 */
    public Style withBackground(Style foreground, String backgroundToken) {
        return enabled ? foreground.withBg(color(backgroundToken)) : Style.PLAIN;
    }

    /** @return 新的已启用/禁用颜色快照，revision 加一。 */
    public Theme withEnabled(boolean value) {
        return value == enabled ? this : new Theme(name, appearance, colors, value, revision + 1);
    }

    /** 由色板构造主题；enabled 由终端能力/NO_COLOR 决定。 */
    public static Theme from(ThemePalette.Definition definition, boolean enabled, long revision) {
        return new Theme(definition.name(), definition.appearance(), definition.colors(), enabled, revision);
    }
}
