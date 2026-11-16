package com.jadecode.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.execution.ToolIdentity;
import com.jadecode.mcp.McpManager;
import com.jadecode.mcp.McpToolDescriptor;
import java.util.List;
import java.util.Map;

/**
 * 把已发现的外部工具适配成普通 Tool；不检查权限、不写历史、不直接操作 SDK。
 * 真实路由使用 descriptor 的原始身份与连接代次，模型可见名字只用于本次目录查找。
 */
public final class McpTool implements Tool {
    private final McpManager manager;
    private final McpToolDescriptor descriptor;
    private final String name;

    /** 保存发现结果；构造没有 IO，也不会连接服务。 */
    public McpTool(McpManager manager, McpToolDescriptor descriptor) {
        this.manager = java.util.Objects.requireNonNull(manager);
        this.descriptor = descriptor;
        name = ToolCatalog.mcpName(descriptor.serverName(), descriptor.name());
    }

    /** 生成可信注册项，身份不由模型参数或描述里的 readOnlyHint 决定。 */
    public ToolCatalog.Entry entry() {
        return new ToolCatalog.Entry(this, new ToolIdentity(ToolIdentity.Kind.MCP, descriptor.serverName(),
                descriptor.name(), descriptor.hostReadOnly()));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return descriptor.description();
    }

    private Map<String, String> properties() {
        return Map.of();
    }

    private List<String> required() {
        return List.of();
    }

    @Override
    public ObjectNode inputSchema() {
        return descriptor.inputSchema();
    }

    /** 权限通过后只调用一次服务；关闭检查来自可信任务上下文，失败仍交给模型处理。 * @param context Harness 提供的任务上下文；不从模型参数读取，离线只读调用可按工具契约传 null
     * @param input 模型参数对象，只读取；具体字段由工具自行校验
     */
    @Override
    public ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context) {
        if (context != null && context.isCancelled())
            return new ToolOutcome("Error: Session closed", true);
        var result = manager.call(descriptor, input);
        return new ToolOutcome(result.text(), result.isError());
    }
}
