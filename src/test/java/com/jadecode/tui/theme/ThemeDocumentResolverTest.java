package com.jadecode.tui.theme;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.text.Color;

/**
 * R9 N02：主题文档/解析器的图解析、显式/默认/auto、环境绑定与错误语义。
 *
 * <p>这里不镜像每个 RGB 值，只验证“文件写了什么 → 最终角色来自谁”的契约；具体资产色值
 * 由 {@link TuiThemeTest} 与主题资产 hash 记录负责。</p>
 */
class ThemeDocumentResolverTest {
    /** 一份最小 v2 探针主题：两个变体、可辨色值、auto 面板。 */
    private static final String V2_MINIMAL = """
            {"schemaVersion":2,"name":"probe","variants":{
              "dark":{"vars":{"foreground":"#E0E0E0","background":"#101010","primary":"#112233",
                      "accentVar":"#445566"},
                "colors":{"text":"foreground","accent":"accentVar","panelBg":"auto",
                          "mdHeading":"primary","brandBannerBody":"primary","brandBannerShadow":"#010203"}},
              "light":{"vars":{"foreground":"#202020","background":"#FAFAFA","primary":"#778899"},
                "colors":{"text":"foreground","accent":"primary","panelBg":"auto"}}}}
            """;

    @Test void builtinResourceHoldsBothVariantsAndFullRoleSet() {
        var document = ThemePalette.builtinDocument();
        assertEquals(ThemeDocument.VERSION_2, document.schemaVersion());
        assertEquals("jadecode", document.name());
        assertEquals(List.of("dark", "light"), document.variantNames());
        var dark = document.variant("dark");
        var light = document.variant("light");
        assertEquals(75, dark.colors().size(), "asset covers the 61 old roles plus R9 additions");
        assertEquals(75, light.colors().size());
        assertTrue(dark.colors().containsKey("brandBannerShadow"));
        assertEquals(0.10, dark.panelBgMix(), 1e-9);
    }

    @Test void builtinResolutionKeepsBlueHierarchyOnEveryVariant() {
        var dark = ThemePalette.builtin("dark");
        var light = ThemePalette.builtin("light");
        assertEquals(Color.rgb(0x6C, 0xB6, 0xD4), dark.colors().get("brandPrimary"));
        assertEquals(Color.rgb(0x47, 0x7E, 0x99), dark.colors().get("brandBannerShadow"));
        assertEquals(Color.rgb(0x0B, 0x6F, 0x91), light.colors().get("brandPrimary"));
        assertEquals(Color.rgb(0x8B, 0xB6, 0xC7), light.colors().get("brandBannerShadow"));
        assertEquals(dark.colors().get("brandPrimary"), dark.colors().get("mdHeading"));
        assertEquals(dark.colors().get("brandPrimary"), dark.colors().get("mdCode"));
        assertEquals(dark.colors().get("brandPrimary"), dark.colors().get("mdListBullet"));
        assertEquals(dark.colors().get("brandPrimary"), dark.colors().get("syntaxKeyword"));
        assertEquals(dark.colors().get("brandAccent"), dark.colors().get("syntaxFunction"));
        // 成功/警告/失败仍保持绿/黄/红语义，不从通用角色漂移。
        assertEquals(dark.colors().get("success"), dark.colors().get("toolDiffAdded"));
        assertEquals(dark.colors().get("error"), dark.colors().get("approvalDeny"));
        assertFalse(dark.colors().get("text").isDefault());
    }

    @Test void version2FileSelectsExplicitVariantAndDerivesPanelFromItsOwnBackground() {
        var document = ThemeDocument.parse(V2_MINIMAL);
        var dark = ThemeResolver.resolve(document, "dark", false,
                new ThemeResolver.TerminalFacts(true, null, null), "probe");
        var light = ThemeResolver.resolve(document, "light", false,
                new ThemeResolver.TerminalFacts(false, null, null), "probe");
        assertEquals(Color.rgb(0x11, 0x22, 0x33), dark.colors().get("mdHeading"));
        assertEquals(Color.rgb(0x44, 0x55, 0x66), dark.colors().get("accent"));
        assertEquals(Color.rgb(0x77, 0x88, 0x99), light.colors().get("accent"));
        assertEquals(ThemePalette.derivePanelBg(Color.rgb(0x10, 0x10, 0x10), true, 0.10),
                dark.colors().get("panelBg"));
        assertEquals(ThemePalette.derivePanelBg(Color.rgb(0xFA, 0xFA, 0xFA), false, 0.10),
                light.colors().get("panelBg"));
        // 缺失角色走同外观内置默认，但显式品牌色不被覆盖。
        assertEquals(Color.rgb(0x01, 0x02, 0x03), dark.colors().get("brandBannerShadow"));
        assertNotNull(dark.colors().get("syntaxType"));
    }

    @Test void version2RejectsBadVersionShapeAndTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> ThemeDocument.parse("{\"schemaVersion\":3,\"variants\":{}}"));
        assertThrows(IllegalArgumentException.class,
                () -> ThemeDocument.parse("{\"schemaVersion\":2,\"variants\":{\"dark\":{\"colors\":{}}}}"),
                "missing light variant must fail");
        assertThrows(IllegalArgumentException.class,
                () -> ThemeDocument.parse("{\"name\":\"x\",\"variants\":{\"dark\":{\"colors\":{}}}}"),
                "variants without schemaVersion must not silently fall back to v1");
        assertThrows(IllegalArgumentException.class,
                () -> ThemeDocument.parse("{\"schemaVersion\":2,\"variants\":{\"dark\":{\"colors\":{\"text\":true}},"
                        + "\"light\":{\"colors\":{}}}}"), "boolean color value must fail");
        var noRoles = ThemeDocument.parse("{\"schemaVersion\":2,\"variants\":{\"dark\":{\"colors\":{}},"
                + "\"light\":{\"colors\":{}}}}");
        assertThrows(IllegalArgumentException.class, () -> ThemeResolver.resolve(noRoles, "dark", false,
                new ThemeResolver.TerminalFacts(true, null, null), "probe"), "v2 variants still require text/accent");
        assertThrows(IllegalArgumentException.class,
                () -> ThemeDocument.parse("{\"schemaVersion\":2,\"variants\":{\"dark\":{\"colors\":{"
                        + "\"text\":\"#fff\",\"accent\":\"#fff\"},\"derivations\":{\"panelBgMix\":2}},"
                        + "\"light\":{\"colors\":{\"text\":\"#000\",\"accent\":\"#000\"}}}}"));
        assertThrows(IllegalArgumentException.class, () -> ThemeDocument.parse("not json"));
    }

    @Test void variableReferencesDetectUnknownAndCycles() {
        String unknown = "{\"name\":\"x\",\"colors\":{\"text\":\"nope\",\"accent\":\"#fff\"}}";
        var unknownError = assertThrows(IllegalArgumentException.class, () -> ThemePalette.parse(unknown));
        assertTrue(unknownError.getMessage().contains("Theme variable does not exist: nope"));

        String cycle = "{\"name\":\"x\",\"vars\":{\"a\":\"b\",\"b\":\"a\"},"
                + "\"colors\":{\"text\":\"a\",\"accent\":\"#fff\"}}";
        var cycleError = assertThrows(IllegalArgumentException.class, () -> ThemePalette.parse(cycle));
        assertTrue(cycleError.getMessage().contains("Cycle in theme variable references"));

        String autoVar = "{\"name\":\"x\",\"vars\":{\"a\":\"auto\"},"
                + "\"colors\":{\"text\":\"a\",\"accent\":\"#fff\"}}";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ThemePalette.parse(autoVar))
                .getMessage().contains("cannot use auto"));
    }

    @Test void autoIsOnlyAcceptedOnTheFourDerivedRoles() {
        String wrong = "{\"name\":\"x\",\"colors\":{\"text\":\"auto\",\"accent\":\"#fff\"}}";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ThemePalette.parse(wrong))
                .getMessage().contains("Theme color text cannot use auto"));
        String ok = "{\"name\":\"x\",\"colors\":{\"text\":\"#fff\",\"accent\":\"#fff\","
                + "\"panelBg\":\"auto\",\"approvalOnceBg\":\"#123456\",\"approvalOnceFg\":\"auto\"}}";
        var definition = ThemePalette.parse(ok);
        assertNotNull(definition.colors().get("panelBg"));
        assertEquals(Color.rgb(255, 255, 255), definition.colors().get("approvalOnceFg"),
                "dark button background gets a white contrasting foreground");
        assertFalse(definition.panelBgExplicit(), "auto panel is derived, not an explicit value");
    }

    @Test void version1MinimalThemeKeepsOldSemanticsWithSafeFallbacks() {
        var custom = ThemePalette.parse("{\"name\":\"old\",\"appearance\":\"dark\","
                + "\"colors\":{\"text\":\"#ffffff\",\"accent\":\"#336699\"}}");
        assertEquals(Color.rgb(0x33, 0x66, 0x99), custom.colors().get("accent"));
        // S85/S94 兼容：旧主题没有新品牌角色时沿用自身 accent/warning/border，
        // 字标轮廓退回字身，保持原单色意图。
        assertEquals(custom.colors().get("accent"), custom.colors().get("brandPrimary"));
        assertEquals(custom.colors().get("warning"), custom.colors().get("brandAccent"));
        assertEquals(custom.colors().get("border"), custom.colors().get("brandFrame"));
        assertEquals(custom.colors().get("brandPrimary"), custom.colors().get("brandBannerBody"));
        assertEquals(custom.colors().get("brandBannerBody"), custom.colors().get("brandBannerShadow"));
        assertEquals(custom.colors().get("accent"), custom.colors().get("mdHeading"),
                "old custom themes keep their own heading accent");
        // 审批缺省使用自身成功/警告/失败语义；前景由对比算法派生。
        assertEquals(custom.colors().get("success"), custom.colors().get("approvalOnceBg"));
        assertEquals(custom.colors().get("warning"), custom.colors().get("approvalSimilarBg"));
        assertEquals(custom.colors().get("error"), custom.colors().get("approvalDenyBg"));
        assertFalse(custom.colors().get("approvalOnceFg").isDefault());
        assertFalse(custom.panelBgExplicit());
    }

    @Test void explicitEmptyColorIsPreservedInsteadOfBeingOverwritten() {
        var custom = ThemePalette.parse("{\"name\":\"x\",\"appearance\":\"dark\",\"colors\":{"
                + "\"text\":\"#ffffff\",\"accent\":\"#112233\",\"surfaceBg\":\"\"}}");
        assertTrue(custom.colors().get("surfaceBg").isDefault(),
                "explicit empty color means Color.DEFAULT, not 'missing'");
    }

    @Test void systemBindsTerminalTextAndBackgroundButLeavesAccentsInTheTheme() {
        var document = ThemePalette.builtinDocument();
        Color fg = Color.rgb(210, 220, 230);
        Color bg = Color.rgb(12, 16, 20);
        var cold = ThemeResolver.resolve(document, "dark", true,
                new ThemeResolver.TerminalFacts(true, fg, bg), "system");
        var warm = ThemeResolver.resolve(document, "dark", true,
                new ThemeResolver.TerminalFacts(true, Color.rgb(250, 240, 200), Color.rgb(30, 0, 0)), "system");
        assertEquals(fg, cold.colors().get("text"));
        assertEquals(bg, cold.colors().get("surfaceBg"));
        // ANSI/背景变化不能改变主题强调色：这是 S92 的核心。
        assertEquals(cold.colors().get("accent"), warm.colors().get("accent"));
        assertEquals(cold.colors().get("brandPrimary"), warm.colors().get("brandPrimary"));
        assertEquals(cold.colors().get("syntaxKeyword"), warm.colors().get("syntaxKeyword"));
        // 普通文字角色也随绑定更新，而派生面板仍按真实背景重算。
        assertNotEquals(cold.colors().get("text"), warm.colors().get("text"));
        assertNotEquals(cold.colors().get("panelBg"), warm.colors().get("panelBg"));
        // 未报告前景时保持主题自身 foreground，不伪造“已取得颜色”。
        var unreported = ThemeResolver.resolve(document, "light", true,
                new ThemeResolver.TerminalFacts(false, null, null), "system");
        assertFalse(unreported.colors().get("text").isDefault());
        assertEquals(ThemePalette.builtin("light").colors().get("text"), unreported.colors().get("text"));
    }

    @Test void systemUsesDarkOrLightVariantByTerminalAppearance() {
        var document = ThemePalette.builtinDocument();
        var dark = ThemeResolver.resolve(document, "dark", true,
                new ThemeResolver.TerminalFacts(true, Color.rgb(230, 230, 230), Color.rgb(0, 0, 0)), "system");
        var light = ThemeResolver.resolve(document, "light", true,
                new ThemeResolver.TerminalFacts(false, Color.rgb(20, 20, 20), Color.rgb(255, 255, 255)), "system");
        assertEquals("dark", dark.appearance());
        assertEquals("light", light.appearance());
        assertNotEquals(dark.colors().get("brandPrimary"), light.colors().get("brandPrimary"));
        // 兼容入口接收 ANSI 列表但不参与取色，避免调用方误以为强调色仍来自终端色槽。
        var legacy = ThemePalette.system(true, Color.rgb(230, 230, 230), Color.rgb(0, 0, 0),
                List.of(Color.rgb(255, 0, 0), Color.rgb(0, 255, 0)));
        assertEquals(dark.colors().get("brandPrimary"), legacy.colors().get("brandPrimary"));
        assertEquals("system", legacy.name());
    }
}
