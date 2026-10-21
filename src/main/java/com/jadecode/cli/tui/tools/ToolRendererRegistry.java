package com.jadecode.cli.tui.tools;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.model.ToolDisplayState;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.StyledLine;

/**
 * 可信工具名/身份到 renderer 的唯一选择表；所有实际工具都有明确去向或 fallback。
 *
 * <p>选择依据是宿主注册的工具名与 ToolIdentity，不是模型参数自述；MCP 工具不因前缀被猜成
 * read-only。新增工具若没有专用 renderer，必须走 fallback，而不是消失或显示伪造成功。</p>
 */
public final class ToolRendererRegistry {
    private final List<ToolRenderer> renderers;
    private final FallbackToolRenderer fallback = new FallbackToolRenderer();

    /** 装配默认 renderer 集合。 */
    public ToolRendererRegistry() {
        this(List.of(new ShellToolRenderer(), new FileToolRenderer(), new ListingToolRenderer()));
    }

    /** 可注入集合，便于测试 fallback/未知工具。 */
    public ToolRendererRegistry(List<ToolRenderer> renderers) {
        this.renderers = List.copyOf(renderers);
    }

    /** @return 匹配的专用 renderer；无则 fallback。 */
    public ToolRenderer rendererFor(ToolDisplayState state) {
        for (ToolRenderer renderer : renderers)
            if (renderer.supports(state.name(), state.identity())) return renderer;
        return fallback;
    }

    /** @return 排版整张工具卡。 */
    public List<StyledLine> render(ToolDisplayState state, RenderContext context, int width) {
        List<StyledLine> lines = rendererFor(state).render(state, context, width);
        // 结果尾部统一补一个空行，卡片之间可读；不属于原文。
        var result = new ArrayList<>(lines);
        result.add(StyledLine.empty());
        return result;
    }
}
