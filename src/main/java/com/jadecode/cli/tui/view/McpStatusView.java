package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.cli.tui.model.CommandPanelModel;
import com.jadecode.mcp.McpManager;

/**
 * /mcp 的数据投影：只使用已有只读 ServerSnapshot，区分 disabled/configured/connected 与 0 工具。
 *
 * <p>不发起连接/发现/OAuth，不显示 env/启动命令/Token；页面由 CommandPanelView 渲染。</p>
 */
public final class McpStatusView {
    /** @return 结构化只读页面；外部服务名/工具名保留为数据，渲染时净化。 */
    public CommandPanelModel panel(List<McpManager.ServerSnapshot> servers, long generation, long sourceRevision) {
        var services = new ArrayList<CommandPanelModel.Service>();
        for (McpManager.ServerSnapshot server : servers == null ? List.<McpManager.ServerSnapshot>of() : servers)
            services.add(new CommandPanelModel.Service(server.name(), server.enabled(), server.connected(),
                    server.tools()));
        return new CommandPanelModel(CommandPanelModel.Kind.MCP, "MCP servers", generation, sourceRevision,
                List.of(), List.of(), services, UiMessages.NO_MCP);
    }
}
