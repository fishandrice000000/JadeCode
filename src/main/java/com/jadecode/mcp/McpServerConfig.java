package com.jadecode.mcp;

import java.util.List;
import java.util.Set;

/**
 * 宿主明确保存的一个 stdio 服务配置，不含凭据，也不来自模型参数。
 * command 和 args 分开传入 ProcessBuilder；相对路径由连接按工作区解释，不经过 shell。
 * @param enabled 是否允许模型连接
 * @param command 启动程序；路径可相对工作区，裸命令从 PATH 查找
 * @param args 字面参数，不解释重定向或变量展开
 * @param cwd 相对工作区的启动目录，空串表示工作区
 * @param startupTimeoutSeconds 初始化和发现的总等待上限
 * @param callTimeoutSeconds 一次工具调用等待上限
 * @param maxResultChars 进入模型的结果正文上限，单位为 Java 字符
 * @param readOnlyTools 宿主声明的原始只读工具名；不读取服务 annotations 授权
 */
public record McpServerConfig(boolean enabled, String command, List<String> args, String cwd,
        int startupTimeoutSeconds, int callTimeoutSeconds, int maxResultChars, Set<String> readOnlyTools) {
    /** 复制集合、检查边界；错误只指出字段，不回显可能包含敏感值的启动参数。 */
    public McpServerConfig {
        if (command == null || command.isBlank() || command.indexOf('\0') >= 0)
            throw new IllegalArgumentException("MCP command must be a non-empty program name");
        args = List.copyOf(args); readOnlyTools = Set.copyOf(readOnlyTools);
        if (cwd == null || args.stream().anyMatch(a -> a.indexOf('\0') >= 0))
            throw new IllegalArgumentException("Invalid MCP cwd/args");
        if (startupTimeoutSeconds < 1 || startupTimeoutSeconds > 120 || callTimeoutSeconds < 1 || callTimeoutSeconds > 300
                || maxResultChars < 1 || maxResultChars > 100_000)
            throw new IllegalArgumentException("MCP timeout or result length is outside the allowed range");
        if (readOnlyTools.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("MCP read-only tool names cannot be empty");
    }
}
