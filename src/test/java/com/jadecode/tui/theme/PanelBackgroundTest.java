package com.jadecode.tui.theme;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.tui.text.Color;

/** R6-03：panelBg 显式/派生来源、晚回复重算、取消回退与 NO_COLOR 契约。 */
class PanelBackgroundTest {
    @Test void builtinAndSystemThemesDerivePanelBgFromAssumedOrActualBackground() {
        var dark = new ThemeManager("dark", null, true, "light", "dark");
        assertTrue(dark.preview().colors().containsKey("panelBg"));
        Color assumed = ThemePalette.derivePanelBg(Color.rgb(0, 0, 0), true);
        assertEquals(assumed, dark.preview().color("panelBg"));
        // 终端晚回复真实背景后，派生的 panelBg 按新背景重算。
        dark.updateTerminalColors(true, Color.rgb(200, 200, 200), Color.rgb(10, 20, 30), List.of());
        assertEquals(ThemePalette.derivePanelBg(Color.rgb(10, 20, 30), true), dark.preview().color("panelBg"));
        dark.close();

        var light = new ThemeManager("light", null, true, "light", "dark");
        Color lightAssumed = ThemePalette.derivePanelBg(Color.rgb(255, 255, 255), false);
        assertEquals(lightAssumed, light.preview().color("panelBg"));
        light.close();
    }

    @Test void explicitCustomPanelBgWinsAndSurvivesLateReply(@TempDir Path dir) throws Exception {
        Path themes = dir.resolve("themes");
        Files.createDirectories(themes);
        Files.writeString(themes.resolve("custom.json"),
                "{\"name\":\"custom\",\"appearance\":\"dark\",\"colors\":{\"text\":\"#ffffff\",\"accent\":\"#ff00ff\",\"panelBg\":\"#123456\"}}");
        var manager = new ThemeManager("custom", themes, true, "light", "dark");
        assertEquals(Color.rgb(0x12, 0x34, 0x56), manager.preview().color("panelBg"));
        manager.updateTerminalColors(true, Color.rgb(200, 200, 200), Color.rgb(1, 2, 3), List.of());
        assertEquals(Color.rgb(0x12, 0x34, 0x56), manager.preview().color("panelBg"),
                "explicit panelBg must not be replaced by derived value");
        manager.close();
    }

    @Test void previewCancelRestoresCommittedPanelBgAndNoColorDisablesAttributes(@TempDir Path dir) {
        var manager = new ThemeManager("dark", null, true, "light", "dark");
        Color committed = manager.preview().color("panelBg");
        manager.beginPreview();
        assertEquals("", manager.preview("light"));
        assertNotEquals(committed, manager.preview().color("panelBg"));
        manager.cancelPreview();
        assertEquals(committed, manager.preview().color("panelBg"));
        manager.close();

        var theme = manager.preview();
        var disabled = new Theme(theme.name(), theme.appearance(), theme.colors(), false, theme.revision());
        assertTrue(disabled.background("panelBg").isPlain(), "NO_COLOR must not emit panel background");
    }
}
