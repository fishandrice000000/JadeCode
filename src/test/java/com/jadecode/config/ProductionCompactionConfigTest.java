package com.jadecode.config;

import com.jadecode.util.Json;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 升级只触碰压缩段，先校验和备份；全用临时配置，绝不读取机器认证。 */
class ProductionCompactionConfigTest {
    @TempDir Path dir;

    /** 缺省文件使用用户批准的 Token 参数，auto 以 null 表达，不能猜成固定字符上限。 */
    @Test void defaultsUseProductionTokenValues() throws Exception {
        var settings = new JadeConfigFile(dir.resolve("config.json")).load().contextCompaction();
        assertEquals(16384, settings.reserveTokens()); assertEquals(20000, settings.keepRecentTokens());
        assertEquals(32768, settings.summaryMaxOutputTokens()); assertNull(settings.summaryMaxInputTokens());
        assertEquals(50000, settings.largeResultCharLimit()); assertEquals(2000, settings.largeResultPreviewChars());
    }

    /** 校验旧压缩段只迁移一次，完整原文备份和其它设置均保留。 */
    @Test void migratesOldLimitsOnceAndPreservesOtherSettingsAndOriginalBackup() throws Exception {
        Path path = dir.resolve("config.json");
        String old = """
                {"version":1,"custom":{"x":7},"tui":{"theme":"solarized"},"compaction":{
                "autoSummaryEnabled":false,"snipEnabled":true,"microEnabled":true,
                "limits":{"contextCharLimit":300000,"summaryMaxOutputTokens":8000}}}
                """;
        Files.writeString(path, old); var config = new JadeConfigFile(path);
        var loaded = config.load();
        assertFalse(loaded.contextCompaction().autoSummaryEnabled());
        assertEquals(32768, loaded.contextCompaction().summaryMaxOutputTokens());
        var newRoot = Json.mapper().readTree(Files.readString(path));
        assertEquals(7, newRoot.path("custom").path("x").asInt());
        assertEquals("solarized", loaded.tui().theme());
        assertFalse(newRoot.path("compaction").has("limits")); assertFalse(newRoot.path("compaction").has("snipEnabled"));
        assertEquals("auto", newRoot.path("compaction").path("summaryMaxInputTokens").asText());
        try (var files = Files.list(dir)) {
            var backups = files.filter(file -> !file.equals(path)).toList();
            assertEquals(1, backups.size()); assertEquals(old, Files.readString(backups.getFirst()));
        }
        config.load();
        try (var files = Files.list(dir)) { assertEquals(2, files.count(), "重复读取不再次备份/重写"); }
    }

    /** 负预算必须在写盘前失败；用户显式填写的新额度不能被默认值覆盖。 */
    @Test void invalidConfigurationIsNotRewrittenAndExplicitNewBudgetIsPreserved() throws Exception {
        Path path = dir.resolve("config.json");
        String invalid = "{\"compaction\":{\"snipEnabled\":true,\"reserveTokens\":-1}}";
        Files.writeString(path, invalid);
        assertThrows(IOException.class, () -> new JadeConfigFile(path).load());
        assertEquals(invalid, Files.readString(path));
        try (var files = Files.list(dir)) { assertEquals(1, files.count()); }
        Files.writeString(path, "{\"compaction\":{\"summaryMaxInputTokens\":900000,\"summaryMaxOutputTokens\":65536}}");
        var settings = new JadeConfigFile(path).load().contextCompaction();
        assertEquals(900000, settings.summaryMaxInputTokens()); assertEquals(65536, settings.summaryMaxOutputTokens());
    }
}
