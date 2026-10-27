package com.jadecode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 配置只读取与校验，绝不能因声明 command 就启动程序。 */
class McpSettingsTest {
    @TempDir Path directory;

    /** 缺省配置和首次启动默认提供 Fetch，配置读取不要求本机已经安装该程序。 */
    @Test void optionalConfigIsPortableAndPreserved() throws Exception {
        Path file = directory.resolve("config.json");
        assertTrue(new JadeConfigFile(file).load().mcp().servers().get("fetch").enabled());
        Files.writeString(file, "{\"tui\":{\"theme\":\"dark\"}}");
        assertEquals(JadeSettings.Mcp.defaults(), new JadeConfigFile(file).load().mcp());
        assertTrue(JadeConfigFile.template().path("mcp").path("servers").path("fetch").path("enabled").booleanValue());
        // 复制仓库样例到临时目录再加载，避免配置迁移逻辑改写仓库样例。
        Path sample = directory.resolve("sample.json"); Files.copy(Path.of("config.example.json"), sample);
        Path command = Path.of(new JadeConfigFile(sample).load().mcp().servers().get("fetch").command());
        assertTrue(command.isAbsolute(), "默认 Fetch 命令不能依赖项目 cwd");
        assertTrue(command.startsWith(Path.of(System.getProperty("user.home"), ".jadecode", "mcp", "fetch")));

        Files.writeString(file, """
                {"mcp":{"servers":{"fetch":{"command":"custom-fetch/bin/python","args":["-m","mcp_server_fetch"],"readOnlyTools":["fetch"]}}}}
                """);
        var config = new JadeConfigFile(file);
        assertTrue(config.load().mcp().servers().get("fetch").enabled());
        config.saveTheme("dark");
        assertEquals("custom-fetch/bin/python", config.load().mcp().servers().get("fetch").command());
        Files.writeString(file, "{\"mcp\":{\"servers\":{\"fetch\":{\"command\":\"python\",\"args\":[1]}}}}");
        assertThrows(java.io.IOException.class, config::load);
    }

    /** 默认开放不等于强制开放；用户的关闭及替换选择不能被默认 Fetch 覆盖。 */
    @Test void explicitServerTableOverridesDefaultFetch() throws Exception {
        Path file = directory.resolve("config.json");
        var config = new JadeConfigFile(file);
        Files.writeString(file, "{\"mcp\":{\"servers\":{}}}");
        assertTrue(config.load().mcp().servers().isEmpty());
        Files.writeString(file, """
                {"mcp":{"servers":{"fetch":{"enabled":false,"command":"python","args":["-m","mcp_server_fetch"]}}}}
                """);
        assertFalse(config.load().mcp().servers().get("fetch").enabled());
        config.saveTheme("light");
        assertFalse(config.load().mcp().servers().get("fetch").enabled());
        Files.writeString(file, "{\"mcp\":null}");
        assertThrows(java.io.IOException.class, config::load);
    }
}
