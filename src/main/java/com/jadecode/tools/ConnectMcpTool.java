package com.jadecode.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.mcp.McpManager;
import com.jadecode.mcp.McpToolDescriptor;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/** 模型只能选择已启用名称；程序与参数来自可信配置，连接完成后下一次请求才看见新工具。 */
public final class ConnectMcpTool implements Tool {
    private final McpManager manager;
    public ConnectMcpTool(McpManager manager) { this.manager = java.util.Objects.requireNonNull(manager); }
    @Override public String name() { return "connect_mcp"; }
    @Override public String description() { return "Connect a configured MCP server and discover tools for the next model request. Does not install programs."; }
    @Override public Map<String,String> properties() { return Map.of("name", "string"); }
    @Override public List<String> required() { return List.of("name"); }
    /** schema 只列宿主配置中的名称，模型不能传 command、args 或伪造权限声明。 */
    @Override public ObjectNode inputSchema() {
        ObjectNode schema = Tool.super.inputSchema();
        var names = ((ObjectNode) schema.path("properties").path("name")).putArray("enum");
        manager.availableServers().forEach(names::add);
        schema.put("additionalProperties", false);
        return schema;
    }
    @Override public String execute(ObjectNode input) { return executeOutcome(input, null).content(); }

    /**
     * 初始化、发现与命名检查全部完成才报告成功；异常返回失败结果，不结束整个 Agent 循环。
     * 发布前回调只合并数据并检查名称，没有网络或模型调用。
     */
    @Override public ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context) {
        if (context != null && context.isCancelled()) return new ToolOutcome("Error: Session closed", true);
        if (input.size() != 1 || !input.path("name").isTextual()) return new ToolOutcome("Error: connect_mcp requires only a string name", true);
        try {
            String name = input.path("name").textValue();
            var tools = manager.connect(name, found -> {
                List<McpToolDescriptor> combined = new ArrayList<>(manager.tools()); combined.addAll(found);
                ToolCatalog.validateMcpNames(combined);
            });
            return new ToolOutcome("Connected MCP server " + name + "; tools available next request: "
                    + tools.stream().map(t -> ToolCatalog.mcpName(t.serverName(), t.name())).toList(), false);
        } catch (IllegalArgumentException | IllegalStateException failed) {
            return new ToolOutcome("Error: " + failed.getMessage()
                    + ". If Fetch is not installed, run scripts/install-fetch.sh and check the mcp.servers configuration.", true);
        }
    }
}
