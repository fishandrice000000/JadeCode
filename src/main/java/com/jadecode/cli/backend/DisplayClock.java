package com.jadecode.cli.backend;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 后端权威的轮次显示计时。
 *
 * <p>为什么不能把 {@code System.nanoTime()} 发给 Node：不同进程的单调钟起点不同，node 只能
 * 相减自己的钟，结果会是天文数字。这里把“已用时间”换算成相对毫秒与运行/暂停/显示停止
 * 状态，Node 只在此基础上按本地增量插值。</p>
 *
 * <p>语义与旧 TUI 一致：审批暂停时计时不增长；最后回答完成（DISPLAY_STOP）后冻结显示，
 * 但 Memory/后台等业务状态仍然真实可见；重叠的多份审批只扣一次暂停。</p>
 */
public final class DisplayClock {
    /** 一次快照：Node 用它更新时间标签。 */
    public record Tick(String actor, String runId, long elapsedMs, boolean running, boolean paused,
            boolean displayStopped, long revision) { }

    private record Turn(String runId, long sampledAtNanos, long sampledElapsedNanos,
            int pauseDepth, boolean displayStopped) { }

    private final Map<String, Turn> turns = new HashMap<>();
    private final Map<String, Long> completed = new HashMap<>();
    private final BiConsumer<String, Tick> sink;
    private long revision;

    /** @param sink 接收显示计时事件（第二参数为快照，第一参数为 actor） */
    public DisplayClock(BiConsumer<String, Tick> sink) { this.sink = sink; }

    /** 新的大轮次开始；id 是执行线程给出的 run 编号。 */
    public synchronized void started(String actor, String runId) {
        turns.put(actor, new Turn(runId == null ? "" : runId, System.nanoTime(), 0, 0, false));
        completed.remove(actor);
        publish(actor);
    }

    /** 进入/离开人工审批等待；重叠暂停只扣一次。 */
    public synchronized void paused(String actor, boolean pause, long sampledElapsedNanos) {
        Turn turn = turns.get(actor);
        if (turn == null) return;
        if (sampledElapsedNanos > 0) reanchor(actor, turn, sampledElapsedNanos);
        int depth = Math.max(0, turn.pauseDepth() + (pause ? 1 : -1));
        turns.put(actor, new Turn(turn.runId(), System.nanoTime(), turn.sampledElapsedNanos(), depth, turn.displayStopped()));
        publish(actor);
    }

    /** 最后回答显示截止/恢复；只冻结显示，不结束业务轮次。 */
    public synchronized void displayStopped(String actor, boolean stopped, long sampledElapsedNanos) {
        Turn turn = turns.get(actor);
        if (turn == null) return;
        if (sampledElapsedNanos > 0) reanchor(actor, turn, sampledElapsedNanos);
        turns.put(actor, new Turn(turn.runId(), System.nanoTime(), turn.sampledElapsedNanos(), turn.pauseDepth(), stopped));
        publish(actor);
    }

    /** 轮次结束；保留冻结耗时供最终回答卡片使用。 */
    public synchronized void ended(String actor, String runId, long elapsedNanos) {
        Turn turn = turns.get(actor);
        if (turn != null && (runId == null || runId.equals(turn.runId()))) {
            completed.put(actor, Math.max(0, elapsedNanos > 0 ? elapsedNanos : elapsed(turn, System.nanoTime())));
            turns.remove(actor);
        }
        publish(actor);
    }

    /** 会话关闭：清掉所有活动轮次，避免页面继续动画。 */
    public synchronized void reset() {
        turns.clear();
        publishAll();
    }

    /** @return 当前快照；没有活动轮次时返回最近冻结耗时（running=false）。 */
    public synchronized Tick tick(String actor) {
        Turn turn = turns.get(actor);
        if (turn != null) return snapshot(actor, turn, System.nanoTime());
        Long frozen = completed.get(actor);
        return new Tick(actor, "", frozen == null ? 0 : frozen / 1_000_000L, false, false, true, revision);
    }

    /** 所有 actor 的当前快照；Node 在快照/诊断刷新时使用。 */
    public synchronized Map<String, Tick> all() {
        Map<String, Tick> result = new HashMap<>();
        for (String actor : turns.keySet()) result.put(actor, tick(actor));
        for (String actor : completed.keySet()) result.putIfAbsent(actor, tick(actor));
        return Map.copyOf(result);
    }

    private void reanchor(String actor, Turn turn, long sampledElapsedNanos) {
        turns.put(actor, new Turn(turn.runId(), System.nanoTime(), Math.max(0, sampledElapsedNanos),
                turn.pauseDepth(), turn.displayStopped()));
    }

    private static long elapsed(Turn turn, long nowNanos) {
        boolean frozen = turn.pauseDepth() > 0 || turn.displayStopped();
        return turn.sampledElapsedNanos() + (frozen ? 0 : Math.max(0, nowNanos - turn.sampledAtNanos()));
    }

    private Tick snapshot(String actor, Turn turn, long nowNanos) {
        return new Tick(actor, turn.runId(), elapsed(turn, nowNanos) / 1_000_000L, true,
                turn.pauseDepth() > 0, turn.displayStopped(), revision);
    }

    private void publish(String actor) {
        revision++;
        sink.accept(actor, tick(actor));
    }

    private void publishAll() {
        revision++;
        for (var entry : all().entrySet()) sink.accept(entry.getKey(), entry.getValue());
    }
}
