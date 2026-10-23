package com.jadecode.tui.theme;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.cli.tui.UsageFormatter;
import com.jadecode.session.ContextUsage;
import com.jadecode.session.SessionUsage;
import com.jadecode.tui.text.Color;
import com.jadecode.tui.text.Style;

/** 主题色板/system 派生/NO_COLOR 与数字格式的单元测试。 */
class TuiThemeTest {
    @Test void builtinThemesParseAndResolveVars() {
        var dark = ThemePalette.builtin("dark");
        assertEquals("dark", dark.name());
        assertEquals("dark", dark.appearance());
        assertTrue(dark.colors().size() > 30);
        assertNotEquals(Color.DEFAULT, dark.colors().get("text"));
        var light = ThemePalette.builtin("light");
        assertEquals("light", light.appearance());
    }

    @Test void okhslRoundTripIsStableWithinRounding() {
        Color color = ThemePalette.okhsl(295, 0.5, 0.67);
        assertTrue(color.kind() == Color.Kind.RGB);
        double[] hsl = ThemePalette.rgbToOkhsl(color.red(), color.green(), color.blue());
        assertEquals(295, hsl[0], 2.0);
        assertEquals(0.5, hsl[1], 0.08);
        assertEquals(0.67, hsl[2], 0.03);
    }

    @Test void systemThemeUsesTerminalPaletteAndFallsBack() {
        var ansi = new java.util.ArrayList<Color>();
        for (int i = 0; i < 16; i++) ansi.add(ThemePalette.indexedApprox(i));
        var system = ThemePalette.system(true, Color.rgb(220, 220, 220), Color.rgb(10, 10, 10), ansi);
        assertEquals("system", system.name());
        assertEquals("dark", system.appearance());
        assertTrue(ThemePalette.lightness(system.colors().get("text")) > 0.7);
        var fallback = ThemePalette.system(false, null, null, List.of());
        assertEquals("light", fallback.appearance());
    }


    @Test void builtinThemesCarryJadeBrandTokensAndSemanticMappings() {
        var dark = ThemePalette.builtin("dark");
        assertEquals(Color.rgb(0x6C, 0xB6, 0xD4), dark.colors().get("brandPrimary"));
        assertEquals(Color.rgb(0xF0, 0xA3, 0x4A), dark.colors().get("brandAccent"));
        assertEquals(Color.rgb(0x91, 0xA9, 0xB5), dark.colors().get("brandFrame"));
        assertEquals(dark.colors().get("brandPrimary"), dark.colors().get("accent"));
        assertEquals(dark.colors().get("brandPrimary"), dark.colors().get("borderAccent"));
        assertEquals(dark.colors().get("brandPrimary"), dark.colors().get("mdLink"));
        // S91/S92 迁移：统一主题资产把 Markdown 标题/列表符号映射到 primary 蓝色层次，
        // 不再沿用第八轮“标题等于 brandAccent 橙色”的旧外观断言。
        assertEquals(dark.colors().get("brandPrimary"), dark.colors().get("mdHeading"));
        assertEquals(dark.colors().get("brandPrimary"), dark.colors().get("mdListBullet"));
        assertEquals(dark.colors().get("brandAccent"), dark.colors().get("customMessageLabel"));
        assertEquals(Color.rgb(0x24, 0x3B, 0x47), dark.colors().get("selectedBg"));

        var light = ThemePalette.builtin("light");
        assertEquals(Color.rgb(0x0B, 0x6F, 0x91), light.colors().get("brandPrimary"));
        assertEquals(Color.rgb(0xA4, 0x5A, 0x0A), light.colors().get("brandAccent"));
        assertEquals(Color.rgb(0x64, 0x7F, 0x8A), light.colors().get("brandFrame"));
        assertEquals(Color.rgb(0xDD, 0xEE, 0xF5), light.colors().get("selectedBg"));
    }

    @Test void systemThemeUsesBrandSemanticsButKeepsTerminalTextAndBackground() {
        var ansi = new java.util.ArrayList<Color>();
        for (int i = 0; i < 16; i++) ansi.add(ThemePalette.indexedApprox(i));
        var system = ThemePalette.system(true, Color.rgb(210, 220, 230), Color.rgb(12, 16, 20), ansi);
        assertEquals(Color.rgb(210, 220, 230), system.colors().get("text"), "system keeps the real terminal foreground");
        assertNotNull(system.colors().get("brandPrimary"));
        assertEquals(system.colors().get("brandPrimary"), system.colors().get("accent"));
        // S92：system 只绑定终端前景/背景，标题等强调色来自统一内置主题的 primary。
        assertEquals(system.colors().get("brandPrimary"), system.colors().get("mdHeading"));
        assertFalse(system.colors().get("brandFrame").equals(system.colors().get("text")));
    }

