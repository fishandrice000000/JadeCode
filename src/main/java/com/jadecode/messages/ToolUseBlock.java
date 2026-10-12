package com.jadecode.messages;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * ToolUseBlock -- LLM 调用工具请求记录
 * 
 * 形如
 * {
 * "type": "tool_use",
 * "id": "toolu_01",
 * "name": "bash",
 * "input": {"command": "ls"}
 * }
 * 
 * @param id    工具调用 ID
 * @param name  调用工具名称
 * @param input 工具输入
 */
@JsonTypeName("tool_use")
public record ToolUseBlock(String id, String name, JsonNode input) implements ContentBlock {

    /**
     * 前置不变量:工具调用 ID 不能为 null / 空白。
     *
     * <p>校验实现集中于 {@link ToolExchangeProtocol},合法输入的 JSON 形态与行为不变。</p>
     */
    public ToolUseBlock {
        ToolExchangeProtocol.requireValidToolId(id, "tool_use id");
    }
}
