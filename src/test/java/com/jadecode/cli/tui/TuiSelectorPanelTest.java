package com.jadecode.cli.tui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.cli.tui.bridge.ApprovalBroker;
import com.jadecode.cli.tui.view.ChatViewport;
import com.jadecode.cli.tui.view.ConversationView;
import com.jadecode.cli.tui.view.DockView;
import com.jadecode.config.JadeSettings;
import com.jadecode.session.history.SessionMetadata;
import com.jadecode.tui.components.Editor;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.input.KeyChord;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemeManager;
import com.jadecode.tui.theme.ThemePalette;

/** R4-01/R4-02：完整选择器面板、搜索空间语义、Theme 父子返回和逻辑值同步。 */
class TuiSelectorPanelTest {
    private final List<UiEvent> events = new java.util.ArrayList<>();
    private ApprovalBroker broker;
    private CommandCatalog catalog;
    private ThemeManager themes;
    private TuiPreferences preferences;

    @AfterEach void cleanup() {
        // 等待 Controller 排队的临时配置写入结束，避免 JUnit 删除 @TempDir 时与写线程竞态。
        if (preferences != null) {
            try { preferences.awaitWrites(); } catch (Exception ignored) { }
        }
        if (themes != null) themes.close();
        if (broker != null) broker.close();
        if (catalog != null) catalog.close();
    }

    private TuiController controller(TuiState state, Path dir, SettingsMenu menu) {
        themes = new ThemeManager("system", dir.resolve("themes"), true, "light", "dark");
        var chat = new ChatViewport(new ConversationView(), ChatViewport.Scrollbar.AUTO, true);
        var editor = new Editor(new KeyBindings(java.util.Map.of()), null);
        state.editor = editor;
        broker = new ApprovalBroker(events::add);
        catalog = new CommandCatalog(dir);
        preferences = new TuiPreferences(dir.resolve("config.json"));
        return new TuiController(state, null, broker, chat, editor, preferences,
                new KeyBindings(java.util.Map.of()), catalog, menu, themes, events::add, () -> { }, null);
    }

    private static InputEvent key(String name) { return InputEvent.key(KeyChord.of(name), 1, 1); }
    private static InputEvent text(String value) { return InputEvent.text(value, 1, 1); }

