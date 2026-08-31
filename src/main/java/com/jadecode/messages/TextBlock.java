package com.jadecode.messages;

import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * TextBlock 记录 -- LLM 的普通回复
 * 
 * 形如:
 * {
 * "type": "text",
 * "text": "好的,我来执行这个命令。"
 * }
 * 
 * @param text 回复内容
 */
@JsonTypeName("text")
public record TextBlock(String text) implements ContentBlock {

}
