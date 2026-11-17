package com.jadecode.safety.checks;

import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.PathResolver;

/**
 * 文件工具共用读写语义与真实路径解析；输出资料只给规则层，不产生读写副作用。
 */
public final class FilePermissionCheck implements ToolPermissionCheck {
    private final PathResolver resolver;

    /**
     * 绑定工作区路径解析器，供文件工具把原始路径和真实目标转成读写资源。
     *
     * @param resolver 绑定真实工作区的路径解析器，用于定位相对路径和链接目标
     */
    public FilePermissionCheck(PathResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Glob 的 root 也是读取资源，不能因为是搜索工具就漏掉外部目录或路径规则。
     *
     * @param input 本次工具参数 JSON 对象，包含该工具 schema 约定的字段
     * @return 将 root 作为读取资源的 Glob 分析结果
     */
    public Analysis analyzeGlob(ObjectNode input) {
        if (input.has("root") && !input.path("root").isTextual())
            return new Analysis("glob", resolver.workspaceReal(), List.of(), List.of(), false, true, false,
                    "glob.root must be a string", "");
        String raw = input.path("root").asText(".");
        Path target = resolver.resolve(raw), original = resolver.workspaceReal().resolve(raw).normalize();
        var resources = target.equals(original) ? List.of(new Resource(target, "READ"))
                : List.of(new Resource(target, "READ"), new Resource(original, "READ"));
        return new Analysis("glob", resolver.workspaceReal(), resources, List.of(), false, true, false, "", "");
    }

    /**
     * 读取文件工具的 path，以 read_file 标记 READ，其余文件工具标记 WRITE；
     * 将原始路径和解析后的真实目标都纳入资源列表，返回规则匹配所需的 Analysis。
     * path 缺失或空白时返回带错误说明的分析结果。
     *
     * @param tool 模型调用的工具注册名
     * @param input 本次工具参数 JSON 对象，包含该工具 schema 约定的字段
     * @return 本次工具的真实工作目录、资源、读写与不确定属性；输入错误保存在 error
     */
    @Override
    public Analysis analyze(String tool, ObjectNode input) {
        Path cwd = resolver.workspaceReal();
        if (!input.path("path").isTextual() || input.path("path").textValue().isBlank())
            return new Analysis(tool, cwd, List.of(), List.of(), false, false, false,
                    "Tool is missing a non-empty path", "");
        // 同一份解析器交给实际文件工具，不能让检查与执行各自使用不同 cwd。
        Path target = resolver.resolve(input.path("path").textValue());
        boolean write = !tool.equals("read_file");
        // 原始名称与真实目标都检查：.env 即使链接到普通名字，也仍是敏感入口。
        Path original = cwd.resolve(input.path("path").textValue()).normalize();
        String operation = write ? "WRITE" : "READ";
        var resources = original.equals(target) ? List.of(new Resource(target, operation))
                : List.of(new Resource(original, operation), new Resource(target, operation));
        return new Analysis(tool, cwd, resources, List.of(), write, false, false, "", "");
    }
}
