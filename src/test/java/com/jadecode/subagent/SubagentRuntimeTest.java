package com.jadecode.subagent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.LlmClient;
import com.jadecode.llm.LlmException;
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
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SubagentRuntime 单元测试:全程用脚本 / 记录假客户端 + 内存假工具 + 自定义事件表,
 * 不访问网络、真实 LLM、用户输入或真实文件系统。
 */
class SubagentRuntimeTest {

    /** 内存假工具:记录调用次数,返回预置输出(不碰真实文件系统) */
    private static final class FakeTool implements Tool {
        private final String name;
        private final String output;
        private int calls;

        FakeTool(String name, String output) {
            this.name = name;
            this.output = output;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "fake " + name;
        }

        @Override
        public Map<String, String> properties() {
            return Map.of();
        }

        @Override
        public List<String> required() {
            return List.of();
        }

        @Override
        public String execute(ObjectNode input) {
            calls++;
            return output;
        }
    }

    /** 预置一条 assistant 回复(子代理侧只需要 message,usage 归零) */
    private static ModelResponse assistant(ContentBlock... blocks) {
        return new ModelResponse(Message.assistant(List.of(blocks)), new ModelResponse.Usage(0, 0));
    }

    /** 预置一条 tool_use:入参留空对象(FakeTool 不读参数) */
    private static ToolUseBlock toolUse(String id, String name) {
        return new ToolUseBlock(id, name, Json.mapper().createObjectNode());
    }

    /** 造运行时:system 与 maxTokens 固定,便于透传断言 */
    private static SubagentRuntime runtime(LlmClient client, Map<String, Tool> tools, Hooks hooks) {
        return new SubagentRuntime(client, tools, "sub-system", 8000, hooks);
    }

    /** 登记一个收集 SUBAGENT_END 入参的 hook,返回收集列表 */
    private static List<Map<String, ?>> recordEnds(Hooks hooks) {
        List<Map<String, ?>> ends = new ArrayList<>();
        hooks.register(Hooks.SUBAGENT_END, params -> {
            ends.add(params);
            return null;
        });
        return ends;
    }

    /** ① 纯文本与请求透传:返回该文本;首个请求只有子 prompt,system / maxTokens / 工具名正确 */
    @Test
    void pureTextReplyReturnsTextAndForwardsConfig() throws Exception {
        RecordingLlmClient recording = new RecordingLlmClient(
                new ScriptedLlmClient(assistant(new TextBlock("子代理结论"))));
        SubagentRuntime runtime = runtime(recording, Map.of("bash", new FakeTool("bash", "ok")), new Hooks());

        String result = runtime.run("调研这个项目");

        assertEquals("子代理结论", result);
        List<CompleteRequest> requests = recording.requests();
        assertEquals(1, requests.size());
        assertEquals("sub-system", requests.get(0).system());
        assertEquals(8000, requests.get(0).maxTokens());
        assertEquals(List.of("bash"), requests.get(0).tools().stream().map(Tool::name).toList());
        // 消息隔离:首个请求只有子 prompt,父对话一个字都没进来
        List<Message> first = requests.get(0).messages();
        assertEquals(1, first.size());
        assertEquals(Role.USER, first.get(0).role());
        assertEquals("调研这个项目", first.get(0).extractText());
    }

    /** ② 空白总结:返回 (no summary);END 的 output 也是降级文本,终态 DONE */
    @Test
    void blankFinalTextFallsBackToNoSummary() throws Exception {
        Hooks hooks = new Hooks();
        List<Map<String, ?>> ends = recordEnds(hooks);
        SubagentRuntime runtime = runtime(new ScriptedLlmClient(assistant(new TextBlock("   "))), Map.of(), hooks);

        String result = runtime.run("task");

        assertEquals("(no summary)", result);
        assertEquals(1, ends.size());
        assertEquals("(no summary)", ends.get(0).get(Hooks.PARAM_OUTPUT));
        assertEquals(SubagentOutcome.DONE, ends.get(0).get(Hooks.PARAM_OUTCOME));
    }

    /** ③ 工具执行与回灌:已知工具执行一次;第二次请求带匹配 id / output 的 tool_result */
    @Test
    void knownToolExecutesOnceAndResultIsFedBack() throws Exception {
        FakeTool bash = new FakeTool("bash", "hello");
        RecordingLlmClient recording = new RecordingLlmClient(new ScriptedLlmClient(
                assistant(toolUse("t1", "bash")),
                assistant(new TextBlock("done"))));
        SubagentRuntime runtime = runtime(recording, Map.of("bash", bash), new Hooks());

        assertEquals("done", runtime.run("run bash"));

        assertEquals(1, bash.calls);
        List<Message> second = recording.requests().get(1).messages();
        assertEquals(3, second.size()); // user(prompt) → assistant(tool_use) → user(tool_result)
        ToolResultBlock r = (ToolResultBlock) second.get(2).content().get(0);
        assertEquals("t1", r.toolUseId());
        assertEquals("hello", r.content());
    }

