package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;

/**
 * /hotkeys：只列出当前真实可执行动作与实际命令，键位与行为共享 KeyBindings/CommandCatalog。
 *
 * <p>缺 Agent 后端的取消/树/模型切换等动作已标 HIDDEN，不会出现在这里；命令帮助只接受真实
 * 命令目录，不维护静态旧别名列表。没有默认键的动作不显示空键行，避免提示不存在的快捷方式。</p>
 */
public final class HotkeysView {
    /** @return 帮助行（英文）；commands 来自实际 CommandCatalog。 */
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
