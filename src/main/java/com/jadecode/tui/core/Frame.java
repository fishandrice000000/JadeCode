package com.jadecode.tui.core;

import java.util.List;
import com.jadecode.tui.text.StyledLine;

/** 一次完整布局，包括光标和点击区域。三者必须来自同一帧，不能各自重新计算。 */
public record Frame(List<StyledLine> lines, int cursorRow, int cursorColumn, List<Hit> hits) {
    public Frame { lines = List.copyOf(lines); hits = List.copyOf(hits); }
    /** 以文档行为坐标的点击范围；action 是界面条目编号，不是模型指令。 */
    public record Hit(int row, int fromColumn, int toColumn, String action) { }
}
