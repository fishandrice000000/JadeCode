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
import com.jadecode.safety.Hooks;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;

public class AgentLoop {
    private final LlmClient client;
    private final Map<String, Tool> tools; // 分发表:tool_use 的 name → 工具实例
    private final String system;
    private final int maxTokens;
    private final Hooks hooks;

    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks) {
        this.client = client;
        this.tools = tools;
        this.system = system;
        this.maxTokens = maxTokens;
        this.hooks = hooks;
    }

    public Hooks hooks() {
        return hooks;
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
                String force = hooks.trigger(Hooks.STOP, Map.of(Hooks.PARAM_MESSAGES, messages));
                if (force != null) {
                    messages.add(Message.userText(force));
                    continue;
                }
                return;
            }

            // 5. 调用 tool
            List<ContentBlock> results = new ArrayList<>();

            for (ToolUseBlock u : toolCalls) {
                // 5.1 查表与权限闸门都在 PRE_TOOL_USE 的 hook 里,首个非 null 即拦截
                String blocked = hooks.trigger(Hooks.PRE_TOOL_USE, Map.of(Hooks.PARAM_BLOCK, u));
                if (blocked != null) {
                    results.add(new ToolResultBlock(u.id(), blocked));
                    continue;
                }

                // 5.2 调用工具获取输出
                Tool tool = tools.get(u.name());
                ObjectNode node = u.input() instanceof ObjectNode o ? o : Json.mapper().createObjectNode();
                String output = tool.execute(node);

                // 5.3 触发事件 POST_TOOL_USE
                hooks.trigger(Hooks.POST_TOOL_USE, Map.of(Hooks.PARAM_BLOCK, u, Hooks.PARAM_OUTPUT, output));

                // 5.4 回灌结果
                results.add(new ToolResultBlock(u.id(), output));
            }

            // 6. 全部结果合成一条 user 消息:messages.add(Message.userBlocks(results))
            // 一个 ToolResultBlock(u.id(), output) 对应一个 ToolUseBlock
            messages.add(Message.userBlocks(results));
        }
    }
}
