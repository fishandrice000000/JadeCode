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

}
