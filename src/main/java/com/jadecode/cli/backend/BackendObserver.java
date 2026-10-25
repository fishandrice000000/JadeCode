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
 * 把真实 {@link ExecutionNotice} 转成可信的界面事件，并维护快照需要的运行事实。
 *
 * <p>可信边界：actor 来自工厂绑定，{@link ToolIdentity} 来自宿主注册；模型参数里的文字
 * 只能成为 detail/参数预览，不能赋予工具身份或权限。本类不渲染、不读终端、不猜测成功：
 * {@code RETURNED} 保持中性，错误/阻止/未执行各有真实状态。</p>
 *
 * <p>它同时推进 {@link DisplayClock}（轮次计时）并维护 Team/后台/缓冲的轻量状态；快照刷新
 * 只做节流请求，不会让 UI 每帧从 Java 拉全历史。</p>
 */
public final class BackendObserver implements ExecutionObserver {
    /** detail 超过这个长度时改用 blob，避免单帧过大。 */
    private static final int INLINE_DETAIL_LIMIT = 4096;

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
     * @param snapshotRefresh 需要刷新后端快照时调用（内部已节流）；不得在观察线程等渲染
     */
    public BackendObserver(WireContext wire, DisplayClock clock, Consumer<Void> snapshotRefresh) {
        this.wire = wire;
        this.clock = clock;
        this.snapshotRefresh = snapshotRefresh;
    }

    @Override public void accept(ExecutionNotice notice) {
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
        if (detail.length() > INLINE_DETAIL_LIMIT) {
            payload.put("detail", "");
            payload.put("detailBlobRef", wire.payloads().sendText("notice-detail", detail, wire.sessionId(), wire.generation(), "notice"));
        } else {
            payload.put("detail", detail);
        }
        ToolIdentity identity = notice.identity();
        if (identity == null) payload.putNull("toolIdentity");
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

    private void clockNotice(ExecutionNotice notice) {
        String actor = notice.actor();
        switch (notice.kind()) {
            case TURN_START -> clock.started(actor, notice.id());
            case TURN_PAUSE -> clock.paused(actor, true, notice.elapsedNanos());
            case TURN_RESUME -> clock.paused(actor, false, notice.elapsedNanos());
            case TURN_DISPLAY_STOP -> clock.displayStopped(actor, true, notice.elapsedNanos());
            case TURN_DISPLAY_RESUME -> clock.displayStopped(actor, false, notice.elapsedNanos());
            case TURN_END -> clock.ended(actor, notice.id(), notice.elapsedNanos());
            default -> { }
        }
    }

    private void trackState(ExecutionNotice notice) {
        switch (notice.kind()) {
            case SESSION -> {
                String actor = notice.actor();
                if (!actor.isBlank()) {
                    if ("CLOSED".equals(notice.state())) actors.remove(actor); else actors.put(actor, notice.state());
                }
                if ("CLOSED".equals(notice.state())) clock.reset();
            }
            case BUFFER -> {
                bufferSummary = notice.detail() == null ? "" : notice.detail();
                bufferState = notice.state();
            }
            case BACKGROUND -> {
                String key = notice.actor() + "/" + notice.id();
                if ("STOPPED".equals(notice.state()) || "DONE".equals(notice.state())) background.remove(key);
                else background.put(key, notice.state());
            }
            default -> { }
        }
    }

    /** 用量/工具/轮次结束后刷新 footer 与 Team 状态；250ms 节流，不轮询。 */
    private void maybeRefreshSnapshot(ExecutionNotice.Kind kind) {
        boolean interesting = switch (kind) {
            case USAGE, TOOL_END, TURN_END, SESSION, MODEL, BUFFER, BACKGROUND, COMPACT, WORKFLOW, GOAL -> true;
            default -> false;
        };
        if (!interesting || snapshotRefresh == null) return;
        long now = System.nanoTime();
        long previous = lastRefresh.get();
        if (now - previous < 250_000_000L) return;
        if (!lastRefresh.compareAndSet(previous, now)) return;
        snapshotRefresh.accept(null);
    }

    /** @return 真实 Actor → 状态快照；Node 用它显示 Team 身份。 */
    public Map<String, String> actors() { return Map.copyOf(actors); }

    /** @return 后台任务键 → 状态。 */
    public Map<String, String> background() { return Map.copyOf(background); }

    /** @return 最近一次业务缓冲摘要；空串表示没有缓冲。 */
    public String bufferSummary() { return bufferSummary; }

    /** @return 缓冲状态（QUEUED/INSERTION）。 */
    public String bufferState() { return bufferState; }

    /** 新会话代次：清空旧事实，避免快照混入上一会话的 Team/后台状态。 */
    public void reset() {
        actors.clear();
        background.clear();
        bufferSummary = "";
        bufferState = "";
    }
}
