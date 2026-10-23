package com.jadecode.cli.tui.view;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.tui.components.Markdown;
import com.jadecode.tui.components.SelectList;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.Color;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.ThemeManager;

/**
 * R9 A9-07：同一份自定义 v2 文件变化后，界面、普通 Markdown、代码/标题角色与 Banner/mascot
 * 必须一起换色；验证走 Manager 的真实 watch 采纳路径，而不是只改 BrandingView 一个组件。
 */
class Round9ThemeProjectionTest {
    private static final String V2 = """
            {"schemaVersion":2,"name":"probe","variants":{
              "dark":{"vars":{"foreground":"#E0E0E0","background":"#101418","primary":"#112233",
                      "shadow":"#010203"},
                "colors":{"text":"foreground","accent":"primary","mdHeading":"primary",
                          "mdCode":"primary","mdListBullet":"primary","mdCodeBlock":"foreground",
                          "syntaxKeyword":"shadow","brandPrimary":"primary",
                          "brandBannerBody":"primary","brandBannerShadow":"shadow"}},
              "light":{"vars":{"foreground":"#202020","background":"#FAFAFA","primary":"#778899",
                      "shadow":"#040506"},
                "colors":{"text":"foreground","accent":"primary","mdHeading":"primary",
                          "mdCode":"primary","mdListBullet":"primary","mdCodeBlock":"foreground",
                          "syntaxKeyword":"shadow","brandPrimary":"primary",
                          "brandBannerBody":"primary","brandBannerShadow":"shadow"}}}}
            """;

    @Test void sameFileChangeUpdatesMarkdownBannerAndPanelFromTheRealWatchPath(@TempDir Path dir)
            throws Exception {
        Path themes = dir.resolve("themes");
        Path file = themes.resolve("probe.json");
        Files.createDirectories(themes);
        Files.writeString(file, V2);
        var manager = new ThemeManager("probe", themes, true, "probe", "probe");
        var results = new LinkedBlockingQueue<ThemeManager.WatchResult>();
        manager.watch(results::add);
        try {
            var before = manager.preview();
            assertColors(before, Color.rgb(0x11, 0x22, 0x33), Color.rgb(0x01, 0x02, 0x03),
                    Color.rgb(0xE0, 0xE0, 0xE0));

            Thread.sleep(300);
            Files.writeString(file, V2
                    .replace("#112233", "#AABBCC")
                    .replace("#010203", "#040506")
                    .replace("#E0E0E0", "#F0F0F0"));
            // 等待并采纳真实 watch 结果；中途可能收到旧中间态。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                var result = results.poll(200, TimeUnit.MILLISECONDS);
                if (result == null) continue;
                manager.acceptWatch(result);
                if (Color.rgb(0xAA, 0xBB, 0xCC).equals(manager.preview().color("accent"))) break;
            }
            var after = manager.preview();
            assertNotEquals(before.revision(), after.revision(), "visible change must bump the theme revision");
            assertColors(after, Color.rgb(0xAA, 0xBB, 0xCC), Color.rgb(0x04, 0x05, 0x06),
                    Color.rgb(0xF0, 0xF0, 0xF0));

            // Banner/mascot：字身与轮廓分别取新角色。
            var branding = new BrandingView().render("1.0.0", context(after), 120, 40);
            assertTrue(branding.getFirst().spans().stream().anyMatch(span -> span.text().contains("█")
                    && span.style().fg().equals(after.color("brandBannerBody"))));
            assertTrue(branding.getFirst().spans().stream().anyMatch(span -> span.text().contains("╗")
                    && span.style().fg().equals(after.color("brandBannerShadow"))));
            assertTrue(branding.stream().flatMap(line -> line.spans().stream())
                    .noneMatch(span -> span.style().fg().equals(before.color("brandBannerBody"))),
                    "old banner colour must not survive the same-file reload");

            // 普通 Markdown：正文、标题、列表符号全部使用新主题角色。
            var markdown = new Markdown().render("# Title\n\nbody text\n\n- item\n", 60, after, false);
            assertTrue(markdown.stream().flatMap(line -> line.spans().stream())
                    .anyMatch(span -> span.text().contains("Title")
                            && span.style().fg().equals(after.style("mdHeading").fg())));
            assertTrue(markdown.stream().flatMap(line -> line.spans().stream())
                    .anyMatch(span -> span.text().contains("body")
                            && span.style().fg().equals(after.style("text").fg())),
                    "plain paragraph text must come from the theme text role");
            assertTrue(markdown.stream().flatMap(line -> line.spans().stream())
                    .anyMatch(span -> span.text().contains("-")
                            && span.style().fg().equals(after.style("mdListBullet").fg())));

            // 选择器面板：右上标题与查询前缀取新 accent。
            var state = new TuiState();
            state.installGeneration(0);
            state.selectorKind = "Settings";
            state.selectorTitle = "Settings";
            state.selector = new SelectList(List.of(
                    new SelectList.Item("mode", "Mode", "fullscreen"),
                    new SelectList.Item("theme", "Theme", "probe")), true);
            state.selectorInput = new com.jadecode.tui.components.Input("Search settings", value -> { });
            var panel = new SelectorPanelView().render(state, context(after), 80, 20).lines();
            assertTrue(panel.getFirst().spans().stream().anyMatch(span -> span.text().contains("Settings")
                    && span.style().fg().equals(after.style("accent").fg())));
            var query = panel.get(1);
            assertTrue(query.spans().stream().anyMatch(span -> span.text().equals("> ")
                    && span.style().fg().equals(after.style("accent").fg())));
            // 旧主题的提示不会残留：所有行宽固定，面板仍无侧边/底色。
            assertTrue(panel.stream().allMatch(line -> line.width() == 80));
            assertTrue(panel.stream().noneMatch(line -> line.text().startsWith("│")));
        } finally {
            manager.close();
        }
    }

    private static void assertColors(com.jadecode.tui.theme.Theme theme, Color accent, Color shadow, Color text) {
        assertEquals(accent, theme.color("accent"));
        assertEquals(accent, theme.color("mdHeading"));
        assertEquals(accent, theme.color("mdListBullet"));
        assertEquals(accent, theme.color("brandBannerBody"));
        assertEquals(shadow, theme.color("brandBannerShadow"));
        assertEquals(text, theme.color("text"));
    }

    private static RenderContext context(com.jadecode.tui.theme.Theme theme) {
        return new RenderContext(120, 40, theme);
    }
}
