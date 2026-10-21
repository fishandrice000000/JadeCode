package com.jadecode.tui.core;

import com.jadecode.tui.terminal.TerminalCapabilities;
import com.jadecode.tui.theme.Theme;

/**
 * 同一次布局/绘制的只读上下文：尺寸、主题、能力与单调时钟。
 *
 * <p>`width` 是终端完整列数（不再沿用旧的 `columns-1` 惯例）；需要保留最后一列时由具体
 * renderer 决定，而不是所有组件都少画一列。`height` 为可见行数，极窄/未知尺寸由调用方
 * 提供显式 fallback。主题 revision 与 nowNanos 都来自同一帧，动画/样式判断不会各自采样。</p>
 *
 * @param width 可用列数，>=1
 * @param height 可用行数，>=1
 * @param theme 当前主题快照
 * @param capabilities 终端能力快照
 * @param nowNanos 同一帧使用的单调时钟
 * @param revision 模型/布局 revision，用于使缓存和动作证据失效
 */
public record RenderContext(int width, int height, Theme theme, TerminalCapabilities capabilities,
        long nowNanos, long revision) {
    public RenderContext {
        width = Math.max(1, width);
        height = Math.max(1, height);
    }

    /** 只关心尺寸/主题的便捷构造；能力按空环境探测，时钟取当前值。 */
    public RenderContext(int width, int height, Theme theme) {
        this(width, height, theme, TerminalCapabilities.detect(java.util.Map.of()), System.nanoTime(), 0);
    }

    /** @return 宽度变化后的新上下文；高度/主题/能力不变。 */
    public RenderContext withWidth(int value) {
        return new RenderContext(value, height, theme, capabilities, nowNanos, revision);
    }

    /** @return 高度变化后的新上下文。 */
    public RenderContext withHeight(int value) {
        return new RenderContext(width, value, theme, capabilities, nowNanos, revision);
    }

    /** @return 相同尺寸但新 revision 的上下文，用于使旧动作失效。 */
    public RenderContext nextRevision() {
        return new RenderContext(width, height, theme, capabilities, nowNanos, revision + 1);
    }
}
