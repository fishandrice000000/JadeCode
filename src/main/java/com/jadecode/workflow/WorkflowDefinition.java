package com.jadecode.workflow;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 宿主编写的一份流程。模型只选择 name 和输入，不提供 script 或修改版本。
 * version 是维护者的兼容性承诺：编排语义改变时应修改它，旧 run 不会悄悄套用新流程。
 * phases 是显示分组，argsSchema 是输入契约；构造仅校验元数据，无 IO 或模型副作用。
 */
public record WorkflowDefinition(String name, String description, String version, List<String> phases,
        ObjectNode argsSchema, Script script) {
    /** 可信 Java 代码可以使用编排原语；抛错会使运行失败，已记录的成功步骤保留。 */
    @FunctionalInterface
    public interface Script {
        /** 输入为该次运行的副本，不能靠修改它改变已保存的恢复身份。 */
        JsonNode run(WorkflowContext context, ObjectNode args) throws Exception;
    }

    /** 注册前拒绝非法名称、空版本和不支持的 schema，防止运行到一半才发现配置错误。 */
    public WorkflowDefinition {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || description == null || description.isBlank() || version == null || version.isBlank()
                || phases == null || phases.stream().anyMatch(p -> p == null || p.isBlank()) || script == null)
            throw new IllegalArgumentException("Invalid workflow registration");
        phases = List.copyOf(phases);
        WorkflowSchema.checkSchema(argsSchema);
        if (!"object".equals(argsSchema.path("type").asText()))
            throw new IllegalArgumentException("Workflow args schema must describe an object");
        argsSchema = argsSchema.deepCopy();
    }

    /** 返回副本，外部代码不能改变已登记的输入契约。 */
    @Override public ObjectNode argsSchema() { return argsSchema.deepCopy(); }

    /**
     * 建立只读 registry，拒绝同名覆盖。只接受已经构造好的可信定义，不扫描或执行用户目录代码。
     * Map 的 name 与实际 Definition 始终一致；模型查询未知名会得到明确错误。
     */
    public static Map<String, WorkflowDefinition> registry(List<WorkflowDefinition> definitions) {
        var result = new java.util.LinkedHashMap<String, WorkflowDefinition>();
        for (var definition : definitions) {
            if (result.putIfAbsent(definition.name(), definition) != null)
                throw new IllegalArgumentException("Duplicate workflow name: " + definition.name());
        }
        return Map.copyOf(result);
    }
}
