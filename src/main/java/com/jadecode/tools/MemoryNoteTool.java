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

    /**
     * 只保存已装配的维护者，不执行 IO。
     *
     * @param memory 当前会话的长期记忆入口，负责参考、候选笔记和提取作业
     */
    public MemoryNoteTool(MemoryManager memory) {
        this.memory = memory;
    }

    /**
     * 返回候选记忆工具名 memory_note，供模型工具目录和执行查找使用。
     *
     * @return 该工具的稳定注册名
     */
    public String name() {
        return "memory_note";
    }

    /**
     * 返回工具的模型可见说明，解释 remember、correct、forget 候选的内容与作用域要求，以及候选入队后的状态。
     *
     * @return 根据当前已装配能力生成的模型可见说明
     */
    public String description() {
        return "Propose a user-backed remember/correct/forget note. Include the exact fact and scope. "
                + "This queues a candidate; it does not change formal memory or establish user authorization.";
    }

    /**
     * 返回候选类型 kind 和正文 body 的字符串参数定义，供 schema 生成和运行时参数校验使用。
     *
     * @return 当前工具支持的参数名到 JSON 类型的映射
     */
    private Map<String, String> properties() {
        return Map.of("kind", "string", "body", "string");
    }

    /**
     * 返回必填参数 kind 和 body，供工具 schema 标注与输入校验使用。
     *
     * @return 该工具要求提供的参数名列表
     */
    private List<String> required() {
        return List.of("kind", "body");
    }

    /**
     * 检查 kind、body 的字段类型及本轮 USER 执行上下文，再交给 MemoryManager 校验来源和保存候选。成功返回待处理回执，
     * 参数、来源或保存失败返回错误 ToolOutcome。
     *
     * @param input 记忆类型与正文参数
     * @param context 后端提供的任务来源、轮次与取消状态
     * @return 候选保存回执或具体错误
     */
    public com.jadecode.tools.ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context) {
        if (context == null || context.source() != ExecutionContext.Source.USER || context.isCancelled())
            return new com.jadecode.tools.ToolOutcome("Error: An active user task is required", true);
        if (input.size() != 2 || !input.path("kind").isTextual() || !input.path("body").isTextual())
            return new com.jadecode.tools.ToolOutcome("Error: kind and body must be strings", true);
        var result = memory.note(input.path("kind").asText(), input.path("body").asText(), context.id());
        return new ToolOutcome(result.text(), result.error());
    }

    /**
     * 提供完整参数结构；标量构造器只负责组装 Schema，不授予执行权限。
     *
     * @return 完整的 object 参数 schema，含字段类型与必填声明
     */
    @Override
    public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
        return com.jadecode.tools.ToolSchema.objectSchema(properties(), required());
    }
}
