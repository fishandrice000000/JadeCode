package com.jadecode.tui.theme;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.tui.text.Color;

/**
 * R9 N03：主题选择、预览 ticket、逻辑标识同步、auto/v2 变体、文件监听与坏文件语义。
 *
 * <p>文件监听用真实 WatchService + 临时目录，不 mock Manager 内部；只验证“worker 读文件、
 * UI 采纳不可变结果”的公开契约。</p>
 */
class ThemeManagerTest {
    private static final String PROBE_V2 = """
            {"schemaVersion":2,"name":"probe","variants":{
              "dark":{"vars":{"foreground":"#E6EDF3","background":"#101418","primary":"#112233"},
                "colors":{"text":"foreground","accent":"primary","brandPrimary":"primary",
                          "brandBannerBody":"primary","brandBannerShadow":"#010203"}},
              "light":{"vars":{"foreground":"#263540","background":"#F8FAFB","primary":"#778899"},
                "colors":{"text":"foreground","accent":"primary","brandPrimary":"primary",
                          "brandBannerBody":"primary","brandBannerShadow":"#040506"}}}}
            """;

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test void latePreviewResultsCannotReplaceANewerSelection(@TempDir Path dir) throws Exception {
        var manager = new ThemeManager("system", dir.resolve("themes"), true, "light", "dark");
        try {
            manager.beginPreview();
            var first = manager.requestPreview("light");
            var second = manager.requestPreview("dark");
            var firstDocument = ThemePalette.builtinDocument();
            // 先到的旧结果（light）必须被丢弃，不能覆盖后发的 dark 候选。
            assertEquals("", manager.acceptPreview(new ThemeManager.LoadResult(first, firstDocument, null)));
            assertNotEquals(Color.rgb(0x0B, 0x6F, 0x91), manager.preview().color("brandPrimary"));
            assertEquals("", manager.acceptPreview(new ThemeManager.LoadResult(second, firstDocument, null)));
            assertEquals(Color.rgb(0x6C, 0xB6, 0xD4), manager.preview().color("brandPrimary"));
            assertTrue(manager.previewMatches("dark"));
            assertFalse(manager.previewMatches("light"));
        } finally {
            manager.close();
        }
    }

    @Test void applyPreviewSynchronisesLogicalIdSoSetPairDoesNotReloadOldTheme(@TempDir Path dir) {
        var manager = new ThemeManager("system", dir.resolve("themes"), true, "light", "dark");
        try {
            manager.beginPreview();
            assertEquals("", manager.preview("light"));
            manager.applyPreview();
            assertEquals("light", manager.logicalName());
            assertEquals("light", manager.preview().name());
            // 旧实现只换了 Theme；这里 setPair 若按旧 configuredName 重载会跳回 system。
            manager.setPair("light", "dark");
            assertEquals("light", manager.logicalName());
            assertEquals("light", manager.preview().name());
            manager.cancelPreview();
            assertEquals("light", manager.logicalName(), "cancelPreview after apply restores committed selection");
        } finally {
            manager.close();
        }
    }

    @Test void failedPreviewKeepsCommittedThemeAndReportsEnglishError(@TempDir Path dir) throws Exception {
        var manager = new ThemeManager("dark", dir.resolve("themes"), true, "light", "dark");
        try {
            manager.beginPreview();
            write(dir.resolve("themes/broken.json"), "{not json");
            String error = manager.preview("broken");
            assertFalse(error.isEmpty());
            assertTrue(error.contains("Cannot parse theme JSON"), error);
            assertEquals(Color.rgb(0x6C, 0xB6, 0xD4), manager.preview().color("brandPrimary"));
            assertEquals("dark", manager.logicalName(), "failed preview must not commit a logical id");
        } finally {
            manager.close();
        }
    }

