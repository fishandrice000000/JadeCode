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
    /**
     * 语义 Markdown 页面：按真实服务器/状态/工具分组；外部名称只进 inline code，
     * 不触发连接、发现、OAuth 或秘密读取。resize/主题变化由 MessageCard 重排。
     */
    public String markdown(List<McpManager.ServerSnapshot> servers) {
        var out = new StringBuilder();
        out.append("## MCP servers\n\n");
        out.append("Read-only view. No connection or tool discovery is triggered.\n\n");
        if (servers == null || servers.isEmpty()) {
            out.append(UiMessages.NO_MCP).append(".\n");
            return out.toString();
        }
        for (McpManager.ServerSnapshot server : servers) {
            String state = !server.enabled() ? "disabled" : server.connected() ? "connected" : "configured";
            out.append("### ").append(MarkdownSafe.code(server.name())).append('\n');
            out.append("- **Status:** ").append(state).append('\n');
            if (server.connected()) {
                if (server.tools().isEmpty()) out.append("- **Tools:** connected; no tools advertised\n");
                else {
                    out.append("- **Tools:**\n");
                    for (String tool : server.tools())
                        out.append("  - ").append(MarkdownSafe.code(tool)).append('\n');
                }
            } else if (!server.enabled()) {
                out.append("- **Tools:** not loaded while disabled\n");
            } else {
                out.append("- **Tools:** not discovered\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** @return 只读状态行（旧调用）；Controller 已改用语义 Markdown。 */
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
