package com.jadecode.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.jadecode.llm.*;
import com.jadecode.messages.Role;
import com.jadecode.util.Json;

/**
 * 三种记忆辅助请求共用的 JSON 读取规则，不解释长期价值，也不写盘。
 * 允许伴随 thinking 和包住全文的一层 JSON 代码围栏；只读取正式文本，不展示或解析思考。
 * 不从解释文字中猜数组、不修补截断 JSON，避免把半份整理结果当成完整库。
 */
public final class MemoryReply {
    /** 固定诊断，不携带模型正文、原异常消息或凭据。 */
    public enum Reason {
        NONE("No error"), REQUEST_FAILED("Auxiliary model request failed"), MISSING_RESPONSE("Model returned no message"),
        INVALID_BLOCKS("Reply role or content blocks do not meet the JSON contract"), EMPTY_TEXT("Reply has no text, possibly only thinking"),
        OUTPUT_LIMIT("Output limit reached; a possibly truncated result was discarded. Adjust the memory output budget"),
        CONTEXT_LIMIT("Auxiliary request exceeds the model context window"), REFUSAL("Model refused the auxiliary request"),
        NOT_ARRAY("Reply top level is not a JSON array"), INVALID_JSON("Text is not complete JSON, or contains extra explanation/trailing data"),
        INVALID_INDEX("Recall index is not an integer within the current catalog"), INVALID_RECORD("Consolidation entry is missing a field or has an invalid type"),
        INVALID_COUNT("Consolidation must return a non-empty complete library without increasing the record count"), NAME_CONFLICT("Consolidation returned duplicate names or conflicting targets"),
        INPUT_LIMIT("The full catalog or library exceeds the configured input character budget; incomplete material was not sent"),
        CATALOG_ITEM_TOO_LARGE("A single catalog entry exceeds the recall page budget; fell back to keyword search over the full library");
        private final String description;
        Reason(String description) { this.description = description; }
        public String description() { return description; }
    }

    /** 仅传递安全分类，不保留 Jackson 可能含候选正文的异常链。 */
    static final class Invalid extends IllegalArgumentException {
        final Reason reason;
        Invalid(Reason reason) { super(reason.description()); this.reason = reason; }
    }

    /**
     * 检查协议停止原因和消息形状，再解析整个文本为数组；失败不产生任何 IO。
     * 缺少 stopReason 的旧客户端兼容保留，但明确的截断/拒绝必须拒绝，不能靠 JSON 恰好闭合猜成功。
     */
    static JsonNode array(ModelResponse response) {
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
        var fence = java.util.regex.Pattern.compile("(?is)\\A```(?:json)?[ \\t]*\\R(.*)\\R```\\z").matcher(text);
        if (fence.matches()) text = fence.group(1).strip();
        try {
            JsonNode value = Json.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            if (value == null || !value.isArray()) throw new Invalid(Reason.NOT_ARRAY);
            return value;
        } catch (JsonProcessingException invalidJson) { throw new Invalid(Reason.INVALID_JSON); }
    }

    /** 辅助任务只需结构化正文；已适配 OFF 的后端明确关闭思考，未知后端保持默认不乱加字段。 */
    static CompleteRequest request(LlmClient client, String system, String input, int maxTokens) {
        var thinking = client.thinkingChoices().contains(ThinkingSettings.OFF) ? ThinkingSettings.OFF : ThinkingSettings.DEFAULT;
        return new CompleteRequest(system, java.util.List.of(com.jadecode.messages.Message.userText(input)), maxTokens, java.util.List.of(), thinking);
    }
    private MemoryReply() { }
}
