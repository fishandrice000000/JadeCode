package com.jadecode.cli.tui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.cli.tui.view.ChatViewport;
import com.jadecode.cli.tui.view.ConversationView;
import com.jadecode.config.JadeConfigFile;
import com.jadecode.config.JadeSettings;
import com.jadecode.tui.components.Editor;
import com.jadecode.tui.components.SelectList;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.input.KeyChord;
import com.jadecode.tui.theme.ThemeManager;

/** R3-03/S33：普通设置成功只更新值与持久化，不写 Setting saved 转录；失败仍可见。 */
class TuiSettingsQuietTest {
    private final List<UiEvent> events = new ArrayList<>();
    private ChatViewport chat;

    private TuiController controller(TuiState state, TuiPreferences preferences, SettingsMenu menu) {
        var themes = new ThemeManager("dark", null, true, "light", "dark");
        chat = new ChatViewport(new ConversationView(), ChatViewport.Scrollbar.AUTO, true);
        return new TuiController(state, null, null, chat,
                new Editor(new KeyBindings(java.util.Map.of()), null), preferences,
                new KeyBindings(java.util.Map.of()), null, menu, themes, events::add, () -> { }, null);
    }

    @Test void successfulChangesAndThemeApplyDoNotAddTranscriptMessages(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.json");
        var preferences = new TuiPreferences(config);
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes"));
        var state = new TuiState();
        state.installGeneration(0);
        var controller = controller(state, preferences, menu);
        for (String id : List.of("fullscreenScrollbar", "fullscreenCopyOnSelect",
                "showHardwareCursor", "fullscreenWheelScrollLines")) {
            controller.applySettingsPatch(menu.cycle(id));
        }
        preferences.awaitWrites();

        // 主题 Apply 走真实选择器分支；成功同样只能由预览/当前值体现。
        var themeState = new TuiState();
        themeState.installGeneration(0);
        themeState.selectorKind = "Theme";
        themeState.selector = new SelectList(List.of(new SelectList.Item("dark", "dark", "")), true);
        var themePreferences = new TuiPreferences(config);
        var themed = controller(themeState, themePreferences, menu);
        themed.accept(new UiEvent.Input(InputEvent.key(KeyChord.of("enter"), 1, 1)));
        themePreferences.awaitWrites();

        assertTrue(events.stream().noneMatch(event -> event instanceof UiEvent.Message message
                && message.label().equals("Settings") && message.text().contains("saved")), events.toString());
        assertTrue(events.stream().noneMatch(event -> event instanceof UiEvent.Message message
                && message.label().equals("Theme")), events.toString());
        var loaded = new JadeConfigFile(config).load();
        assertEquals("always", loaded.tui().fullscreenScrollbar(), events + " raw=" + (Files.exists(config) ? Files.readString(config) : "missing"));
        assertFalse(loaded.tui().fullscreenCopyOnSelect());
        assertTrue(loaded.tui().showHardwareCursor());
        assertEquals(1, loaded.tui().fullscreenWheelScrollLines(), "cycled value must persist");
        assertEquals("dark", loaded.tui().theme());
    }

    @Test void wheelHotUpdateKeepsZeroAsAutoInsteadOfConstantThree(@TempDir Path dir) throws Exception {
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes"));
        var state = new TuiState();
        state.installGeneration(0);
        var preferences = new TuiPreferences(dir.resolve("config.json"));
        var controller = controller(state, preferences, menu);
        controller.applySettingsPatch(java.util.Map.of("fullscreenWheelScrollLines", 0));
        assertEquals(0, chat.wheelScrollLines(), "0 must reach the auto policy, not become 3");
        menu.reload(SettingsMenu.apply(menu.current(), java.util.Map.of("fullscreenWheelScrollLines", 3)));
        controller.applySettingsPatch(java.util.Map.of("fullscreenWheelScrollLines", 3));
        assertEquals(3, chat.wheelScrollLines());
        preferences.awaitWrites();
    }

    @Test void failedWriteStillProducesVisibleWarning(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.json");
        Files.createDirectory(config); // 让真实写盘失败，而不是伪造成功。
        var menu = new SettingsMenu(JadeSettings.defaults().tui(), dir.resolve("themes"));
        var state = new TuiState();
        state.installGeneration(0);
        var preferences = new TuiPreferences(config);
        var controller = controller(state, preferences, menu);
        controller.applySettingsPatch(menu.cycle("thinkingVisible"));
        preferences.awaitWrites();
        assertTrue(events.stream().anyMatch(event -> event instanceof UiEvent.Message message
                && message.error() && message.label().equals("Warning")
                && message.text().contains(UiMessages.NOT_SAVED)), events.toString());
    }
}
