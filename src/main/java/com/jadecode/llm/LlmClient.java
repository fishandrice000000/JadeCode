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
    default ModelMetadata metadata() {
        return new ModelMetadata(ModelMetadata.Limits.unknown(), "");
    }

    /** 只列确实适配的请求设置；未知后端保留默认，不发送猜测参数。 */
    default java.util.List<ThinkingSettings> thinkingChoices() {
        return java.util.List.of(ThinkingSettings.DEFAULT);
    }

    /** DEFAULT 是否有已知服务商含义由适配器说明，不由 TUI 按模型名猜测。 */
    default String thinkingLabel(ThinkingSettings value) {
        return value == ThinkingSettings.DEFAULT ? "default" : value.value();
    }

    /**
     * 同步完成一次模型请求，可将生成中的片段交给本次请求独占的展示对象。
     *
     * <p>无展示的正式或辅助请求显式传入 ResponseStream.NONE；SDK 此时使用普通请求，
     * 其它展示对象使用事件流。完整响应始终由返回值交付，片段不能作为工具执行依据。
     * 展示结束与失败由执行器报告，客户端不得把半份响应伪装成成功。</p>
     * @param request 完整请求，不修改其中的历史
     * @param stream 非 null 的展示对象；不展示时使用 ResponseStream.NONE，不保存到共享客户端
     * @return 完整响应及真实用量；未报告的用量保持未知
     * @throws LlmException 网络、服务端、流或响应拼装失败
     */
    ModelResponse complete(CompleteRequest request, ResponseStream stream) throws LlmException;
}
