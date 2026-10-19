package com.jadecode.tui.core;

/** 合并模型高频增量。只有界面线程调用；输入可立即刷新，普通变化最多约每 16ms 一帧。 */
public final class RenderScheduler {
    private boolean dirty = true;
    private long last;
    /** 登记变化，不立即写屏幕。 */
    public void request() { dirty = true; }
    /** 判断是否应刷新；immediate 用于按键，动画由调用者按需登记。 */
    public boolean ready(boolean immediate) {
        return dirty && (immediate || System.nanoTime() - last >= 16_000_000L);
    }
    /** 只有实际画完一帧才清脏标记。 */
    public void rendered() { dirty = false; last = System.nanoTime(); }
}
