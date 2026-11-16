package com.jadecode.config;

import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 当前配置严格读取，拒绝旧压缩字段且不改写原文件；全部使用临时目录。 */
class ProductionCompactionConfigTest {
    @TempDir
    Path dir;

    /** 缺省文件使用用户批准的 Token 参数，auto 以 null 表达，不能猜成固定字符上限。 */
    @Test
    void defaultsUseProductionTokenValues() throws Exception {
        var settings = new JadeConfigFile(dir.resolve("config.json")).load().contextCompaction();
        assertEquals(16384, settings.reserveTokens());
        assertEquals(20000, settings.keepRecentTokens());
        assertEquals(32768, settings.summaryMaxOutputTokens());
        assertNull(settings.summaryMaxInputTokens());
        assertEquals(50000, settings.largeResultCharLimit());
        assertEquals(2000, settings.largeResultPreviewChars());
    }

    /** 旧压缩段明确拒绝，失败不迁移、不备份、不修改原文。 */
    @Test
    void rejectsOldLimitsWithoutWritingAnyFiles() throws Exception {
        Path path = dir.resolve("config.json");
        String old = """
                {"version":1,"custom":{"x":7},"tui":{"theme":"solarized"},"compaction":{
                "autoSummaryEnabled":false,"snipEnabled":true,"microEnabled":true,
                "limits":{"contextCharLimit":300000,"summaryMaxOutputTokens":8000}}}
                """;
        Files.writeString(path, old);
        var config = new JadeConfigFile(path);
        assertThrows(IOException.class, config::load);
        assertEquals(old, Files.readString(path));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "读取失败不能创建备份或迁移文件");
        }
    }

    /** 负预算必须在写盘前失败；用户显式填写的新额度不能被默认值覆盖。 */
    @Test
    void invalidConfigurationIsNotRewrittenAndExplicitNewBudgetIsPreserved() throws Exception {
        Path path = dir.resolve("config.json");
        String invalid = "{\"version\":1,\"compaction\":{\"snipEnabled\":true,\"reserveTokens\":-1}}";
        Files.writeString(path, invalid);
        assertThrows(IOException.class, () -> new JadeConfigFile(path).load());
        assertEquals(invalid, Files.readString(path));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
        Files.writeString(path,
                "{\"version\":1,\"compaction\":{\"summaryMaxInputTokens\":900000,\"summaryMaxOutputTokens\":65536}}");
        var settings = new JadeConfigFile(path).load().contextCompaction();
        assertEquals(900000, settings.summaryMaxInputTokens());
        assertEquals(65536, settings.summaryMaxOutputTokens());
    }

    /** 只发现旧 tui.json 时采用当前默认值，不读取或修改旧主题文件。 */
    @Test
    void oldThemeFileIsIgnoredAndMissingVersionIsRejected() throws Exception {
        Path old = dir.resolve("tui.json"), current = dir.resolve("config.json");
        Files.writeString(old, "{\"theme\":\"light\"}");
        assertEquals("system", new JadeConfigFile(current).load().tui().theme());
        assertFalse(Files.exists(current));
        String partial = "{\"tui\":{\"theme\":\"dark\"}}";
        Files.writeString(current, partial);
        assertThrows(IOException.class, () -> new JadeConfigFile(current).load());
        assertEquals(partial, Files.readString(current));
        assertEquals("{\"theme\":\"light\"}", Files.readString(old));
    }
}
