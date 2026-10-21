package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.mcp.McpManager;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.StyledLine;

/**
 * /mcp 只读页面：服务名、configured/connected 状态与真实工具清单。
 *
 * <p>不新增连接/删除/配置/OAuth 按钮；不触发连接或发现；不显示启动命令、env、Token。
 * connected 且 0 工具与服务未连接可区分。所有字段来自 McpManager.snapshot 的纯内存复制。</p>
 */
public final class McpStatusView {
    /** @return 只读状态行；无配置时显示明确英文提示。 */
    public List<StyledLine> render(List<McpManager.ServerSnapshot> servers, RenderContext context, int width) {
        var out = new ArrayList<StyledLine>();
        out.add(StyledLine.of(DisplayWidth.ellipsize(UiMessages.MCP_READ_ONLY, width),
                context.theme().style("accent")));
        if (servers == null || servers.isEmpty()) {
            out.add(StyledLine.of(DisplayWidth.ellipsize(UiMessages.NO_MCP, width), context.theme().style("muted")));
            return out;
        }
        for (McpManager.ServerSnapshot server : servers) {
            String state = !server.enabled() ? "disabled" : server.connected() ? "connected" : "configured";
            out.add(StyledLine.of(DisplayWidth.ellipsize("● " + server.name() + " · " + state, width),
                    context.theme().style(server.connected() ? "success" : "muted")));
            if (server.connected()) {
                if (server.tools().isEmpty())
                    out.add(StyledLine.of(DisplayWidth.ellipsize("  Connected; no tools advertised", width),
                            context.theme().style("dim")));
                else for (String tool : server.tools())
                    out.addAll(StyledLine.wrap(List.of(new Span("  " + tool, context.theme().style("toolOutput"), null)),
                            width));
            }
        }
        return out;
    }
}
