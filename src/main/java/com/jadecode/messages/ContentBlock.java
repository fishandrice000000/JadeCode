package com.jadecode.messages;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * ContentBlock -- 消息内容块(sealed)。
 *
 * <p>三个实现:TextBlock("text")/ ToolUseBlock("tool_use")/ ToolResultBlock("tool_result")。
 * 下面两个注解让 Jackson 按 "type" 字段决定反序列化成哪个实现——线上 JSON 与内部模型的
 * 往返全靠它,新增块类型必须同步这里。</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
        @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
        @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result")
})
public sealed interface ContentBlock permits TextBlock, ToolUseBlock, ToolResultBlock {

}
