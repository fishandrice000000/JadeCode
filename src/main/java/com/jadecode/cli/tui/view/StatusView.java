package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.cli.tui.UsageFormatter;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.StyledLine;

/**
 * 状态行：80ms spinner、真实整轮耗时、等待后台/排队事实与 Team 成员状态。
 *
 * <p>耗时只来自 TurnTimer 的 TURN_START/PAUSE/TURN_END 快照；审批暂停不增长，TURN_END 冻结，
 * 历史 unknown 不画 0.0s。没有 retry/ETA/压缩百分比来源就只显示真实状态。后台 0 项隐藏；
 * Team 身份使用真实 actor，不在 UI 改写运行主体。</p>
 */
public final class StatusView {
    private static final String SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";

    /** 渲染单行状态（必要时在极窄宽度下只留状态）。 */
    public List<StyledLine> render(TuiState state, RenderContext context, int width) {
        String leader = state.actors.getOrDefault("leader", "IDLE");
        if (leader.equals("IDLE") && state.turnActive("leader")) leader = "RUNNING";
        boolean busy = leader.equals("RUNNING") || leader.equals("WAITING_BACKGROUND");
        int tick = (int) Math.floorMod(context.nowNanos() / com.jadecode.tui.core.RenderScheduler.ANIMATION_INTERVAL_NANOS,
                SPINNER.length());
        String label = switch (leader) {
            case "RUNNING" -> UiMessages.WORKING;
            case "WAITING_BACKGROUND" -> UiMessages.WAITING;
            case "IDLE" -> UiMessages.IDLE;
            case "CLOSED" -> UiMessages.SESSION_CLOSED;
            default -> leader;
        };
        var text = new StringBuilder();
        if (busy) text.append(SPINNER.charAt(tick)).append(' ');
        text.append(label);
        Long elapsed = state.turnElapsed("leader", context.nowNanos());
        if (elapsed != null && (busy || leader.equals("IDLE")))
            text.append(" · ").append(leader.equals("IDLE") ? "turn " : "").append(UsageFormatter.turnDuration(elapsed));
        long background = state.background.values().stream().filter(value -> value.equals("RUNNING")).count();
        if (background > 0) text.append(" · ").append(background).append(' ').append(UiMessages.BACKGROUND);
        long tools = state.activeTools();
        if (tools > 0) text.append(" · ").append(tools).append(" tool").append(tools == 1 ? "" : "s");
        if (!state.pending.isBlank()) text.append(" · ").append(UiMessages.PENDING).append(" (").append(state.pendingKind).append(")");
        var members = new ArrayList<String>();
        state.actors.forEach((actor, actorState) -> {
            if (!actor.isBlank() && !actor.equals("leader") && !actor.contains("/") && !actorState.equals("CLOSED"))
                members.add(actor + " " + UiMessages.status(actorState));
        });
        if (!members.isEmpty()) text.append(" · ").append(String.join(" · ", members));
        List<StyledLine> lines = new ArrayList<>();
        lines.add(StyledLine.of(DisplayWidth.ellipsize(text.toString(), width),
                context.theme().style(busy ? "accent" : "muted")));
        if (!state.pending.isBlank())
            lines.addAll(StyledLine.wrap(List.of(new Span(DisplayWidth.ellipsize("  " + state.pending, width * 2),
                    context.theme().style("dim"), null)), width));
        return lines;
    }
}
