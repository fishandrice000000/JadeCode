package com.jadecode.cli.backend;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.execution.ExecutionObserver;
import com.jadecode.execution.ToolIdentity;
import com.jadecode.util.Json;

/**
 * 将执行器和会话发布的 ExecutionNotice 转为 execution.notice，携带 Agent 身份、宿主工具身份、状态、耗时和用量。
 * 与此同时推进 DisplayClock，维护队友、后台和用户缓冲的轻量状态，并请求节流快照刷新。
 */
public final class BackendObserver implements ExecutionObserver {
    /** detail 超过这个长度时改用 blob，避免单帧过大。 */

    private final WireContext wire;
    private final DisplayClock clock;
    private final Map<String, String> actors = new ConcurrentHashMap<>();
    private final Map<String, String> background = new ConcurrentHashMap<>();
    private final AtomicLong noticeIds = new AtomicLong();
    private final Consumer<Void> snapshotRefresh;
    private final AtomicLong lastRefresh = new AtomicLong();
    private volatile String bufferSummary = "";
    private volatile String bufferState = "";

    /**
     * 绑定执行事实出口、轮次时钟与状态刷新回调。会话运行时发布通知后，观察器先更新时钟，再发送结构事件并刷新轻量状态。
     *
     * @param wire 当前会话代次的事件出口与身份上下文
     * @param clock 按 Agent 记录轮次显示耗时的时钟
     * @param snapshotRefresh 需要刷新后端快照时调用（内部已节流）；不得在观察线程等渲染
     */
    public BackendObserver(WireContext wire, DisplayClock clock, Consumer<Void> snapshotRefresh) {
        this.wire = wire;
        this.clock = clock;
        this.snapshotRefresh = snapshotRefresh;
    }

    /**
     * 收到执行事实后先更新轮次时钟，再编码事件身份、状态、用量、耗时和宿主工具身份，发送 execution.notice。
     * 随后更新 Team、后台和缓冲状态，并按事件类型请求状态快照。
     *
     * @param notice 当前执行事实，包含 Agent、事件种类、状态、工具身份与用量
     */
    @Override
    public void accept(ExecutionNotice notice) {
        // 计时先更新：即使下面的展示事件失败，业务状态也不会被显示端裁决。
        clockNotice(notice);
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("noticeId", Long.toString(noticeIds.incrementAndGet()));
        payload.put("actor", notice.actor());
        payload.put("kind", notice.kind().name());
        payload.put("id", notice.id());
        payload.put("name", notice.name());
        payload.put("state", notice.state());
        payload.put("inputTokens", Long.toString(notice.inputTokens()));
        payload.put("outputTokens", Long.toString(notice.outputTokens()));
        payload.put("elapsedMs", notice.elapsedNanos() / 1_000_000L);
        String detail = notice.detail() == null ? "" : notice.detail();
        payload.put("detail", detail);
        ToolIdentity identity = notice.identity();
        if (identity == null)
            payload.putNull("toolIdentity");
        else {
            ObjectNode tool = payload.putObject("toolIdentity");
            tool.put("kind", identity.kind().name());
            tool.put("serverName", identity.serverName());
            tool.put("originalName", identity.originalName());
            tool.put("hostReadOnly", identity.hostReadOnly());
        }
        wire.session().sendEvent("execution.notice", payload, wire.sessionId(), wire.generation());
        trackState(notice);
        maybeRefreshSnapshot(notice.kind());
    }

    /**
     * 按轮次开始、暂停、恢复、停止展示计时和结束事件推进对应 Agent 的显示时钟；其他事件维持现有时钟状态。
     *
     * @param notice 当前执行事实，包含 Agent、事件种类、状态、工具身份与用量
     */
    private void clockNotice(ExecutionNotice notice) {
        String actor = notice.actor();
        switch (notice.kind()) {
            case TURN_START -> clock.started(actor, notice.id());
            case TURN_PAUSE -> clock.paused(actor, true, notice.elapsedNanos());
            case TURN_RESUME -> clock.paused(actor, false, notice.elapsedNanos());
            case TURN_DISPLAY_STOP -> clock.displayStopped(actor, true, notice.elapsedNanos());
            case TURN_DISPLAY_RESUME -> clock.displayStopped(actor, false, notice.elapsedNanos());
            case TURN_END -> clock.ended(actor, notice.id(), notice.elapsedNanos());
            default -> {
            }
        }
    }

