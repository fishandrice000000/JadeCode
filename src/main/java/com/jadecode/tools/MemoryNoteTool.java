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
    public MemoryNoteTool(MemoryManager memory) { this.memory = memory; }
    public String name() { return "memory_note"; }
    public String description() { return "Propose a user-backed remember/correct/forget note. Include the exact fact and scope. "
            + "This queues a candidate; it does not change formal memory or establish user authorization."; }
    public Map<String, String> properties() { return Map.of("kind", "string", "body", "string"); }
    public List<String> required() { return List.of("kind", "body"); }
    /** 没有可信任务上下文时拒绝，不能退回从 input 推测用户身份。 */
    public String execute(ObjectNode input) { return "Error: Trusted user task context is required"; }
    /** 类型、种类和长度在工具/维护者两层检查；错误返回给模型，不假装保存成功。 */
    public String execute(ObjectNode input, ExecutionContext context) {
        if (context == null || context.source() != ExecutionContext.Source.USER || context.isCancelled())
            return "Error: An active user task is required";
        if (input.size() != 2 || !input.path("kind").isTextual() || !input.path("body").isTextual())
            return "Error: kind and body must be strings";
        return memory.note(input.path("kind").asText(), input.path("body").asText(), context.id());
    }
}
