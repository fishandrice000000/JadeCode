package com.jadecode.safety.checks;

import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.PathResolver;

/** 文件工具共用读写语义与真实路径解析；输出资料只给规则层，不产生读写副作用。 */
public final class FilePermissionCheck implements ToolPermissionCheck {
    private final PathResolver resolver;
    public FilePermissionCheck(PathResolver resolver) { this.resolver = resolver; }
    @Override public Analysis analyze(String tool, ObjectNode input) {
        Path cwd = resolver.workspaceReal();
        if (!input.path("path").isTextual() || input.path("path").textValue().isBlank())
            return new Analysis(tool, cwd, List.of(), List.of(), false, false, false, "Tool is missing a non-empty path");
        // 同一份解析器交给实际文件工具，不能让检查与执行各自使用不同 cwd。
        Path target = resolver.resolve(input.path("path").textValue());
        boolean write = !tool.equals("read_file");
        // 原始名称与真实目标都检查：.env 即使链接到普通名字，也仍是敏感入口。
        Path original = cwd.resolve(input.path("path").textValue()).normalize();
        String operation = write ? "WRITE" : "READ";
        var resources = original.equals(target) ? List.of(new Resource(target, operation))
                : List.of(new Resource(original, operation), new Resource(target, operation));
        return new Analysis(tool, cwd, resources, List.of(), write, false, false, "");
    }
}
