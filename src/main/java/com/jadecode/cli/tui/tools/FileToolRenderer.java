package com.jadecode.cli.tui.tools;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.jadecode.cli.tui.model.ToolDisplayState;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.StyledLine;

/**
 * read_file/write_file/edit_file 的专用工具卡。
 *
 * <p>read：显示路径与可用范围，收起时正文为空，展开显示真实结果；write：显示路径和参数
 * 内容前 10 行并标 `Preview · not executed`；edit：old_text/new_text 完整时显示纯片段 diff，
 * 明确未执行。预览不会读取磁盘或执行工具；没有来源的行号/范围不伪造。</p>
 */
public final class FileToolRenderer implements ToolRenderer {
    private static final java.util.Set<String> NAMES = java.util.Set.of("read_file", "write_file", "edit_file");

    @Override public boolean supports(String name, com.jadecode.execution.ToolIdentity identity) {
        return NAMES.contains(name);
    }

    @Override public List<StyledLine> render(ToolDisplayState state, RenderContext context, int width) {
        var out = new ArrayList<StyledLine>();
        out.add(ToolPresentation.header(state, context, width));
        JsonNode args = ToolPresentation.arguments(state);
        String path = ToolPresentation.string(args, "path");
        String heading = !path.isBlank() ? path : ToolPresentation.safe(state.arguments());
        out.add(StyledLine.of(DisplayWidth.ellipsize("  " + heading, width), context.theme().style("muted")));
        switch (state.name()) {
            case "read_file" -> read(state, context, width, args, out);
            case "write_file" -> write(state, context, width, args, out);
            case "edit_file" -> edit(state, context, width, args, out);
            default -> { }
        }
        return out;
    }

    private void read(ToolDisplayState state, RenderContext context, int width, JsonNode args,
            List<StyledLine> out) {
        Integer offset = ToolPresentation.integer(args, "offset");
        Integer limit = ToolPresentation.integer(args, "limit");
        if (offset != null || limit != null) {
            String range = (offset == null ? "" : "from line " + offset) + (limit == null ? "" : " · " + limit + " lines");
            out.add(StyledLine.of(DisplayWidth.ellipsize("  " + range.strip(), width), context.theme().style("dim")));
        }
        if (!state.expanded()) {
            // 收起状态刻意不显示正文；结果仍完整保存在 state 中。
            if (state.result().isEmpty() && state.status() == ToolDisplayState.Status.RETURNED)
                out.add(StyledLine.of("  (no content)", context.theme().style("dim")));
            return;
        }
        out.addAll(ToolPresentation.body(state.result(), context, width, "normal"));
    }

    private void write(ToolDisplayState state, RenderContext context, int width, JsonNode args,
            List<StyledLine> out) {
        String content = ToolPresentation.string(args, "content");
        if (!content.isBlank()) {
            out.add(StyledLine.of(DisplayWidth.ellipsize("  Preview · not executed", width),
                    context.theme().style("warning")));
            var preview = ToolPresentation.head(content, 10, context, width, "toolOutput");
            out.addAll(preview);
            long total = DisplayWidth.lines(content).size();
            if (total > 10) out.add(ToolPresentation.truncated(total + " lines total; full content preserved",
                    context, width));
        }
        if (!state.result().isEmpty()) {
            out.add(StyledLine.of(DisplayWidth.ellipsize("  Result", width), context.theme().style("dim")));
            out.addAll(state.expanded() ? ToolPresentation.body(state.result(), context, width, "normal")
                    : ToolPresentation.head(state.result(), 10, context, width, "muted"));
        }
    }

    private void edit(ToolDisplayState state, RenderContext context, int width, JsonNode args,
            List<StyledLine> out) {
        String oldText = ToolPresentation.string(args, "old_text");
        String newText = ToolPresentation.string(args, "new_text");
        if (!oldText.isBlank() || !newText.isBlank()) {
            if (!state.expanded()) {
                out.addAll(ToolPresentation.head(oldText + "\n" + newText, 10, context, width, "muted"));
                out.add(ToolPresentation.truncated("Preview · not executed; Ctrl+O for diff", context, width));
            } else out.addAll(DiffPreview.render(oldText, newText, width, context.theme()));
        }
        if (!state.result().isEmpty()) {
            out.add(StyledLine.of(DisplayWidth.ellipsize("  Result", width), context.theme().style("dim")));
            out.addAll(ToolPresentation.head(state.result(), state.expanded() ? 200 : 10, context, width, "normal"));
        }
    }
}
