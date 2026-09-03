package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Tool LLM 调用的工具接口
 */
public interface Tool {
    String name(); // 与模型 tool_use 块的 name 对应

    String description(); // 给模型看:何时用、怎么用

    // Tool 可用参数
    Map<String, String> properties();

    // Tool 必需参数
    List<String> required();

    // JSON Schema:模型读它学会怎么传 input
    default ObjectNode inputSchema() {
        return ToolSchema.objectSchema(properties(), required());
    }

    String execute(ObjectNode input); // 出错返回错误字符串,绝不抛异常
}
