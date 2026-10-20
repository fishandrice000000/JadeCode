package com.jadecode.safety.rules;

import java.nio.file.Path;
import java.util.List;

/**
 * 可保存的声明式规则。COMMAND 按 argv 前缀且限定 cwd；PATH 按真实路径与操作匹配。
 * 空 workspace 仅供用户明确配置的跨项目规则，程序生成的建议始终绑定实际工作区。
 */
public record PermissionRule(String id, Effect effect, Kind kind, String tool, String workspace,
        String operation, String path, boolean recursive, List<String> argvPrefix) {
    public enum Effect { ALLOW, ASK, DENY }
    public enum Kind { TOOL, PATH, COMMAND }
    public PermissionRule {
        if (id == null || id.isBlank() || effect == null || kind == null || tool == null || tool.isBlank())
            throw new IllegalArgumentException("权限规则缺少 id/effect/kind/tool");
        workspace = workspace == null ? "" : workspace;
        operation = operation == null ? "" : operation;
        path = path == null ? "" : path;
        argvPrefix = argvPrefix == null ? List.of() : List.copyOf(argvPrefix);
        if (!workspace.isEmpty() && !Path.of(workspace).isAbsolute()) throw new IllegalArgumentException("workspace 须为绝对路径");
        if (kind == Kind.COMMAND && (argvPrefix.isEmpty() || !tool.equals("bash") || argvPrefix.getFirst().isBlank()))
            throw new IllegalArgumentException("命令规则须指定 bash 和非空 argvPrefix");
        if (kind == Kind.PATH && (path.isBlank() || !Path.of(path).isAbsolute()
                || !List.of("READ", "WRITE", "DELETE").contains(operation)))
            throw new IllegalArgumentException("路径规则须指定绝对路径和 READ/WRITE/DELETE");
        if (kind != Kind.PATH && (!operation.isEmpty() || !path.isEmpty() || recursive)
                || kind != Kind.COMMAND && !argvPrefix.isEmpty())
            throw new IllegalArgumentException("规则不能混填不同种类的匹配字段");
    }
    /** 给审批界面展示准确范围，不把“此类”隐藏成整把工具授权。 */
    public String description() {
        return switch (kind) {
            case COMMAND -> "cwd=" + workspace + "；argv 前缀=" + com.jadecode.util.Json.mapper().valueToTree(argvPrefix);
            case PATH -> operation + " " + path + (recursive ? "/**" : "") + "；工作区=" + workspace;
            case TOOL -> "工具=" + tool + "；工作区=" + workspace;
        };
    }
}