    @Test void explicitCustomBrandTokensWinOverFallbacks() {
        var custom = ThemePalette.parse("{\"name\":\"branded\",\"colors\":{"
                + "\"text\":\"#ffffff\",\"accent\":\"#ff00ff\","
                + "\"brandPrimary\":\"#112233\",\"brandAccent\":\"#445566\",\"brandFrame\":\"#778899\"}}");
        assertEquals(Color.rgb(0x11, 0x22, 0x33), custom.colors().get("brandPrimary"));
        assertEquals(Color.rgb(0x44, 0x55, 0x66), custom.colors().get("brandAccent"));
        assertEquals(Color.rgb(0x77, 0x88, 0x99), custom.colors().get("brandFrame"));
        assertEquals(Color.rgb(0xff, 0x00, 0xff), custom.colors().get("accent"),
                "explicit custom accent must keep its own priority");
    }

    @Test void minimalCustomThemeFallsBackBrandTokensWithoutNulls() {
        var custom = ThemePalette.parse("{\"name\":\"old\",\"colors\":{\"text\":\"#ffffff\",\"accent\":\"#336699\"}}");
        assertNotNull(custom.colors().get("brandPrimary"));
        assertEquals(custom.colors().get("accent"), custom.colors().get("brandPrimary"));
        assertNotNull(custom.colors().get("brandAccent"));
        assertNotNull(custom.colors().get("brandFrame"));
        assertEquals(custom.colors().get("border"), custom.colors().get("brandFrame"));
        assertNotNull(Theme.from(custom, true, 1).style("brandPrimary").fg());
    }

    @Test void noColorThemeReturnsPlainStylesButKeepsStructure() {
        var definition = ThemePalette.builtin("dark");
        Theme theme = Theme.from(definition, false, 1);
        assertTrue(theme.style("error").isPlain());
        assertNotEquals(Color.DEFAULT, theme.color("error"));
        Theme colored = Theme.from(definition, true, 2);
        assertFalse(colored.style("error").isPlain());
        assertTrue(colored.style("mdHeading").bold());
        assertTrue(colored.style("mdLink").underline());
        assertFalse(colored.style("text").isPlain());
    }

    @Test void tokenFormattingUsesCapitalKAndFixedSegments() {
        assertEquals("999", UsageFormatter.tokens(999));
        assertEquals("1.0K", UsageFormatter.tokens(1000));
        assertEquals("10.0K", UsageFormatter.tokens(9999));
        assertEquals("10K", UsageFormatter.tokens(10000));
        assertEquals("100K", UsageFormatter.tokens(100000));
        assertEquals("1.0M", UsageFormatter.tokens(1_000_000));
        assertEquals("10M", UsageFormatter.tokens(10_000_000));
        assertEquals("1000K", UsageFormatter.tokens(999_999));
    }

    @Test void durationsAlwaysKeepOneDecimalUnderOneMinute() {
        assertEquals("29.0s", UsageFormatter.turnDuration(29_000_000_000L));
        assertEquals("0.1s", UsageFormatter.turnDuration(50_000_000L));
        assertEquals("1m00s", UsageFormatter.turnDuration(60_000_000_000L));
        assertEquals("12ms", UsageFormatter.toolDuration(12_000_000L));
    }

    @Test void contextShowsEstimatedOccupancyNotRemainingPercent() {
        assertEquals("12.3%/32K", UsageFormatter.context(new ContextUsage(3936, 32000)));
        assertEquals("", UsageFormatter.context(null));
        var totals = new SessionUsage.Totals(1000, 20, 800, 100, 3, 1);
        assertEquals("↑1.0K ↓20", UsageFormatter.tokenCounts(totals));
        assertEquals("R800 W100", UsageFormatter.cacheCounts(totals));
        assertTrue(UsageFormatter.cacheHit(totals).startsWith("CH"));
        assertEquals("missing 1", UsageFormatter.missing(totals));
    }

    @Test void escapeTimeoutAndSettingsDefaultsAreApprovedValues() {
        var tui = com.jadecode.config.JadeSettings.defaults().tui();
        assertEquals("system", tui.theme());
        assertEquals("fullscreen", tui.mode());
        assertEquals("transcript", tui.fullscreenExitOutput());
        assertTrue(tui.fullscreenCopyOnSelect());
        assertEquals(0, tui.escapeTimeoutMillis());
    }
}
