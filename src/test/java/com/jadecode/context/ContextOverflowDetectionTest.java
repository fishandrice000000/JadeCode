package com.jadecode.context;

import com.jadecode.llm.LlmException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextCompactor.isContextOverflow 的错误分流契约测试。
 *
 * <p>
 * 这个判定的作用是"正式模型调用失败后,要不要尝试一次压缩补救",所以测试同时覆盖两侧:
 * 该认出来的上下文过长说明(大小写、嵌入长说明、两个关键词任意一个),
 * 以及<b>不能</b>被认成上下文过长的普通失败(认证、限流、网络、普通 4xx)和相似但不是契约词的措辞。
 * </p>
 *
 * <p>
 * 全部使用内存构造的 {@link LlmException},不调用真实模型、不读密钥、不产生 IO。
 * </p>
 */
class ContextOverflowDetectionTest {

    /** 两个约定关键词:命中任一(忽略大小写、允许嵌在更长说明里)就要判 true */
    @Test
    void matchesBothKeywordsCaseInsensitivelyAndInsideLongerMessages() {
        // 关键词 1:prompt_too_long 的各种大小写与嵌入形态
        assertTrue(ContextCompactor.isContextOverflow(new LlmException("prompt_too_long")));
        assertTrue(ContextCompactor.isContextOverflow(new LlmException("Prompt_Too_Long")));
        assertTrue(ContextCompactor.isContextOverflow(new LlmException("PROMPT_TOO_LONG")));
        assertTrue(ContextCompactor.isContextOverflow(new LlmException("PrOmPt_ToO_LoNg")));
        assertTrue(ContextCompactor.isContextOverflow(new LlmException("Error code: prompt_too_long")));
        assertTrue(ContextCompactor.isContextOverflow(
                new LlmException("invalid_request_error: prompt_too_long (request id: abc-123)")));

        // 关键词 2:too many tokens 的各种大小写与嵌入形态
        assertTrue(ContextCompactor.isContextOverflow(new LlmException("too many tokens")));
        assertTrue(ContextCompactor.isContextOverflow(new LlmException("Too Many Tokens")));
        assertTrue(ContextCompactor.isContextOverflow(new LlmException("ERROR: TOO MANY TOKENS IN REQUEST")));
        assertTrue(ContextCompactor.isContextOverflow(
                new LlmException("the request is rejected because it contains too many tokens for this model")));

        // 两个同时出现
        assertTrue(ContextCompactor.isContextOverflow(
                new LlmException("prompt_too_long: too many tokens in the request")));
    }

    /** 异常为 null、或说明为 null / 空 / 纯空白:无法判定,一律 false 且不抛异常 */
    @Test
    void returnsFalseWhenExceptionOrMessageIsMissing() {
        assertFalse(ContextCompactor.isContextOverflow(null));
        assertFalse(ContextCompactor.isContextOverflow(new LlmException((String) null)));
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("")));
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("   ")));
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("  \n\t  ")));
    }

    /** 认证 / 限流 / 网络 / 普通 400:压缩重试救不了,必须原样向外传播 */
    @Test
    void returnsFalseForOrdinaryFailuresThatMustNotEnterCompaction() {
        assertFalse(ContextCompactor.isContextOverflow(
                new LlmException("401 Unauthorized: invalid api key", 401)));
        assertFalse(ContextCompactor.isContextOverflow(
                new LlmException("429 Too Many Requests: rate limit exceeded", 429)));
        assertFalse(ContextCompactor.isContextOverflow(
                new LlmException("Connection reset by peer")));
        assertFalse(ContextCompactor.isContextOverflow(
                new LlmException("400 Bad Request: invalid_request_error", 400)));
    }

    /** HTTP 状态码既不决定 true,也不能让关键词失效 */
    @Test
    void httpStatusCodeNeitherDecidesAloneNorDisablesKeywordMatch() {
        // 命中关键词:换任何状态码都仍然判 true
        assertTrue(ContextCompactor.isContextOverflow(new LlmException("prompt_too_long", 500)));
        assertTrue(ContextCompactor.isContextOverflow(new LlmException("too many tokens", 400)));
        assertTrue(ContextCompactor.isContextOverflow(new LlmException("prompt_too_long", 0)));

        // 只有状态码、没有关键词:一律 false
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("500 Internal Server Error", 500)));
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("400 Bad Request", 400)));
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("413 Payload Too Large", 413)));
    }

    /** 相似但不在契约内的措辞一律 false:不扩词、不做空格 / 下划线归一化 */
    @Test
    void returnsFalseForSimilarButOutOfContractWording() {
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("context_length_exceeded")));
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("maximum context window exceeded")));
        assertFalse(ContextCompactor.isContextOverflow(
                new LlmException("input length exceeds the context window")));
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("too many")));
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("too many requests")));
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("too many tools")));
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("prompt too long"))); // 不做空格归一化
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("prompt-too-long"))); // 不做连字符归一化
        assertFalse(ContextCompactor.isContextOverflow(new LlmException("tokens: too many"))); // 词序不同不算
    }

    /** 只看异常本身:不读 cause,也不改动异常;重复调用结果稳定 */
    @Test
    void doesNotInspectCauseAndHasNoSideEffects() {
        // 外层说明没有目标词,即使 cause 带目标词也返回 false
        LlmException overflowCause = new LlmException("request failed", new LlmException("prompt_too_long"));
        assertFalse(ContextCompactor.isContextOverflow(overflowCause));

        // 调用是纯读取:异常自身的说明与状态码不变,重复调用结果一致
        LlmException overflow = new LlmException("too many tokens", 400);
        assertTrue(ContextCompactor.isContextOverflow(overflow));
        assertEquals("too many tokens", overflow.getMessage());
        assertEquals(400, overflow.statusCode());
        assertTrue(ContextCompactor.isContextOverflow(overflow));
    }
}
