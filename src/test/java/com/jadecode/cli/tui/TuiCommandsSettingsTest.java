package com.jadecode.cli.tui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.config.JadeConfigFile;
import com.jadecode.config.JadeSettings;
import com.jadecode.tui.input.KeyBindings;

/** 命令目录/设置菜单/配置 patch/键表的边界测试。 */
class TuiCommandsSettingsTest {
    @Test void catalogUsesCanonicalNamesAndDropsOldAliases() {
        var catalog = new CommandCatalog(Path.of("."));
        for (String name : List.of("/settings", "/name", "/session", "/hotkeys", "/copy", "/new",
                "/resume", "/quit", "/model", "/thinking", "/permission", "/mcp"))
            assertNotNull(catalog.find(name), name);
        for (String old : List.of("/help", "/exit", "/usage", "/theme", "/rename", "/compact", "/tree",
                "/fork", "/clone", "/export", "/import", "/share", "/trust", "/login", "/logout"))
            assertNull(catalog.find(old), old);
        assertFalse(catalog.helpLines().isEmpty());
        catalog.close();
    }

    @Test void settingsCycleBuildsWhitelistPatchAndThemeChoices() {
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), null);
        var patch = menu.cycle("mode");
        assertTrue(patch.isEmpty(), "mode is changed through the explicit mode action");
        var scrollbar = menu.cycle("fullscreenScrollbar");
        assertEquals("always", scrollbar.get("fullscreenScrollbar"));
        menu.reload(JadeSettings.defaults().tui());
        assertTrue(menu.cycle("showHardwareCursor").get("showHardwareCursor") instanceof Boolean);
        assertTrue(menu.themeChoices().contains("system"));
        assertTrue(menu.themeChoices().contains("auto"));
    }

    @Test void wheelSettingCyclesThroughPiCandidatesAndPersistsAutoSentinel(@TempDir Path dir) throws Exception {
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes"));
        var seen = new java.util.ArrayList<Integer>();
        for (int i = 0; i < 6; i++) seen.add((Integer) menu.cycle("fullscreenWheelScrollLines")
                .get("fullscreenWheelScrollLines"));
        assertEquals(List.of(1, 2, 3, 5, 10, 0), seen);
        assertEquals("auto", menu.items().stream().filter(item -> item.id().equals("fullscreenWheelScrollLines"))
                .findFirst().orElseThrow().value());

        Path file = dir.resolve("config.json");
        var config = new JadeConfigFile(file);
        config.saveTui(Map.of("fullscreenWheelScrollLines", 0));
        assertEquals(0, config.load().tui().fullscreenWheelScrollLines());
        config.saveTui(Map.of("fullscreenWheelScrollLines", 7));
        assertEquals(7, config.load().tui().fullscreenWheelScrollLines());
        var custom = new SettingsMenu(config.load().tui(), dir.resolve("themes"));
        assertEquals(10, custom.cycle("fullscreenWheelScrollLines").get("fullscreenWheelScrollLines"));
        assertEquals(0, custom.cycle("fullscreenWheelScrollLines").get("fullscreenWheelScrollLines"),
                "custom candidate list must wrap back to auto");
    }

    @Test void configDefaultsToSystemThemeButKeepsExplicitDarkAndUnknownFields(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"version\":1,\"tui\":{\"theme\":\"dark\",\"futureField\":true},\"custom\":{\"x\":1}}");
        var config = new JadeConfigFile(file);
        assertEquals("dark", config.load().tui().theme());
        config.saveTui(Map.of("mode", "regular", "thinkingVisible", false));
        var reloaded = config.load();
        assertEquals("regular", reloaded.tui().mode());
        assertFalse(reloaded.tui().thinkingVisible());
        String raw = Files.readString(file);
        assertTrue(raw.contains("futureField"));
        assertTrue(raw.contains("\"custom\""));
        assertEquals("system", JadeSettings.defaults().tui().theme());
        // C24：默认 system/auto 与自定义主题标识也必须能保存；旧校验只允许 dark/light。
        config.saveTui(Map.of("theme", "system"));
        assertEquals("system", config.load().tui().theme());
        config.saveTui(Map.of("theme", "auto"));
        assertEquals("auto", config.load().tui().theme());
        config.saveTui(Map.of("theme", "solarized"));
        assertEquals("solarized", config.load().tui().theme());
    }

    @Test void configRejectsInvalidValuesWithoutRewriting(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"version\":1,\"tui\":{\"mode\":\"fullscreen\"}}");
        var config = new JadeConfigFile(file);
        assertThrows(IllegalArgumentException.class, () -> config.saveTui(Map.of("unknownField", true)));
        assertThrows(java.io.IOException.class, () -> config.saveTui(Map.of("editorPaddingX", 9)));
        assertEquals("fullscreen", config.load().tui().mode());
    }

    @Test void selectedCandidateBackgroundCoversTextOnlyNotTrailingPadding() {
        var theme = com.jadecode.tui.theme.Theme.from(
                com.jadecode.tui.theme.ThemePalette.builtin("dark"), true, 1);
        var context = new com.jadecode.tui.core.RenderContext(120, 10, theme);
        var list = new com.jadecode.tui.components.SelectList(List.of(
                new com.jadecode.tui.components.SelectList.Item("/quit", "/quit", "Close the session")), true);
        var line = list.render(120, 3, context).getFirst();
        var selectedBg = theme.color("selectedBg");
        int column = 0;
        boolean textHasBackground = false;
        for (var span : line.spans()) {
            boolean background = span.style().bg().equals(selectedBg);
            if (column < 30 && background) textHasBackground = true;
            if (column >= 40) assertFalse(background, "trailing padding must not be painted: " + line.text());
            column += com.jadecode.tui.text.DisplayWidth.width(span.text());
        }
        assertTrue(textHasBackground);
        assertEquals(120, line.width());
    }

    @Test void keyBindingsHideUnsupportedActionsAndReportConflicts() {
        var bindings = new KeyBindings(Map.of("app.tools.expand", List.of("ctrl+t")));
        assertTrue(bindings.all().stream().anyMatch(a -> a.id().equals("app.model.select") && !a.visible()));
        assertFalse(bindings.visible().stream().anyMatch(a -> a.id().equals("app.session.tree")));
        assertTrue(bindings.conflicts().stream().anyMatch(value -> value.contains("app.tools.expand")));
        assertTrue(bindings.chords("app.model.select").isEmpty());
        assertFalse(bindings.action("app.permission.menu").defaultChords().stream().findAny().isPresent());
    }
}
