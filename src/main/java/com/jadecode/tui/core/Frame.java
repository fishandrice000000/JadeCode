package com.jadecode.tui.core;

import java.util.List;
import com.jadecode.tui.text.StyledLine;

/**
 * 一次完整布局，包括文字、光标和点击区域。三者必须来自同一帧，不能各自重新计算。
 * bottomStartRow 指明要贴住窗口底部的区域从哪行开始；渲染器可在它前面补空行，
 * 但必须一起移动光标和点击区域。-1 表示普通布局，不做底部定位。
 */
public record Frame(List<StyledLine> lines, int cursorRow, int cursorColumn, List<Hit> hits, int bottomStartRow) {
    /** 既有独立组件没有底部区域，保持原来的四参数构造方式与布局行为。 */
    public Frame(List<StyledLine> lines, int cursorRow, int cursorColumn, List<Hit> hits) {
        this(lines, cursorRow, cursorColumn, hits, -1);
    }
    public Frame {
        lines = List.copyOf(lines); hits = List.copyOf(hits);
        if (bottomStartRow < -1 || bottomStartRow >= lines.size())
            throw new IllegalArgumentException("底部区域起始行不在画面内");
    }
    /** 以文档行为坐标的点击范围；action 是界面条目编号，不是模型指令。 */
    public record Hit(int row, int fromColumn, int toColumn, String action) { }
}
