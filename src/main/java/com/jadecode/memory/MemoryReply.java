package com.jadecode.memory;

import com.jadecode.llm.*;
import com.jadecode.messages.Role;

/**
 * 提取辅助回复的协议边界：只接受完整正式正文，允许伴随 thinking，但不读取思考内容。
 * 严格两字段 JSON 由维护者继续解析；这里不修补围栏、截断正文或其它旧格式。
 */
final class MemoryReply {
    /** 固定诊断不携带模型正文与凭据；只保留当前提取链需要的类别。 */
    enum Reason {
        MISSING_RESPONSE("Model returned no message"), INVALID_BLOCKS("Reply role or blocks violate the text contract"),
        EMPTY_TEXT("Reply has no text, possibly only thinking"), OUTPUT_LIMIT("Output limit reached; incomplete memory discarded"),
        CONTEXT_LIMIT("Auxiliary request exceeds the model context window"), REFUSAL("Model refused the auxiliary request");
        private final String description;
        Reason(String description) { this.description = description; }
        String description() { return description; }
    }

    /** 只传递分类，不保存可能包含秘密的解析异常链。 */
    static final class Invalid extends IllegalArgumentException {
        final Reason reason;
        Invalid(Reason reason) { super(reason.description()); this.reason = reason; }
    }

    /** 验证完整正文协议，再由维护者执行严格对象解析；本方法不产生 IO。 */
    static void validateTextResponse(ModelResponse response) {
        if (response == null || response.message() == null) throw new Invalid(Reason.MISSING_RESPONSE);
        switch (response.stopReason()) {
            case "max_tokens" -> throw new Invalid(Reason.OUTPUT_LIMIT);
            case "model_context_window_exceeded" -> throw new Invalid(Reason.CONTEXT_LIMIT);
            case "refusal" -> throw new Invalid(Reason.REFUSAL);
            default -> { }
        }
        var message = response.message();
        if (message.role() != Role.ASSISTANT || message.content() == null || !message.hasOnlyTextAndThinking())
            throw new Invalid(Reason.INVALID_BLOCKS);
        String text = message.extractText().strip();
        if (text.isEmpty()) throw new Invalid(Reason.EMPTY_TEXT);
    }

    /** 辅助任务只需结构化正文；已适配 OFF 的后端明确关闭思考，未知后端保持默认不乱加字段。 */
    static CompleteRequest request(LlmClient client, String system, String input, int maxTokens) {
        var thinking = client.thinkingChoices().contains(ThinkingSettings.OFF) ? ThinkingSettings.OFF : ThinkingSettings.DEFAULT;
        return new CompleteRequest(system, java.util.List.of(com.jadecode.messages.Message.userText(input)), maxTokens, java.util.List.of(), thinking);
    }
    private MemoryReply() { }
}
