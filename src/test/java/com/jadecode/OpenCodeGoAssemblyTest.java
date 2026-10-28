package com.jadecode;

import com.jadecode.bootstrap.JadeCodeFactory;
import com.jadecode.cli.SessionHost;
import com.jadecode.config.*;
import com.jadecode.execution.ExecutionObserver;
import com.jadecode.goal.PromptGoalEvaluator;
import com.jadecode.llm.*;
import com.jadecode.messages.*;
import com.jadecode.session.SessionRuntime;
import com.jadecode.session.history.SessionStore;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 真实生产工厂与会话宿主，仅注入假传输及临时用户根，验证所有用途的身份接线。 */
@Timeout(20)
class OpenCodeGoAssemblyTest {
    @TempDir Path temporary;

    private static AppConfig config() {
        return new AppConfig("fixture-key", ModelProvider.OPENCODE_GO.defaultBaseUrl(), "deepseek-v4.1-flash", 8000,
                ModelProvider.OPENCODE_GO);
    }
    private static ModelResponse text(String body) {
        return new ModelResponse(Message.assistant(List.of(new TextBlock(body))), new ModelResponse.Usage(3, 2), "end_turn");
    }
    private static ModelResponse call(String name, String arguments) {
        try {
            return new ModelResponse(Message.assistant(List.of(new ToolUseBlock(UUID.randomUUID().toString(), name,
                    Json.mapper().readTree(arguments)))), new ModelResponse.Usage(3, 2), "tool_use");
        } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
    }

    /** 假后端也明确提供与目标配置相同的能力，不能靠默认接口假装支持 low。 */
    private static LlmClient capable(LlmClient transport) {
        return new LlmClient() {
            @Override public ModelMetadata metadata() { return new ModelMetadata(new ModelMetadata.Limits(1000000, null, 100000), "fixture"); }
            @Override public List<ThinkingSettings> thinkingChoices() {
                return List.of(ThinkingSettings.DEFAULT, ThinkingSettings.LOW, ThinkingSettings.HIGH, ThinkingSettings.MAX);
            }
            @Override public ModelResponse complete(CompleteRequest request) throws LlmException { return transport.complete(request); }
        };
    }

    /** Provider 身份夹具显式使用离线宿主后端；Docker 的真实接线由独立集成测试验证。 */
    private JadeSettings settings(boolean memory) throws Exception {
        Path file = temporary.resolve("settings-" + memory + ".json");
        Files.writeString(file, """
                {"memory":{"enabled":%s},"thinking":{"effort":"low"},
                 "permissions":{"defaultProfile":"auto-review"},"mcp":{"servers":{}},
                 "sandbox":{"enabled":false},
                 "compaction":{"autoSummaryEnabled":false,"keepRecentTokens":0}}
                """.formatted(memory));
        return new JadeConfigFile(file).load();
    }
    private SessionRuntime runtime(Path workspace, JadePaths jade, JadeSettings settings, AppConfig config, LlmClient transport) {
        return JadeCodeFactory.create(config, workspace, capable(transport), ignored -> { }, ignored -> { },
                (hooks, cwd, output) -> { }, () -> ResponseStream.NONE, ExecutionObserver.NONE, settings,
                settings.permissions().state(null), jade);
    }

