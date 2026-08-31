package com.jadecode.messages;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * ToolResultBlock -- LLM 的工具调用结果记录
 * 
 * 形如
 * {
 * "type": "tool_result",
 * "tool_use_id": "toolu_01",
 * "content": "pom.xml\nREADME.md"
 * }
 * 
 * @param toolUseId 工具调用 ID
 * @param content   工具返回内容
 */
@JsonTypeName("tool_result")
public record ToolResultBlock(@JsonProperty("tool_use_id") String toolUseId, String content) implements ContentBlock {

}
