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
            throw new IllegalArgumentException("Permission rule is missing id/effect/kind/tool");
        workspace = workspace == null ? "" : workspace;
        operation = operation == null ? "" : operation;
        path = path == null ? "" : path;
        argvPrefix = argvPrefix == null ? List.of() : List.copyOf(argvPrefix);
        if (!workspace.isEmpty() && !Path.of(workspace).isAbsolute()) throw new IllegalArgumentException("workspace must be an absolute path");
        if (kind == Kind.COMMAND && (argvPrefix.isEmpty() || !tool.equals("bash") || argvPrefix.getFirst().isBlank()))
            throw new IllegalArgumentException("Command rules must specify bash and a non-empty argvPrefix");
        if (kind == Kind.PATH && (path.isBlank() || !Path.of(path).isAbsolute()
                || !List.of("READ", "WRITE", "DELETE").contains(operation)))
            throw new IllegalArgumentException("Path rules must specify an absolute path and READ/WRITE/DELETE");
        if (kind != Kind.PATH && (!operation.isEmpty() || !path.isEmpty() || recursive)
                || kind != Kind.COMMAND && !argvPrefix.isEmpty())
            throw new IllegalArgumentException("A rule cannot mix matching fields from different kinds");
    }
    /** 给审批界面展示准确范围，不把“此类”隐藏成整把工具授权。 */
    public String description() {
        return switch (kind) {
            case COMMAND -> "cwd=" + workspace + "; argv prefix=" + com.jadecode.util.Json.mapper().valueToTree(argvPrefix);
            case PATH -> operation + " " + path + (recursive ? "/**" : "") + "; workspace=" + workspace;
            case TOOL -> "tool=" + tool + "; workspace=" + workspace;
        };
    }
}
