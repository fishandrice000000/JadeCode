package com.jadecode.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 从已退役的 TUI 设置测试中迁移的配置写者契约。
 *
 * <p>这些断言不依赖旧 Java 渲染器：它们直接验证 {@link JadeConfigFile} 的白名单 patch、
 * 未知字段保留、非法值拒绝以及 auto/自定义滚轮值的持久化。Node 设置页只发送 patch，
 * Java 仍是唯一配置写者。</p>
 */
class JadeConfigTuiTest {
    @Test
    void defaultsToSystemThemeAndKeepsUnknownFieldsAndThemeIds(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"version\":1,\"tui\":{\"theme\":\"dark\",\"futureField\":true},\"custom\":{\"x\":1}}");
        JadeConfigFile config = new JadeConfigFile(file);
        assertEquals("dark", config.load().tui().theme());
        config.saveTui(Map.of("mode", "regular", "thinkingVisible", false));
        JadeSettings reloaded = config.load();
        assertEquals("regular", reloaded.tui().mode());
        assertFalse(reloaded.tui().thinkingVisible());
        String raw = Files.readString(file);
        assertTrue(raw.contains("futureField"), "unknown tui fields must be preserved");
        assertTrue(raw.contains("\"custom\""), "unknown root fields must be preserved");
        assertEquals("system", JadeSettings.defaults().tui().theme());
        config.saveTui(Map.of("theme", "system"));
        assertEquals("system", config.load().tui().theme());
        config.saveTui(Map.of("theme", "auto"));
        assertEquals("auto", config.load().tui().theme());
        config.saveTui(Map.of("theme", "solarized"));
        assertEquals("solarized", config.load().tui().theme());
    }

    @Test
    void rejectsInvalidPatchesWithoutRewritingTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"version\":1,\"tui\":{\"mode\":\"fullscreen\"}}");
        JadeConfigFile config = new JadeConfigFile(file);
        assertThrows(IllegalArgumentException.class, () -> config.saveTui(Map.of("unknownField", true)));
        assertThrows(java.io.IOException.class, () -> config.saveTui(Map.of("editorPaddingX", 9)));
        assertEquals("fullscreen", config.load().tui().mode(), "a rejected patch must not rewrite valid settings");
    }

    @Test
    void persistsWheelAutoSentinelAndCustomValue(@TempDir Path dir) throws Exception {
        JadeConfigFile config = new JadeConfigFile(dir.resolve("config.json"));
        config.saveTui(Map.of("fullscreenWheelScrollLines", 0));
        assertEquals(0, config.load().tui().fullscreenWheelScrollLines());
        config.saveTui(Map.of("fullscreenWheelScrollLines", 7));
        assertEquals(7, config.load().tui().fullscreenWheelScrollLines());
    }
}
