package com.jadecode.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Tool LLM 调用的工具接口
 */
public interface Tool {
    String name(); // 与模型 tool_use 块的 name 对应

    String description(); // 给模型看:何时用、怎么用

    ObjectNode inputSchema(); // JSON Schema:模型读它学会怎么传 input

    String execute(ObjectNode input); // 出错返回错误字符串,绝不抛异常
}
