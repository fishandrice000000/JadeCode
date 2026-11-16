package com.jadecode.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.memory.MemoryManager;
import java.util.*;

/**
 * 主会话的待处理记忆入口：只收候选，不接受路径，也不能直接改正式手册。
 * Session 绑定真实用户原话，工具把候选交给独立整理器核对，避免模型自称用户已授权。
 */
public final class MemoryNoteTool implements Tool {
    private final MemoryManager memory;

    /** 只保存已装配的维护者，不执行 IO。 */
    public MemoryNoteTool(MemoryManager memory) {
        this.memory = memory;
    }

    public String name() {
        return "memory_note";
    }

    public String description() {
        return "Propose a user-backed remember/correct/forget note. Include the exact fact and scope. "
                + "This queues a candidate; it does not change formal memory or establish user authorization.";
    }

    private Map<String, String> properties() {
        return Map.of("kind", "string", "body", "string");
    }

    private List<String> required() {
        return List.of("kind", "body");
    }

    /** 类型、种类和长度在工具/维护者两层检查；错误返回给模型，不假装保存成功。 * @param context Harness 提供的任务来源与取消检查；不从模型参数读取
     * @param input 模型参数对象，只读取；具体字段由工具自行校验
     */
    public com.jadecode.tools.ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context) {
        if (context == null || context.source() != ExecutionContext.Source.USER || context.isCancelled())
            return new com.jadecode.tools.ToolOutcome("Error: An active user task is required", true);
        if (input.size() != 2 || !input.path("kind").isTextual() || !input.path("body").isTextual())
            return new com.jadecode.tools.ToolOutcome("Error: kind and body must be strings", true);
        var result = memory.note(input.path("kind").asText(), input.path("body").asText(), context.id());
        return new ToolOutcome(result.text(), result.error());
    }

    /** 提供完整参数结构；标量构造器只负责组装 Schema，不授予执行权限。 */
    @Override
    public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
        return com.jadecode.tools.ToolSchema.objectSchema(properties(), required());
    }
}
