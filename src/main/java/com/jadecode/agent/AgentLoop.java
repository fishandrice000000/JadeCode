package com.jadecode.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.LlmClient;
import com.jadecode.llm.LlmException;
import com.jadecode.llm.ModelResponse;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;

public class AgentLoop {
    private final LlmClient client;
    private final Map<String, Tool> tools; // 分发表:tool_use 的 name → 工具实例
    private final String system;
    private final int maxTokens;

    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens) {
        this.client = client;
        this.tools = tools;
        this.system = system;
        this.maxTokens = maxTokens;
    }

    /** 在传入的 messages 上原地续跑,直到模型不再调工具为止 */
    public void run(List<Message> messages) throws LlmException {
        while (true) {
            // 1. 组装 CompleteRequest 并调用获取 LLM 回复
            ModelResponse response = client
                    .complete(new CompleteRequest(system, messages, maxTokens, List.copyOf(tools.values())));

            // 2. 追加 assistant 消息
            messages.add(response.message());

            // 3. 从 response.message().content() 收集 ToolUseBlock
            List<ToolUseBlock> toolCalls = new ArrayList<>();

            for (ContentBlock c : response.message().content()) {
                if (c instanceof ToolUseBlock u) {
                    toolCalls.add(u);
                }
            }

            // 4. 没有 tool_use, 此轮结束
            if (toolCalls.isEmpty()) {
                return;
            }

            // 5. 从 toolCalls 中逐个检查工具是否存在于分发表中
            List<ContentBlock> results = new ArrayList<>();

            for (ToolUseBlock u : toolCalls) {
                Tool tool = tools.get(u.name());

                // 5.1 使用的工具不在分发表中, 构造异常结果
                if (tool == null) {
                    results.add(new ToolResultBlock(u.id(), "Error: Unknown tool: " + u.name()));
                    continue;
                }

                // 5.2 使用的工具在分发表中, 执行工具并收集结果
                // 需检查 input 是否为 ObjectNode
                results.add(new ToolResultBlock(
                        u.id(),
                        tool.execute(u.input() instanceof ObjectNode node ? node : Json.mapper().createObjectNode())));
            }

            // 6. 全部结果合成一条 user 消息:messages.add(Message.userBlocks(results))
            // 一个 ToolResultBlock(u.id(), output) 对应一个 ToolUseBlock
            messages.add(Message.userBlocks(results));
        }
    }
}
