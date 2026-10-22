package com.jadecode.cli.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.jadecode.config.JadeSettings;
import com.jadecode.tui.components.SelectList;
import com.jadecode.tui.theme.ThemeManager;

/**
 * 纯 TUI 设置菜单：只暴露已批准的非秘密显示设置与主题/模式子流程。
 *
 * <p>不出现 provider/认证/模型池/压缩策略/传输/遥测/扩展包管理；主题预览与持久化分离，
 * Esc 回滚、Enter/Apply 才排队保存。每项显示当前真实值；未知/非法值由配置层报错，不
 * 静默覆盖。菜单本身不写文件/不切 Host，动作由 Controller 执行。</p>
 */
public final class SettingsMenu {
    /** 一项目录条目；id 用于 controller 分派，value 为当前显示值。 */
    public record Item(String id, String label, String value) { }

    private JadeSettings.Tui current;
    private final List<String> customThemes;

    public SettingsMenu(JadeSettings.Tui current, java.nio.file.Path themesDirectory) {
        this.current = current;
        this.customThemes = ThemeManager.customThemes(themesDirectory);
    }

    /** @return 当前设置快照。 */
    public JadeSettings.Tui current() { return current; }

    /** 重新载入设置（保存失败回滚/其它窗口刷新）。 */
    public void reload(JadeSettings.Tui value) { current = value; }

    /** @return 菜单候选；标签含真实当前值，ID 稳定。 */
    public SelectList list() {
        var items = new ArrayList<SelectList.Item>();
        for (Item item : items()) items.add(new SelectList.Item(item.id(), item.label(), item.value()));
        var list = new SelectList(items, true);
        return list;
    }

    /** @return 按批准范围排列的设置项与当前值。 */
    public List<Item> items() {
        return List.of(
                new Item("theme", "Theme", current.theme()),
                new Item("lightTheme", "Light theme", current.lightTheme()),
                new Item("darkTheme", "Dark theme", current.darkTheme()),
                new Item("mode", "TUI mode", current.mode()),
                new Item("fullscreenExitOutput", "Fullscreen exit output", current.fullscreenExitOutput()),
                new Item("fullscreenScrollbar", "Fullscreen scrollbar", current.fullscreenScrollbar()),
                new Item("fullscreenCopyOnSelect", "Copy on select", Boolean.toString(current.fullscreenCopyOnSelect())),
                new Item("fullscreenWheelScrollLines", "Wheel scroll lines", current.fullscreenWheelScrollLines() == 0 ? "auto" : Integer.toString(current.fullscreenWheelScrollLines())),
                new Item("showHardwareCursor", "Hardware cursor", Boolean.toString(current.showHardwareCursor())),
                new Item("editorPaddingX", "Editor padding X", Integer.toString(current.editorPaddingX())),
                new Item("outputPad", "Output padding", Integer.toString(current.outputPad())),
                new Item("autocompleteMaxVisible", "Autocomplete items", Integer.toString(current.autocompleteMaxVisible())),
                new Item("mermaidRendering", "Mermaid rendering", current.mermaidRendering()),
                new Item("showImages", "Show images", Boolean.toString(current.showImages())),
                new Item("imageWidthCells", "Image width", Integer.toString(current.imageWidthCells())),
                new Item("clearOnShrink", "Clear on shrink", Boolean.toString(current.clearOnShrink())),
                new Item("showTerminalProgress", "Terminal progress", Boolean.toString(current.showTerminalProgress())),
                new Item("quietStartup", "Quiet startup", Boolean.toString(current.quietStartup())),
                new Item("thinkingVisible", "Thinking visible", Boolean.toString(current.thinkingVisible())),
                new Item("toolsExpanded", "Tools expanded", Boolean.toString(current.toolsExpanded())),
                new Item("commandSuggestions", "Command suggestions", Boolean.toString(current.commandSuggestions())));
    }

    /** @return 当前值说明/下一步动作。 */
    public String description(String id) {
        return switch (id) {
            case "theme" -> "Choose system, dark, light, auto, or a custom theme file.";
            case "mode" -> "Switch between fullscreen and regular without restarting the session.";
            case "lightTheme", "darkTheme" -> "Theme used by the auto pair.";
            case "fullscreenCopyOnSelect" -> "Copy a fullscreen selection when released.";
            case "showHardwareCursor" -> "Show the real cursor for IME positioning.";
            case "imageWidthCells" -> "Maximum terminal image width in cells.";
            case "quietStartup" -> "Show only the compact brand line at startup.";
            default -> "Enter to change this setting; changes are saved to cli config.";
        };
    }

    /** @return 主题可选值（含自定义文件），供 Controller 的预览选择器。 */
    public List<String> themeChoices() {
        var values = new ArrayList<String>(List.of("system", "auto", "dark", "light"));
        for (String theme : customThemes) if (!values.contains(theme)) values.add(theme);
        return values;
    }

    /** @return light/dark 配对可选值（不含 system/auto）。 */
    public List<String> pairedThemeChoices() {
        var values = new ArrayList<String>(List.of("dark", "light"));
        values.addAll(customThemes);
        return values;
    }

