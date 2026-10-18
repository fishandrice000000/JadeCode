package com.jadecode.llm;

/**
 * 一次正式模型请求的展示对象，只接收可显示片段，不负责拼历史或执行工具。
 *
 * <p>每次请求（包括重试）使用新对象，不能把它放在共享 LlmClient 的可变字段中，否则
 * Leader 和队友会把文字写进彼此的界面。文本片段只是预览；完整消息仍由客户端返回。
 * 签名、加密思考与工具参数不通过这个接口显示。</p>
 */
public interface ResponseStream {
    /** 不展示的调用使用此对象；队友和辅助请求不因此改变自己的消息协议。 */
    ResponseStream NONE = new ResponseStream() { };

    /** @param text 正文增量，可能只有几个字符；不是完整回复，不写入消息历史 */
    default void text(String text) { }

    /** @param text 接口实际返回的可展示 thinking 增量，不代表全部内部推理 */
    default void thinking(String text) { }

    /** 完整响应已拼好并通过调用方校验，结束预览；不是任务或工具执行成功。 */
    default void complete() { }

    /** 请求或完整响应校验失败，结束预览并标明不完整；不能将预览当成有效历史。 */
    default void failed() { }
}
