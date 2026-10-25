package com.jadecode.tools;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.workflow.WorkflowRuntime;

/**
 * 模型选择可信流程的唯一工具入口。只接收名字/输入/续跑 ID，不接收代码、元数据、模型或权限来源。
 * Executor 先做原有 PreToolUse/Gate；这里再验证参数并同步等运行结果，不自行追加主会话历史。
 * 内部失败带 run_id 和 isError 返回，让主模型能决定是否在用户授权范围内显式续跑。
 */
public final class WorkflowTool implements Tool {
    private final WorkflowRuntime runtime;

    /** 保存所属 Session 的运行时；构造不建目录、不发模型请求。 */
    public WorkflowTool(WorkflowRuntime runtime) { this.runtime = java.util.Objects.requireNonNull(runtime); }
    /** 与课程工具名一致；不是允许模型执行任意 Java/Python 的入口。 */
    @Override public String name() { return "Workflow"; }
    /** 目录来自宿主，首个预设必须由主模型提供实际变更文本，不能假称内部步骤会自己读文件。 */
    @Override public String description() {
        return "Run a saved workflow synchronously, with bounded parallel model steps. " + runtime.catalog()
                + " For review-changes, supply actual change text in args.changes; optional args.budget is an observed token budget."
                + " Steps cannot read files or use tools. To resume the same input/settings, pass resume_from_run_id and omit args.";
    }
    /** 参数是平铺外壳，具体 args 契约由可信定义校验。 */
    @Override public Map<String, String> properties() { return Map.of("name", "string", "args", "object", "resume_from_run_id", "string"); }
    /** 新运行至少需要名字；每个预设再验证自己的必填输入。 */
    @Override public List<String> required() { return List.of("name"); }
    /** 模型不能通过附加 source/script 字段改变执行来源或上传代码。 */
    @Override public ObjectNode inputSchema() { return Tool.super.inputSchema().put("additionalProperties", false); }
    /** 旧无 context 调用不伪造一次人工授权。 */
    @Override public String execute(ObjectNode input) { return "Error: Workflow requires execution context"; }
    /** 兼容只收正文的调用者；正式执行器使用 executeOutcome 保留明确失败标记。 */
    @Override public String execute(ObjectNode input, ExecutionContext context) { return executeOutcome(input, context).content(); }

    /**
     * 校验整个外壳后只运行一次流程，不修改输入。null 不是“缺省 args”，resume 必须是非空字符串。
     * 参数/存储失败转成工具错误；意外运行异常仍由执行器处理，不能吞掉后当作成功。
     */
    @Override public ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context) {
        if (context == null || context.isCancelled()) return new ToolOutcome("Error: Workflow session is closed or has no execution context", true);
        try {
            if (input == null || !input.path("name").isTextual() || !input.get("name").asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
                throw new IllegalArgumentException("Workflow name must be a registered name");
            input.fieldNames().forEachRemaining(key -> {
                if (!Set.of("name", "args", "resume_from_run_id").contains(key)) throw new IllegalArgumentException("Unknown Workflow parameter: " + key);
            });
            if (input.has("args") && !input.get("args").isObject()) throw new IllegalArgumentException("Workflow args must be an object");
            if (input.has("resume_from_run_id") && (!input.get("resume_from_run_id").isTextual() || input.get("resume_from_run_id").asText().isBlank()))
                throw new IllegalArgumentException("Workflow resume ID must be a string");
            var result = runtime.run(input.get("name").asText(), input.has("args") ? (ObjectNode) input.get("args") : null,
                    input.has("resume_from_run_id") ? input.get("resume_from_run_id").asText() : null, context);
            return new ToolOutcome(result.toString(), !result.path("status").asText().equals("completed"));
        } catch (IOException | IllegalArgumentException failure) {
            return new ToolOutcome("Error: " + failure.getMessage(), true);
        }
    }
}
