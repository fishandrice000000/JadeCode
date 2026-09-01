package com.jadecode.llm;

import com.jadecode.messages.Message;

/**
 * ModelResponse 一次调用 LLM 的结果
 * 
 * @param message role=ASSISTANT 的消息
 * @param usage   调用响应的 Token 用量
 */
public record ModelResponse(Message message, Usage usage) {
    public record Usage(int inputTokens, int outputTokens) {
    }
}