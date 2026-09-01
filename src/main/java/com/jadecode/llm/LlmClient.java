package com.jadecode.llm;

/**
 * LlmClient LLM 客户端接口
 */
public interface LlmClient {
    /** 同步调用一次模型。失败抛 LlmException(带 statusCode)。 */
    ModelResponse complete(CompleteRequest request) throws LlmException;
}
