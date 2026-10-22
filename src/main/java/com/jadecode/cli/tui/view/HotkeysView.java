package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.CommandCatalog;
import com.jadecode.cli.tui.model.CommandPanelModel;
import com.jadecode.tui.input.KeyBindings;

/**
 * /hotkeys 的数据投影：只读取实际支持的 KeyBindings 与真实 CommandCatalog，按上下文分组。
 *
 * <p>不硬编码第二份动作目录、不写通知/转录；页面组件由 CommandPanelView 渲染。</p>
 */
public final class HotkeysView {
    /** @return 结构化只读页面；键/命令来自真实目录，未支持动作不会出现。 */
    public CommandPanelModel panel(KeyBindings bindings, List<CommandCatalog.Command> commands,
            long generation, long sourceRevision) {
        var groups = new ArrayList<CommandPanelModel.Group>();
        // 常用页只保存真实目录的索引：重绑定或隐藏动作后，这里的键名不会漂移。
        var common = new ArrayList<CommandPanelModel.Entry>();
        for (String id : List.of("tui.input.submit", "tui.input.newLine", "tui.input.tab", "app.tools.expand",
                "app.thinking.toggle", "app.editor.external", "app.clipboard.pasteImage", "app.suspend")) {
            bindings.visible().stream().filter(action -> action.id().equals(id) && !bindings.chords(id).isEmpty())
                    .findFirst().ifPresent(action -> common.add(
                            new CommandPanelModel.Entry(keys(bindings, action.id()), action.label(), "")));
        }
        if (!common.isEmpty()) groups.add(new CommandPanelModel.Group("common", "Common", common));
        String[] contexts = {"GLOBAL", "EDITOR", "SELECT", "ALT_SCREEN", "SEARCH", "APPROVAL"};
        for (String context : contexts) {
            var entries = new ArrayList<CommandPanelModel.Entry>();
            for (var action : bindings.visible()) {
                if (!action.context().name().equals(context) || bindings.chords(action.id()).isEmpty()) continue;
                entries.add(new CommandPanelModel.Entry(keys(bindings, action.id()), action.label(), ""));
            }
            if (!entries.isEmpty())
                groups.add(new CommandPanelModel.Group(context.toLowerCase(java.util.Locale.ROOT),
                        groupTitle(context), entries));
        }
        var commandEntries = new ArrayList<CommandPanelModel.Entry>();
        for (CommandCatalog.Command command : commands)
            commandEntries.add(new CommandPanelModel.Entry(command.usage(), command.description(), ""));
        if (!commandEntries.isEmpty())
            groups.add(new CommandPanelModel.Group("commands", "Commands", commandEntries));
        return new CommandPanelModel(CommandPanelModel.Kind.HOTKEYS, "Hotkeys", generation,
                sourceRevision, groups, List.of(), List.of(), "No supported keys or commands are available.");
    }

    /** 从生效绑定读取键名，配置覆盖或解绑也会反映到常用页和完整分类。 */
    private static String keys(KeyBindings bindings, String id) {
        return bindings.chords(id).stream().map(com.jadecode.tui.input.KeyChord::display)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String groupTitle(String group) {
        return switch (group) {
            case "GLOBAL" -> "Global";
            case "EDITOR" -> "Editor";
            case "SELECT" -> "Selection";
            case "ALT_SCREEN" -> "Transcript and search";
            case "SEARCH" -> "Search";
            case "APPROVAL" -> "Approval";
            default -> group;
        };
    }
}
