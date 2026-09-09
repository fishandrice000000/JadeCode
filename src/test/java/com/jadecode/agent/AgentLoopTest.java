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
import com.jadecode.tools.BashTool;
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
        AgentLoop loop = new AgentLoop(new ScriptedLlmClient(script),
                Map.of("bash", new BashTool()), "test-system", 8000, hooks);
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
}
