package com.jadecode.execution;

import java.util.Objects;

/**
 * 注册工具时由宿主生成的可信身份，不从模型 input 读取。
 * 权限层只依赖此数据，不需要引用具体 Tool 或 MCP 客户端。
 * @param kind 内置或 MCP 来源
 * @param serverName 配置中的服务名；内置工具为空串
 * @param originalName 原始工具名，不通过模型可见前缀反推
 * @param hostReadOnly 宿主配置的只读声明；服务 annotations 不能填写它
 */
public record ToolIdentity(Kind kind, String serverName, String originalName, boolean hostReadOnly) {
    public enum Kind { BUILTIN, MCP }
    /** 校验身份，不运行工具或赋予模型填写此对象的能力。 */
    public ToolIdentity {
        Objects.requireNonNull(kind); Objects.requireNonNull(serverName);
        if (originalName == null || originalName.isBlank() || kind == Kind.MCP && serverName.isBlank())
            throw new IllegalArgumentException("工具身份无效");
        if (kind == Kind.BUILTIN && (!serverName.isEmpty() || hostReadOnly)) throw new IllegalArgumentException("内置工具身份无效");
    }
    /** 内置工具沿用现有权限解释，不能借此自称 MCP 只读工具。 */
    public static ToolIdentity builtin(String name) { return new ToolIdentity(Kind.BUILTIN, "", name, false); }
}
