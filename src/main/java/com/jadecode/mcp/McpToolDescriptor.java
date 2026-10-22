package com.jadecode.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * 工具发现的不可变快照；不依赖模型 Tool，也不包含可执行 SDK 对象。
 * @param serverName 配置中的原始服务名
 * @param connectionId 本次连接的代次，阻止旧快照借用新连接
 * @param name 服务返回的原始工具名
 * @param description 工具说明，仅作外部信息
 * @param inputSchema 完整 object schema，构造和读取均深复制
 * @param hostReadOnly 宿主配置的只读声明，不来自服务 hints
 */
public record McpToolDescriptor(String serverName, String connectionId, String name, String description,
        ObjectNode inputSchema, boolean hostReadOnly) {
    /** 校验必要身份与根 schema，不运行工具；非法定义不能进入已发布目录。 */
    public McpToolDescriptor {
        if (serverName == null || serverName.isBlank() || connectionId == null || connectionId.isBlank()
                || name == null || name.isBlank()) throw new IllegalArgumentException("MCP tool identity cannot be empty");
        Objects.requireNonNull(inputSchema, "inputSchema");
        if (!inputSchema.path("type").asText().equals("object")) throw new IllegalArgumentException("MCP inputSchema must be an object");
        description = description == null ? "" : description;
        inputSchema = inputSchema.deepCopy();
    }
    /** 返回独立 schema，防止消费者改变已发布快照。 */
    @Override public ObjectNode inputSchema() { return inputSchema.deepCopy(); }
}
