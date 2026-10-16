package com.jadecode.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 发给模型的工具说明，只含数据，不具备执行工具的能力。
 *
 * <p>执行器每次请求前从实际工具生成本快照，SDK 只读取名称、说明和参数结构。
 * JSON 节点本身可修改，因此构造和读取时都深复制，避免工具或请求消费者改变已交出的定义。
 * 完整 schema 被保留，但 SDK 目前仍只转换 type/properties/required，不能据此认为
 * 根级 additionalProperties 等字段已经发送给模型。</p>
 * @param name 模型使用的工具名，保持原工具名称
 * @param description 给模型的用途和用法说明
 * @param inputSchema 完整参数结构；不可为 null，构造时不修改传入节点
 */
public record ToolDefinition(String name, String description, ObjectNode inputSchema) {
    /**
     * 保存参数结构的独立快照，不持有原工具对象，不运行工具或产生 IO。
     * @param name 工具名
     * @param description 工具说明
     * @param inputSchema 原工具生成的完整参数结构，内部嵌套节点也复制
     * @throws NullPointerException inputSchema 为 null
     */
    public ToolDefinition {
        inputSchema = inputSchema.deepCopy();
    }

    /**
     * 交出可供消费者读取的副本；修改它不会改变本定义或其它请求看到的参数结构。
     * @return 包含完整 schema 的深复制节点，不执行任何 IO
     */
    @Override
    public ObjectNode inputSchema() {
        return inputSchema.deepCopy();
    }
}
