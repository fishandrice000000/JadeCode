package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.CommandCatalog;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.input.KeyChord;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;

/**
 * /hotkeys：只列出当前真实可执行动作与实际命令，键位与行为共享 KeyBindings/CommandCatalog。
 *
 * <p>缺 Agent 后端的取消/树/模型切换等动作已标 HIDDEN，不会出现在这里；命令帮助只接受真实
 * 命令目录，不维护静态旧别名列表。没有默认键的动作不显示空键行，避免提示不存在的快捷方式。</p>
 */
public final class HotkeysView {
    /**
     * 生成未预折行的语义 Markdown；Controller 直接存入 NOTICE，由 MessageCard/固定引擎按
     * 当前主题与宽度重新排版。键/命令来自实际 KeyBindings/CommandCatalog，不另造静态表。
     */
    public String markdown(KeyBindings bindings, List<CommandCatalog.Command> commands) {
        var out = new StringBuilder();
        out.append("## Keys and commands\n\n");
        out.append("**Keys**\n\n");
        boolean any = false;
        String[] groups = {"GLOBAL", "EDITOR", "SELECT", "ALT_SCREEN", "SEARCH", "APPROVAL"};
        for (String group : groups) {
            var actions = bindings.visible().stream()
                    .filter(action -> action.context().name().equals(group))
                    .filter(action -> !action.keys().isBlank())
                    .toList();
            if (actions.isEmpty()) continue;
            any = true;
            out.append("**").append(groupTitle(group)).append("**\n");
            for (var action : actions)
                out.append("- ").append(MarkdownSafe.code(action.keys())).append(" — ")
                        .append(action.label()).append('\n');
            out.append('\n');
        }
        if (!any) out.append("- No supported key actions are available.\n\n");
        out.append("**Commands**\n\n");
        for (CommandCatalog.Command command : commands)
            out.append("- ").append(MarkdownSafe.code(command.usage())).append(" — ")
                    .append(command.description()).append('\n');
        out.append("\n**Copy behavior**\n\n");
        out.append("- `Ctrl+X` copies the active selection first, otherwise the latest assistant reply.\n");
        out.append("- `Ctrl+C` copies a fullscreen selection when one exists; without a selection it clears the draft, and a second press exits.\n");
        out.append("- A right click copies an existing fullscreen selection; it never answers an approval and never falls back to the last reply.\n");
        return out.toString();
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

    /** @return 帮助行（英文）；保留旧调用，Controller 已改用语义 Markdown。 */
    public List<StyledLine> render(KeyBindings bindings, List<String> commands, RenderContext context, int width) {
        var out = new ArrayList<StyledLine>();
        out.add(StyledLine.of(DisplayWidth.ellipsize(UiMessages.HOTKEYS, width), context.theme().style("accent")));
        out.add(StyledLine.of(DisplayWidth.ellipsize("Commands", width), context.theme().style("muted")));
        for (String command : commands)
            out.addAll(StyledLine.wrap(List.of(new com.jadecode.tui.text.Span("  " + command,
                    context.theme().style("text"), null)), width));
        out.add(StyledLine.of(DisplayWidth.ellipsize("Keys", width), context.theme().style("muted")));
        for (var action : bindings.visible()) {
            if (action.defaultChords().isEmpty()) continue;
            out.addAll(StyledLine.wrap(List.of(new com.jadecode.tui.text.Span(
                    "  " + action.keys() + "  " + action.label(), context.theme().style("text"), null)), width));
        }
        for (String line : UiMessages.quickHelp())
            out.addAll(StyledLine.wrap(List.of(new com.jadecode.tui.text.Span(line,
                    context.theme().style("dim"), null)), width));
        return out;
    }
}
