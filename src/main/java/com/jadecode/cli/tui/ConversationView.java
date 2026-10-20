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
    /** 点阵逐格滚动，保持一列宽；不改变任务状态，只作为终端动画。 */
    private static final String SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";
    static final long ANIMATION_INTERVAL_NANOS = 100_000_000L;
    private final Markdown markdown = new Markdown();
    /**
     * 把当前显示资料排成一份完整画面，不请求模型，也不消费 SessionInbox。
     * Frame 中的行号从这份画面的开头计数，不是终端当前可见区的行号；渲染器负责换算。
     * 光标和鼠标区域与文本一起计算，折叠、换行后才不会点到另一张卡或另一项审批。
     * 这里只更新可重建的排版缓存、编辑器坐标及审批阅读位置，不删除消息原文，不写终端。
     */
    public Frame render(TuiState state, RenderContext context) {
        int width = context.width();
        var lines = new ArrayList<StyledLine>(); var hits = new ArrayList<Frame.Hit>();
        lines.add(StyledLine.of(DisplayWidth.ellipsize("JadeCode  /  coding agent", width), "heading"));
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
            lines.add(StyledLine.of(DisplayWidth.ellipsize("Queued · " + state.pendingKind + " · 空 Enter 插入当前轮次", width), "accent"));
            lines.addAll(StyledLine.wrap(List.of(new Span(DisplayWidth.ellipsize(state.pending, width * 2), "muted")), width));
        }
        String status = status(state, System.nanoTime());
        String members = state.actors.entrySet().stream().filter(e -> !e.getKey().equals("leader") && !e.getKey().contains("/"))
                .map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(" · "));
        // 状态、编辑器/选择器/审批及页脚视为一整块底部区域；空位只允许补在它前面。
        int bottomStartRow = lines.size();
        lines.add(StyledLine.of(DisplayWidth.ellipsize("── " + status + (members.isEmpty() ? "" : " · " + members), width), "accent"));
        int cursorRow, cursorColumn;
        // 2. 下方区域只有一个输入对象：审批优先，其次选择器，最后才是提示词编辑器。
        //    审批和选择器不改 editor，因此结束后原草稿和光标仍在。
        if (!state.approvals.isEmpty()) {
            Cursor cursor = approval(state, context, lines, hits);
            cursorRow = cursor.row(); cursorColumn = cursor.column();
        } else if (state.selector != null) {
            lines.add(StyledLine.of(DisplayWidth.ellipsize(state.selectorKind + " · filter: " + state.selector.filter(), width), "heading"));
            if (state.selectorKind.equals("Permission")) lines.addAll(StyledLine.wrap(
                    List.of(new Span(PermissionMenu.description(state.selector.selected()), "muted")), width));
            int first = lines.size();
            int max = Math.max(1, Math.min(6, context.height() / 3));
            lines.addAll(state.selector.render(width, max));
            lines.add(StyledLine.of(DisplayWidth.ellipsize("↑/↓ 选择 · Enter 确认 · Esc 返回草稿", width), "muted"));
            cursorRow = first + state.selector.selectedRow(max); cursorColumn = 0;
        } else {
            if (state.commands != null) {
                // 候选与编辑器同屏显示，不把输入区替换成只能过滤列表的选择器。
                lines.add(StyledLine.of(DisplayWidth.ellipsize("── Commands", width), "heading"));
                lines.addAll(state.commands.render(width, Math.max(1, Math.min(4, context.height() / 5))));
                lines.add(StyledLine.of(DisplayWidth.ellipsize("↑/↓ 选择 · Tab 补全 · Enter 执行 · Esc 关闭", width), "muted"));
            }
            var editor = state.editor.layout(width, Math.max(1, context.height() * 3 / 10));
            cursorRow = lines.size() + editor.row(); cursorColumn = editor.column(); lines.addAll(editor.lines());
        }
        if (context.height() < 8) return new Frame(lines.stream().map(l -> l.crop(width)).toList(), cursorRow, Math.min(width, cursorColumn), hits, bottomStartRow);
        // 3. 页脚使用会话只读快照。没有正数报告就不占一块位置，也不把未知显示为实测零。
        lines.add(StyledLine.of("─".repeat(Math.max(1, width)), "muted"));
        var fields = new ArrayList<String>();
        if (!state.model.isBlank()) fields.add(state.model);
        if (!state.permissionProfile.isBlank()) fields.add(state.permissionProfile);
        if (state.session != null) {
            fields.add(state.session.thinkingLabel());
            var total = state.session.usage().total();
            if (total.inputTokens() > 0 || total.outputTokens() > 0)
                fields.add("Tokens ↑" + tokens(total.inputTokens()) + " ↓" + tokens(total.outputTokens()));
            // 只有匹配最近历史的主 Agent 报告才有效；队友的独立上下文不占 Leader 窗口。
            if (state.session.context() != null) fields.add("上下文剩余约 " + state.session.context().remainingPercent() + "%");
        }
        lines.add(StyledLine.of(DisplayWidth.ellipsize(state.directory + (state.branch.isBlank() ? "" : " · " + state.branch), width), "muted"));
        lines.add(StyledLine.of(footer(fields, width), "muted"));
        // 审批已有自己的阅读/确认提示，不再让旧的全局 hint 挤占详情和按钮的位置。
        if (!state.hint.isBlank() && state.approvals.isEmpty()) lines.add(StyledLine.of(DisplayWidth.ellipsize(state.hint, width), "warning"));
        // 输入区域必须留在视口内；极小窗口下只显示当前输入附近，不让光标跑到屏幕外。
        return new Frame(lines.stream().map(l -> l.crop(width)).toList(), cursorRow, Math.min(width, cursorColumn), hits, bottomStartRow);
    }

    /** 一次局部布局的光标位置，行号仍属于完整 Frame，后续底部定位会一起平移。 */
    private record Cursor(int row, int column) { }

    /**
     * 把审批理由、完整参数和长期授权范围排成可滚动的详情，不静默丢掉命令尾部。
     * 页大小先扣除状态、标题、行数、按钮、提示和页脚；只读取其中一页来显示，原文完整保留。
     * 偏移属于本申请的界面状态，宽度变化后按新换行结果夹取，不能越界。
     * 本方法只添加显示行/hit 并更新阅读位置，不批准操作、不改草稿。
     * 过小窗口不显示可确认 hit，要求先扩大；避免按钮只露一部分时仍能键盘批准。
     */
    private Cursor approval(TuiState state, RenderContext context, List<StyledLine> lines, List<Frame.Hit> hits) {
        int width = context.width();
        var request = state.approvals.getFirst();
        String actor = state.displayActor(request.actor());
        lines.add(StyledLine.of(DisplayWidth.ellipsize("Approval · " + (actor.isEmpty() ? "" : actor + " · ") + request.tool(), width), "warning"));
        String source = request.reason() + "\n参数：\n" + request.arguments()
                + (request.suggestion().isEmpty() ? "" : "\n长期授权范围：\n" + request.suggestion());
        List<StyledLine> details = StyledLine.wrap(List.of(new Span(source, "normal")), width);
        boolean stacked = width < 42;
        int footerRows = context.height() < 8 ? 0 : 3;
        // 三个 1 依次是状态行、审批标题、详情行数；按钮横排一行/竖排三行，最后两行放操作说明。
        int pageSize = context.height() - footerRows - 1 - 1 - 1 - (stacked ? 3 : 1) - 2;
        state.approvalTotalLines = details.size(); state.approvalPageSize = Math.max(0, pageSize);
        if (pageSize < 1 || width < 20) {
            state.approvalPageSize = 0;
            int row = lines.size();
            lines.addAll(StyledLine.wrap(List.of(new Span("窗口太小，请扩大窗口后阅读并审批。", "warning")), width));
            return new Cursor(row, 0);
        }
        // 行号说明这一页只是全文的一部分；PgUp/PgDn、上下键或 Home/End 可以到达任意一行。
        state.approvalOffset = Math.max(0, Math.min(state.approvalOffset, Math.max(0, details.size() - pageSize)));
        int end = Math.min(details.size(), state.approvalOffset + pageSize);
        lines.add(StyledLine.of(DisplayWidth.ellipsize("详情 " + (state.approvalOffset + 1) + "–" + end + " / " + details.size() + " 行", width), "muted"));
        lines.addAll(details.subList(state.approvalOffset, end));
        int row = lines.size(), column = 0, selectedColumn = 0;
        String[] labels = { "仅本次", "允许此类命令", "拒绝" };
        String[] actions = { "approve:", "similar:", "deny:" };
        var buttons = new ArrayList<Span>();
        for (int i = 0; i < labels.length; i++) {
            boolean enabled = i != 1 || !request.suggestion().isEmpty();
            String text = "[" + (state.approvalChoice == i ? ">" : " ") + labels[i] + " ]";
            int size = DisplayWidth.width(text);
            String style = !enabled ? "muted" : state.approvalChoice == i
                    ? (i == 2 ? "approval-deny-selected" : "approval-selected") : (i == 2 ? "approval-deny" : "approval");
            if (stacked) lines.add(StyledLine.of(text, style));
            else {
                if (i > 0) { buttons.add(new Span("  ", "normal")); column += 2; }
                buttons.add(new Span(text, style));
            }
            if (state.approvalChoice == i) selectedColumn = column;
            if (enabled) hits.add(new Frame.Hit(row + (stacked ? i : 0), stacked ? 0 : column,
                    (stacked ? 0 : column) + size, actions[i] + request.id()));
            column += size;
        }
        if (!stacked) lines.add(new StyledLine(buttons));
        lines.add(StyledLine.of(DisplayWidth.ellipsize(stacked ? "↑↓/PgUp/PgDn 阅读" : "↑↓ / PgUp/PgDn 阅读 · Home/End 首尾", width), "muted"));
        lines.add(StyledLine.of(DisplayWidth.ellipsize(stacked ? "Tab 选择 · Enter 确认" : "←→ / Tab 选择 · Enter 确认 · Esc 不作答", width), "muted"));
        return new Cursor(row + (stacked ? Math.max(0, state.approvalChoice) : 0), stacked ? 0 : selectedColumn);
    }

    /**
     * 窄屏保留 /help 这一可用入口，其它字段不足时明确省略，不让长模型名静默吃掉全部提示。
     * 空字段先过滤，启动时还没取得模型名也不会出现前导分隔符。take 本身的契约不变。
     */
    private String footer(List<String> fields, int width) {
        String main = fields.stream().filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.joining(" · "));
        if (main.isEmpty()) return DisplayWidth.ellipsize("/help", width);
        if (width <= 8) return DisplayWidth.ellipsize("/help", width);
        return DisplayWidth.ellipsize(main, width - 8) + " · /help";
    }

    /**
     * 生成主 Agent 状态行，不从历史卡片或各个工具用时反推整轮耗时。
     * RUNNING/等待后台显示持续增加的计数；TURN_END 到达后 IDLE 显示“此轮用时”。
     * 刚启动或恢复的历史没有可信运行起点时不伪造零用时；关闭期间不继续播放忙碌动画。
     */
    static String status(TuiState state, long nowNanos) {
        if (state.closing) return "Closing…";
        String leader = state.actors.getOrDefault("leader", "IDLE");
        // Session 在写最终日志前可能先发布 IDLE；显示计数仍要等真正的 TURN_END 才冻结。
        if (leader.equals("IDLE") && state.turnActive("leader")) leader = "RUNNING";
        boolean busy = leader.equals("RUNNING") || leader.equals("WAITING_BACKGROUND");
        int tick = (int) Math.floorMod(nowNanos / ANIMATION_INTERVAL_NANOS, SPINNER.length());
        String text = (busy ? SPINNER.substring(tick, tick + 1) + " " : "") + leader;
        Long elapsed = state.turnElapsed("leader", nowNanos);
        if (elapsed != null && (busy || leader.equals("IDLE")))
            text += " · " + (leader.equals("IDLE") ? "此轮用时 " : "") + turnDuration(elapsed);
        long background = state.background.values().stream().filter(s -> s.equals("RUNNING")).count();
        return text + (background == 0 ? "" : " · background " + background);
    }

    /**
     * 用 K/M 等单位显示较大的 token 数，统一保留一位小数，例如 100.0K、1.4M。
     * 不足一千时仍显示实际整数条数；这里只改显示，持久化仍存 long 原值。
     * 四舍五入达到下一个单位时先换单位，避免显示成 1000.0K。
     */
    static String tokens(long value) {
        if (value < 1000) return Long.toString(value);
        String[] units = { "K", "M", "B", "T", "Q" };
        double number = value / 1000.0; int unit = 0;
        while (Math.round(number * 10) >= 10_000 && unit < units.length - 1) { number /= 1000; unit++; }
        return String.format(java.util.Locale.ROOT, "%.1f", number) + units[unit];
    }

    /**
     * 将单调时钟测得的纳秒排成易读时长。小于一秒保留整数毫秒，不足一分钟固定一位小数；
     * 整秒也显示为 29.0s，不因末位是零就删掉小数，以免运行中计数器反复变宽变窄。
     * 再长拆成天/小时/分钟/秒，不显示无意义的零项，也不把天继续变成年。
     * 这里只格式化，不重新计时，因而重绘和恢复不会让已结束的耗时增长。
     */
    static String duration(long nanos) {
        long millis = Math.max(0, nanos) / 1_000_000;
        if (millis < 1000) return millis + "ms";
        long seconds = millis / 1000;
        if (millis < 60_000) {
            long tenths = (millis + 50) / 100;
            if (tenths < 600) return (tenths / 10) + "." + (tenths % 10) + "s";
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
     * 状态栏与最终 Reply 共用的整轮显示格式，起止时刻仍由 Session 决定。
     * 不足一分钟固定一位小数，连 50ms 也显示为 0.1s；不显示毫秒。
     * 分钟以上使用紧凑的整数单位，如 1m02s、1h02m03s、1d02h03m04s，秒位不再带小数。
     * 较小单位补两位，整分钟仍显示 00s，避免计数器在分钟切换时忽隐忽现。
     */
    static String turnDuration(long nanos) {
        long millis = Math.max(0, nanos) / 1_000_000;
        if (millis < 60_000) {
            long tenths = (millis + 50) / 100;
            if (tenths < 600) return tenths / 10 + "." + tenths % 10 + "s";
            millis = 60_000; // 四舍五入到一分钟时换单位，不显示 60.0s。
        }
        long seconds = millis / 1000;
        long days = seconds / 86_400, hours = seconds / 3600 % 24, minutes = seconds / 60 % 60;
        seconds %= 60;
        // 大单位只从第一个非零项开始，但它后面的所有较小项都保留，包括零。
        if (days > 0) return String.format(java.util.Locale.ROOT, "%dd%02dh%02dm%02ds", days, hours, minutes, seconds);
        if (hours > 0) return String.format(java.util.Locale.ROOT, "%dh%02dm%02ds", hours, minutes, seconds);
        return String.format(java.util.Locale.ROOT, "%dm%02ds", minutes, seconds);
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
        if (entry.elapsed > 0) title += " · " + (entry.kind.equals("Reply") ? turnDuration(entry.elapsed) : duration(entry.elapsed));
        out.add(header(title, style, width));
        String body = entry.body.toString();
        if (entry.kind.equals("Tool")) {
            out.add(StyledLine.of(DisplayWidth.ellipsize("  " + entry.arguments, width), "muted"));
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
            if (cropped) out.add(StyledLine.of(DisplayWidth.ellipsize("… Ctrl+O 展开完整结果", width), "muted"));
        } else if (entry.kind.equals("Thinking")) out.addAll(StyledLine.wrap(List.of(new Span(body, "thinking")), width));
        // 参数快照中的每行都是一个字段。Markdown 会把普通换行合并为空格，不适用于这里。
        else if (entry.kind.equals("Model")) out.addAll(StyledLine.wrap(List.of(new Span(body, "normal")), width));
        else out.addAll(markdown.render(body, width));
        if (entry.status.equals("INCOMPLETE")) out.add(StyledLine.of(DisplayWidth.ellipsize("输出中断：这些片段不是完整响应", width), "error"));
        return List.copyOf(out);
    }

    /**
     * 为卡片标题补齐一条淡灰细线，不另占一行。文字保持原有状态色，细线单独使用低对比度。
     * 先按显示列宽截取标题，再计算剩余空间；中文／emoji 不按 char 计宽，极窄窗口只留标题。
     * 输入标题先清理控制字符，返回可缓存的显示行，不写终端或修改原条目。
     */
    private StyledLine header(String title, String style, int width) {
        String text = DisplayWidth.ellipsize(title, Math.max(1, width));
        int remaining = width - DisplayWidth.width(text);
        if (remaining < 3) return StyledLine.of(text, style);
        return new StyledLine(List.of(new Span(text, style), new Span(" " + "─".repeat(remaining - 1), "separator")));
    }
}
