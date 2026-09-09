package com.jadecode.llm;

/**
 * LlmClient -- 模型调用接口,全项目与 LLM 交互的唯一入口。
 *
 * <p>生产实现是 {@link AnthropicSdkLlmClient}(唯一接触 SDK 类型的类);
 * 测试用 ScriptedLlmClient / RecordingLlmClient 替身。实现可换——调用方只认这个接口,
 * 不认底层是 SDK 还是手写 HTTP。</p>
 */
public interface LlmClient {
    /**
     * 同步调用一次模型。
     *
     * @param request 请求(system / messages / maxTokens / tools)
     * @return 模型的回复与用量
     * @throws LlmException 调用失败;带 HTTP 状态码(0 表示网络类异常)
     */
    ModelResponse complete(CompleteRequest request) throws LlmException;
}
