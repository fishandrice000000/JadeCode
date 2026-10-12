package com.jadecode.subagent;

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
import com.jadecode.messages.ToolExchangeProtocol;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.safety.Hooks;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;

/**
 * 子代理运行时:一次委派 = 一段全新 messages 的嵌套循环。
 *
 * <p>
 * 与父循环({@link com.jadecode.agent.AgentLoop})的关系:共享 LlmClient / Hooks / 工作区,
 * 但消息完全隔离——子代理从只有一条 user 消息的列表起步,父代理只收它的最终文本。
 * 工具表由 Main 装配传入(不含 task,故只有一层委派)。
 * </p>
 */
public class SubagentRuntime {
    /** 子代理最多跑几轮;循环正常走完即超限,回 stopped 文案 */
    private static final int MAX_TURNS = 30;

    /** stopped 文案 */
    private static final String STOPPED = "Subagent stopped after " + MAX_TURNS
            + " turns without a final answer.";

    /** no summary 文案 */
    private static final String NO_SUMMARY = "(no summary)";

    /** unknown tool 文案 */
    private static final String UNKNOWN_TOOL = "Error: Unknown tool: ";

    /** LLM 客户端:与父循环共用同一实例 */
    private final LlmClient client;

    /** 子代理工具表(基础工具,不含 task) */
    private final Map<String, Tool> tools;

    /** 子代理的 system prompt */
    private final String system;

    /** 单次请求的 max_tokens,与父循环同值 */
    private final int maxTokens;

    /** 共享事件表:子代理的工具调用同样过权限与生命周期 hook */
    private final Hooks hooks;

    /**
     * @param client    LLM 客户端
     * @param tools     子代理工具表
     * @param system    子代理 system prompt
     * @param maxTokens 单请求 token 上限
     * @param hooks     共享事件表
     */
    public SubagentRuntime(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks) {
        this.client = client;
        this.tools = tools;
        this.system = system;
        this.maxTokens = maxTokens;
        this.hooks = hooks;
    }

    /**
     * 跑一次委派,返回子代理的最终文本。
     *
     * <p>
     * 步骤见作业单第三节 ①:新建消息列表 → 至多 30 轮循环 → 无 tool_use 时触发 Stop hook,
     * 放行则返回最终文本(空白 → "(no summary)")→ 轮数耗尽返回 stopped 文案。
     * </p>
     *
     * <p>
     * 生命周期:入口恰好一次 SUBAGENT_START,出口恰好一次 SUBAGENT_END——正常收尾 DONE、
     * 轮数耗尽 STOPPED、LLM 异常与模型回复内重复调用 ID FAILED。END 只由本类触发。
     * </p>
     *
     * @param prompt 父代理给的子任务描述
     * @return 子代理最终文本;超限时为 "Subagent stopped after 30 turns without a final
     *         answer."
     * @throws LlmException 网络 / API 错误,向上抛给调用方决定重试或退出
     */
    public String run(String prompt) throws LlmException {
        List<Message> messages = new ArrayList<>();

        messages.add(Message.userText(prompt));

        // 触发事件 SUBAGENT_START
        hooks.trigger(Hooks.SUBAGENT_START, Map.of());

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            // 1. 组装 CompleteRequest 并调用获取 LLM 回复;全类唯一的受检异常来源,
            // 就地收尾:END(FAILED) 后原样抛给调用方,保证出口的 END 恰好一次
            ModelResponse response;
            try {
                response = client
                        .complete(new CompleteRequest(system, messages, maxTokens, List.copyOf(tools.values())));
            } catch (LlmException e) {
                hooks.trigger(Hooks.SUBAGENT_END,
                        Map.of(Hooks.PARAM_OUTPUT, "Error: " + e.getMessage(),
                                Hooks.PARAM_OUTCOME, SubagentOutcome.FAILED));
                throw e;
            }

            // 2. 从 response.message().content() 收集 ToolUseBlock,并在追加 assistant 消息
            //    与执行任何工具之前拒绝重复调用 ID;失败走 END(FAILED) 恰好一次后原样抛出
            List<ToolUseBlock> toolCalls = new ArrayList<>();

            for (ContentBlock c : response.message().content()) {
                if (c instanceof ToolUseBlock u) {
                    toolCalls.add(u);
                }
            }
            try {
                ToolExchangeProtocol.requireDistinctCallIds(toolCalls);
            } catch (IllegalArgumentException e) {
                hooks.trigger(Hooks.SUBAGENT_END,
                        Map.of(Hooks.PARAM_OUTPUT, "Error: " + e.getMessage(),
                                Hooks.PARAM_OUTCOME, SubagentOutcome.FAILED));
                throw e;
            }

            // 3. 追加 assistant 消息
            messages.add(response.message());

            // 4. 没有 tool_use, 此轮结束
            if (toolCalls.isEmpty()) {
                // 触发事件 STOP
                String force = hooks.trigger(Hooks.STOP, Map.of(Hooks.PARAM_MESSAGES, messages));
                if (force != null) {
                    messages.add(Message.userText(force));
                    continue;
                }

                String output = response.message().extractText();
                output = output.isBlank() ? NO_SUMMARY : output;

                // 触发事件 SUBAGENT_END(正常收尾)
                hooks.trigger(Hooks.SUBAGENT_END,
                        Map.of(Hooks.PARAM_OUTPUT, output, Hooks.PARAM_OUTCOME, SubagentOutcome.DONE));

                return output;
            }

            // 5. 调用 tool
            List<ContentBlock> results = new ArrayList<>();

            for (ToolUseBlock u : toolCalls) {
                // 先查本类自己的工具表:子表是父表的子集,父表查得到的 task / todo_write 在这里是未知工具。
                // 未知工具在此短路,不进任何工具 hook——否则会打出一行"调用过"的假日志
                Tool tool = tools.get(u.name());
                if (tool == null) {
                    results.add(new ToolResultBlock(u.id(), UNKNOWN_TOOL + u.name()));
                    continue;
                }

                // 已知工具才进 PRE_TOOL_USE:权限闸门与日志都挂在这里,首个非 null 即拦截
                String blocked = hooks.trigger(Hooks.PRE_TOOL_USE, Map.of(Hooks.PARAM_BLOCK, u));
                if (blocked != null) {
                    results.add(new ToolResultBlock(u.id(), blocked));
                    continue;
                }

                // 调用工具获取输出
                ObjectNode node = u.input() instanceof ObjectNode o ? o : Json.mapper().createObjectNode();
                String output = tool.execute(node);

                // 触发事件 POST_TOOL_USE
                hooks.trigger(Hooks.POST_TOOL_USE, Map.of(Hooks.PARAM_BLOCK, u, Hooks.PARAM_OUTPUT, output));
                // 触发事件 SUBAGENT_TOOL
                hooks.trigger(Hooks.SUBAGENT_TOOL,
                        Map.of(Hooks.PARAM_BLOCK, u, Hooks.PARAM_OUTPUT, output));

                // 回灌结果
                results.add(new ToolResultBlock(u.id(), output));
            }

            // 6. 全部结果合成一条 user 消息:一个 ToolResultBlock(u.id(), output) 对应一个 ToolUseBlock
            messages.add(Message.userBlocks(results));
        }

        // 触发事件 SUBAGENT_END(30 轮耗尽)
        hooks.trigger(Hooks.SUBAGENT_END,
                Map.of(Hooks.PARAM_OUTPUT, STOPPED, Hooks.PARAM_OUTCOME, SubagentOutcome.STOPPED));
        return STOPPED;
    }
}