    /** ④ 同轮多工具:串行各执行一次;两个结果同处一条 user 消息,顺序与 tool_use 一致 */
    @Test
    void sameRoundToolsRunSeriallyInOrder() throws Exception {
        FakeTool bash = new FakeTool("bash", "one");
        FakeTool read = new FakeTool("read_file", "two");
        RecordingLlmClient recording = new RecordingLlmClient(new ScriptedLlmClient(
                assistant(toolUse("t1", "bash"), toolUse("t2", "read_file")),
                assistant(new TextBlock("done"))));
        SubagentRuntime runtime = runtime(recording, Map.of("bash", bash, "read_file", read), new Hooks());

        runtime.run("both");

        assertEquals(1, bash.calls);
        assertEquals(1, read.calls);
        List<ContentBlock> results = recording.requests().get(1).messages().get(2).content();
        assertEquals(2, results.size());
        assertEquals("t1", ((ToolResultBlock) results.get(0)).toolUseId());
        assertEquals("one", ((ToolResultBlock) results.get(0)).content());
        assertEquals("t2", ((ToolResultBlock) results.get(1)).toolUseId());
        assertEquals("two", ((ToolResultBlock) results.get(1)).content());
    }

    /** ⑤ Stop hook 强制续跑:返回值作为 user 消息进入下一次请求,随后正常收尾 */
    @Test
    void stopHookForcesAnotherRound() throws Exception {
        Hooks hooks = new Hooks();
        AtomicBoolean firstStop = new AtomicBoolean(true);
        hooks.register(Hooks.STOP, params -> firstStop.getAndSet(false) ? "keep going" : null);

        RecordingLlmClient recording = new RecordingLlmClient(new ScriptedLlmClient(
                assistant(new TextBlock("first")),
                assistant(new TextBlock("final"))));
        SubagentRuntime runtime = runtime(recording, Map.of(), hooks);

        assertEquals("final", runtime.run("task"));

        assertEquals(2, recording.requests().size());
        List<Message> second = recording.requests().get(1).messages();
        assertEquals(3, second.size());
        assertEquals(Role.USER, second.get(2).role());
        assertEquals("keep going", second.get(2).extractText());
    }

    /** ⑥ PRE 拦截:工具不执行;拦截文案回灌;POST 与 SUBAGENT_TOOL 均不触发 */
    @Test
    void preToolUseBlockSkipsExecutionAndLaterHooks() throws Exception {
        Hooks hooks = new Hooks();
        List<String> later = new ArrayList<>();
        hooks.register(Hooks.PRE_TOOL_USE, params -> "Permission denied: nope");
        hooks.register(Hooks.POST_TOOL_USE, params -> {
            later.add("post");
            return null;
        });
        hooks.register(Hooks.SUBAGENT_TOOL, params -> {
            later.add("sub");
            return null;
        });

        FakeTool bash = new FakeTool("bash", "should not run");
        RecordingLlmClient recording = new RecordingLlmClient(new ScriptedLlmClient(
                assistant(toolUse("t1", "bash")),
                assistant(new TextBlock("done"))));
        SubagentRuntime runtime = runtime(recording, Map.of("bash", bash), hooks);

        runtime.run("task");

        assertEquals(0, bash.calls);
        assertEquals(List.of(), later);
        ToolResultBlock r = (ToolResultBlock) recording.requests().get(1).messages().get(2).content().get(0);
        assertEquals("Permission denied: nope", r.content());
    }

