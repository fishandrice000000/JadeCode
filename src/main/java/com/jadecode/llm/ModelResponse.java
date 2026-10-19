package com.jadecode.llm;

import com.jadecode.messages.Message;

/**
 * ModelResponse 一次调用 LLM 的结果
 * 
 * @param message role=ASSISTANT 的消息
 * @param usage   后端报告的 Token 用量；null 表示未报告，不是实际用量为零
 * @param stopReason 服务端停止原因，例如 end_turn／max_tokens；缺失为 ""，不凭文本猜原因
 */
public record ModelResponse(Message message, Usage usage, String stopReason) {
    /** 旧客户端和离线替身未报告原因时保持兼容，不能假定正常 end_turn。 */
    public ModelResponse(Message message, Usage usage) { this(message, usage, ""); }
    public ModelResponse { if (stopReason == null) stopReason = ""; }
    /**
     * @param inputTokens  本次调用的输入 token 数
     * @param outputTokens 本次调用的输出 token 数
     */
    public record Usage(long inputTokens, long outputTokens) {
    }
}
