package com.jadecode.llm;

import com.jadecode.messages.Message;

/**
 * ModelResponse 一次调用 LLM 的结果
 * 
 * @param message role=ASSISTANT 的消息
 * @param usage   后端报告的 Token 用量；null 表示未报告，不是实际用量为零
 */
public record ModelResponse(Message message, Usage usage) {
    /**
     * @param inputTokens  本次调用的输入 token 数
     * @param outputTokens 本次调用的输出 token 数
     */
    public record Usage(long inputTokens, long outputTokens) {
    }
}
