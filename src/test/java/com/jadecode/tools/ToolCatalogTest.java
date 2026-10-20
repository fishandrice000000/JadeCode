package com.jadecode.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.execution.ToolIdentity;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.ModelResponse;
import com.jadecode.messages.*;
import com.jadecode.mcp.McpToolDescriptor;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 动态工具目录的请求边界：当前批次不能使用刚刚连接才发现的工具。 */
class ToolCatalogTest {
    /** 轻量假工具只改变测试状态，不启动进程或请求模型。 */
    private static Tool tool(String name, java.util.function.Supplier<String> execute) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "fixture"; }
            public Map<String,String> properties() { return Map.of(); }
            public List<String> required() { return List.of(); }
            public String execute(ObjectNode input) { return execute.get(); }
        };
    }

    /** 本次回复故意猜测新工具；连接成功也不能改变它原先看到的快照，下一请求才可见。 */
    @Test void replyUsesOriginalSnapshotAfterConnect() throws Exception {
        AtomicBoolean connected = new AtomicBoolean(); AtomicInteger executed = new AtomicInteger();
        Tool connect = tool("connect_mcp", () -> { connected.set(true); return "connected"; });
        Tool external = tool("mcp__fixture__echo", () -> { executed.incrementAndGet(); return "echo"; });
        var catalog = new ToolCatalog(Map.of(connect.name(), connect), () -> connected.get()
                ? List.of(new ToolCatalog.Entry(external, new ToolIdentity(ToolIdentity.Kind.MCP, "fixture", "echo", true))) : List.of());
        AtomicInteger requests = new AtomicInteger();
        var engine = new AgentExecutor(request -> {
            if (requests.getAndIncrement() == 0) {
                assertEquals(1, request.tools().size());
                return new ModelResponse(Message.assistant(List.of(new ToolUseBlock("connect", "connect_mcp", Json.mapper().createObjectNode()),
                        new ToolUseBlock("guessed", external.name(), Json.mapper().createObjectNode()))), null);
            }
            assertEquals(2, request.tools().size());
            return new ModelResponse(Message.assistant(List.of(new TextBlock("done"))), null);
        }, catalog, "", 100, new Hooks(), null, () -> com.jadecode.llm.ResponseStream.NONE);
        var messages = new java.util.ArrayList<>(List.of(Message.userText("test")));
        var reply = engine.request(messages, "test", "", ignored -> { });
        var batch = engine.executeTools(reply, ExecutionContext.create(ExecutionContext.Source.USER, null), 0);
        assertTrue(((ToolResultBlock) batch.results().get(1)).isError());
        assertEquals(0, executed.get());
        messages.add(reply.message()); messages.add(Message.userBlocks(batch.results()));
        engine.request(messages, "test", "", ignored -> { });
        engine.close();
    }

    /** 规范化后同名与超长名称必须明确失败，不能覆盖另一个服务的路由。 */
    @Test void normalizedNamesCannotCollideOrOverflow() {
        var schema = Json.mapper().createObjectNode().put("type", "object");
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.validateMcpNames(List.of(
                new McpToolDescriptor("docs.one", "1", "search", "", schema, false),
                new McpToolDescriptor("docs_one", "2", "search", "", schema, false))));
        assertThrows(IllegalArgumentException.class, () -> ToolCatalog.mcpName("x".repeat(60), "search"));
    }
}
