package com.jadecode.mcp;

import com.jadecode.util.Json;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** 测试专用 SDK 服务进程；用于验证真实 stdio，不是交付给用户的 Fetch 替代品。 */
public final class McpFixtureServer {
    /** 写出自身 PID 供父测试验证清理；stall 模式故意不答初始化，其余模式由官方 SDK 处理协议。 */
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
        if (args.length > 1 && args[1].equals("stall")) { new CountDownLatch(1).await(); return; }
        var mapper = new JacksonMcpJsonMapper(Json.mapper().copy());
        var schema = Map.<String,Object>of("type", "object", "properties", Map.of("text", Map.of("type", "string")),
                "required", List.of("text"), "additionalProperties", false);
        McpServer.sync(new StdioServerTransportProvider(mapper)).jsonMapper(mapper).serverInfo("fixture", "1")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(McpSchema.Tool.builder("echo").description("echo input").inputSchema(schema).build(), (exchange, call) ->
                        McpSchema.CallToolResult.builder().addTextContent(call.arguments().get("text").toString())
                                .isError("fail".equals(call.arguments().get("text"))).build())
                .toolCall(McpSchema.Tool.builder("structured").inputSchema(Map.of("type", "object")).build(), (exchange, call) ->
                        McpSchema.CallToolResult.builder().addTextContent("text").structuredContent(Map.of("value", 1)).build())
                .toolCall(McpSchema.Tool.builder("environment").inputSchema(Map.of("type", "object")).build(), (exchange, call) ->
                        McpSchema.CallToolResult.builder().addTextContent(String.join(",", System.getenv().keySet())).build())
                .build();
        new CountDownLatch(1).await();
    }
}
