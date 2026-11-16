package com.jadecode.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.config.AppConfig;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.hooks.Hooks;
import com.jadecode.messages.*;
import com.jadecode.tools.Tool;
import com.jadecode.tools.ToolOutcome;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 检查外部工具所需的线上契约；假模型与参数转换不连接任何真实模型服务。 */
class ToolWireContractTest {
    /** 来源只属于内部历史，SDK 应发标准 USER 文本，不向兼容端点泄漏内部字段。 */
    @Test
    void sdkOmitsInternalMemorySource() throws Exception {
        var client = new AnthropicSdkLlmClient(new AppConfig("offline", "http://127.0.0.1:1", "offline", 100,
                com.jadecode.config.ModelProvider.ANTHROPIC_COMPATIBLE));
        var method = AnthropicSdkLlmClient.class.getDeclaredMethod("toParams", CompleteRequest.class);
        method.setAccessible(true);
        var params = (com.anthropic.models.messages.MessageCreateParams) method.invoke(client,
                new CompleteRequest("fixed boundary", List.of(Message.memoryReference("reference text")), 100,
                        List.of(), com.jadecode.llm.ThinkingSettings.DEFAULT, ""));
        var json = com.anthropic.core.ObjectMappers.jsonMapper().valueToTree(params.messages());
        assertEquals("user", json.get(0).get("role").asText());
        assertEquals("reference text", json.get(0).get("content").get(0).get("text").asText());
        assertFalse(json.get(0).has("source"));
    }

    /** 旧日志缺省非错误；新日志以及替换正文后的结果都保留明确失败标志。 */
    @Test
    void oldAndNewHistoryKeepErrorSemantics() throws Exception {
        var old = Json.mapper().readValue(
                "{\"type\":\"tool_result\",\"tool_use_id\":\"x\",\"content\":\"Error: ordinary text\"}",
                ContentBlock.class);
        assertFalse(((ToolResultBlock) old).isError());
        var failed = new ToolResultBlock("x", "failed", true).withContent("saved at path");
        var roundTrip = (ToolResultBlock) Json.mapper().readValue(Json.mapper().writeValueAsString(failed),
                ContentBlock.class);
        assertTrue(roundTrip.isError());
        assertEquals("x", roundTrip.toolUseId());
    }

    /** SDK 转换保留明确失败标志；只反射转换方法，不发请求。 */
    @Test
    void sdkKeepsErrorFlag() throws Exception {
        var client = new AnthropicSdkLlmClient(new AppConfig("offline", "http://127.0.0.1:1", "offline", 100,
                com.jadecode.config.ModelProvider.ANTHROPIC_COMPATIBLE));
        var content = AnthropicSdkLlmClient.class.getDeclaredMethod("toContentBlockParam", ContentBlock.class);
        content.setAccessible(true);
        var block = (com.anthropic.models.messages.ContentBlockParam) content.invoke(client,
                new ToolResultBlock("x", "failed", true));
        assertTrue(block.asToolResult().isError().orElseThrow());
    }

    /** 根级约束和嵌套引用必须到达 SDK；已知字段不能重复进入额外字段表。 */
    @Test
    void sdkKeepsFullSchema() throws Exception {
        var client = new AnthropicSdkLlmClient(new AppConfig("offline", "http://127.0.0.1:1", "offline", 100,
                com.jadecode.config.ModelProvider.ANTHROPIC_COMPATIBLE));
        ObjectNode schema = (ObjectNode) Json.mapper().readTree("""
                {"type":"object","properties":{"value":{"$ref":"#/$defs/value"}},"required":["value"],
                 "additionalProperties":false,"$defs":{"value":{"type":"integer","minimum":2}}}
                """);
        var method = AnthropicSdkLlmClient.class.getDeclaredMethod("toSdkTool", ToolDefinition.class);
        method.setAccessible(true);
        var sdk = (com.anthropic.models.messages.Tool) method.invoke(client,
                new ToolDefinition("external", "desc", schema));
        assertEquals(com.anthropic.core.JsonValue.from(false),
                sdk.inputSchema()._additionalProperties().get("additionalProperties"));
        assertEquals(com.anthropic.core.JsonValue.fromJsonNode(schema.get("$defs")),
                sdk.inputSchema()._additionalProperties().get("$defs"));
        assertEquals(List.of("value"), sdk.inputSchema().required().orElseThrow());
        assertFalse(sdk.inputSchema()._additionalProperties().containsKey("type"));
    }

    /** 工具可以返回失败而让模型继续；执行器写回失败标志，不把失败正文当成异常或成功。 */
    @Test
    void executorPreservesExplicitFailureWithoutAbortingTask() {
        Tool tool = new Tool() {
            public String name() {
                return "external";
            }

            public String description() {
                return "offline";
            }

            private Map<String, String> properties() {
                return Map.of();
            }

            private List<String> required() {
                return List.of();
            }

            public ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context) {
                return new ToolOutcome("not found", true);
            }

            /** 提供完整参数结构；标量构造器只负责组装 Schema，不授予执行权限。 */
            @Override
            public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
                return com.jadecode.tools.ToolSchema.objectSchema(properties(), required());
            }
        };
        var engine = new com.jadecode.agent.AgentExecutor((request, ignoredStream) -> {
            throw new AssertionError("不应请求模型");
        }, com.jadecode.tools.ToolCatalog.fixed(Map.of("external", tool)), "", 10, new Hooks(), null,
                () -> com.jadecode.llm.ResponseStream.NONE, com.jadecode.execution.ExecutionObserver.NONE,
                () -> com.jadecode.llm.ThinkingSettings.DEFAULT, () -> "");
        var result = engine.executeTools(new AgentExecutor.ModelReply(
                Message.userText("offline fixture"),
                List.of(new ToolUseBlock("x", "external", Json.mapper().createObjectNode())),
                false, null, com.jadecode.tools.ToolCatalog.fixed(Map.of("external", tool)).snapshot(), "fixture",
                null),
                ExecutionContext.create(ExecutionContext.Source.USER, null));
        assertNull(result.failure());
        assertTrue(((ToolResultBlock) result.results().getFirst()).isError());
    }
}
