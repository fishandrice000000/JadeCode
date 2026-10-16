package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.LlmClient;
import com.jadecode.llm.LlmException;
import com.jadecode.llm.ModelResponse;
import com.jadecode.llm.RecordingLlmClient;
import com.jadecode.llm.ScriptedLlmClient;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.subagent.SubagentRuntime;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SubagentTool 单元测试:薄壳的契约、缺参短路、透传与异常转换。
 * 子代理运行时用假客户端装配,不访问网络或真实文件系统。
 */
class SubagentToolTest {

    /** 用给定客户端装一把 task 工具:system / maxTokens 固定,子代理工具表为空 */
    private static SubagentTool toolWith(LlmClient client) {
        return new SubagentTool(new SubagentRuntime(client, Map.of(), "sub-system", 8000, new Hooks()));
    }

    /** 预置一条纯文本回复 */
    private static ModelResponse text(String content) {
        return new ModelResponse(Message.assistant(List.of(new TextBlock(content))),
                new ModelResponse.Usage(0, 0));
    }

    /** ① 定义契约:name / description / properties / required 与默认 inputSchema 平铺一致 */
    @Test
    void definesTaskToolContract() {
        SubagentTool tool = toolWith(new ScriptedLlmClient()); // 契约用例不执行,客户端不会被调用

        assertEquals("task", tool.name());
        assertEquals("Run a subagent with fresh conversation context and return its final text.",
                tool.description());
        assertEquals(Map.of("prompt", "string"), tool.properties());
        assertEquals(List.of("prompt"), tool.required());

        ObjectNode schema = tool.inputSchema();
        assertEquals("object", schema.get("type").asText());
        assertEquals("string", schema.get("properties").get("prompt").get("type").asText());
        assertEquals(1, schema.get("required").size());
        assertEquals("prompt", schema.get("required").get(0).asText());
    }

    /** ② 缺 prompt:回缺参错误,且 LLM 一次都不被调用 */
    @Test
    void missingPromptReturnsErrorWithoutCallingLlm() {
        RecordingLlmClient recording = new RecordingLlmClient(new ScriptedLlmClient());
        SubagentTool tool = toolWith(recording);

        String result = tool.execute(Json.mapper().createObjectNode());

        assertEquals("Error: Missing required parameter: prompt", result);
        assertEquals(0, recording.requests().size());
    }

    /** ③ 正常透传:prompt 成为子代理首条 user 消息,execute 返回最终总结 */
    @Test
    void passesPromptThroughAndReturnsSummary() {
        RecordingLlmClient recording = new RecordingLlmClient(new ScriptedLlmClient(text("调研结论")));
        SubagentTool tool = toolWith(recording);

        String result = tool.execute(Json.mapper().createObjectNode().put("prompt", "调研这个项目"));

        assertEquals("调研结论", result);
        Message first = recording.requests().get(0).messages().get(0);
        assertEquals(Role.USER, first.role());
        assertEquals("调研这个项目", first.extractText());
    }

    /** ④ 异常转换:LLM 抛 LlmException,execute 不抛出,回灌 "Error: " + 消息(Tool 契约:绝不抛异常) */
    @Test
    void convertsLlmExceptionToErrorText() {
        LlmClient boom = request -> {
            throw new LlmException("boom");
        };
        SubagentTool tool = toolWith(boom);

        String result = tool.execute(Json.mapper().createObjectNode().put("prompt", "go"));

        assertEquals("Error: boom", result);
    }
}
