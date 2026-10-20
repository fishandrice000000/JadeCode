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
        NONE("无错误"), REQUEST_FAILED("辅助模型请求失败"), MISSING_RESPONSE("模型未返回消息"),
        INVALID_BLOCKS("回复角色或内容块不符合 JSON 要求"), EMPTY_TEXT("回复没有正文，可能只有思考"),
        OUTPUT_LIMIT("达到输出上限，未采用可能截断的结果；可调整记忆输出额度"),
        CONTEXT_LIMIT("辅助请求超过模型上下文窗口"), REFUSAL("模型拒绝了辅助请求"),
        NOT_ARRAY("回复顶层不是 JSON 数组"), INVALID_JSON("正文不是完整 JSON，或含额外说明/尾随数据"),
        INVALID_INDEX("召回索引不是当前目录内的整数"), INVALID_RECORD("整理条目字段缺失或类型不合法"),
        INVALID_COUNT("整理必须返回非空且数量不增加的完整库"), NAME_CONFLICT("整理返回重复名称或目标冲突"),
        INPUT_LIMIT("完整目录或库超过配置的输入字符额度，未发送不完整材料"),
        CATALOG_ITEM_TOO_LARGE("单条目录资料超过召回页预算，已使用全库关键词降级");
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
