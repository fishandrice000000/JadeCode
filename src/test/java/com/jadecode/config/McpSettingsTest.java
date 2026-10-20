package com.jadecode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 配置只读取与校验，绝不能因声明 command 就启动程序。 */
class McpSettingsTest {
    @TempDir Path directory;

    /** 缺省禁用 MCP；配置程序可尚未安装，保存主题仍完整保留服务定义。 */
    @Test void optionalConfigIsPortableAndPreserved() throws Exception {
        Path file = directory.resolve("config.json");
        assertTrue(new JadeConfigFile(file).load().mcp().servers().isEmpty());
        Files.writeString(file, """
                {"mcp":{"servers":{"fetch":{"command":".jadecode/mcp/fetch/bin/python","args":["-m","mcp_server_fetch"],"readOnlyTools":["fetch"]}}}}
                """);
        var config = new JadeConfigFile(file);
        assertTrue(config.load().mcp().servers().get("fetch").enabled());
        config.saveTheme("dark");
        assertEquals(".jadecode/mcp/fetch/bin/python", config.load().mcp().servers().get("fetch").command());
        Files.writeString(file, "{\"mcp\":{\"servers\":{\"fetch\":{\"command\":\"python\",\"args\":[1]}}}}");
        assertThrows(java.io.IOException.class, config::load);
    }
}
