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
    }

    @Test void configRejectsInvalidValuesWithoutRewriting(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"version\":1,\"tui\":{\"mode\":\"fullscreen\"}}");
        var config = new JadeConfigFile(file);
        assertThrows(IllegalArgumentException.class, () -> config.saveTui(Map.of("unknownField", true)));
        assertThrows(java.io.IOException.class, () -> config.saveTui(Map.of("editorPaddingX", 9)));
        assertEquals("fullscreen", config.load().tui().mode());
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