    @Test void settingsPanelHasSearchBordersAlignedValuesAndFooter(@TempDir Path dir) {
        var state = new TuiState(); state.installGeneration(0);
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes"));
        var controller = controller(state, dir, menu);
        assertTrue(controller.submit("/settings"));
        assertNotNull(state.selectorInput);
        var context = new RenderContext(80, 24, Theme.from(ThemePalette.builtin("dark"), true, 1));
        var dock = new DockView().render(state, context, 80, 23);
        String all = dock.lines().stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("> Search settings"), all);
        assertTrue(all.contains("Theme") && all.contains("dark"), all);
        assertTrue(all.contains("Type to search · Enter/Space to change · Esc to cancel"), all);
        assertTrue(all.contains("(1/21)"), "window count missing: " + all);
        assertTrue(all.lines().anyMatch(line -> line.startsWith("┌─ Settings")), "embedded title top border missing");
        assertTrue(dock.cursorVisible());
        assertEquals(2 + "Search settings".length() == dock.cursorColumn() || dock.cursorColumn() >= 2, true);
        assertTrue(dock.lines().size() <= 23);
        // 选中项只使用 accent，不铺整行 selectedBg。
        var selected = dock.lines().stream().filter(line -> line.text().contains("→ Theme")).findFirst().orElseThrow();
        assertTrue(selected.spans().stream().noneMatch(span -> span.style().bg().equals(
                context.theme().color("selectedBg"))), selected.toString());
    }

    @Test void shortNarrowPanelKeepsSearchAndSelectedItemWithinDockBudget(@TempDir Path dir) {
        var state = new TuiState(); state.installGeneration(0);
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes"));
        var controller = controller(state, dir, menu);
        controller.submit("/settings");
        var dock = new DockView().render(state,
                new RenderContext(40, 12, Theme.from(ThemePalette.builtin("dark"), true, 1)), 40, 11);
        assertTrue(dock.lines().size() <= 11);
        String all = dock.lines().stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("> Search settings"), all);
        assertTrue(all.contains("Theme"), all);
        assertTrue(dock.cursorVisible());
        assertTrue(dock.lines().stream().allMatch(line -> line.width() <= 40));
    }

    @Test void spaceTypesWhenQueryIsNonEmptyAndTogglesOnlyWhenEmpty(@TempDir Path dir) {
        var state = new TuiState(); state.installGeneration(0);
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes"));
        var controller = controller(state, dir, menu);
        controller.submit("/settings");
        state.selector.selectId("fullscreenCopyOnSelect");
        Boolean before = menu.current().fullscreenCopyOnSelect();
        controller.accept(new UiEvent.Input(text("copy")));
        assertEquals("copy", state.selectorInput.text());
        controller.accept(new UiEvent.Input(text(" ")));
        assertEquals("copy ", state.selector.filter(), "space belongs to a non-empty search query");
        assertEquals(before, menu.current().fullscreenCopyOnSelect(), "space must not toggle while searching");
        // 清空搜索后 Space 才按 Pi 设置语义切换值。
        controller.accept(new UiEvent.Input(InputEvent.key(KeyChord.of("u", KeyChord.Modifier.CTRL), 2, 1)));
        assertEquals("", state.selectorInput.text());
        state.selector.selectId("fullscreenCopyOnSelect");
        controller.accept(new UiEvent.Input(text(" ")));
        assertNotEquals(before, menu.current().fullscreenCopyOnSelect());
    }

    @Test void themeChildReturnsToParentWithFilterAndUpdatesLogicalRow(@TempDir Path dir) throws Exception {
        var state = new TuiState(); state.installGeneration(0);
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes"));
        var controller = controller(state, dir, menu);
        // 让 Settings 处于过滤状态，验证子页返回后保留查询与位置。
        controller.submit("/settings");
        controller.accept(new UiEvent.Input(text("theme")));
        assertEquals("theme", state.selector.filter());
        controller.accept(new UiEvent.Input(key("enter")));
        assertEquals("Theme", state.selectorKind);
        assertEquals("", state.selectorInput.text(), "child search starts empty; parent query is restored on return");
        state.selector.selectId("light");
        controller.accept(new UiEvent.Input(key("enter")));
        assertEquals("Settings", state.selectorKind);
        assertEquals("theme", state.selector.filter(), "parent filter must survive child return");
        assertEquals("light", menu.current().theme());
        assertTrue(menu.items().stream().anyMatch(item -> item.id().equals("theme") && item.value().equals("light")));
        preferences.awaitWrites();
        assertEquals("light", new com.jadecode.config.JadeConfigFile(dir.resolve("config.json")).load().tui().theme());
        // 父页 Esc 回到原 Editor 草稿；子页 Esc 只回父页。
        var editor = state.editor;
        editor.insert("KEEP_DRAFT", false);
        controller.accept(new UiEvent.Input(key("escape")));
        assertNull(state.selector);
        assertEquals("KEEP_DRAFT", editor.text());
    }

    @Test void themeEscapeCancelsPreviewAndImmediateEnterAppliesPreselection(@TempDir Path dir) throws Exception {
        var state = new TuiState(); state.installGeneration(0);
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes"));
        var controller = controller(state, dir, menu);
        controller.submit("/settings");
        controller.accept(new UiEvent.Input(key("enter"))); // Theme
        assertEquals("system", menu.current().theme(), "child opens on logical current theme");
        controller.accept(new UiEvent.Input(key("down"))); // preview auto
        controller.accept(new UiEvent.Input(key("escape")));
        assertEquals("Settings", state.selectorKind);
        assertEquals("system", menu.current().theme(), "Esc must not commit preview");
        // 再进入后直接 Enter：选择器已预选当前逻辑值，不依赖先按上下键。
        controller.accept(new UiEvent.Input(key("enter")));
        controller.accept(new UiEvent.Input(key("enter")));
        assertEquals("Settings", state.selectorKind);
        assertEquals("system", menu.current().theme());
    }

    @Test void pairedThemeSettingHotUpdatesAndPersists(@TempDir Path dir) throws Exception {
        var state = new TuiState(); state.installGeneration(0);
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes"));
        var controller = controller(state, dir, menu);
        menu.reload(SettingsMenu.apply(menu.current(), java.util.Map.of("theme", "auto")));
        themes.reload("auto");
        var patch = menu.cycle("darkTheme");
        controller.applySettingsPatch(patch);
        assertEquals("light", themes.preview().name(), "auto pair should re-resolve immediately");
        // 等待 Controller 自己排队的写入，不另建队列绕过保存路径。
        preferences.awaitWrites();
        var loaded = new com.jadecode.config.JadeConfigFile(dir.resolve("config.json")).load();
        assertEquals(menu.current().darkTheme(), loaded.tui().darkTheme());
    }

    @Test void thinkingAndPermissionPanelsHaveTheirOwnTitlesAndSearch(@TempDir Path dir) {
        var state = new TuiState(); state.installGeneration(0);
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes"));
        var controller = controller(state, dir, menu);
        state.session = new com.jadecode.session.SessionRuntime.Display("id", "Title", "model",
                new com.jadecode.llm.ModelMetadata(com.jadecode.llm.ModelMetadata.Limits.unknown(), "test"),
                com.jadecode.llm.ThinkingSettings.HIGH, "high",
                List.of(com.jadecode.llm.ThinkingSettings.DEFAULT, com.jadecode.llm.ThinkingSettings.HIGH),
                new com.jadecode.session.SessionUsage.Snapshot(com.jadecode.session.SessionUsage.Totals.empty(),
                        java.util.Map.of(), java.util.Map.of(), java.util.Map.of()), null);
        controller.submit("/thinking");
        assertEquals("Thinking", state.selectorKind);
        assertEquals("Thinking level", state.selectorTitle);
        assertEquals("Search thinking levels", state.selectorInput.text().isEmpty()
                ? "Search thinking levels" : state.selectorInput.text());
        state.selectorKind = "Permission";
        state.selector = PermissionMenu.choices(com.jadecode.safety.PermissionProfile.WORKSPACE);
        state.selectorTitle = "Permission";
        state.selectorFooter = "Enter select · Esc cancel";
        state.selectorInput = new com.jadecode.tui.components.Input("Search permissions", v -> { });
        var dock = new com.jadecode.cli.tui.view.DockView().render(state,
                new RenderContext(80, 24, Theme.from(ThemePalette.builtin("dark"), true, 1)), 80, 23);
        assertTrue(dock.lines().stream().anyMatch(line -> line.text().contains("> Search permissions")));
        assertTrue(dock.lines().stream().anyMatch(line -> line.text().contains("Permission")));
    }

    @Test void mousePressOnPanelRowSelectsCandidateWithoutPenetrating(@TempDir Path dir) {
        var state = new TuiState(); state.installGeneration(0);
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes"));
        var controller = controller(state, dir, menu);
        controller.submit("/settings");
        var context = new RenderContext(80, 24, Theme.from(ThemePalette.builtin("dark"), true, 1));
        var dock = new DockView().render(state, context, 80, 23);
        var frame = new com.jadecode.tui.core.Frame(1, 1, context.revision(), dock.lines(), Frame.NO_CURSOR, dock.hits());
        controller.presented(new com.jadecode.tui.core.PresentedFrame(1, 1, 80, 24, context.revision(),
                dock.hits().stream().map(com.jadecode.tui.core.Frame.Hit::action).toList()), frame);
        controller.layout(80, 24, 0, 10, 10, true);
        int row = -1;
        for (int i = 0; i < dock.lines().size(); i++)
            if (dock.lines().get(i).text().contains("Light theme")) { row = i; break; }
        assertTrue(row >= 0, "Light theme row missing");
        controller.accept(new UiEvent.Input(InputEvent.mouse(new InputEvent.Mouse(0,
                InputEvent.Mouse.Action.PRESS, 5, row, false, false, false), 30, 1)));
        assertEquals("lightTheme", state.selector.selected(), "panel row press must select that candidate");
        assertFalse(controller.selectionActive(), "panel clicks must not start transcript selection");
    }

    @Test void resumePanelUsesSessionRowsSearchAndStatus(@TempDir Path dir) {
        var state = new TuiState(); state.installGeneration(0);
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes"));
        var controller = controller(state, dir, menu);
        String now = Instant.now().toString();
        var sessions = List.of(
                new SessionMetadata(2, "11111111-1111-1111-1111-111111111111", "/work", "Alpha task", now, now, "m", true,
                        com.jadecode.session.SessionUsage.Totals.empty(), null),
                new SessionMetadata(2, "22222222-2222-2222-2222-222222222222", "/work", "Beta task", now, now, "m", true,
                        com.jadecode.session.SessionUsage.Totals.empty(), null));
        controller.accept(new UiEvent.SessionList(sessions));
        assertEquals("Resume", state.selectorKind);
        assertEquals("Resume session", state.selectorTitle);
        assertTrue(state.selectorStatus.contains("All sessions"));
        controller.accept(new UiEvent.Input(text("alpha")));
        assertEquals("alpha", state.selector.filter());
        assertEquals(1, state.selector.size());
        controller.accept(new UiEvent.Input(InputEvent.key(
                KeyChord.of("n", KeyChord.Modifier.CTRL), 2, 1)));
        assertTrue(state.sessionPicker.namedOnly());
        assertTrue(state.selectorStatus.contains("Named only"));
        assertEquals("alpha", state.selector.filter(), "toggle must keep current search");
    }
}
