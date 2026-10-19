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
     * @param inputTokens  本次调用未计入缓存读写的普通输入 token 数
     * @param outputTokens 本次调用的输出 token 数
     * @param cacheReadInputTokens 本次从缓存读到的输入；已包含在总输入中，不是额外的输出
     * @param cacheCreationInputTokens 本次写入缓存的输入；不再叠加 TTL 明细等子项
     */
    public record Usage(long inputTokens, long outputTokens, long cacheReadInputTokens, long cacheCreationInputTokens) {
        /** 没有缓存字段的旧调用保留原含义；不能额外再把 inputTokens 重复加一次。 */
        public Usage(long inputTokens, long outputTokens) { this(inputTokens, outputTokens, 0, 0); }
        public Usage {
            if (inputTokens < 0 || outputTokens < 0 || cacheReadInputTokens < 0 || cacheCreationInputTokens < 0)
                throw new IllegalArgumentException("模型用量不能为负数");
        }
        /** Anthropic wire 的普通输入不包含缓存读写；总输入才可用于占用与累计。 */
        public long totalInputTokens() { return Math.addExact(Math.addExact(inputTokens, cacheReadInputTokens), cacheCreationInputTokens); }
    }
}
