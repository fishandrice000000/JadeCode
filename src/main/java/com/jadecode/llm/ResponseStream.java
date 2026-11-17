package com.jadecode.llm;

/**
 * 描述一次模型请求的展示回调。每次尝试使用独立对象，按正文、思考和工具参数块接收增量；
 * 完整消息由 LlmClient 的返回值交给执行器采用。默认方法为空，供只关心部分通道的接收者选择覆盖。
 */
public interface ResponseStream {
    /** 不展示的调用使用此对象；队友和辅助请求不因此改变自己的消息协议。 */
    ResponseStream NONE = new ResponseStream() {
    };

    /**
     * 接收本次模型响应新生成的正文片段，供具体展示出口累计到回答通道。
     *
     * @param text 本次新增的正文片段，接收者按到达顺序追加到当前请求的回答通道
     */
    default void text(String text) {
    }

    /**
     * 接收模型接口实际提供的可展示思考片段，供具体展示出口累计到思考通道。
     *
     * @param text 模型接口本次返回的可展示 thinking 片段，接收者追加到思考通道
     */
    default void thinking(String text) {
    }

    /**
     * 每次正式请求尝试开始时调用；retry 必须生成新 ID。
     * 无展示或不关心请求开始的接收者可以使用空实现。
     *
     * @param requestId 一次模型请求的观察 ID，用于区分不同响应流
     */
    default void requestStarted(String requestId) {
    }

    /**
     * SDK 识别到 tool_use 块后通知展示端创建工具参数预览，后续片段通过相同 blockIndex 续接。
     *
     * @param blockIndex 本响应内的块下标
     * @param callId 模型给出的 tool_use ID；执行阶段仍使用同一真实 ID
     * @param name 工具名，仅用于显示
     * @param initialInputJson 开始事件中的初始参数 JSON，后续增量由 toolInputDelta 提供
     */
    default void toolBlockStarted(int blockIndex, String callId, String name, String initialInputJson) {
    }

    /**
     * 合法 openBlock 的 input_json 增量；只用于预览，可能不完整，禁止据此执行。
     *
     * @param blockIndex 工具调用在模型内容块中的下标，用于把参数片段追加到同一个预览块
     * @param fragment 当前工具参数 JSON 的新增文本片段
     */
    default void toolInputDelta(int blockIndex, String fragment) {
    }

    /**
     * 工具块的原始 JSON 参数校验通过后，通知展示端结束指定块的参数预览。
     *
     * @param blockIndex 工具调用在模型内容块中的下标，用于把参数片段追加到同一个预览块
     */
    default void toolBlockClosed(int blockIndex) {
    }

    /**
     * 完整响应已拼好并通过角色和调用 ID 校验后，结束本次请求的展示；会话继续采用完整回复并判断下一步。
     *
     * @param stopReason 服务端真实停止原因；unknown 保持原值，不能根据正文猜
     */
    default void complete(String stopReason) {
    }

    /**
     * 失败时仍传递服务端已报告的停止原因，让界面区分输出截断与一般请求错误。
     * 不采用消息或执行工具；没有取得停止原因时由调用者显式传入空串。
     *
     * @param stopReason 已取得的真实停止原因；没有完整响应时传空串，不根据异常文字猜测
     */
    default void failed(String stopReason) {
    }
}
