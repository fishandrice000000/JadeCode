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
 * @param isError 工具明确报告的失败状态；旧历史没有此字段时按 false 读取
 */
@JsonTypeName("tool_result")
public record ToolResultBlock(@JsonProperty("tool_use_id") String toolUseId, String content,
        @JsonProperty("is_error") @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
        boolean isError) implements ContentBlock {

    /** 兼容旧工具和旧调用方；不扫描正文推测是否失败。 */
    public ToolResultBlock(String toolUseId, String content) { this(toolUseId, content, false); }

    /** 重建结果正文时保留身份与失败标志；不修改原结果，不产生 IO。 */
    public ToolResultBlock withContent(String replacement) { return new ToolResultBlock(toolUseId, replacement, isError); }

    /**
     * 工具调用 ID 不能为 null 或空白。校验代码放在 {@link ToolExchangeProtocol}。
     */
    public ToolResultBlock {
        ToolExchangeProtocol.requireValidToolId(toolUseId, "tool_result tool_use_id");
    }
}
