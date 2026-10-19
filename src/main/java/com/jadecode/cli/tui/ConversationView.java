package com.jadecode.cli.tui;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.components.Markdown;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.StyledLine;

/**
 * 将界面状态组合成一帧。消息、光标和点击区域在同一次布局里计算，避免位置错配。
 * 已完成条目缓存按宽度及版本复用；主题只改变编码颜色，不必重新解析 Markdown。
 */
public final class ConversationView {
    private final Markdown markdown = new Markdown();
    /**
     * 把当前显示资料排成一份完整画面，不请求模型，也不消费 SessionInbox。
     * Frame 中的行号从这份画面的开头计数，不是终端当前可见区的行号；渲染器负责换算。
     * 光标和鼠标区域与文本一起计算，折叠、换行后才不会点到另一张卡或另一项审批。
     * 这里只更新可重建的排版缓存及编辑器坐标，不删除消息原文，不写终端。
     */
    public Frame render(TuiState state, RenderContext context) {
        int width = context.width();
        var lines = new ArrayList<StyledLine>(); var hits = new ArrayList<Frame.Hit>();
        lines.add(StyledLine.of(DisplayWidth.take("JadeCode  /  coding agent", width), "heading"));
        // 1. 先按到达顺序排消息。没改过的条目复用排版，避免每帧重新解析所有 Markdown。
        for (var entry : state.entries) {
            int start = lines.size();
            if (entry.cacheWidth != width || entry.cacheVersion != entry.version) {
                entry.cached = entry(entry, width); entry.cacheWidth = width; entry.cacheVersion = entry.version;
            }
            if (entry.kind.equals("Thinking") && !(state.thinkingVisible ^ entry.expanded)) {
                lines.add(StyledLine.of(DisplayWidth.take("  ◇ " + TuiState.actorName(entry.actor) + " · Thinking… (click / Ctrl+T)", width), "thinking"));
            } else lines.addAll(entry.cached);
            if (entry.kind.equals("Tool") || entry.kind.equals("Thinking"))
                for (int row = start; row < lines.size(); row++) hits.add(new Frame.Hit(row, 0, width, "entry:" + entry.id));
            lines.add(StyledLine.of("", "normal"));
        }
        if (!state.pending.isEmpty()) {
            // 这里显示的是 Session 发来的缓冲快照；不是在界面里再排一份可执行任务。
            lines.add(StyledLine.of(DisplayWidth.take("Queued · " + state.pendingKind + " · 空 Enter 插入当前轮次", width), "accent"));
            lines.addAll(StyledLine.wrap(List.of(new Span(DisplayWidth.take(DisplayWidth.clean(state.pending).replace('\n', ' '), width * 2), "muted")), width));
        }
        String leader = state.actors.getOrDefault("leader", "IDLE");
        long running = state.background.values().stream().filter(s -> s.equals("RUNNING")).count();
        int tick = (int) (System.nanoTime() / 160_000_000L % 4);
        String spinner = leader.equals("RUNNING") ? "◐◓◑◒".substring(tick, tick + 1) : "○";
        String status = state.closing ? "Closing…" : spinner + " " + leader + " · background " + running;
        String members = state.actors.entrySet().stream().filter(e -> !e.getKey().equals("leader") && !e.getKey().contains("/"))
                .map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(" · "));
        lines.add(StyledLine.of(DisplayWidth.take("── " + status + (members.isEmpty() ? "" : " · " + members), width), "accent"));
        int cursorRow, cursorColumn;
        // 2. 下方区域只有一个输入对象：审批优先，其次选择器，最后才是提示词编辑器。
        //    审批和选择器不改 editor，因此结束后原草稿和光标仍在。
        if (!state.approvals.isEmpty()) {
            var request = state.approvals.getFirst();
            lines.add(StyledLine.of(DisplayWidth.take("Approval · " + request.actor() + " · " + request.tool(), width), "warning"));
            List<StyledLine> details = StyledLine.wrap(List.of(new Span(request.reason() + "\n" + request.arguments(), "normal")), width);
            lines.addAll(details.subList(0, Math.min(details.size(), Math.max(2, context.height() / 4))));
            int row = lines.size();
            // 选中项同时有文字箭头；NO_COLOR 下也能知道 Enter 将选择什么。
            String allow = state.approvalChoice == 0 ? "[>同意 ]" : "[ 同意 ]";
            String deny = state.approvalChoice == 1 ? "[>拒绝 ]" : "[ 拒绝 ]";
            if (width >= 18) {
                lines.add(new StyledLine(List.of(new Span(allow, state.approvalChoice == 0 ? "selected" : "normal"),
                        new Span("  ", "normal"), new Span(deny, state.approvalChoice == 1 ? "selected" : "normal"))));
                hits.add(new Frame.Hit(row, 0, 8, "approve:" + request.id()));
                hits.add(new Frame.Hit(row, 10, 18, "deny:" + request.id()));
            } else {
                // 窄窗口竖排时同步移动命中区域，不能只改文字位置不改审批点击位置。
                lines.add(StyledLine.of(allow, state.approvalChoice == 0 ? "selected" : "normal"));
                lines.add(StyledLine.of(deny, state.approvalChoice == 1 ? "selected" : "normal"));
                hits.add(new Frame.Hit(row, 0, Math.min(8, width), "approve:" + request.id()));
                hits.add(new Frame.Hit(row + 1, 0, Math.min(8, width), "deny:" + request.id()));
            }
            lines.add(StyledLine.of(DisplayWidth.take("←/→ 或 Tab 选择，Enter 确认；Esc 不作答 · 草稿已保留", width), "muted"));
            cursorRow = row + (width < 18 && state.approvalChoice == 1 ? 1 : 0);
            cursorColumn = width >= 18 && state.approvalChoice == 1 ? 10 : 0;
        } else if (state.selector != null) {
            lines.add(StyledLine.of(DisplayWidth.take(state.selectorKind + " · filter: " + state.selector.filter(), width), "heading"));
            int first = lines.size();
            lines.addAll(state.selector.render(width, Math.max(1, Math.min(6, context.height() / 3))));
            lines.add(StyledLine.of(DisplayWidth.take("↑/↓ 选择 · Enter 确认 · Esc 返回草稿", width), "muted"));
            cursorRow = first; cursorColumn = 0;
        } else {
            if (state.commands != null) {
                // 候选与编辑器同屏显示，不把输入区替换成只能过滤列表的选择器。
                lines.add(StyledLine.of(DisplayWidth.take("── Commands", width), "heading"));
                lines.addAll(state.commands.render(width, Math.max(1, Math.min(4, context.height() / 5))));
                lines.add(StyledLine.of(DisplayWidth.take("↑/↓ 选择 · Tab 补全 · Enter 执行 · Esc 关闭", width), "muted"));
            }
            var editor = state.editor.layout(width, Math.max(1, context.height() * 3 / 10));
            cursorRow = lines.size() + editor.row(); cursorColumn = editor.column(); lines.addAll(editor.lines());
        }
        if (context.height() < 8) return new Frame(lines.stream().map(l -> l.crop(width)).toList(), cursorRow, Math.min(width, cursorColumn), hits);
        // 3. 页脚只使用程序实际取得的数据。没有后端用量时明确写“未报告”，不能把字符数当 token。
        lines.add(StyledLine.of("─".repeat(Math.max(1, width)), "muted"));
        String usage = state.usageReported ? "Leader tokens ↑" + state.inputTokens + " ↓" + state.outputTokens : "Leader tokens 未报告";
        if (state.missingUsage > 0) usage += "（缺 " + state.missingUsage + " 次报告）";
        lines.add(StyledLine.of(DisplayWidth.take(state.directory + (state.branch.isBlank() ? "" : " · " + state.branch), width), "muted"));
        lines.add(StyledLine.of(DisplayWidth.take(state.model + " · " + usage + " · /help", width), "muted"));
        if (!state.hint.isBlank()) lines.add(StyledLine.of(DisplayWidth.take(state.hint, width), "warning"));
        // 输入区域必须留在视口内；极小窗口下只显示当前输入附近，不让光标跑到屏幕外。
        return new Frame(lines.stream().map(l -> l.crop(width)).toList(), cursorRow, Math.min(width, cursorColumn), hits);
    }

    /**
     * 排一张消息卡。工具显示参数及结果，思考作为普通文本，其余正文按 Markdown 排版。
     * 折叠只缩短显示预览，Entry 中的原结果完整保留；正常返回也不自动涂成业务成功。
     */
    private List<StyledLine> entry(TuiState.Entry entry, int width) {
        var out = new ArrayList<StyledLine>();
        String style = entry.kind.equals("Error") || entry.status.equals("ERROR") || entry.status.equals("INCOMPLETE") ? "error"
                : entry.status.equals("BLOCKED") || entry.status.equals("INTERRUPTED") || entry.kind.equals("Warning") ? "warning"
                : entry.kind.equals("User") ? "user" : entry.kind.equals("Thinking") ? "thinking" : "accent";
        String title = TuiState.actorName(entry.actor) + " · " + entry.title;
        if (!entry.status.isBlank()) title += " · " + entry.status;
        if (entry.elapsed > 0) title += " · " + entry.elapsed / 1_000_000 + "ms";
        out.add(StyledLine.of(DisplayWidth.take(title, width), style));
        String body = entry.body.toString();
        if (entry.kind.equals("Tool")) {
            out.add(StyledLine.of(DisplayWidth.take("  " + DisplayWidth.clean(entry.arguments).replace('\n', ' '), width), "muted"));
            boolean cropped = false;
            if (!entry.expanded) {
                // 预览同时限制逻辑行和字符，防止一个超长的单行输出在折叠状态生成数万显示行。
                String preview = body.lines().limit(10).collect(java.util.stream.Collectors.joining("\n"));
                if (preview.length() > width * 10) preview = preview.substring(0, Math.min(preview.length(), width * 10));
                cropped = preview.length() < body.length(); body = preview;
            }
            var wrapped = StyledLine.wrap(List.of(new Span(body, "normal")), width);
            if (!entry.expanded && wrapped.size() > 10) { wrapped = wrapped.subList(0, 10); cropped = true; }
            out.addAll(wrapped);
            if (cropped) out.add(StyledLine.of(DisplayWidth.take("… click / Ctrl+O 展开完整结果", width), "muted"));
        } else if (entry.kind.equals("Thinking")) out.addAll(StyledLine.wrap(List.of(new Span(body, "thinking")), width));
        else out.addAll(markdown.render(body, width));
        if (entry.status.equals("INCOMPLETE")) out.add(StyledLine.of(DisplayWidth.take("输出中断：这些片段不是完整响应", width), "error"));
        return List.copyOf(out);
    }
}
