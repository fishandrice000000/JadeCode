package com.jadecode.cli.tui.tools;

import java.util.List;
import com.jadecode.cli.tui.model.ToolDisplayState;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.StyledLine;

/**
 * 工具调用/结果/展开的显示契约。实现只读取 {@link ToolDisplayState}，不执行工具、不读目标
 * 文件、不猜业务成功；输出在 pending→returned/error 时更新同一张卡。
 */
public interface ToolRenderer {
    /**
     * @param state 工具卡状态（含真实输出/状态）
     * @param context 同帧只读上下文
     * @param width 可用列
     * @return 完整卡片行（含标题/参数/结果/展开提示）
     */
    List<StyledLine> render(ToolDisplayState state, RenderContext context, int width);

    /** @return 是否处理该工具名/身份；由 Registry 调用，默认不处理。 */
    default boolean supports(String name, com.jadecode.execution.ToolIdentity identity) { return false; }
}
