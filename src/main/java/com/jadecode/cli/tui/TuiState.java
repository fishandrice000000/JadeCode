package com.jadecode.cli.tui;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jadecode.cli.tui.approval.ApprovalViewState;
import com.jadecode.cli.tui.model.TranscriptEntry;
import com.jadecode.cli.tui.model.TranscriptModel;
import com.jadecode.cli.tui.model.ToolDisplayState;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.tui.components.Editor;
import com.jadecode.tui.components.Input;
import com.jadecode.tui.components.SelectList;

/**
 * 单 UI 线程拥有的显示聚合态：转录、编辑器、审批、菜单、计时与只读会话快照。
 *
 * <p>不是模型 history、不是任务调度器、不是用量账本。异步事件只有在 UI 线程采用后才修改
 * 这里；旧 generation 的迟到事件由控制器丢弃。计时来自执行线程的真实 TURN_START/PAUSE/
 * RESUME/TURN_END 通知，不从卡片建立时刻或工具耗时反推；历史 unknown 不补 0.0s。</p>
 */
public final class TuiState {
    /** 有界 widget slot 上限（F090）；超过时由 DockView 裁剪并报告。 */
    public static final int MAX_WIDGET_ROWS = 10;

    /** 显示转录（消息/工具/流式块）。 */
    public final TranscriptModel transcript = new TranscriptModel();
    /** Pi 多行编辑器；由 root 装配后替换/设置 listener。 */
    public Editor editor;
    /** 当前审批显示状态；未审批时无 request。 */
    public final ApprovalViewState approval = new ApprovalViewState();
    /** 排队中的审批快照；只用于显示“还有 N 项”。 */
    public final ArrayDeque<UiEvent.ApprovalRequested> approvals = new ArrayDeque<>();
    /** 当前选择器的候选/过滤真源；selectorInput 只保存实际搜索框文字与光标。 */
    public SelectList selector;
    public Input selectorInput;
    public String selectorKind = "";
    /** 选择器面板的专用排版元数据，由 Controller 维护；DockView 不访问 Host/配置。 */
    public String selectorTitle = "", selectorDescription = "", selectorFooter = "", selectorStatus = "";
    public com.jadecode.cli.tui.SessionPicker sessionPicker;
    public SelectList commands;
    public boolean commandsDismissed = true;

    public long generation;
    public String sessionId = "";
    public boolean loadingResume, closing;
    public String hint = "";
    public String pending = "", pendingKind = "";
    public String model = "", modelInfo = "", branch = "", directory = "";
    public String permissionProfile = "Workspace";
    public boolean thinkingVisible = true, toolsExpanded, quietStartup;
    public com.jadecode.session.SessionRuntime.Display session;
    /** 只读 MCP 服务快照；由 root 每次刷新时更新，不在这里连接。 */
    public List<com.jadecode.mcp.McpManager.ServerSnapshot> mcpServers = List.of();
    /** 上方/下方 widget 行（通用 UI slot），最多 MAX_WIDGET_ROWS 行。 */
    public final List<com.jadecode.tui.text.StyledLine> upperWidgets = new java.util.ArrayList<>();
    public final List<com.jadecode.tui.text.StyledLine> lowerWidgets = new java.util.ArrayList<>();
    /** 输入生产者序号/终端代次水位，审批/点击证据使用。 */
    public long inputSequence;
    public long terminalEpoch;
    /** 最近一次成功呈现的帧（由 root 在 renderer 返回后安装）。 */
    public com.jadecode.tui.core.PresentedFrame presented;
    /** 右上角临时 flash：只属于 UI，不进转录/模型历史/用量。 */
    private String flash = "";
    private long flashDeadlineNanos;
    private boolean flashError;

    public final Map<String, String> actors = new LinkedHashMap<>();
    public final Map<String, String> background = new LinkedHashMap<>();
    private final Map<String, TurnView> turns = new LinkedHashMap<>();
    private final Map<String, Long> completedTurnTimes = new LinkedHashMap<>();

    private static final class TurnView {
        final String id;
        final long startedNanos;
        long sampledAt;
        long sampledElapsed;
        boolean paused;
        TranscriptEntry lastReply;
        TurnView(String id, long startedNanos) {
            this.id = id; this.startedNanos = startedNanos; sampledAt = startedNanos;
        }
    }

    /** 设置一次临时提示；同一文本重复设置只刷新期限。 */
    public void flash(String text, long durationMillis, boolean error) {
        if (text == null || text.isBlank()) return;
        flash = text;
        flashError = error;
        flashDeadlineNanos = System.nanoTime() + Math.max(100, durationMillis) * 1_000_000L;
    }

    /** @return 当前是否应显示 flash。 */
    public boolean flashActive() { return !flash.isEmpty() && System.nanoTime() < flashDeadlineNanos; }

    /** @return flash 文本；不显示时为空。 */
    public String flashText() { return flashActive() ? flash : ""; }

    /** @return flash 是否为错误样式。 */
    public boolean flashError() { return flashError; }

    /** @return 下一次 flash 到期时刻（纳秒）；无提示返回 Long.MAX_VALUE。 */
    public long flashDeadline() { return flash.isEmpty() ? Long.MAX_VALUE : flashDeadlineNanos; }

    /** 到期清除；返回是否发生了变化，供宿主请求一次重绘。 */
    public boolean clearExpiredFlash() {
        if (flash.isEmpty() || System.nanoTime() < flashDeadlineNanos) return false;
        flash = ""; flashError = false; flashDeadlineNanos = 0;
        return true;
    }

