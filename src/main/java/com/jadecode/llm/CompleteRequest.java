package com.jadecode.llm;

import java.util.List;

import com.jadecode.messages.Message;

/**
 * CompleteRequest -- 一次调用 LLM 的输入。
 *
 * @param system    系统提示词;可为 null——SDK 层见到 null 就不注入 system prompt
 * @param messages  调用发送的消息(完整历史,非增量)
 * @param maxTokens 单次输出最大 Token 数
 * @param tools     供模型选择的工具定义列表，不包含可执行工具；schema 由定义自行保存快照
 * @param thinking  本次请求的思考设置；DEFAULT 不额外指定，和是否展示思考无关
 */
public record CompleteRequest(String system, List<Message> messages, int maxTokens, List<ToolDefinition> tools,
        ThinkingSettings thinking) {
    /** 旧调用与辅助请求默认不指定思考参数，保留原服务商行为。 */
    public CompleteRequest(String system, List<Message> messages, int maxTokens, List<ToolDefinition> tools) {
        this(system, messages, maxTokens, tools, ThinkingSettings.DEFAULT);
    }
    public CompleteRequest { if (thinking == null) thinking = ThinkingSettings.DEFAULT; }
    /** 创建这次请求的设置副本，不改系统提示词、历史或工具定义。 */
    public CompleteRequest withThinking(ThinkingSettings value) { return new CompleteRequest(system, messages, maxTokens, tools, value); }
}
