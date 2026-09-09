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
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.safety.Hooks;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;

/**
 * Agent 主循环:与 LLM 对话直到模型不再调工具为止。
 *
 * <p>
 * 每轮流程:组装 CompleteRequest 并调用 → assistant 消息入栈 → 收集该回复里的
 * tool_use → 逐条串行执行(PreToolUse hook 可拦截)→ 结果合成一条 user 消息
 * 回灌,进入下一轮。停止条件:模型回复里没有 tool_use(停之前先问 Stop hook,
 * 它可能强制追加一轮)。
 * </p>
 *
 * <p>
 * todo 提醒:模型"连续三轮没用过 todo_write"时,该轮回灌消息里追加一条
 * &lt;reminder&gt;Update your todos.&lt;/reminder&gt;。轮次按 agent 回合计——
 * 一轮内调用几条工具都只算一轮(计数在 for 结束后统一推进);
 * 被 PreToolUse 拦截的 todo_write 不算使用。计数器是 run() 的局部变量:
 * 跨轮存活,但每次 run()(即每条用户消息)从 0 重新开始。
 * </p>
 */
public class AgentLoop {
    /** LLM 连接;Main 装配时注入 */
    private final LlmClient client;

    /** 工具分发表:模型 tool_use 块的 name → Tool;执行按此查表 */
    private final Map<String, Tool> tools;

    /** system prompt 原文,随每次请求原样发给模型 */
    private final String system;

    /** 单次请求的 max_tokens 上限 */
    private final int maxTokens;

    /** hook 事件表:PreToolUse(拦截)/ PostToolUse(展示)/ Stop(强制续跑) */
    private final Hooks hooks;

    /** todo_write 的工具名:只有成功执行它才算"用了 todo"(见 {@link #run}) */
    private static final String TODO_TOOL = "todo_write";

    /** 连续 3 轮未用 todo_write 时,追加进回灌消息的提醒文本 */
    private static final String REMINDER_MESSAGE = "<reminder>Update your todos.</reminder>";

    /**
     * @param client    LLM 客户端(真实或测试替身)
     * @param tools     工具分发表
     * @param system    system prompt 原文
     * @param maxTokens 单请求 token 上限
     * @param hooks     hook 事件表
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks) {
        this.client = client;
        this.tools = tools;
        this.system = system;
        this.maxTokens = maxTokens;
        this.hooks = hooks;
    }

    /**
     * @return hook 事件表(测试与 Main 需要拿它注册/断言 hook)
     */
    public Hooks hooks() {
        return hooks;
    }

    /**
     * 在传入的 messages 上原地续跑,直到模型不再调工具为止。
     *
     * <p>
     * 轮次语义:一轮 = while 循环一圈(模型一次回复 + 该回复里全部工具的串行执行)。
     * 工具执行串行,结果按 tool_use 出现顺序回灌;todo 提醒计数在 for 结束后
     * 统一推进一次,所以同轮的多条调用不会让计数多跳。
     * </p>
     *
     * @param messages 会话消息列表,原地追加 assistant / user 消息;调用方持有的
     *                 就是"当前会话",run 结束后可直接继续对话或持久化
     * @throws LlmException 网络 / API 错误,向上抛给调用方决定重试或退出
     */
    public void run(List<Message> messages) throws LlmException {
        int roundsSinceTodo = 0; // 连续几轮没用 todo_write;每条用户消息(每次 run)从 0 重计

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
            boolean usedTodo = false; // 本轮是否成功执行过 todo_write;for 外声明,每轮一块

            for (ToolUseBlock u : toolCalls) {
                // 查表与权限闸门都在 PRE_TOOL_USE 的 hook 里,首个非 null 即拦截
                String blocked = hooks.trigger(Hooks.PRE_TOOL_USE, Map.of(Hooks.PARAM_BLOCK, u));
                if (blocked != null) {
                    results.add(new ToolResultBlock(u.id(), blocked));
                    continue; // 拦截的 todo_write 不算使用:置位只在成功路径后
                }

                // 调用工具获取输出
                Tool tool = tools.get(u.name());
                ObjectNode node = u.input() instanceof ObjectNode o ? o : Json.mapper().createObjectNode();
                String output = tool.execute(node);

                // 成功执行过 todo_write 就置位(拦截的已在上面 continue)
                if (u.name().equals(TODO_TOOL)) {
                    usedTodo = true;
                }

                // 触发事件 POST_TOOL_USE
                hooks.trigger(Hooks.POST_TOOL_USE, Map.of(Hooks.PARAM_BLOCK, u, Hooks.PARAM_OUTPUT, output));

                // 回灌结果
                results.add(new ToolResultBlock(u.id(), output));
            }

            // 轮级 reminder 计数:在 for 结束后统一推进——一轮无论几条工具调用都只算一轮;
            // ≥3 塞一条提醒并把计数归零,下轮重新累计
            roundsSinceTodo = usedTodo ? 0 : roundsSinceTodo + 1;
            if (roundsSinceTodo >= 3) {
                results.add(new TextBlock(REMINDER_MESSAGE));
                roundsSinceTodo = 0;
            }

            // 6. 全部结果合成一条 user 消息:一个 ToolResultBlock(u.id(), output) 对应一个 ToolUseBlock
            messages.add(Message.userBlocks(results));
        }
    }
}