    /**
     * 把会话、用户缓冲和后台通知更新到快照缓存。关闭的 Agent 和完成的后台项从缓存移除；Leader 关闭时重置时钟，
     * 单个队友关闭时结束该队友时钟。
     *
     * @param notice 当前执行事实，包含 Agent、事件种类、状态、工具身份与用量
     */
    private void trackState(ExecutionNotice notice) {
        switch (notice.kind()) {
            case SESSION -> {
                String actor = notice.actor();
                if (!actor.isBlank()) {
                    if ("CLOSED".equals(notice.state()))
                        actors.remove(actor);
                    else
                        actors.put(actor, notice.state());
                }
                if ("CLOSED".equals(notice.state())) {
                    // 关闭单个队友只冻结它的时钟；Leader 仍在执行/等待，不能把全队计时清空。
                    if ("leader".equals(actor))
                        clock.reset();
                    else if (!actor.isBlank())
                        clock.ended(actor, null, 0);
                }
            }
            case BUFFER -> {
                bufferSummary = notice.detail() == null ? "" : notice.detail();
                bufferState = notice.state();
            }
            case BACKGROUND -> {
                String key = notice.actor() + "/" + notice.id();
                if ("STOPPED".equals(notice.state()) || "DONE".equals(notice.state())
                        || "CANCELLED".equals(notice.state()))
                    background.remove(key);
                else
                    background.put(key, notice.state());
            }
            default -> {
            }
        }
    }

    /**
     * 常规事件按 250ms 节流刷新 footer；Team 与会话状态变化立即请求快照，不轮询。
     *
     * @param kind 本次执行通知种类，用于决定快照刷新策略
     */
    private void maybeRefreshSnapshot(ExecutionNotice.Kind kind) {
        boolean interesting = switch (kind) {
            case USAGE, TOOL_END, TURN_END, SESSION, MODEL, BUFFER, BACKGROUND, COMPACT, WORKFLOW, GOAL, TEAM -> true;
            default -> false;
        };
        if (!interesting || snapshotRefresh == null)
            return;
        long now = System.nanoTime();
        long previous = lastRefresh.get();
        if (kind != ExecutionNotice.Kind.TEAM && kind != ExecutionNotice.Kind.SESSION && now - previous < 250_000_000L)
            return;
        if (!lastRefresh.compareAndSet(previous, now))
            return;
        snapshotRefresh.accept(null);
    }

    /**
     * 读取当前已登记的 Agent 状态，形成供 Team 面板使用的不可变映射。
     *
     * @return 真实 Actor → 状态快照；Node 用它显示 Team 身份。
     */
    public Map<String, String> actors() {
        return Map.copyOf(actors);
    }

    /**
     * 读取当前尚在展示的后台命令状态，形成界面快照副本。
     *
     * @return 后台任务键 → 状态。
     */
    public Map<String, String> background() {
        return Map.copyOf(background);
    }

    /**
     * 读取最近一次 BUFFER 事实中的用户缓冲摘要。
     *
     * @return 最近一次业务缓冲摘要；空串表示没有缓冲。
     */
    public String bufferSummary() {
        return bufferSummary;
    }

    /**
     * 读取用户缓冲当前的排队或插入状态。
     *
     * @return 缓冲状态（QUEUED/INSERTION）。
     */
    public String bufferState() {
        return bufferState;
    }

    /**
     * 新会话代次：清空旧事实，避免快照混入上一会话的 Team/后台状态。
     */
    public void reset() {
        actors.clear();
        background.clear();
        bufferSummary = "";
        bufferState = "";
    }
}
