package com.jadecode.llm;

/**
 * 一次已经请求过模型的用量事实，不是对话消息，也不是需要重放的任务。
 * actor 和 purpose 由装配代码绑定，模型不能给自己改统计身份。
 * usage 为 null 表示没有取得报告；不能用零替代，更不能据此推算账单金额。
 * @param requestId 本次调用的独立编号，用于恢复和重复提交时去重
 * @param model 实际请求的模型名称
 * @param actor Leader、队友或一次性子代理的身份
 * @param purpose 正式任务、一次性子任务、Workflow 步骤、摘要、记忆或辅助审批
 * @param usage 服务端实际报告的值，可能缺失
 */
public record UsageRecord(String requestId, String model, String actor, Purpose purpose, ModelResponse.Usage usage) {
    public enum Purpose { FORMAL, SUBAGENT, SUMMARY, MEMORY, APPROVAL, WORKFLOW }

    public UsageRecord {
        if (requestId == null || requestId.isBlank() || model == null || actor == null || actor.isBlank() || purpose == null)
            throw new IllegalArgumentException("Usage record is missing a call identity");
    }
}
