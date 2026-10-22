package com.jadecode.llm;

/**
 * LlmClient -- 模型调用接口,全项目与 LLM 交互的唯一入口。
 *
 * <p>生产实现是 {@link AnthropicSdkLlmClient}(唯一接触 SDK 类型的类);
 * 测试用 ScriptedLlmClient / RecordingLlmClient 替身。实现可换——调用方只认这个接口,
 * 不认底层是 SDK 还是手写 HTTP。</p>
 */
public interface LlmClient {
    /** 客户端已经取得的限额；默认不查询网络，离线替身未知时也不伪造限额。 */
    default ModelMetadata metadata() { return new ModelMetadata(ModelMetadata.Limits.unknown(), ""); }
    /** 只列确实适配的请求设置；未知后端保留默认，不发送猜测参数。 */
    default java.util.List<ThinkingSettings> thinkingChoices() { return java.util.List.of(ThinkingSettings.DEFAULT); }
    /** DEFAULT 是否有已知服务商含义由适配器说明，不由 TUI 按模型名猜测。 */
    default String thinkingLabel(ThinkingSettings value) { return value == ThinkingSettings.DEFAULT ? "default" : value.value(); }
    /**
     * 同步调用一次模型。
     *
     * @param request 请求(system / messages / maxTokens / tools)
     * @return 模型的回复与用量
     * @throws LlmException 调用失败;带 HTTP 状态码(0 表示网络类异常)
     */
    ModelResponse complete(CompleteRequest request) throws LlmException;

    /**
     * 请求完整响应，同时可把生成中的正文和 thinking 交给本次请求的展示对象。
     *
     * <p>默认实现保持旧客户端和测试替身可用，不伪造网络片段；生产 SDK 覆盖此方法。
     * 方法仍同步返回完整 ModelResponse，不要求 Session 或普通工具全部异步化。
     * complete/failed 由正式请求的执行器调用，辅助请求继续使用单参数方法。</p>
     * @param request 本次请求，不在这里修改历史
     * @param stream 本次请求独占的展示对象；不保存到共享客户端
     * @return 完整响应；失败不能返回半份消息
     * @throws LlmException 网络、服务端、流或拼装失败
     */
    default ModelResponse complete(CompleteRequest request, ResponseStream stream) throws LlmException {
        return complete(request);
    }
}
