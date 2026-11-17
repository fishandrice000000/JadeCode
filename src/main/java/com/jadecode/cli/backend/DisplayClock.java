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
    /**
     * 一次快照：Node 用它更新时间标签。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param runId 当前轮次 ID，用于关联取消、后台命令和任务收尾
     * @param elapsedMs 界面显示的耗时，单位毫秒
     * @param running 该 Agent 的轮次是否正在进行
     * @param paused 显示计时是否处于暂停状态
     * @param displayStopped 候选最终回答后是否暂时停止展示计时
     * @param revision 显示时钟快照版本，状态发布时递增
     */
    public record Tick(String actor, String runId, long elapsedMs, boolean running, boolean paused,
            boolean displayStopped, long revision) {
    }

    /**
     * 保存运行中轮次的采样时刻、累计耗时、暂停深度和展示停止状态，供下一次时钟查询推算当前耗时。
     *
     * @param runId 当前轮次 ID，用于关联取消、后台命令和任务收尾
     * @param sampledAtNanos 记录累计值时的单调时钟采样点，单位纳秒
     * @param sampledElapsedNanos 采样点已经累计的轮次耗时，单位纳秒
     * @param pauseDepth 嵌套暂停深度，大于零时暂停累计
     * @param displayStopped 候选最终回答后是否暂时停止展示计时
     */
    private record Turn(String runId, long sampledAtNanos, long sampledElapsedNanos,
            int pauseDepth, boolean displayStopped) {
    }

    private final Map<String, Turn> turns = new HashMap<>();
    private final Map<String, Long> completed = new HashMap<>();
    private final BiConsumer<String, Tick> sink;
    private long revision;

    /**
     * 绑定显示时钟的状态出口。运行事件推进采样与版本，前端通过 Tick 在采样点之后推算展示耗时。
     *
     * @param sink 接收显示计时事件（第二参数为快照，第一参数为 actor）
     */
    public DisplayClock(BiConsumer<String, Tick> sink) {
        this.sink = sink;
    }

    /**
     * 新的大轮次开始；id 是执行线程给出的 run 编号。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param runId 当前轮次 ID，用于关联取消、后台命令和任务收尾
     */
    public synchronized void started(String actor, String runId) {
        turns.put(actor, new Turn(runId == null ? "" : runId, System.nanoTime(), 0, 0, false));
        completed.remove(actor);
        publish(actor);
    }

    /**
     * 进入/离开人工审批等待；重叠暂停只扣一次。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param pause 本次操作是进入暂停还是退出一层暂停
     * @param sampledElapsedNanos 采样点已经累计的轮次耗时，单位纳秒
     */
    public synchronized void paused(String actor, boolean pause, long sampledElapsedNanos) {
        Turn turn = turns.get(actor);
        if (turn == null)
            return;
        long now = System.nanoTime();
        // 先把本次采样带进新对象；不能先写 map，再用旧 turn 覆盖回去。
        // DISPLAY_STOP 后的审批恢复不能把 Memory 阶段时间加回已经冻结的回答。
        long sampled = turn.displayStopped() ? turn.sampledElapsedNanos()
                : sampledElapsedNanos > 0 ? sampledElapsedNanos : elapsed(turn, now);
        int depth = Math.max(0, turn.pauseDepth() + (pause ? 1 : -1));
        turns.put(actor, new Turn(turn.runId(), now, sampled, depth, turn.displayStopped()));
        publish(actor);
    }

    /**
     * 最后回答显示截止/恢复；只冻结显示，不结束业务轮次。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param stopped 是否停止该轮次的展示计时
     * @param sampledElapsedNanos 采样点已经累计的轮次耗时，单位纳秒
     */
    public synchronized void displayStopped(String actor, boolean stopped, long sampledElapsedNanos) {
        Turn turn = turns.get(actor);
        if (turn == null)
            return;
        long now = System.nanoTime();
        long sampled = sampledElapsedNanos > 0 ? sampledElapsedNanos : elapsed(turn, now);
        turns.put(actor, new Turn(turn.runId(), now, sampled, turn.pauseDepth(), stopped));
        publish(actor);
    }

    /**
     * 轮次结束；保留冻结耗时供最终回答卡片使用。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param runId 当前轮次 ID，用于关联取消、后台命令和任务收尾
     * @param elapsedNanos 业务侧取得的累计耗时，单位纳秒
     */
    public synchronized void ended(String actor, String runId, long elapsedNanos) {
        Turn turn = turns.get(actor);
        if (turn != null && (runId == null || runId.equals(turn.runId()))) {
            // 业务结束可能晚于最终回答（例如 Memory 提取）；保持显示截止时刻的耗时。
            completed.put(actor, Math.max(0, turn.displayStopped() ? turn.sampledElapsedNanos()
                    : elapsedNanos > 0 ? elapsedNanos : elapsed(turn, System.nanoTime())));
            turns.remove(actor);
        }
        publish(actor);
    }

    /**
     * 会话关闭：清掉所有活动轮次，避免页面继续动画。
     */
    public synchronized void reset() {
        turns.clear();
        publishAll();
    }

    /**
     * 按当前单调时间读取指定 Agent 的轮次采样，生成最新的显示 Tick。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @return 当前快照；没有活动轮次时返回最近冻结耗时（running=false）。
     */
    public synchronized Tick tick(String actor) {
        Turn turn = turns.get(actor);
        if (turn != null)
            return snapshot(actor, turn, System.nanoTime());
        Long frozen = completed.get(actor);
        return new Tick(actor, "", frozen == null ? 0 : frozen / 1_000_000L, false, false, true, revision);
    }

    /**
     * 所有 actor 的当前快照；Node 在快照/诊断刷新时使用。
     *
     * @return 各 Agent 此刻的显示时钟不可变快照
     */
    public synchronized Map<String, Tick> all() {
        Map<String, Tick> result = new HashMap<>();
        for (String actor : turns.keySet())
            result.put(actor, tick(actor));
        for (String actor : completed.keySet())
            result.putIfAbsent(actor, tick(actor));
        return Map.copyOf(result);
    }

    /**
     * 基于采样累计值计算当前纳秒耗时；暂停或展示计时停止时保留采样值，运行时加上采样以来的非负时间差。
     *
     * @param turn 当前时钟中的轮次采样记录
     * @param nowNanos 当前单调时钟采样值，单位纳秒
     * @return 从采样值累计到 nowNanos 的轮次工作耗时，单位纳秒
     */
    private static long elapsed(Turn turn, long nowNanos) {
        boolean frozen = turn.pauseDepth() > 0 || turn.displayStopped();
        return turn.sampledElapsedNanos() + (frozen ? 0 : Math.max(0, nowNanos - turn.sampledAtNanos()));
    }

    /**
     * 按当前纳秒时刻计算一名 Agent 的毫秒耗时，并携带轮次身份、暂停、展示停止及版本生成 Tick。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param turn 当前时钟中的轮次采样记录
     * @param nowNanos 当前单调时钟采样值，单位纳秒
     * @return 携带当前版本的 Agent 时钟 Tick，耗时换算为毫秒
     */
    private Tick snapshot(String actor, Turn turn, long nowNanos) {
        return new Tick(actor, turn.runId(), elapsed(turn, nowNanos) / 1_000_000L, true,
                turn.pauseDepth() > 0, turn.displayStopped(), revision);
    }

    /**
     * 推进时钟版本，把指定 Agent 的最新 Tick 发送给时钟接收者。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     */
    private void publish(String actor) {
        revision++;
        sink.accept(actor, tick(actor));
    }

    /**
     * 推进一次时钟版本，再将全部 Agent 的时钟快照逐一交给接收者，供批量状态变化后刷新。
     */
    private void publishAll() {
        revision++;
        for (var entry : all().entrySet())
            sink.accept(entry.getKey(), entry.getValue());
    }
}
