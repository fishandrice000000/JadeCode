package com.jadecode.cli.tui.tools;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.jadecode.cli.tui.model.ToolDisplayState;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;

/**
 * bash 工具卡：`$ command`、折叠显示最后 5 个视觉行、展开全部真实结果。
 *
 * <p>没有执行增量来源，不画伪实时日志；没有用户 `!` shell 入口，也不提供 Esc 取消。长单行
 * 先按列折行再取尾部，因此“最后 5 行”指真实视觉行。完整结果始终保留在状态中，截断只影响
 * 折叠显示。</p>
 */
public final class ShellToolRenderer implements ToolRenderer {
    @Override public boolean supports(String name, com.jadecode.execution.ToolIdentity identity) {
        return name.equals("bash");
    }

    @Override public List<StyledLine> render(ToolDisplayState state, RenderContext context, int width) {
        var out = new ArrayList<StyledLine>();
        out.add(ToolPresentation.header(state, context, width));
        JsonNode args = ToolPresentation.arguments(state);
        String command = ToolPresentation.string(args, "command");
        if (command.isBlank()) command = ToolPresentation.safe(state.arguments());
        out.add(StyledLine.of(DisplayWidth.ellipsize("$ " + command.replace('\n', ' '), width),
                context.theme().style("text")));
        if (!state.result().isEmpty()) {
            if (state.expanded()) out.addAll(ToolPresentation.body(state.result(), context, width, "normal"));
            else {
                var tail = ToolPresentation.tail(state.result(), 5, context, width, "muted");
                out.addAll(tail);
                long total = ToolPresentation.body(state.result(), context, width, "normal").size();
                if (total > 5) out.add(ToolPresentation.truncated("Ctrl+O to expand · " + total + " lines",
                        context, width));
            }
        }
        return out;
    }
}
