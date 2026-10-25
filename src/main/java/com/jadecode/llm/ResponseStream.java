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

    /**
     * 每次正式请求尝试开始时调用；retry 必须生成新 ID。
     * 默认无操作，旧调用方不需要实现。
     */
    default void requestStarted(String requestId) { }

    /**
     * SDK 已识别到一个 tool_use 块并开始累计参数。
     * 只用于建立显示预览，不代表参数完整或会执行。
     * @param blockIndex 本响应内的块下标
     * @param callId 模型给出的 tool_use ID；执行阶段仍使用同一真实 ID
     * @param name 工具名，仅用于显示
     * @param initialInputJson 初始参数片段（可能为空对象），不是最终参数
     */
    default void toolBlockStarted(int blockIndex, String callId, String name, String initialInputJson) { }

    /** 合法 openBlock 的 input_json 增量；只用于预览，可能不完整，禁止据此执行。 */
    default void toolInputDelta(int blockIndex, String fragment) { }

    /** 该工具块的参数已通过原 JSON 闭合/校验；只表示参数结束，不是整响应可执行。 */
    default void toolBlockClosed(int blockIndex) { }

    /**
     * 完整响应已拼好并通过角色/调用 ID 校验，结束预览；不是任务或工具执行成功。
     * @param stopReason 服务端真实停止原因；unknown 保持原值，不能根据正文猜
     */
    default void complete(String stopReason) { complete(); }

    /** 完整响应已拼好并通过调用方校验，结束预览；不是任务或工具执行成功。 */
    default void complete() { }

    /** 请求或完整响应校验失败，结束预览并标明不完整；不能将预览当成有效历史。 */
    default void failed() { }

    /**
     * 失败时仍传递服务端已报告的停止原因，让界面区分输出截断与一般请求错误。
     * 默认委托旧 failed，旧展示实现仍能收到一次失败通知；不采用消息或执行工具。
     * @param stopReason 已取得的真实停止原因；没有完整响应时传空串，不根据异常文字猜测
     */
    default void failed(String stopReason) { failed(); }
}
