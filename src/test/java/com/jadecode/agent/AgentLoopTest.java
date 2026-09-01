package com.jadecode.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.jadecode.tools.BashTool;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {

    /** 预置脚本并立即跑循环。脚本耗尽时假客户端会抛异常——无限循环的保护伞 */
    private List<Message> runWith(String userText, ModelResponse... script) throws Exception {
        AgentLoop loop = new AgentLoop(new ScriptedLlmClient(script),
                Map.of("bash", new BashTool()), "test-system", 8000);
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

    /** ④ 未知工具名:不崩,回灌错误结果,循环继续直到模型收尾 */
    @Test
    void unknownToolProducesErrorResultAndLoopContinues() throws Exception {
        List<Message> messages = runWith("use unknown",
                assistant(toolUse("toolu_1", "no_such_tool", "anything")),
                assistant(new TextBlock("done")));

        assertEquals(4, messages.size());
        ToolResultBlock r = (ToolResultBlock) messages.get(2).content().get(0);
        assertEquals("toolu_1", r.toolUseId());
        assertTrue(r.content().startsWith("Error: Unknown tool:"));
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
        AgentLoop loop = new AgentLoop(recording, Map.of("bash", new BashTool()), "test-system", 8000);

        loop.run(new ArrayList<>(List.of(Message.userText("hi"))));

        List<CompleteRequest> requests = recording.requests();
        assertEquals(1, requests.size());
        CompleteRequest req = requests.get(0);
        assertEquals("test-system", req.system());
        assertEquals(8000, req.maxTokens());
        assertEquals(1, req.tools().size());
        assertEquals("bash", req.tools().get(0).name());
    }
}
