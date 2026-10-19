package com.jadecode.tui.components;

import java.util.List;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.StyledLine;

/** 顺序组合通用组件；不额外创建终端或维护第二套刷新循环。 */
public record VerticalStack(List<Component> children) implements Component {
    public VerticalStack { children = List.copyOf(children); }
    @Override public List<StyledLine> render(RenderContext context) {
        return children.stream().flatMap(c -> c.render(context).stream()).toList();
    }
}
