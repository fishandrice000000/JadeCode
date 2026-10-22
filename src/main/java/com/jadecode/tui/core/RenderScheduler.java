package com.jadecode.tui.core;

/**
 * 合并高频模型增量与输入刷新：普通变化最多 16ms 一帧，输入立即，动画按 80ms 限期，
 * 空闲时不重画整屏。
 *
 * <p>只由 UI 线程调用。业务时钟（整轮 TurnTimer）不属于这里；本类只决定“现在要不要重绘”。
 * ready() 不消费 dirty，只有 renderer 真正 flush 成功后才调用 rendered()，避免写失败时
 * 丢失待显示内容。</p>
 */
public final class RenderScheduler {
    /** 普通刷新合并周期（纳秒）。 */
    public static final long FRAME_INTERVAL_NANOS = 16_000_000L;
    /** spinner/动画周期（纳秒）。 */
    public static final long ANIMATION_INTERVAL_NANOS = 80_000_000L;

    private final java.util.function.LongSupplier clock;
    private boolean dirty = true;
    private long lastFrame;
    private long nextDeadline = Long.MAX_VALUE;

    /** 使用系统单调时钟。 */
    public RenderScheduler() { this(System::nanoTime); }

    /** 可注入时钟，便于测试空闲/合并窗口。 */
    public RenderScheduler(java.util.function.LongSupplier clock) { this.clock = clock; }

    /** 登记普通脏状态（模型增量、状态变化）。 */
    public void request() { dirty = true; }

    /** 登记动画下一帧期限；已到期时立即请求。 */
    public void requestAnimation(long deadlineNanos) {
        if (deadlineNanos <= clock.getAsLong()) request();
        else nextDeadline = Math.min(nextDeadline, deadlineNanos);
    }

    /** @return 是否有待绘制内容。 */
    public boolean dirty() { return dirty; }

    /**
     * @param immediate 输入/退出等需要立刻刷新的原因
     * @return 当前是否应绘制
     */
    public boolean ready(boolean immediate) {
        long now = clock.getAsLong();
        // 动画期限到期必须独立触发一次绘制：dirty=false 只表示没有模型/输入变化，
        // 不能阻止 spinner/计时器到期刷新。旧实现第一句直接返回 false，导致 3 秒无输出
        // 时计时不更新。
        if (now >= nextDeadline) return true;
        if (!dirty) return false;
        if (immediate || now - lastFrame >= FRAME_INTERVAL_NANOS) return true;
        return false;
    }

    /** 一帧真正 flush 成功后调用；清脏标记并推进动画期限。 */
    public void rendered() {
        dirty = false;
        lastFrame = clock.getAsLong();
        nextDeadline = Long.MAX_VALUE;
    }

    /** 画布尺寸/主题变化等需要立即重排时使用；不伪造已绘制。 */
    public void invalidate() { dirty = true; }

    /** @return 下一次动画期限（纳秒）；无期限返回 Long.MAX_VALUE。 */
    public long nextDeadline() { return nextDeadline; }
}
