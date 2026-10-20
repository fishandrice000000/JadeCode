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
            // Team 的成员变化也会改变标题。使缓存失效只重排显示，不修改消息或 actor ID。
            String actor = state.displayActor(entry.actor);
            if (entry.cacheWidth != width || entry.cacheVersion != entry.version || !entry.cacheActor.equals(actor)) {
                entry.cached = entry(entry, width, actor); entry.cacheWidth = width; entry.cacheVersion = entry.version;
                entry.cacheActor = actor;
            }
            if (entry.kind.equals("Thinking") && !(state.thinkingVisible ^ entry.expanded)) {
                lines.add(header("  ◇ " + (actor.isEmpty() ? "" : actor + " · ") + "Thinking… (Ctrl+T)", "thinking", width));
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
        String status = state.closing ? "Closing…" : spinner + " " + leader + (running == 0 ? "" : " · background " + running);
        String members = state.actors.entrySet().stream().filter(e -> !e.getKey().equals("leader") && !e.getKey().contains("/"))
                .map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(" · "));
        lines.add(StyledLine.of(DisplayWidth.take("── " + status + (members.isEmpty() ? "" : " · " + members), width), "accent"));
        int cursorRow, cursorColumn;
        // 2. 下方区域只有一个输入对象：审批优先，其次选择器，最后才是提示词编辑器。
        //    审批和选择器不改 editor，因此结束后原草稿和光标仍在。
        if (!state.approvals.isEmpty()) {
            var request = state.approvals.getFirst();
            String actor = state.displayActor(request.actor());
            lines.add(StyledLine.of(DisplayWidth.take("Approval · " + (actor.isEmpty() ? "" : actor + " · ") + request.tool(), width), "warning"));
            List<StyledLine> details = StyledLine.wrap(List.of(new Span(request.reason() + "\n" + request.arguments(), "normal")), width);
            lines.addAll(details.subList(0, Math.min(details.size(), Math.max(2, context.height() / 4))));
            if (!request.suggestion().isEmpty()) lines.addAll(StyledLine.wrap(
                    List.of(new Span("长期授权范围：" + request.suggestion(), "muted")), width));
            int row = lines.size();
            // 同一套文字计算显示宽度和点击位置。窄屏竖排；禁用项不产生点击动作。
            String[] labels = { "仅本次", "允许此类命令", "拒绝" };
            String[] actions = { "approve:", "similar:", "deny:" };
            var buttons = new ArrayList<Span>(); int column = 0, selectedColumn = 0;
            boolean stacked = width < 42;
            for (int i = 0; i < labels.length; i++) {
                boolean enabled = i != 1 || !request.suggestion().isEmpty();
                String text = "[" + (state.approvalChoice == i ? ">" : " ") + labels[i] + " ]";
                int size = DisplayWidth.width(text);
                String style = !enabled ? "muted" : state.approvalChoice == i ? "selected" : "normal";
                if (stacked) lines.add(StyledLine.of(text, style));
                else { if (i > 0) { buttons.add(new Span("  ", "normal")); column += 2; } buttons.add(new Span(text, style)); }
                if (state.approvalChoice == i) selectedColumn = column;
                if (enabled) hits.add(new Frame.Hit(row + (stacked ? i : 0), stacked ? 0 : column,
                        Math.min(width, (stacked ? 0 : column) + size), actions[i] + request.id()));
                column += size;
            }
            if (!stacked) lines.add(new StyledLine(buttons));
            lines.add(StyledLine.of(DisplayWidth.take("←/→ 或 Tab 选择，Enter 确认；Esc 不作答 · 草稿已保留", width), "muted"));
            cursorRow = row + (stacked ? Math.max(0, state.approvalChoice) : 0);
            cursorColumn = stacked ? 0 : selectedColumn;
        } else if (state.selector != null) {
            lines.add(StyledLine.of(DisplayWidth.take(state.selectorKind + " · filter: " + state.selector.filter(), width), "heading"));
            if (state.selectorKind.equals("Permission")) lines.addAll(StyledLine.wrap(
                    List.of(new Span(PermissionMenu.description(state.selector.selected()), "muted")), width));
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
        // 3. 页脚使用会话只读快照。没有正数报告就不占一块位置，也不把未知显示为实测零。
        lines.add(StyledLine.of("─".repeat(Math.max(1, width)), "muted"));
        var fields = new ArrayList<String>();
        fields.add(state.model);
        fields.add(state.permissionProfile);
        if (state.session != null) {
            fields.add(state.session.thinkingLabel());
            var total = state.session.usage().total();
            if (total.inputTokens() > 0 || total.outputTokens() > 0)
                fields.add("Tokens ↑" + tokens(total.inputTokens()) + " ↓" + tokens(total.outputTokens()));
            // 只有匹配最近历史的主 Agent 报告才有效；队友的独立上下文不占 Leader 窗口。
            if (state.session.context() != null) fields.add("上下文剩余约 " + state.session.context().remainingPercent() + "%");
        }
        fields.add("/help");
        lines.add(StyledLine.of(DisplayWidth.take(state.directory + (state.branch.isBlank() ? "" : " · " + state.branch), width), "muted"));
        lines.add(StyledLine.of(DisplayWidth.take(String.join(" · ", fields), width), "muted"));
        if (!state.hint.isBlank()) lines.add(StyledLine.of(DisplayWidth.take(state.hint, width), "warning"));
        // 输入区域必须留在视口内；极小窗口下只显示当前输入附近，不让光标跑到屏幕外。
        return new Frame(lines.stream().map(l -> l.crop(width)).toList(), cursorRow, Math.min(width, cursorColumn), hits);
    }

    /** 十进制缩写只用于显示，持久化仍存 long 原值；进位后不显示成 1000K。 */
    static String tokens(long value) {
        if (value < 1000) return Long.toString(value);
        String[] units = { "K", "M", "B", "T", "Q" };
        double number = value / 1000.0; int unit = 0;
        while (Math.round(number * 10) >= 10_000 && unit < units.length - 1) { number /= 1000; unit++; }
        return String.format(java.util.Locale.ROOT, "%.1f", number).replaceAll("\\.0$", "") + units[unit];
    }

    /**
     * 将单调时钟测得的纳秒排成易读时长。小于一秒保留毫秒，不足一分钟最多一位小数；
     * 再长拆成天/小时/分钟/秒，不显示无意义的零项，也不把天继续变成年。
     * 这里只格式化，不重新计时，因而重绘和恢复不会让已结束的耗时增长。
     */
    static String duration(long nanos) {
        long millis = Math.max(0, nanos) / 1_000_000;
        if (millis < 1000) return millis + "ms";
        long seconds = millis / 1000;
        if (millis < 60_000) {
            long tenths = (millis + 50) / 100;
            if (tenths < 600) return (tenths % 10 == 0 ? Long.toString(tenths / 10)
                    : (tenths / 10) + "." + (tenths % 10)) + "s";
            seconds = 60; // 四舍五入达到一分钟时进位，不显示 60.0s。
        }
        var parts = new ArrayList<String>();
        long[] units = { 86_400, 3600, 60, 1 }; String[] names = { "d", "h", "m", "s" };
        for (int i = 0; i < units.length; i++) {
            long amount = seconds / units[i]; seconds %= units[i];
            if (amount > 0) parts.add(amount + names[i]);
        }
        return String.join(" ", parts);
    }

    /**
     * 排一张消息卡。工具显示参数及结果，思考作为普通文本，其余正文按 Markdown 排版。
     * 折叠只缩短显示预览，Entry 中的原结果完整保留；正常返回也不自动涂成业务成功。
     */
    private List<StyledLine> entry(TuiState.Entry entry, int width, String actor) {
        var out = new ArrayList<StyledLine>();
        String style = entry.kind.equals("Error") || entry.status.equals("ERROR") || entry.status.equals("INCOMPLETE") ? "error"
                : entry.status.equals("BLOCKED") || entry.status.equals("INTERRUPTED") || entry.kind.equals("Warning") ? "warning"
                : entry.kind.equals("User") ? "user" : entry.kind.equals("Thinking") ? "thinking" : "accent";
        String title = (actor.isEmpty() ? "" : actor + " · ") + entry.title;
        // RETURNED 仍是内部执行事实，只省略它的常态文字；拒绝、异常、运行中等状态照常保留。
        if (!entry.status.isBlank() && !(entry.kind.equals("Tool") && entry.status.equals("RETURNED"))) title += " · " + entry.status;
        if (entry.elapsed > 0) title += " · " + duration(entry.elapsed);
        out.add(header(title, style, width));
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
            // 折叠预览降低对比度，避免很多工具输出抢过模型回答；展开后用普通正文色阅读。
            // 只改变显示片段的样式，不改正文、工具状态或返回给模型的结果。
            var wrapped = StyledLine.wrap(List.of(new Span(body, entry.expanded ? "normal" : "muted")), width);
            if (!entry.expanded && wrapped.size() > 10) { wrapped = wrapped.subList(0, 10); cropped = true; }
            out.addAll(wrapped);
            if (cropped) out.add(StyledLine.of(DisplayWidth.take("… Ctrl+O 展开完整结果", width), "muted"));
        } else if (entry.kind.equals("Thinking")) out.addAll(StyledLine.wrap(List.of(new Span(body, "thinking")), width));
        // 参数快照中的每行都是一个字段。Markdown 会把普通换行合并为空格，不适用于这里。
        else if (entry.kind.equals("Model")) out.addAll(StyledLine.wrap(List.of(new Span(body, "normal")), width));
        else out.addAll(markdown.render(body, width));
        if (entry.status.equals("INCOMPLETE")) out.add(StyledLine.of(DisplayWidth.take("输出中断：这些片段不是完整响应", width), "error"));
        return List.copyOf(out);
    }

    /**
     * 为卡片标题补齐一条淡灰细线，不另占一行。文字保持原有状态色，细线单独使用低对比度。
     * 先按显示列宽截取标题，再计算剩余空间；中文／emoji 不按 char 计宽，极窄窗口只留标题。
     * 输入标题先清理控制字符，返回可缓存的显示行，不写终端或修改原条目。
     */
    private StyledLine header(String title, String style, int width) {
        String text = DisplayWidth.take(DisplayWidth.clean(title).replace('\n', ' '), Math.max(1, width));
        int remaining = width - DisplayWidth.width(text);
        if (remaining < 3) return StyledLine.of(text, style);
        return new StyledLine(List.of(new Span(text, style), new Span(" " + "─".repeat(remaining - 1), "separator")));
    }
}
