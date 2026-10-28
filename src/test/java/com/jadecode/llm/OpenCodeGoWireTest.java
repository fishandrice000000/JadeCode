package com.jadecode.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.config.AppConfig;
import com.jadecode.config.ModelProvider;
import com.jadecode.hooks.Hooks;
import com.jadecode.messages.*;
import com.jadecode.session.SessionRuntime;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 SDK 和回环 HTTP 夹具检查 Go 的 Messages 线格式；所有 Key 都是假值，不连接公网。 */
class OpenCodeGoWireTest {
    private static final String MODEL = "deepseek-v4.1-flash";
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<Captured> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService executor;

    private record Reply(int status, String body, Map<String, String> headers) { }
    private record Captured(String path, Map<String, List<String>> headers, JsonNode body) {
        String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
        }
    }

    /** 每个测试独占随机端口；脚本耗尽直接报错，避免 SDK 等待一个永不结束的假连接。 */
    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(new Captured(exchange.getRequestURI().getPath(), Map.copyOf(exchange.getRequestHeaders()),
                        Json.mapper().readTree(exchange.getRequestBody().readAllBytes())));
                Reply reply = replies.poll();
                if (reply == null) reply = new Reply(500, "{\"error\":{\"message\":\"fixture exhausted\"}}",
                        Map.of("x-should-retry", "false"));
                reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }

    /** 只清理本测试创建的服务与线程，不关闭或修改任何生产客户端/全局配置。 */
    @AfterEach void stopServer() { server.stop(0); executor.shutdownNow(); }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/inference/go/anthropic"; }
    private AnthropicSdkLlmClient client() {
        return new AnthropicSdkLlmClient(new AppConfig("fixture-go-key", baseUrl(), MODEL, 500, ModelProvider.OPENCODE_GO),
                new ModelMetadata(new ModelMetadata.Limits(10000, null, 100), "fixture"));
    }
    private CompleteRequest request() {
        return new CompleteRequest("fixed system", List.of(Message.userText("check")), 500, List.of(),
                ThinkingSettings.LOW, "stable-conversation");
    }
    private void json(String body) { replies.add(new Reply(200, body, Map.of("Content-Type", "application/json"))); }
    private void sse(String body) { replies.add(new Reply(200, body, Map.of("Content-Type", "text/event-stream"))); }

    /** 标准 Messages 完整回复；缓存 token 与普通输入分开，不能在适配后被重复累计。 */
    private static String message(String content, String reason) {
        return """
                {"id":"msg-fixture","type":"message","role":"assistant","model":"deepseek-v4.1-flash",
                 "content":%s,"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":11,"output_tokens":5,"cache_read_input_tokens":7,"cache_creation_input_tokens":3}}
                """.formatted(content, reason);
    }
    private static String event(String type, Map<String, ?> body) throws Exception {
        var data = new LinkedHashMap<String, Object>(body); data.put("type", type);
        return "event: " + type + "\ndata: " + Json.mapper().writeValueAsString(data) + "\n\n";
    }

    /** 思考与两个工具块走完整生命周期；参数故意分片，验证 SDK 拼装后仍保留原 ID 与签名。 */
    private static String toolStream(String arguments, boolean stop) throws Exception {
        JsonNode start = Json.mapper().readTree(message("[]", "tool_use"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) start).putNull("stop_reason");
        StringBuilder body = new StringBuilder(event("message_start", Map.of("message", start)));
        body.append(event("content_block_start", Map.of("index", 0,
                "content_block", Map.of("type", "thinking", "thinking", "", "signature", ""))));
        body.append(event("content_block_delta", Map.of("index", 0,
                "delta", Map.of("type", "thinking_delta", "thinking", "check inputs"))));
        body.append(event("content_block_delta", Map.of("index", 0,
                "delta", Map.of("type", "signature_delta", "signature", "fixture-signature"))));
        body.append(event("content_block_stop", Map.of("index", 0)));
        for (int index = 1; index <= 2; index++) {
            body.append(event("content_block_start", Map.of("index", index,
                    "content_block", Map.of("type", "tool_use", "id", "call-" + index, "name", "inspect", "input", Map.of()))));
            int middle = arguments.length() / 2;
            for (String fragment : List.of(arguments.substring(0, middle), arguments.substring(middle)))
                body.append(event("content_block_delta", Map.of("index", index,
                        "delta", Map.of("type", "input_json_delta", "partial_json", fragment))));
            body.append(event("content_block_stop", Map.of("index", index)));
        }
        if (stop) {
            body.append(event("message_delta", Map.of("delta", Map.of("stop_reason", "tool_use"), "usage", Map.of("output_tokens", 5))));
            body.append(event("message_stop", Map.of()));
        }
        return body.toString();
    }

    /** 两轮真实 HTTP：首轮流式工具，第二轮回放思考/签名及成功与失败的工具结果。 */
    @Test void sendsGoIdentityAndEffortAndReplaysCompleteToolHistory() throws Exception {
        sse(toolStream("{\"path\":\"中文\"}", true));
        json(message("[{\"type\":\"text\",\"text\":\"done\"}]", "end_turn"));
        var client = client(); var preview = new ArrayList<String>();
        var tool = new ToolDefinition("inspect", "fixture", (com.fasterxml.jackson.databind.node.ObjectNode) Json.mapper().readTree(
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"));
        var firstRequest = new CompleteRequest("fixed system", request().messages(), 500, List.of(tool), ThinkingSettings.LOW, "stable-conversation");
        ModelResponse first = client.complete(firstRequest, new ResponseStream() {
            @Override public void thinking(String value) { preview.add(value); }
            @Override public void toolBlockClosed(int index) { preview.add("closed-" + index); }
        });
        assertEquals("tool_use", first.stopReason());
        assertEquals(List.of("check inputs", "closed-1", "closed-2"), preview);
        assertEquals(21, first.usage().totalInputTokens());
        assertEquals("中文", first.message().blocksOfType(ToolUseBlock.class).getFirst().input().path("path").asText());
        var history = new ArrayList<>(firstRequest.messages()); history.add(first.message());
        history.add(Message.userBlocks(List.of(new ToolResultBlock("call-1", "found", false), new ToolResultBlock("call-2", "missing", true))));
        assertEquals("done", client.complete(new CompleteRequest("fixed system", history, 500, List.of(tool), ThinkingSettings.MAX,
                firstRequest.sessionId())).message().extractText());
        for (Captured sent : requests) {
            assertEquals("/inference/go/anthropic/v1/messages", sent.path());
            assertEquals("Bearer fixture-go-key", sent.header("Authorization")); assertEquals("", sent.header("x-api-key"));
            assertEquals("jadecode/1.0.0", sent.header("User-Agent")); assertEquals("jadecode", sent.header("x-opencode-client"));
            assertEquals("stable-conversation", sent.header("x-opencode-session"));
            assertEquals(MODEL, sent.body().path("model").asText()); assertEquals(100, sent.body().path("max_tokens").asInt());
            assertEquals("enabled", sent.body().path("thinking").path("type").asText());
            assertFalse(sent.body().has("sessionId")); assertFalse(sent.body().has("reasoning_effort"));
        }
        assertTrue(requests.getFirst().body().path("stream").asBoolean());
        assertEquals("low", requests.getFirst().body().path("output_config").path("effort").asText());
        JsonNode replay = requests.getLast().body().path("messages");
        assertEquals("fixture-signature", replay.get(1).path("content").get(0).path("signature").asText());
        assertEquals("call-2", replay.get(2).path("content").get(1).path("tool_use_id").asText());
        assertTrue(replay.get(2).path("content").get(1).path("is_error").asBoolean());
    }

    /** default 不假装是 high；无签名思考仍可回放，不能生成一个伪签名。 */
    @Test void defaultEffortOmitsParametersAndMissingSignatureStaysMissing() throws Exception {
        json(message("[{\"type\":\"thinking\",\"thinking\":\"check\"},{\"type\":\"text\",\"text\":\"done\"}]", "end_turn"));
        var client = client();
        var response = client.complete(request().withThinking(ThinkingSettings.DEFAULT));
        assertNull(response.message().blocksOfType(ThinkingBlock.class).getFirst().signature());
        assertFalse(requests.getFirst().body().has("thinking")); assertFalse(requests.getFirst().body().has("output_config"));
        assertEquals("default", client.thinkingLabel(ThinkingSettings.DEFAULT));
        assertEquals(List.of(ThinkingSettings.DEFAULT, ThinkingSettings.LOW, ThinkingSettings.HIGH, ThinkingSettings.MAX), client.thinkingChoices());
        assertThrows(IllegalArgumentException.class, () -> client.complete(request().withThinking(ThinkingSettings.OFF)));
        assertThrows(IllegalArgumentException.class, () -> client.complete(request().withSessionId("")));
        assertEquals(1, requests.size(), "非法设置必须在发请求前拒绝");
    }

    /** 已显示的工具片段不能在非法 JSON 或缺少最终结束事件时成为可执行回复。 */
    @Test void rejectsInvalidToolArgumentsAndStreamsWithoutMessageStop() throws Exception {
        sse(toolStream("{\"path\":", true)); sse(toolStream("{}", false));
        assertThrows(LlmException.class, () -> client().complete(request(), ResponseStream.NONE));
        assertThrows(LlmException.class, () -> client().complete(request(), ResponseStream.NONE));
        assertEquals(2, requests.size(), "开始读取后的断流不应隐藏重放整份请求");
    }

    /** max_tokens 即使带有完整工具 JSON，也不能被 Session 采用或触发工具副作用。 */
    @Test void outputTruncationDoesNotExecuteTools() throws Exception {
        json(message("[{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"inspect\",\"input\":{}}]", "max_tokens"));
        AtomicInteger executed = new AtomicInteger();
        Tool tool = new Tool() {
            public String name() { return "inspect"; }
            public String description() { return "fixture"; }
            public Map<String, String> properties() { return Map.of(); }
            public List<String> required() { return List.of(); }
            public String execute(com.fasterxml.jackson.databind.node.ObjectNode input) { executed.incrementAndGet(); return "bad"; }
        };
        var engine = new AgentExecutor(new SessionLlmClient(client()), Map.of("inspect", tool), "fixture", 100, new Hooks());
        try (var runtime = new SessionRuntime(engine, ignored -> { }, ignored -> { })) {
            assertThrows(LlmException.class, () -> runtime.run(new ArrayList<>(List.of(Message.userText("inspect")))));
        }
        assertEquals(0, executed.get()); assertEquals(1, requests.size());
    }

    /** 认证/额度错误不回显服务端原文，也不尝试其它模型或按量端点。 */
    @Test void reportsAuthenticationAndAllowanceFailuresWithoutEchoingResponseSecrets() {
        for (int status : List.of(401, 402, 403, 429)) {
            replies.add(new Reply(status, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"DO_NOT_ECHO_fixture-go-key\"}}",
                    Map.of("Content-Type", "application/json", "x-should-retry", "false")));
            var failure = assertThrows(LlmException.class, () -> client().complete(request()));
            assertEquals(status, failure.statusCode()); assertFalse(failure.getMessage().contains("fixture-go-key"));
            assertNull(failure.getCause());
        }
        assertEquals(4, requests.size());
    }

    /** 沿用 SDK 的限流重试；重试身份仍是同一会话，不用每次请求的随机记账 ID 替换。 */
    @Test void sdkRetryKeepsTheConversationHeader() throws Exception {
        replies.add(new Reply(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"limited\"}}",
                Map.of("Content-Type", "application/json", "retry-after-ms", "1")));
        json(message("[{\"type\":\"text\",\"text\":\"done\"}]", "end_turn"));
        assertEquals("done", client().complete(request()).message().extractText());
        assertEquals(2, requests.size());
        assertTrue(requests.stream().allMatch(sent -> sent.header("x-opencode-session").equals("stable-conversation")));
    }

    /** 旧配置的认证线格式不因新增 Go 路线改变。 */
    @Test void originalAnthropicCompatibleRequestsKeepTheirAuthentication() throws Exception {
        json(message("[{\"type\":\"text\",\"text\":\"done\"}]", "end_turn"));
        new AnthropicSdkLlmClient(new AppConfig("fixture-direct-key", baseUrl(), "fixture", 100))
                .complete(request().withThinking(ThinkingSettings.DEFAULT));
        assertEquals("fixture-direct-key", requests.getFirst().header("x-api-key"));
        assertEquals("", requests.getFirst().header("Authorization"));
        assertEquals("", requests.getFirst().header("x-opencode-session"));
    }
}
