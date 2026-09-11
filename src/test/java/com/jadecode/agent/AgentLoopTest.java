package com.jadecode.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.ModelResponse;
import com.jadecode.llm.RecordingLlmClient;
import com.jadecode.llm.ScriptedLlmClient;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.safety.Hooks;
import com.jadecode.subagent.SubagentRuntime;
import com.jadecode.todo.TodoManager;
import com.jadecode.tools.BashTool;
import com.jadecode.tools.SubagentTool;
import com.jadecode.tools.TodoWriteTool;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {

    /** 预置脚本并立即跑循环。脚本耗尽时假客户端会抛异常——无限循环的保护伞 */
    private List<Message> runWith(String userText, ModelResponse... script) throws Exception {
        return runWithHooks(userText, new Hooks(), script); // 空事件表:已知工具全放行,无任何拦截
    }

    /** 带自定义 hook 注册表的跑法:模拟 Main 接线(查表/权限等 hook 由用例自己注册) */
    private List<Message> runWithHooks(String userText, Hooks hooks, ModelResponse... script) throws Exception {
        return runWithTools(userText, hooks, Map.of("bash", new BashTool()), script);
    }

    /** 自定义工具表的跑法:reminder 用例需要 bash 与 todo_write 同时在场 */
    private List<Message> runWithTools(String userText, Hooks hooks, Map<String, Tool> tools,
            ModelResponse... script) throws Exception {
        AgentLoop loop = new AgentLoop(new ScriptedLlmClient(script), tools, "test-system", 8000, hooks);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText(userText));
        loop.run(messages);
        return messages;
    }

    private static ModelResponse assistant(ContentBlock... blocks) {
        return new ModelResponse(Message.assistant(List.of(blocks)), new ModelResponse.Usage(0, 0));
    }

    private static ToolUseBlock toolUse(String id, String name, String command) {
        ObjectNode input = Json.mapper().createObjectNode().put("command", command);
        return new ToolUseBlock(id, name, input);
    }

    /** todo_write 的参数:空 todos 数组(合法:清空计划,工具输出 "No todos.") */
    private static ToolUseBlock todoUse(String id) {
        ObjectNode input = Json.mapper().createObjectNode();
        input.putArray("todos");
        return new ToolUseBlock(id, "todo_write", input);
    }

    /** task 的参数是 prompt(不是 command),故不能复用 toolUse 助手 */
    private static ToolUseBlock taskUse(String id, String prompt) {
        ObjectNode input = Json.mapper().createObjectNode().put("prompt", prompt);
        return new ToolUseBlock(id, "task", input);
    }

    /** ① 纯文本回复:历史恰 2 条,无工具执行 */
    @Test
    void pureTextReplyEndsLoop() throws Exception {
        List<Message> messages = runWith("hi", assistant(new TextBlock("你好")));

        assertEquals(2, messages.size());
        assertEquals(Role.ASSISTANT, messages.get(1).role());
        assertEquals("你好", messages.get(1).extractText());
    }

    /** ② 一次 tool_use:回灌一条 user 消息,含一个 tool_result,id 逐块对应 */
    @Test
    void singleToolUseBackfillsOneUserMessage() throws Exception {
        List<Message> messages = runWith("ls",
                assistant(toolUse("toolu_1", "bash", "echo hello")),
                assistant(new TextBlock("done")));

        assertEquals(4, messages.size());
        assertEquals(Role.USER, messages.get(2).role());
        List<ContentBlock> results = messages.get(2).content();
        assertEquals(1, results.size());
        ToolResultBlock r = (ToolResultBlock) results.get(0);
        assertEquals("toolu_1", r.toolUseId());
        assertEquals("hello", r.content());
    }

    /** ③ 一条 assistant 消息含 2 个 tool_use:串行执行,回灌顺序与 tool_use 一致 */
    @Test
    void multipleToolUsesExecuteSerially() throws Exception {
        List<Message> messages = runWith("two commands",
                assistant(toolUse("toolu_1", "bash", "echo first"),
                        toolUse("toolu_2", "bash", "echo second")),
                assistant(new TextBlock("done")));

        List<ContentBlock> results = messages.get(2).content();
        assertEquals(2, results.size());
        assertEquals("toolu_1", ((ToolResultBlock) results.get(0)).toolUseId());
        assertEquals("first", ((ToolResultBlock) results.get(0)).content());
        assertEquals("toolu_2", ((ToolResultBlock) results.get(1)).toolUseId());
        assertEquals("second", ((ToolResultBlock) results.get(1)).content());
    }

    /** ④ 未知工具:装配查表 hook(复刻 Main 接线——循环无兜底,查表是 hook 的职责),回灌 Error 并继续 */
    @Test
    void unknownToolIsBlockedByMembershipHookAndLoopContinues() throws Exception {
        Hooks hooks = new Hooks();
        Map<String, Tool> tools = Map.of("bash", new BashTool()); // 分发表:只有 bash
        hooks.register(Hooks.PRE_TOOL_USE, params -> {
            ToolUseBlock b = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            return tools.containsKey(b.name()) ? null : "Error: Unknown tool: " + b.name();
        });

        List<Message> messages = runWithHooks("use unknown", hooks,
                assistant(toolUse("toolu_1", "no_such_tool", "anything")),
                assistant(new TextBlock("done")));

        assertEquals(4, messages.size());
        ToolResultBlock r = (ToolResultBlock) messages.get(2).content().get(0);
        assertEquals("toolu_1", r.toolUseId());
        assertEquals("Error: Unknown tool: no_such_tool", r.content());
        assertEquals("done", messages.get(3).extractText());
    }

    /** ⑤ 工具链:user → assistant(tool_use) → user(tool_result) → assistant(text) */
    @Test
    void toolChainBuildsFourTurnHistory() throws Exception {
        List<Message> messages = runWith("chain",
                assistant(toolUse("toolu_1", "bash", "true")),
                assistant(new TextBlock("done")));

        assertEquals(Role.USER, messages.get(0).role());
        assertEquals(Role.ASSISTANT, messages.get(1).role());
        assertEquals(Role.USER, messages.get(2).role());
        assertEquals(Role.ASSISTANT, messages.get(3).role());
        assertEquals("(no output)",
                ((ToolResultBlock) messages.get(2).content().get(0)).content());
    }

    /** ⑥ 请求透传:system / maxTokens / tools 与构造注入一致 */
    @Test
    void requestCarriesInjectedConfig() throws Exception {
        ScriptedLlmClient scripted = new ScriptedLlmClient(assistant(new TextBlock("done")));
        RecordingLlmClient recording = new RecordingLlmClient(scripted);
        AgentLoop loop = new AgentLoop(recording, Map.of("bash", new BashTool()), "test-system", 8000, new Hooks());

        loop.run(new ArrayList<>(List.of(Message.userText("hi"))));

        List<CompleteRequest> requests = recording.requests();
        assertEquals(1, requests.size());
        CompleteRequest req = requests.get(0);
        assertEquals("test-system", req.system());
        assertEquals(8000, req.maxTokens());
        assertEquals(1, req.tools().size());
        assertEquals("bash", req.tools().get(0).name());
    }

    /** ⑦ 闸门拒绝经 hook 回灌:hook 返回 "Permission denied: ..."(模拟 Main 权限 lambda),原样进 tool_result,工具未执行 */
    @Test
    void deniedToolReturnsHookMessageAndLoopContinues() throws Exception {
        Hooks hooks = new Hooks();
        hooks.register(Hooks.PRE_TOOL_USE, params -> "Permission denied: Potentially destructive command");

        List<Message> messages = runWithHooks("delete a file", hooks,
                assistant(toolUse("toolu_1", "bash", "rm foo.txt")),
                assistant(new TextBlock("done")));

        assertEquals(4, messages.size());
        ToolResultBlock r = (ToolResultBlock) messages.get(2).content().get(0);
        assertEquals("toolu_1", r.toolUseId());
        // 回灌的是 hook 返回值本身——内容为拒句即证明工具没有真正执行
        assertEquals("Permission denied: Potentially destructive command", r.content());
        assertEquals("done", messages.get(3).extractText());
    }

    /** ⑧ Stop hook 强制续跑:首次返回非 null → 注入 user 消息再跑一轮;放行后正常退出 */
    @Test
    void stopHookCanForceAnotherRound() throws Exception {
        Hooks hooks = new Hooks();
        AtomicBoolean firstStop = new AtomicBoolean(true);
        hooks.register(Hooks.STOP, params -> firstStop.getAndSet(false) ? "keep going" : null);

        List<Message> messages = runWithHooks("hi", hooks,
                assistant(new TextBlock("first")), // 纯文本 → 触发 Stop → 被 hook 拦下续跑
                assistant(new TextBlock("final"))); // 第二轮纯文本 → Stop 放行 → 退出

        assertEquals(4, messages.size());
        assertEquals("first", messages.get(1).extractText());
        assertEquals(Role.USER, messages.get(2).role());
        assertEquals("keep going", messages.get(2).extractText()); // hook 返回值注入为 user 消息
        assertEquals("final", messages.get(3).extractText());
    }

    /** ⑨ PostToolUse:回调收到 (block, output),output 与工具执行结果一致 */
    @Test
    void postToolUseReceivesBlockAndOutput() throws Exception {
        Hooks hooks = new Hooks();
        List<String[]> seen = new ArrayList<>();
        hooks.register(Hooks.POST_TOOL_USE, params -> {
            ToolUseBlock b = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            seen.add(new String[] { b.name(), (String) params.get(Hooks.PARAM_OUTPUT) });
            return null;
        });

        List<Message> messages = runWithHooks("echo", hooks,
                assistant(toolUse("toolu_1", "bash", "echo hello")),
                assistant(new TextBlock("done")));

        assertEquals(1, seen.size());
        assertEquals("bash", seen.get(0)[0]);
        assertEquals("hello", seen.get(0)[1]); // output = 工具执行结果
        assertEquals("hello", ((ToolResultBlock) messages.get(2).content().get(0)).content());
    }

    /** ⑩ 查表 hook 短路在后续 hook 之前:未知工具只触发查表;已知工具后续 hook 照常执行 */
    @Test
    void membershipShortCircuitSkipsLaterHooksOnlyForUnknown() throws Exception {
        Hooks hooks = new Hooks();
        Map<String, Tool> tools = Map.of("bash", new BashTool());
        List<String> calls = new ArrayList<>();
        hooks.register(Hooks.PRE_TOOL_USE, params -> { // ① 查表(模拟 Main 注册序)
            ToolUseBlock b = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            return tools.containsKey(b.name()) ? null : "Error: Unknown tool: " + b.name();
        });
        hooks.register(Hooks.PRE_TOOL_USE, params -> { // ② 后续 hook(权限/日志):记录到调用的名字
            calls.add(((ToolUseBlock) params.get(Hooks.PARAM_BLOCK)).name());
            return null;
        });

        List<Message> messages = runWithHooks("both", hooks,
                assistant(toolUse("toolu_1", "no_such_tool", "anything"),
                        toolUse("toolu_2", "bash", "echo ok")),
                assistant(new TextBlock("done")));

        // 未知工具:查表短路,② 没被调用
        assertEquals("Error: Unknown tool: no_such_tool",
                ((ToolResultBlock) messages.get(2).content().get(0)).content());
        // 已知工具:② 照常执行
        assertEquals("ok", ((ToolResultBlock) messages.get(2).content().get(1)).content());
        assertEquals(List.of("bash"), calls); // 记录型 hook 只见过 bash,没见过 no_such_tool
    }

    /** ⑪ 连续三轮纯 bash(未用 todo_write):前两轮回灌只有 tool_result,第三轮尾部追加 reminder */
    @Test
    void reminderAppearsAfterThreeBashOnlyRounds() throws Exception {
        List<Message> messages = runWith("do it",
                assistant(toolUse("toolu_1", "bash", "echo one")),
                assistant(toolUse("toolu_2", "bash", "echo two")),
                assistant(toolUse("toolu_3", "bash", "echo three")),
                assistant(new TextBlock("done")));

        // 第一、二轮:只有 tool_result,无 reminder
        assertEquals(1, messages.get(2).content().size());
        assertEquals(1, messages.get(4).content().size());
        // 第三轮:tool_result 仍在原位,尾部多一条 reminder(不顶掉结果)
        List<ContentBlock> third = messages.get(6).content();
        assertEquals(2, third.size());
        assertEquals("toolu_3", ((ToolResultBlock) third.get(0)).toolUseId());
        assertInstanceOf(TextBlock.class, third.get(1));
        assertEquals("<reminder>Update your todos.</reminder>", ((TextBlock) third.get(1)).text());
        // 提醒后模型正常收尾
        assertEquals("done", messages.get(7).extractText());
    }

    /** ⑫ 第三轮成功执行 todo_write:该轮不提醒且计数归零——之后还要再连三轮 bash 才再见 reminder */
    @Test
    void todoWriteResetsCounter() throws Exception {
        List<Message> messages = runWithTools("multi-step", new Hooks(),
                Map.of("bash", new BashTool(), "todo_write", new TodoWriteTool(new TodoManager())),
                assistant(toolUse("t1", "bash", "echo 1")), // 轮1:bash → 1
                assistant(toolUse("t2", "bash", "echo 2")), // 轮2:bash → 2
                assistant(todoUse("t3")), // 轮3:todo_write → 清零(若没清零,本轮就该提醒)
                assistant(toolUse("t4", "bash", "echo 4")), // 轮4:→ 1
                assistant(toolUse("t5", "bash", "echo 5")), // 轮5:→ 2
                assistant(toolUse("t6", "bash", "echo 6")), // 轮6:→ 3 → reminder
                assistant(new TextBlock("done")));

        // 轮3(todo_write):只有 tool_result;轮4、5 也都没有 reminder
        assertEquals(1, messages.get(6).content().size());
        assertEquals(1, messages.get(8).content().size());
        assertEquals(1, messages.get(10).content().size());
        // 轮6:reminder 终于出现
        List<ContentBlock> sixth = messages.get(12).content();
        assertEquals(2, sixth.size());
        assertInstanceOf(ToolResultBlock.class, sixth.get(0));
        assertInstanceOf(TextBlock.class, sixth.get(1));
        assertEquals("done", messages.get(13).extractText());
    }

    /** ⑬ 同轮多条工具调用只推进一轮计数:第 3 轮的 3 条 bash 在轮末触发一次 reminder;
     *  tool_result 与 tool_use 逐条对应,reminder 排全部结果之后 */
    @Test
    void sameRoundMultipleCallsCountAsOneRound() throws Exception {
        List<Message> messages = runWith("batch",
                assistant(toolUse("t1", "bash", "echo 1")), // 轮1 → 1
                assistant(toolUse("t2", "bash", "echo 2")), // 轮2 → 2
                assistant(toolUse("t3a", "bash", "echo 3a"), // 轮3:三条调用只计一轮 → 3 → reminder
                        toolUse("t3b", "bash", "echo 3b"),
                        toolUse("t3c", "bash", "echo 3c")),
                assistant(new TextBlock("done")));

        List<ContentBlock> third = messages.get(6).content();
        assertEquals(4, third.size()); // 3 条 tool_result + 1 条 reminder
        assertEquals("t3a", ((ToolResultBlock) third.get(0)).toolUseId());
        assertEquals("t3b", ((ToolResultBlock) third.get(1)).toolUseId());
        assertEquals("t3c", ((ToolResultBlock) third.get(2)).toolUseId());
        assertInstanceOf(TextBlock.class, third.get(3)); // reminder 在最后,顺序不被顶掉
        assertEquals("done", messages.get(7).extractText());
    }

    /** ⑭ 父循环委派 task:子代理独立消费一轮请求,父只收最终文本;父子消息完全隔离 */
    @Test
    void taskDelegationKeepsChildMessagesIsolated() throws Exception {
        // 父子共用同一客户端:脚本顺序 = 父委派 → 子收尾 → 父收尾
        RecordingLlmClient recording = new RecordingLlmClient(new ScriptedLlmClient(
                assistant(taskUse("toolu_1", "调研这个项目")),
                assistant(new TextBlock("子代理结论")),
                assistant(new TextBlock("父最终回答"))));

        SubagentRuntime subagent = new SubagentRuntime(recording,
                Map.of("bash", new BashTool()), "sub-system", 8000, new Hooks());
        AgentLoop loop = new AgentLoop(recording, Map.of("task", new SubagentTool(subagent)),
                "test-system", 8000, new Hooks());

        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("帮我调研"));
        loop.run(messages);

        // 父历史恰 4 条:user → assistant(task) → user(task_result) → assistant(text)
        assertEquals(4, messages.size());
        assertEquals(Role.USER, messages.get(0).role());
        assertEquals(Role.ASSISTANT, messages.get(1).role());
        assertEquals(Role.USER, messages.get(2).role());
        assertEquals(Role.ASSISTANT, messages.get(3).role());
        assertEquals("父最终回答", messages.get(3).extractText());

        // task 的 tool_result:ID 对应,内容恰为子代理最终文本
        ToolResultBlock r = (ToolResultBlock) messages.get(2).content().get(0);
        assertEquals("toolu_1", r.toolUseId());
        assertEquals("子代理结论", r.content());

        // 第二次请求由子代理发出:只有子 prompt,父问题一个字都没带进去
        List<Message> second = recording.requests().get(1).messages();
        assertEquals(1, second.size());
        assertEquals(Role.USER, second.get(0).role());
        assertEquals("调研这个项目", second.get(0).extractText());
    }
}
