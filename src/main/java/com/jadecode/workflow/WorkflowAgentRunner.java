package com.jadecode.workflow;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.jadecode.llm.*;
import com.jadecode.messages.Message;

/**
 * 把一次流程步骤接到现有 LlmClient。每次只发送本步骤资料，不开放工具或读取主会话历史。
 * thinking/maxTokens 在运行开始时固定，恢复身份也包含它们；同一个底层模型客户端仍可并发复用。
 * client 可以是现有用量包装或假实现；本类不读认证、不请求模型目录、不自己重试。
 */
public record WorkflowAgentRunner(LlmClient client, String model, int maxTokens, ThinkingSettings thinking) {
    private static final String SYSTEM = "You are a focused workflow agent. Use only the supplied reference data. "
            + "Do not claim to read files, execute tools or reproduce issues. Return only the requested result.";

    /** 构造只检查依赖和请求预算，不发 API 请求。 */
    public WorkflowAgentRunner {
        java.util.Objects.requireNonNull(client); java.util.Objects.requireNonNull(thinking);
        if (model == null || model.isBlank() || maxTokens < 1) throw new IllegalArgumentException("Invalid workflow runner");
    }

    /** 模型和格式提示版本参与缓存身份，避免换模型/额度/思考设置后错用旧步骤结果。 */
    String identity() { return "workflow-runner-v1|" + model + "|" + maxTokens + "|" + thinking.name(); }

    /**
     * 发一次无工具请求，schema 是提示而非服务端约束；真实解析与校验由 WorkflowContext 完成。
     * 返回完整响应和真实用量，不把 schema 失败吞掉；调用者保证每次修正也经过并发/预算检查。
     */
    ModelResponse call(String prompt, JsonNode schema) throws LlmException {
        String request = prompt + (schema == null ? "" : "\nReturn only one JSON value matching this schema:\n" + schema);
        return client.complete(new CompleteRequest(SYSTEM, List.of(Message.userText(request)), maxTokens, List.of(), thinking));
    }
}