    /** 同一用户任务实际触发七类请求；恢复不重放旧工具，下一条请求仍用原 UUID 和已保存档位。 */
    @Test void allRequestPurposesCarryTheRestoredIdentity() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        JadePaths jade = new JadePaths(temporary.resolve("jade")); var settings = settings(true);
        var requests = new CopyOnWriteArrayList<CompleteRequest>();
        Set<String> channels = ConcurrentHashMap.newKeySet(); var memorySeen = new CountDownLatch(1);
        AtomicInteger phase = new AtomicInteger();
        LlmClient transport = request -> {
            requests.add(request);
            assertFalse(request.sessionId().isEmpty(), "任何辅助请求都不能绕过会话绑定");
            String system = request.system();
            if (system.equals(PromptGoalEvaluator.SYSTEM)) {
                channels.add("goal"); return text("{\"ok\":true,\"reason\":\"fixture evidence\",\"impossible\":false}");
            }
            if (system.startsWith("Review one exact proposed")) {
                channels.add("approval"); return text("{\"decision\":\"allow\",\"reason\":\"fixture write authorized\"}");
            }
            if (system.startsWith("You are a focused workflow agent.")) {
                channels.add("workflow"); return text("{\"findings\":[]}");
            }
            if (request.messages().getLast().extractText().startsWith("[Harness compaction request]")) {
                channels.add("summary"); return text("Earlier task and tool results summarized.");
            }
            if (request.tools().isEmpty()) {
                channels.add("memory"); memorySeen.countDown(); return text("{\"rollout_summary\":\"\",\"rollout_slug\":\"\"}");
            }
            if (system.contains("Complete the given task")) { channels.add("subagent"); return text("child done"); }
            channels.add("formal");
            return switch (phase.getAndIncrement()) {
                case 0 -> call("task", "{\"prompt\":\"CHILD_TASK\"}");
                // 工作区外、仍在临时根内的明确文件，确实触发辅助审批；普通工作区写入本来可直接通过。
                case 1 -> call("write_file", "{\"path\":\"../proof.txt\",\"content\":\"fixture proof\"}");
                case 2 -> call("Workflow", "{\"name\":\"review-changes\",\"args\":{\"changes\":\"fixture supplied text\"}}");
                case 3 -> call("compact", "{}");
                default -> text("done");
            };
        };
        String id;
        try (var host = new SessionHost(new WorkspacePaths(workspace, jade),
                generation -> runtime(workspace, jade, settings, config(), transport), ignored -> { })) {
            var opened = host.open(null); id = opened.restored().metadata().id();
            assertEquals(config().backendIdentity(), opened.restored().metadata().backendIdentity());
            assertEquals("done", host.startWithInitial(opened.generation(), "/goal Write ../proof.txt and exercise the fixture tools", null, false)
                    .get(10, TimeUnit.SECONDS));
            assertTrue(memorySeen.await(5, TimeUnit.SECONDS));
            assertEquals(Set.of("formal", "subagent", "approval", "workflow", "summary", "goal", "memory"), channels);
            assertEquals("fixture proof", Files.readString(temporary.resolve("proof.txt")));
            host.current().changeThinking(ThinkingSettings.HIGH);
        }
        assertTrue(requests.stream().allMatch(request -> request.sessionId().equals(id)));
        int beforeResume = requests.size();
        try (var host = new SessionHost(new WorkspacePaths(workspace, jade),
                generation -> runtime(workspace, jade, settings, config(), transport), ignored -> { })) {
            var opened = host.open(id);
            assertEquals(beforeResume, requests.size(), "恢复本身不能重放旧模型请求或工具");
            assertEquals(ThinkingSettings.HIGH, host.current().thinking());
            assertEquals("done", host.startWithInitial(opened.generation(), "CONTINUE", null, false).get(5, TimeUnit.SECONDS));
        }
        assertTrue(requests.subList(beforeResume, requests.size()).stream().allMatch(request -> request.sessionId().equals(id)));
        assertTrue(requests.stream().filter(request -> !request.tools().isEmpty()).anyMatch(request -> request.thinking() == ThinkingSettings.HIGH));
    }

    /** 共享同一传输的两个运行时并发请求，身份各自稳定；Go 历史不能切给直连或未知来源。 */
    @Test void independentRuntimesDoNotShareIdentityAndGoRecoveryRejectsDifferentBackends() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace")); var settings = settings(false);
        JadePaths jade = new JadePaths(temporary.resolve("jade"));
        var ids = new ConcurrentHashMap<String, String>(); var entered = new CountDownLatch(2);
        LlmClient transport = request -> {
            String input = request.messages().getFirst().extractText();
            String prior = ids.putIfAbsent(input, request.sessionId());
            if (prior != null) assertEquals(prior, request.sessionId());
            entered.countDown();
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new LlmException("fixture interrupted"); }
            return text("done");
        };
        try (var first = runtime(workspace, jade, settings, config(), transport);
             var second = runtime(workspace, jade, settings, config(), transport)) {
            first.start(false); second.start(false);
            var a = first.submit("FIRST", null); var b = second.submit("SECOND", null);
            a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
            first.submit("NEXT", null).get(5, TimeUnit.SECONDS);
        }
        assertNotEquals(ids.get("FIRST"), ids.get("SECOND"));
        var store = new SessionStore(new WorkspacePaths(workspace, jade), ignored -> { });
        String goId;
        try (var opened = store.create(config().model(), config().backendIdentity())) { goId = opened.restored().metadata().id(); }
        var direct = new AppConfig("fixture-direct", "http://127.0.0.1:1", config().model(), 8000);
        try (var opened = store.open(goId); var runtime = runtime(workspace, jade, settings, direct, request -> text("unexpected"))) {
            assertThrows(IllegalArgumentException.class, () -> runtime.restore(opened.journal(), opened.restored()));
        }
        try (var legacy = store.create(config().model()); var runtime = runtime(workspace, jade, settings, config(), request -> text("unexpected"))) {
            assertThrows(IllegalArgumentException.class, () -> runtime.restore(legacy.journal(), legacy.restored()));
        }
    }

    /** Go 目录缺限额时只使用明确手动值，不向直连厂家发送 Go Key，也不猜测大窗口。 */
    @Test void modelLimitsRemainUnknownUntilExplicitlyConfigured() {
        var resolver = new ModelMetadataResolver(temporary.resolve("metadata"), Clock.systemUTC(), (uri, headers, timeout) -> {
            throw new AssertionError("Go 限额不能借用直连服务目录");
        });
        var unknown = resolver.resolve(config(), JadeSettings.defaults().modelMetadata());
        assertFalse(unknown.limits().known()); assertEquals(8000, unknown.formalOutputBudget(0));
        assertTrue(unknown.source().contains("modelMetadata.overrides"));
        var known = resolver.resolve(config(), new JadeSettings.Models(true, 24, 1000,
                Map.of(config().model(), new ModelMetadata.Limits(50000, null, 12000))));
        assertEquals(50000, known.limits().contextWindowTokens()); assertEquals(12000, known.formalOutputBudget(0));
        assertEquals(12000, known.capOutput(15000)); assertTrue(known.source().contains("manual"));
    }

    /** 真实 Team 工具创建队友并派工；队友复用传输但不继承 Leader 的路由身份。 */
    @Test void teammatesUseTheirOwnStableIdentity() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace")); var settings = settings(false);
        JadePaths jade = new JadePaths(temporary.resolve("jade"));
        var leaderId = new java.util.concurrent.atomic.AtomicReference<String>();
        var memberId = new java.util.concurrent.atomic.AtomicReference<String>();
        var agentId = new java.util.concurrent.atomic.AtomicReference<String>();
        AtomicInteger parentPhase = new AtomicInteger(), memberPhase = new AtomicInteger();
        LlmClient transport = request -> {
            if (request.system().startsWith("You are JadeCode teammate.")) {
                String prior = memberId.compareAndExchange(null, request.sessionId());
                if (prior != null) assertEquals(prior, request.sessionId());
                if (memberPhase.getAndIncrement() == 0) {
                    var match = java.util.regex.Pattern.compile("assignment=(assignment_[a-z0-9-]+)")
                            .matcher(request.messages().getFirst().extractText());
                    assertTrue(match.find());
                    return call("submit_task_result", "{\"assignment_id\":\"" + match.group(1)
                            + "\",\"status\":\"completed\",\"summary\":\"fixture complete\",\"body\":\"fixture report\"}");
                }
                return text("available");
            }
            String prior = leaderId.compareAndExchange(null, request.sessionId());
            if (prior != null) assertEquals(prior, request.sessionId());
            int phase = parentPhase.getAndIncrement();
            if (phase == 0) return call("spawn_teammate", "{\"role\":\"Explorer\\nReturn a fixture report.\"}");
            try {
                String result = request.messages().getLast().blocksOfType(ToolResultBlock.class).isEmpty() ? "{}"
                        : request.messages().getLast().blocksOfType(ToolResultBlock.class).getFirst().content();
                if (phase <= 2 && result.startsWith("Error")) throw new IllegalStateException("Fixture Team tool failed: " + result);
                if (phase == 1) {
                    agentId.set(Json.mapper().readTree(result).path("agent_id").asText());
                    return call("create_task", "{\"subject\":\"fixture task\",\"description\":\"Return a fixture report\"}");
                }
                if (phase == 2) return call("assign_task", "{\"agent_id\":\"" + agentId.get() + "\",\"task_id\":\""
                        + Json.mapper().readTree(result).path("id").asText() + "\",\"summary\":\"fixture\",\"body\":\"Return a report\"}");
            } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
            return text("done");
        };
        try (var runtime = runtime(workspace, jade, settings, config(), transport)) {
            runtime.start(false); assertEquals("done", runtime.submit("Delegate the fixture task", null).get(5, TimeUnit.SECONDS));
            assertNotNull(memberId.get()); assertNotEquals(leaderId.get(), memberId.get());
        }
    }
}