    /**
     * 切换一个非主题、非模式设置，返回将持久化的白名单 patch；不支持项返回空。
     * 布尔翻转、枚举循环、数值循环都在这里确定，Controller 只负责保存/报告。
     */
    public Map<String, Object> cycle(String id) {
        Map<String, Object> patch = switch (id) {
            case "fullscreenExitOutput" -> Map.of(id, next(current.fullscreenExitOutput(), List.of("transcript", "resume-hint")));
            case "fullscreenScrollbar" -> Map.of(id, next(current.fullscreenScrollbar(), List.of("auto", "always", "hidden")));
            case "mermaidRendering" -> Map.of(id, next(current.mermaidRendering(), List.of("streaming", "off", "final")));
            case "fullscreenCopyOnSelect" -> Map.of(id, !current.fullscreenCopyOnSelect());
            case "showHardwareCursor" -> Map.of(id, !current.showHardwareCursor());
            case "showImages" -> Map.of(id, !current.showImages());
            case "clearOnShrink" -> Map.of(id, !current.clearOnShrink());
            case "showTerminalProgress" -> Map.of(id, !current.showTerminalProgress());
            case "quietStartup" -> Map.of(id, !current.quietStartup());
            case "thinkingVisible" -> Map.of(id, !current.thinkingVisible());
            case "toolsExpanded" -> Map.of(id, !current.toolsExpanded());
            case "commandSuggestions" -> Map.of(id, !current.commandSuggestions());
            case "editorPaddingX" -> Map.of(id, (current.editorPaddingX() + 1) % 4);
            case "outputPad" -> Map.of(id, current.outputPad() == 0 ? 1 : 0);
            case "autocompleteMaxVisible" -> Map.of(id, current.autocompleteMaxVisible() >= 20 ? 3 : current.autocompleteMaxVisible() + 1);
            case "imageWidthCells" -> Map.of(id, current.imageWidthCells() >= 120 ? 20 : current.imageWidthCells() + 20);
            // Pi 候选为 auto + 有序去重的 1/2/3/5/10 + 当前自定义值；末项之后回到 auto。
            case "fullscreenWheelScrollLines" -> Map.of(id, WheelScrollPolicy.next(current.fullscreenWheelScrollLines()));
            case "lightTheme" -> Map.of(id, next(current.lightTheme(), pairedThemeChoices()));
            case "darkTheme" -> Map.of(id, next(current.darkTheme(), pairedThemeChoices()));
            default -> Map.of();
        };
        if (patch.isEmpty()) return patch;
        // 立即更新预览快照，下一次菜单重新渲染显示新值；实际写入由 Controller 完成。
        current = apply(current, patch);
        return patch;
    }

    /** @return 只构造新的设置快照，不写磁盘。 */
    public static JadeSettings.Tui apply(JadeSettings.Tui value, Map<String, Object> patch) {
        Object theme = patch.getOrDefault("theme", value.theme());
        Object mode = patch.getOrDefault("mode", value.mode());
        Object exit = patch.getOrDefault("fullscreenExitOutput", value.fullscreenExitOutput());
        Object scrollbar = patch.getOrDefault("fullscreenScrollbar", value.fullscreenScrollbar());
        Object copy = patch.getOrDefault("fullscreenCopyOnSelect", value.fullscreenCopyOnSelect());
        Object wheel = patch.getOrDefault("fullscreenWheelScrollLines", value.fullscreenWheelScrollLines());
        Object hardware = patch.getOrDefault("showHardwareCursor", value.showHardwareCursor());
        Object padX = patch.getOrDefault("editorPaddingX", value.editorPaddingX());
        Object outputPad = patch.getOrDefault("outputPad", value.outputPad());
        Object autoMax = patch.getOrDefault("autocompleteMaxVisible", value.autocompleteMaxVisible());
        Object thinking = patch.getOrDefault("thinkingVisible", value.thinkingVisible());
        Object tools = patch.getOrDefault("toolsExpanded", value.toolsExpanded());
        Object suggestions = patch.getOrDefault("commandSuggestions", value.commandSuggestions());
        Object mermaid = patch.getOrDefault("mermaidRendering", value.mermaidRendering());
        Object images = patch.getOrDefault("showImages", value.showImages());
        Object imageWidth = patch.getOrDefault("imageWidthCells", value.imageWidthCells());
        Object clear = patch.getOrDefault("clearOnShrink", value.clearOnShrink());
        Object progress = patch.getOrDefault("showTerminalProgress", value.showTerminalProgress());
        Object quiet = patch.getOrDefault("quietStartup", value.quietStartup());
        return new JadeSettings.Tui((String) theme, (String) mode, (String) exit, (String) scrollbar,
                (Boolean) copy, (Integer) wheel, (Boolean) hardware, (Integer) padX, (Integer) outputPad,
                (Integer) autoMax, (Boolean) thinking, (Boolean) tools, (Boolean) suggestions, (String) mermaid,
                (Boolean) images, (Integer) imageWidth, (Boolean) clear, (Boolean) progress, (Boolean) quiet,
                value.codeBlockIndent(), value.externalEditor(), value.keybindings(), value.escapeTimeoutMillis(),
                value.lightTheme(), value.darkTheme());
    }

    private static String next(String value, List<String> values) {
        int index = values.indexOf(value);
        return values.get((index + 1) % values.size());
    }
}
