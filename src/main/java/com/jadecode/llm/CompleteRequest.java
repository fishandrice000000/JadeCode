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
 * @param sessionId 本次请求所属的会话路由 ID；空串表示原兼容入口未绑定，绝不写入模型正文
 */
public record CompleteRequest(String system, List<Message> messages, int maxTokens, List<ToolDefinition> tools,
        ThinkingSettings thinking, String sessionId) {
    /** 旧调用与辅助请求默认不指定思考参数，保留原服务商行为。 */
    public CompleteRequest(String system, List<Message> messages, int maxTokens, List<ToolDefinition> tools) {
        this(system, messages, maxTokens, tools, ThinkingSettings.DEFAULT, "");
    }
    /** 旧的显式思考调用也不伪造会话 ID；生产装配在最外层统一绑定。 */
    public CompleteRequest(String system, List<Message> messages, int maxTokens, List<ToolDefinition> tools,
            ThinkingSettings thinking) {
        this(system, messages, maxTokens, tools, thinking, "");
    }
    /** 路由 ID 只接受可安全写入 HTTP 头的有限字符；不允许换行注入或无限长身份。 */
    public CompleteRequest {
        if (thinking == null) thinking = ThinkingSettings.DEFAULT;
        if (sessionId == null) sessionId = "";
        if (!sessionId.isEmpty() && !sessionId.matches("[A-Za-z0-9._:/-]{1,200}"))
            throw new IllegalArgumentException("Model session ID must be a short HTTP-header-safe identifier");
    }
    /** 创建这次请求的设置副本，不改系统提示词、历史或工具定义。 */
    public CompleteRequest withThinking(ThinkingSettings value) { return new CompleteRequest(system, messages, maxTokens, tools, value, sessionId); }
    /** 只替换路由元数据，不改变用户内容、输出预算或思考设置。 */
    public CompleteRequest withSessionId(String value) { return new CompleteRequest(system, messages, maxTokens, tools, thinking, value); }
}