    @Test void autoPairWithOneV2FileSelectsVariantsByTerminalAppearance(@TempDir Path dir) throws Exception {
        Path themes = dir.resolve("themes");
        write(themes.resolve("probe.json"), PROBE_V2);
        var manager = new ThemeManager("auto", themes, true, "probe", "probe");
        try {
            assertEquals(Color.rgb(0x11, 0x22, 0x33), manager.preview().color("accent"),
                    "dark terminal assumption selects the dark variant");
            assertEquals("auto", manager.logicalName());
            // 终端晚回复为浅色：逻辑标识仍是 auto，变体切到 light，显式颜色不被 ANSI 覆盖。
            manager.updateTerminalColors(false, Color.rgb(0x26, 0x35, 0x40), Color.rgb(0xF8, 0xFA, 0xFB), List.of());
            assertEquals(Color.rgb(0x77, 0x88, 0x99), manager.preview().color("accent"));
            assertEquals("auto", manager.logicalName());
            assertEquals("light", manager.preview().appearance());
            // 固定 dark 主题不会因终端转浅而改变变体。
            manager.reload("dark");
            manager.updateTerminalColors(false, null, null, List.of());
            assertEquals("dark", manager.preview().appearance());
            assertEquals("dark", manager.logicalName());
        } finally {
            manager.close();
        }
    }

    @Test void watchReloadsSameFileAndBadContentKeepsLastValidSnapshot(@TempDir Path dir) throws Exception {
        Path themes = dir.resolve("themes");
        Path file = themes.resolve("probe.json");
        write(file, PROBE_V2);
        var manager = new ThemeManager("probe", themes, true, "probe", "probe");
        var results = new LinkedBlockingQueue<ThemeManager.WatchResult>();
        manager.watch(results::add);
        try {
            assertEquals(Color.rgb(0x11, 0x22, 0x33), manager.preview().color("accent"));
            // 留出注册目录的时间，再写同一文件的新内容。
            Thread.sleep(300);
            write(file, PROBE_V2.replace("#112233", "#AABBCC"));
            ThemeManager.WatchResult changed = awaitAccent(results, manager, Color.rgb(0xAA, 0xBB, 0xCC));
            assertEquals("", manager.acceptWatch(changed));
            assertEquals(Color.rgb(0xAA, 0xBB, 0xCC), manager.preview().color("accent"));
            assertEquals(Color.rgb(0xAA, 0xBB, 0xCC), manager.preview().color("brandBannerBody"));

            // 坏文件：返回真实错误，保留最后有效颜色。
            Thread.sleep(200);
            write(file, "{broken");
            ThemeManager.WatchResult broken = results.poll(10, TimeUnit.SECONDS);
            assertNotNull(broken, "watcher must report broken file");
            String error = manager.acceptWatch(broken);
            assertFalse(error.isEmpty());
            assertEquals(Color.rgb(0xAA, 0xBB, 0xCC), manager.preview().color("accent"),
                    "broken watch result must keep the last valid snapshot");

            // 旧代次事件不能复活旧文件内容。
            var stale = new ThemeManager.WatchResult(broken.generation() - 1, file,
                    ThemePalette.builtinDocument(), null);
            assertEquals("", manager.acceptWatch(stale));
            assertEquals(Color.rgb(0xAA, 0xBB, 0xCC), manager.preview().color("accent"));
        } finally {
            manager.close();
        }
    }

    /** 等 watch 报告目标文件的新内容；中途可能收到旧的中间态事件，按颜色轮询。 */
    private static ThemeManager.WatchResult awaitAccent(LinkedBlockingQueue<ThemeManager.WatchResult> results,
            ThemeManager manager, Color expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ThemeManager.WatchResult last = null;
        while (System.nanoTime() < deadline) {
            ThemeManager.WatchResult result = results.poll(200, TimeUnit.MILLISECONDS);
            if (result == null) continue;
            last = result;
            if (result.document() == null) continue;
            String error = manager.acceptWatch(result);
            assertEquals("", error);
            if (expected.equals(manager.preview().color("accent"))) return result;
        }
        fail("watcher did not deliver new accent; last=" + last);
        return null;
    }
}