    /** ⑦ 未知工具早短路:task 回灌 Unknown tool;PRE / POST / SUBAGENT_TOOL 一个都不触发 */
    @Test
    void unknownToolShortCircuitsBeforeAnyHook() throws Exception {
        Hooks hooks = new Hooks();
        List<String> fired = new ArrayList<>();
        hooks.register(Hooks.PRE_TOOL_USE, params -> {
            fired.add("pre");
            return null;
        });
        hooks.register(Hooks.POST_TOOL_USE, params -> {
            fired.add("post");
            return null;
        });
        hooks.register(Hooks.SUBAGENT_TOOL, params -> {
            fired.add("sub");
            return null;
        });

        RecordingLlmClient recording = new RecordingLlmClient(new ScriptedLlmClient(
                assistant(toolUse("t1", "task")),
                assistant(new TextBlock("done"))));
        // 子代理工具表只有基础工具:task 是父表独有,模型幻觉调用它时必须被本地查表拦下
        SubagentRuntime runtime = runtime(recording, Map.of("bash", new FakeTool("bash", "ok")), hooks);

        runtime.run("task");

        assertEquals(List.of(), fired);
        ToolResultBlock r = (ToolResultBlock) recording.requests().get(1).messages().get(2).content().get(0);
        assertEquals("Error: Unknown tool: task", r.content());
    }

    /** ⑧ 30 轮耗尽:LLM 恰调用 30 次;返回标准 stopped 文案;END 恰一次且终态 STOPPED */
    @Test
    void exhaustingThirtyTurnsReturnsStoppedText() throws Exception {
        Hooks hooks = new Hooks();
        List<Map<String, ?>> ends = recordEnds(hooks);

        // 30 条恒为 tool_use 的回复:模型一直干活,永远不给最终文本
        ModelResponse[] script = new ModelResponse[30];
        Arrays.fill(script, assistant(toolUse("t1", "bash")));
        RecordingLlmClient recording = new RecordingLlmClient(new ScriptedLlmClient(script));
        SubagentRuntime runtime = runtime(recording, Map.of("bash", new FakeTool("bash", "ok")), hooks);

        String stopped = "Subagent stopped after 30 turns without a final answer.";
        assertEquals(stopped, runtime.run("loop forever"));

        assertEquals(30, recording.requests().size());
        assertEquals(1, ends.size());
        assertEquals(SubagentOutcome.STOPPED, ends.get(0).get(Hooks.PARAM_OUTCOME));
        assertEquals(stopped, ends.get(0).get(Hooks.PARAM_OUTPUT));
    }

    /** ⑨ 完整事件顺序:START → PRE → POST → SUBAGENT_TOOL → STOP → END(DONE) */
    @Test
    void eventsFireInOrder() throws Exception {
        Hooks hooks = new Hooks();
        List<String> events = new ArrayList<>();
        hooks.register(Hooks.SUBAGENT_START, params -> {
            events.add("start");
            return null;
        });
        hooks.register(Hooks.PRE_TOOL_USE, params -> {
            events.add("pre");
            return null;
        });
        hooks.register(Hooks.POST_TOOL_USE, params -> {
            events.add("post");
            return null;
        });
        hooks.register(Hooks.SUBAGENT_TOOL, params -> {
            events.add("sub");
            return null;
        });
        hooks.register(Hooks.STOP, params -> {
            events.add("stop");
            return null;
        });
        hooks.register(Hooks.SUBAGENT_END, params -> {
            events.add("end");
            return null;
        });

        RecordingLlmClient recording = new RecordingLlmClient(new ScriptedLlmClient(
                assistant(toolUse("t1", "bash")),
                assistant(new TextBlock("done"))));
        SubagentRuntime runtime = runtime(recording, Map.of("bash", new FakeTool("bash", "ok")), hooks);

        runtime.run("task");

        assertEquals(List.of("start", "pre", "post", "sub", "stop", "end"), events);
    }

    /** ⑩ LLM 异常:run 原样抛出;事件仅 START → END(FAILED);END 的 output 为 "Error: " + 异常消息 */
    @Test
    void llmExceptionTriggersFailedOutcomeAndRethrows() {
        Hooks hooks = new Hooks();
        List<String> events = new ArrayList<>();
        List<Map<String, ?>> ends = new ArrayList<>();
        hooks.register(Hooks.SUBAGENT_START, params -> {
            events.add("start");
            return null;
        });
        hooks.register(Hooks.SUBAGENT_END, params -> {
            events.add("end");
            ends.add(params);
            return null;
        });

        // 空脚本:首次 complete 即抛(假客户端的耗尽保护)
        SubagentRuntime runtime = runtime(new ScriptedLlmClient(), Map.of(), hooks);

        LlmException thrown = assertThrows(LlmException.class, () -> runtime.run("task"));

        assertNotNull(thrown.getMessage());
        assertEquals(List.of("start", "end"), events);
        assertEquals(1, ends.size());
        assertEquals(SubagentOutcome.FAILED, ends.get(0).get(Hooks.PARAM_OUTCOME));
        assertEquals("Error: " + thrown.getMessage(), ends.get(0).get(Hooks.PARAM_OUTPUT));
    }
}
