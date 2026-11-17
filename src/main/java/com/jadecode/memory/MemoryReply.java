package com.jadecode.memory;

import com.jadecode.llm.*;
import com.jadecode.messages.Role;

/**
 * 为经验提取构造无工具辅助请求，并校验完整 ASSISTANT 文本响应。
 * 通过后由维护者解析 rollout_summary 和 rollout_slug，格式或停止原因异常以分类错误报告。
 */
final class MemoryReply {
    /**
     * 固定诊断不携带模型正文与凭据；只保留当前提取链需要的类别。
     */
    enum Reason {
        MISSING_RESPONSE("Model returned no message"), INVALID_BLOCKS(
                "Reply role or blocks violate the text contract"), EMPTY_TEXT(
                        "Reply has no text, possibly only thinking"), OUTPUT_LIMIT(
                                "Output limit reached; incomplete memory discarded"), CONTEXT_LIMIT(
                                        "Auxiliary request exceeds the model context window"), REFUSAL(
                                                "Model refused the auxiliary request");

        private final String description;

        /**
         * 保存维护响应校验失败的类别说明，供告警输出使用。
         *
         * @param description 此枚举或工具的业务用途说明
         */
        Reason(String description) {
            this.description = description;
        }

        /**
         * 返回此响应错误类别的简短说明。
         *
         * @return 此响应校验失败类别的固定说明
         */
        String description() {
            return description;
        }
    }

    /**
     * 保存维护响应校验失败的分类，供提取作业记录原因并决定重试。
     */
    static final class Invalid extends IllegalArgumentException {
        final Reason reason;

        /**
         * 用校验失败类别创建维护响应异常，同时保存类别和对应说明，供后台按原因报告并安排重试。
         *
         * @param reason 响应校验失败的分类
         */
        Invalid(Reason reason) {
            super(reason.description());
            this.reason = reason;
        }
    }

    /**
     * 依次检查响应存在、停止原因可接受、消息为 ASSISTANT 且只含文本与思考块，并要求提取后的正文非空。
     * 违反条件时抛出带具体 Reason 的 Invalid，供提取作业报告与重试。
     *
     * @param response 已取得的完整模型响应，含助手消息、停止原因和用量
     */
    static void validateTextResponse(ModelResponse response) {
        if (response == null || response.message() == null)
            throw new Invalid(Reason.MISSING_RESPONSE);
        switch (response.stopReason()) {
            case "max_tokens" -> throw new Invalid(Reason.OUTPUT_LIMIT);
            case "model_context_window_exceeded" -> throw new Invalid(Reason.CONTEXT_LIMIT);
            case "refusal" -> throw new Invalid(Reason.REFUSAL);
            default -> {
            }
        }
        var message = response.message();
        if (message.role() != Role.ASSISTANT || message.content() == null || !message.hasOnlyTextAndThinking())
            throw new Invalid(Reason.INVALID_BLOCKS);
        String text = message.extractText().strip();
        if (text.isEmpty())
            throw new Invalid(Reason.EMPTY_TEXT);
    }

    /**
     * 将 system、单条输入和输出预算组成无工具 CompleteRequest。模型支持 OFF 时关闭思考，其他模型使用 DEFAULT，
     * 返回供后台调用的请求。
     *
     * @param client 用于请求模型的客户端，由装配方确定具体后端
     * @param system 本次请求或执行器使用的系统提示词
     * @param input 脱敏、有界且带来源的提取证据正文
     * @param maxTokens 本次模型回答允许的最大输出 token 数
     * @return 无工具、按模型能力选择思考档位的辅助请求
     */
    static CompleteRequest request(LlmClient client, String system, String input, int maxTokens) {
        var thinking = client.thinkingChoices().contains(ThinkingSettings.OFF) ? ThinkingSettings.OFF
                : ThinkingSettings.DEFAULT;
        return new CompleteRequest(system, java.util.List.of(com.jadecode.messages.Message.userText(input)), maxTokens,
                java.util.List.of(), thinking, "");
    }

    /**
     * 汇集记忆维护请求构造和文本响应校验的静态方法。
     */
    private MemoryReply() {
    }
}
