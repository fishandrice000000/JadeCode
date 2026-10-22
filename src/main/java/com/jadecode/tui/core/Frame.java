package com.jadecode.tui.core;

import java.util.List;
import com.jadecode.tui.geometry.Rect;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * 一次完整布局的不可变结果：显示行、硬件光标和命中区域来自同一 revision。
 *
 * <p>Frame 只是“准备显示”的内容，不代表已经写到终端；renderer flush 成功后才生成
 * {@link PresentedFrame}。坐标约定：行/列从 0 开始；同时属于整屏 Frame（对 fullscreen）
 * 或整篇转录 + dock 的完整文档（对 regular）。renderer 负责换算到可见屏幕。
 * 旧实现中的 `bottomStartRow` 底锚策略已移除：全屏由 ChatViewport/dock 分配空间，
 * regular 让内容自然增长，不再把编辑器贴到终端底部。</p>
 *
 * @param frameId 单调递增帧号
 * @param terminalEpoch 产生该帧的终端代次；resize/恢复后旧帧失效
 * @param revision 模型/布局 revision
 * @param lines 完整行；宽度不得超过 RenderContext.width
 * @param cursor 硬件光标（IME 锚点）；不可见时 visible=false
 * @param hits 可点击区域；坐标与 lines 同一布局
 * @param flash 仅 regular 使用的屏幕层短暂提示；null 表示无。它不属于文档/历史，
 *        renderer 负责画在可见区右上角并在到期后恢复该行，不能写入转录或旧 scrollback。
 */
public record Frame(long frameId, long terminalEpoch, long revision, List<StyledLine> lines,
        Cursor cursor, List<Hit> hits, Flash flash, ScreenLayer layer) {
    public Frame {
        lines = List.copyOf(lines);
        hits = List.copyOf(hits);
    }

    /** 兼容无 flash/layer 的调用方；fullscreen 的 flash 直接合成在整屏行中。 */
    public Frame(long frameId, long terminalEpoch, long revision, List<StyledLine> lines,
            Cursor cursor, List<Hit> hits) {
        this(frameId, terminalEpoch, revision, lines, cursor, hits, null, null);
    }

    /** 兼容无屏幕层的调用方（regular flash 等）。 */
    public Frame(long frameId, long terminalEpoch, long revision, List<StyledLine> lines,
            Cursor cursor, List<Hit> hits, Flash flash) {
        this(frameId, terminalEpoch, revision, lines, cursor, hits, flash, null);
    }

    /**
     * 屏幕层：坐标是当前可见屏幕坐标（0 基），不属于 Frame.lines 文档。regular 用它叠画
     * 命令弹窗等临时内容；renderer 负责按 viewportTop 换算文档行并在关闭/移动/resize 时
     * 重画覆盖处底层内容。fullscreen 由 Application 直接合成到整屏行，通常为 null。
     *
     * @param rect 可见屏幕矩形
     * @param lines 该矩形内的纯显示行
     * @param cursor 层内光标；无则 NO_CURSOR
     * @param hits 层内命中（屏幕坐标）
     * @param revision 生成层的布局 revision
     */
    public record ScreenLayer(Rect rect, List<StyledLine> lines, Cursor cursor, List<Hit> hits, long revision) {
        public ScreenLayer {
            rect = rect == null ? new Rect(0, 0, 0, 0) : rect;
            lines = lines == null ? List.of() : List.copyOf(lines);
            cursor = cursor == null ? NO_CURSOR : cursor;
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    /** 屏幕层短提示；text 不进入 Frame.lines，也不进入模型/转录。 */
    public record Flash(String text, Style style) {
        public Flash {
            text = text == null ? "" : text;
            style = style == null ? Style.PLAIN : style;
        }
    }

    /** 光标不可见时的占位。 */
    public static final Cursor NO_CURSOR = new Cursor(0, 0, false);

    /** 0 基光标位置；visible 决定是否显示真实硬件光标。 */
    public record Cursor(int row, int column, boolean visible) {
        public Cursor {
            row = Math.max(0, row);
            column = Math.max(0, column);
        }
    }

    /** 矩形命中区域与动作身份；同一帧内不允许两个动作重叠（调用方保证）。 */
    public record Hit(Rect rect, ActionToken action) {
        public Hit {
            if (rect == null || action == null) throw new IllegalArgumentException("命中区域和动作不能为空");
        }
    }

    /** @return 指定位置的动作；无命中返回 null。 */
    public ActionToken actionAt(int column, int row) {
        for (Hit hit : hits) if (hit.rect().contains(column, row)) return hit.action();
        return null;
    }

    /** @return 判断某个动作是否出现在本帧（不检查是否已显示）。 */
    public boolean contains(ActionToken token) {
        return hits.stream().anyMatch(hit -> hit.action().equals(token));
    }
}
