package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.StyledLine;

/**
 * 明确占行/占列的空白组件，避免产品视图散落“空 StyledLine”或魔法空行数。
 *
 * <p>固定尺寸用于 dock 间距、审批按钮下方说明；弹性模式返回 0 行，由父布局按剩余空间
 * 分配（当前产品不强行拉伸）。它不持有隐藏业务状态，也不生成可点击区域。</p>
 */
public record Spacer(int rows) implements Component {
    public Spacer {
        if (rows < 0) throw new IllegalArgumentException("Spacer 行数不能为负");
    }

    /** 固定行数空白。 */
    public static Spacer rows(int count) { return new Spacer(count); }

    /** 弹性空白：当前实现为 0 行，交由父布局决定。 */
    public static Spacer flexible() { return new Spacer(0); }

    @Override public List<StyledLine> render(RenderContext context, int width) {
        var result = new ArrayList<StyledLine>(rows);
        for (int i = 0; i < rows; i++) result.add(StyledLine.empty());
        return result;
    }

    @Override public int height(RenderContext context, int width) { return rows; }
}