    /** 退出/resize/会话切换清理旧提示。 */
    public void clearFlash() { flash = ""; flashError = false; flashDeadlineNanos = 0; }

    /** 新运行时代次：清空转录/计时/审批/菜单，保留草稿与主题等 UI 偏好。 */
    public void installGeneration(long value) {
        generation = value; transcript.reset(value); actors.clear(); background.clear();
        turns.clear(); completedTurnTimes.clear(); approvals.clear(); approval.clear();
        clearFlash();
        pending = ""; pendingKind = ""; loadingResume = false;
    }

    /** 采用程序整条消息。Reply 已完成；错误保留真实状态。 */
    public void message(UiEvent.Message message) {
        var entry = transcript.message(message.actor(), message.label(), message.text(), message.error());
        TurnView turn = turns.get(message.actor());
        if (turn != null && entry.kind() == TranscriptEntry.Kind.REPLY) turn.lastReply = entry;
    }

    /** 采用流式增量。 */
    public void delta(UiEvent.Delta delta) {
        var entry = transcript.delta(delta.request(), delta.actor(), delta.kind(), delta.text());
        TurnView turn = turns.get(delta.actor());
        if (turn != null && entry.kind() == TranscriptEntry.Kind.REPLY) turn.lastReply = entry;
    }

    /** 请求结束；失败片段保留，不写成功。 */
    public void end(UiEvent.StreamEnd end) { transcript.endStream(end.request(), end.failed(), end.stopReason()); }

    /** 是否还有未冻结的大轮次。 */
    public boolean hasActiveTurn() { return !turns.isEmpty(); }

    /** @return 指定 actor 是否有活动轮次。 */
    public boolean turnActive(String actor) { return turns.containsKey(actor); }

    /**
     * @return 本轮当前/冻结耗时（纳秒）；null 表示没有可靠计时。
     * 审批暂停时不增长；完成后冻结，不因后续重绘/队友工作增长。
     */
    public Long turnElapsed(String actor, long nowNanos) {
        TurnView active = turns.get(actor);
        if (active != null) return active.startedNanos == 0 ? null : active.sampledElapsed
                + (active.paused ? 0 : Math.max(0, nowNanos - active.sampledAt));
        return completedTurnTimes.get(actor);
    }

    /** 采用运行事实：计时/模型/身份/后台/工具；工具卡片细节委托给 TranscriptModel。 */
    public void execution(ExecutionNotice notice) {
        String actor = notice.actor();
        switch (notice.kind()) {
            case MODEL -> { if (actor.equals("leader")) modelInfo = notice.detail(); }
            case TURN_START -> {
                completedTurnTimes.remove(actor);
                turns.put(actor, new TurnView(notice.id(), notice.startedNanos()));
            }
            case TURN_END -> {
                TurnView turn = turns.get(actor);
                if (turn != null && turn.id.equals(notice.id())) {
                    if (turn.startedNanos != 0 || notice.elapsedNanos() > 0)
                        completedTurnTimes.put(actor, notice.elapsedNanos());
                    TranscriptEntry reply = turn.lastReply;
                    if (notice.state().equals("DONE") && notice.name().equals("Reply") && reply != null
                            && reply.status().equals("DONE")) reply.elapsed(notice.elapsedNanos());
                    turns.remove(actor);
                }
            }
            case TURN_PAUSE, TURN_RESUME -> {
                TurnView turn = turns.get(actor);
                if (turn != null && turn.id.equals(notice.id())) {
                    turn.sampledElapsed = notice.elapsedNanos(); turn.sampledAt = notice.startedNanos();
                    turn.paused = notice.kind() == ExecutionNotice.Kind.TURN_PAUSE;
                }
            }
            case SESSION -> {
                if (!actor.isBlank()) actors.put(actor, notice.state());
                if (actor.equals("leader") && !notice.detail().isBlank()) model = notice.detail();
                if (notice.state().equals("CLOSED")) {
                    turns.remove(actor);
                    transcript.execution(notice);
                }
            }
            case TOOL_START, TOOL_END -> transcript.execution(notice);
            case BUFFER -> {
                if (actor.equals("leader")) { pending = notice.detail(); pendingKind = notice.state(); }
            }
            case BACKGROUND -> background.put(actor + "/" + notice.id(), notice.state());
            case COMPACT -> transcript.addNotice(actor, "Context", notice.detail(), false);
            case SUBAGENT -> transcript.addNotice(actor, "Subagent", notice.detail(), false);
            case USAGE -> { /* 用量只从 SessionUsage 快照读取，不在 UI 累加。 */ }
        }
    }

    /** @return 是否存在未关闭的常驻队友；单人会话不显示 Leader 前缀。 */
    public boolean teamActive() {
        return actors.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith("agent_")
                && !entry.getKey().contains("/") && !entry.getValue().equals("CLOSED"));
    }

    /** 单人模式省略 leader；子代理保留自身名字，真实 actor ID 不被改写。 */
    public String displayActor(String actor) {
        if (actor == null) return "";
        if (teamActive()) return actorName(actor);
        return actor.equals("leader") ? "" : actor.startsWith("leader/") ? actor.substring(7) : actor;
    }

    /** actor 的可读名称（Team 模式）。 */
    public static String actorName(String actor) {
        return actor == null ? "" : actor.equals("leader") ? "Leader"
                : actor.startsWith("leader/") ? "Leader / " + actor.substring(7) : actor;
    }

    /** @return 活跃工具卡数量（状态栏提示）。 */
    public long activeTools() { return transcript.tools().stream().filter(ToolDisplayState::active).count(); }
}
