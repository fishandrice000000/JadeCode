package com.jadecode.tui.core;

import java.util.List;
import com.jadecode.tui.text.StyledLine;

/** 通用显示组件：只根据已有数据产行，不写终端、不请求模型、不执行工具。 */
@FunctionalInterface
public interface Component { List<StyledLine> render(RenderContext context); }
