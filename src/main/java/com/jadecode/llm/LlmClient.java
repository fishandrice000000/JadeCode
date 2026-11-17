package com.jadecode.llm;

/**
 * 提供模型请求、限额和思考档位的统一契约。生产由 AnthropicSdkLlmClient 发送请求，测试替身提供受控响应；
 * 调用方通过 CompleteRequest 和 ModelResponse 交换内部类型。
 */
public interface LlmClient {
    /**
     * 读取此客户端已取得的模型窗口与输出限额；默认实现返回未知资料。
     *
     * @return 客户端已有的模型限额；默认实现返回未知限额
     */
    default ModelMetadata metadata() {
        return new ModelMetadata(ModelMetadata.Limits.unknown(), "");
    }

    /**
     * 列出此客户端适配的思考档位；默认实现只提供 DEFAULT。
     *
     * @return 此适配器支持的思考档位列表
     */
    default java.util.List<ThinkingSettings> thinkingChoices() {
        return java.util.List.of(ThinkingSettings.DEFAULT);
    }

    /**
     * 将请求档位转换为界面名称，DEFAULT 显示 default，其余使用档位稳定值。
     *
     * @param value 需要转换为界面名称的思考档位
     * @return 当前档位的界面名称
     */
    default String thinkingLabel(ThinkingSettings value) {
        return value == ThinkingSettings.DEFAULT ? "default" : value.value();
    }

    /**
     * 同步完成一次模型请求，可将生成中的片段交给本次请求独占的展示对象。
     *
     * <p>无展示的正式或辅助请求显式传入 ResponseStream.NONE；SDK 此时使用普通请求，
     * 其它展示对象使用事件流。完整响应始终由返回值交付，片段不能作为工具执行依据。
     * 展示结束与失败由执行器报告，客户端不得把半份响应伪装成成功。</p>
     *
     * @param request 完整请求，不修改其中的历史
     * @param stream 非 null 的展示对象；不展示时使用 ResponseStream.NONE，不保存到共享客户端
     * @return 完整响应及真实用量；未报告的用量保持未知
     * @throws LlmException 网络、服务端、流或响应拼装失败
     */
    ModelResponse complete(CompleteRequest request, ResponseStream stream) throws LlmException;
}
