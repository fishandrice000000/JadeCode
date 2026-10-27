package com.jadecode.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.bootstrap.JadeCodeFactory;
import com.jadecode.config.*;
import com.jadecode.execution.*;
import com.jadecode.llm.*;
import com.jadecode.messages.*;
import com.jadecode.safety.*;
import com.jadecode.safety.rules.PermissionRule;
import com.jadecode.session.SessionRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 假模型走生产 Factory、PermissionGate、Executor 和 Session，不读取用户 HOME/密钥或调用外部模型。 */
@Timeout(15)
class WorkflowIntegrationTest {
    @TempDir Path workspace;

    /** 测试常量解析错误属于夹具错误，不把它伪装成模型请求失败。 */
    private static ObjectNode json(String value) {
        try { return (ObjectNode) WorkflowSchema.parse(value); }
        catch (IOException error) { throw new IllegalArgumentException(error); }
    }
    /** 正常假模型回复，所有类别都报告 5 token，便于检查分类不会重复记账。 */
    private static ModelResponse reply(ContentBlock... blocks) {
        return new ModelResponse(Message.assistant(List.of(blocks)), new ModelResponse.Usage(2, 3), "end_turn");
    }
    /** 给主模型返回一次 Workflow 调用；stepId 是协议 ID，不是 workflow run_id。 */
    private static ModelResponse call(String stepId, ObjectNode args) { return reply(new ToolUseBlock(stepId, "Workflow", args)); }

    /** 真工厂可注入的设置关闭 Memory/MCP 外部入口，不改变 Workflow 本身的生产接线。 */
    private SessionRuntime session(LlmClient client, PermissionState permissions, ExecutionObserver observer) {
        return session(client, permissions, observer, 1234);
    }

    /** 注入正式输出额度，验证 Workflow 沿用实际解析值，不被另一个固定 2000 上限截断。 */
    private SessionRuntime session(LlmClient client, PermissionState permissions, ExecutionObserver observer, int maxTokens) {
        var defaults = JadeSettings.defaults();
        var settings = new JadeSettings(defaults.tui(), defaults.compaction(), defaults.limits(),
                new JadeSettings.Memory(false, 100, 100, 1000), defaults.modelMetadata(), defaults.thinking(),
                defaults.permissions(), new JadeSettings.Mcp(Map.of()));
        return JadeCodeFactory.create(new AppConfig("offline", "http://127.0.0.1:9", "fixture", maxTokens), workspace, client,
                ignored -> { }, ignored -> { }, (hooks, cwd, out) -> { }, () -> ResponseStream.NONE,
                observer, settings, permissions, com.jadecode.support.TestPaths.jade(workspace));
    }

    /** 主历史仅包含两份工具结果；各类请求身份正确，缓存恢复不再请求步骤模型。 */
    @Test void factoryRunsAndResumesWorkflowWithIsolatedHistoryAndUsage() throws Exception {
        AtomicInteger formal = new AtomicInteger(), steps = new AtomicInteger();
        var progress = new CopyOnWriteArrayList<ExecutionNotice>();
        LlmClient client = request -> {
            if (request.tools().isEmpty()) {
                steps.incrementAndGet(); assertTrue(request.system().startsWith("You are a focused workflow agent."));
                assertEquals(1234, request.maxTokens()); assertEquals(1, request.messages().size());
                assertFalse(request.messages().toString().contains("ROOT_ONLY_CONTEXT"));
                return reply(new TextBlock("{\"findings\":[]}"));
            }
            assertTrue(request.tools().stream().anyMatch(tool -> tool.name().equals("Workflow")));
            int turn = formal.getAndIncrement();
            if (turn == 0) return call("initial", json("{\"name\":\"review-changes\",\"args\":{\"changes\":\"supplied snippet\"}}"));
            ToolResultBlock result = request.messages().getLast().blocksOfType(ToolResultBlock.class).getFirst();
            assertFalse(result.isError()); ObjectNode payload = json(result.content());
            assertEquals("completed", payload.path("status").asText());
            if (turn == 1) {
                assertEquals(3, request.messages().size(), "中间步骤对话不能进入主历史");
                return call("resume", json("{\"name\":\"review-changes\"}").put("resume_from_run_id", payload.path("run_id").asText()));
            }
            assertEquals(0, payload.path("usage").path("requests").asInt());
            assertEquals(4, payload.path("usage").path("cached_steps").asInt());
            return reply(new TextBlock("done"));
        };
        try (var runtime = session(client, PermissionState.defaults(), progress::add)) {
            var messages = new ArrayList<>(List.of(Message.userText("ROOT_ONLY_CONTEXT")));
            runtime.run(messages);
            assertEquals(3, formal.get()); assertEquals(4, steps.get());
            var usage = runtime.display().usage();
            assertEquals(4, usage.byPurpose().get("WORKFLOW").reportedRequests());
            assertEquals(3, usage.byPurpose().get("FORMAL").reportedRequests());
            assertEquals(7, usage.total().reportedRequests());
            assertTrue(progress.stream().anyMatch(n -> n.kind() == ExecutionNotice.Kind.WORKFLOW && n.detail().startsWith("phase:")));
            assertEquals(2, progress.stream().filter(n -> n.kind() == ExecutionNotice.Kind.WORKFLOW && n.detail().startsWith("task_notification:")).count());
            assertEquals("done", messages.getLast().extractText());
        }
    }

    /** 真实工具目录能发现新预设；无发现的审查复用四个缓存步骤，主历史和用量仍隔离。 */
    @Test void factoryDiscoversRunsAndResumesCodeReview() throws Exception {
        AtomicInteger formal = new AtomicInteger(), steps = new AtomicInteger();
        try (var runtime = session(request -> {
            if (request.tools().isEmpty()) {
                steps.incrementAndGet();
                String prompt = request.messages().getFirst().extractText();
                assertTrue(prompt.contains("\nReview supplied code for ")); assertTrue(prompt.contains("Service.java"));
                assertFalse(prompt.contains("ROOT_ONLY_CONTEXT"));
                return reply(new TextBlock("{\"findings\":[],\"questions\":[]}"));
            }
            var tool = request.tools().stream().filter(t -> t.name().equals("Workflow")).findFirst().orElseThrow();
            assertTrue(tool.description().contains("code-review")); assertTrue(tool.description().contains("args.files"));
            int turn = formal.getAndIncrement();
            if (turn == 0) return call("review", json("""
                    {"name":"code-review","args":{"files":[{"path":"Service.java","content":"class Service {}"}],"context":"Review this module."}}
                    """));
            ToolResultBlock block = request.messages().getLast().blocksOfType(ToolResultBlock.class).getFirst();
            assertFalse(block.isError()); ObjectNode out = json(block.content()); assertEquals("completed", out.path("status").asText());
            assertTrue(out.path("result").path("analysis_only").asBoolean()); assertTrue(out.path("result").path("confirmed").isEmpty());
            if (turn == 1) return call("resume-review", json("{\"name\":\"code-review\"}").put("resume_from_run_id", out.path("run_id").asText()));
            assertEquals(0, out.path("usage").path("requests").asInt()); assertEquals(4, out.path("usage").path("cached_steps").asInt());
            return reply(new TextBlock("reviewed"));
        }, PermissionState.defaults(), ExecutionObserver.NONE)) {
            runtime.run(new ArrayList<>(List.of(Message.userText("ROOT_ONLY_CONTEXT"))));
            assertEquals(4, steps.get()); assertEquals(3, formal.get());
            assertEquals(4, runtime.display().usage().byPurpose().get("WORKFLOW").reportedRequests());
        }
    }

    /** 真实持久化会话：一个分支截断并取消三个兄弟后，费用事实可保存，主模型和下一条输入仍可继续。 */
    @Test void truncatedParallelWorkflowKeepsPersistentSessionUsable() throws Exception {
        var allStarted = new CountDownLatch(4); var cancelled = new CountDownLatch(3);
        AtomicInteger formal = new AtomicInteger();
        var paths = com.jadecode.support.TestPaths.workspace(workspace);
        try (var host = new com.jadecode.cli.SessionHost(paths, generation -> session(request -> {
            assertEquals(8192, request.maxTokens(), "Workflow must use the configured effective formal budget");
            if (request.tools().isEmpty()) {
                allStarted.countDown();
                try {
                    if (!allStarted.await(3, TimeUnit.SECONDS)) throw new LlmException("fixture branches did not start");
                    if (request.messages().getFirst().extractText().contains("for correctness."))
                        return new ModelResponse(reply(new TextBlock("{\"findings\":[],\"questions\":[]}")).message(), new ModelResponse.Usage(20, 8192), "max_tokens");
                    new CountDownLatch(1).await(); throw new AssertionError("Expected branch cancellation");
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt(); cancelled.countDown();
                    throw new LlmException("fixture branch cancelled", stopped);
                }
            }
            int turn = formal.getAndIncrement();
            if (turn == 0) return call("limit", json("{\"name\":\"code-review\",\"args\":{\"files\":[{\"path\":\"A.java\",\"content\":\"class A {}\"}]}}"));
            if (turn == 1) {
                try { assertTrue(cancelled.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new LlmException("fixture wait interrupted", stopped); }
                var result = json(request.messages().getLast().blocksOfType(ToolResultBlock.class).getFirst().content());
                assertEquals("failed", result.path("status").asText());
                assertTrue(result.path("result").path("error").asText().contains("max_tokens (limit=8192)"));
            }
            return reply(new TextBlock(turn == 1 ? "workflow failure handled" : "still alive"));
        }, PermissionState.defaults(), ExecutionObserver.NONE, 8192), ignored -> { })) {
            var opened = host.open(null);
            assertEquals("workflow failure handled", host.startWithInitial(opened.generation(), "review", null, false).get(5, TimeUnit.SECONDS));
            // 主 Future 先完成也不表示兄弟分支都记完账；等三个取消请求的缺报告事实进入共享账本。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (host.current().display().usage().total().missingRequests() < 3 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(3, host.current().display().usage().total().missingRequests());
            assertEquals("still alive", host.current().submit("hello", null).get(5, TimeUnit.SECONDS));
            var flowUsage = host.current().display().usage().byPurpose().get("WORKFLOW");
            assertEquals(1, flowUsage.reportedRequests()); assertEquals(3, flowUsage.missingRequests());
        }
        var store = new com.jadecode.session.history.SessionStore(paths, ignored -> { });
        try (var restored = store.open(store.list().getFirst().id())) {
            assertTrue(restored.restored().warnings().isEmpty());
            assertEquals(4, restored.restored().entries().stream().filter(e -> e.usage() != null && e.usage().purpose() == UsageRecord.Purpose.WORKFLOW).count());
        }
    }

    /** 已登记内部工具仍受显式 deny 管理；拒绝时既不调用步骤模型，也不建立运行目录。 */
    @Test void explicitDenyPreventsWorkflowSideEffects() throws Exception {
        var rule = new PermissionRule("deny-workflow", PermissionRule.Effect.DENY, PermissionRule.Kind.TOOL,
                "Workflow", "", "", "", false, List.of());
        var permissions = new PermissionState(PermissionProfile.WORKSPACE, List.of(rule), Set.of(), null);
        AtomicInteger formal = new AtomicInteger();
        try (var runtime = session(request -> {
            assertFalse(request.tools().isEmpty(), "拒绝的工作流不能发内部请求");
            if (formal.getAndIncrement() == 0) return call("denied", json("{\"name\":\"review-changes\",\"args\":{\"changes\":\"code\"}}"));
            var result = request.messages().getLast().blocksOfType(ToolResultBlock.class).getFirst();
            assertTrue(result.isError()); assertTrue(result.content().contains("deny-workflow"));
            return reply(new TextBlock("denied"));
        }, permissions, ExecutionObserver.NONE)) {
            runtime.run(new ArrayList<>(List.of(Message.userText("review"))));
            assertFalse(Files.exists(com.jadecode.support.TestPaths.workspace(workspace).workflows()));
        }
    }

    /** 参数错误与运行失败都形成对应 tool_result.is_error，主模型可继续处理，不能吞成成功。 */
    @Test void unknownInputAndModelFailureRemainToolErrors() throws Exception {
        AtomicInteger formal = new AtomicInteger(), steps = new AtomicInteger();
        try (var runtime = session(request -> {
            if (request.tools().isEmpty()) { steps.incrementAndGet(); throw new LlmException("offline model failed"); }
            if (formal.getAndIncrement() == 0) return reply(
                    new ToolUseBlock("unknown", "Workflow", json("{\"name\":\"missing\"}")),
                    new ToolUseBlock("code", "Workflow", json("{\"name\":\"review-changes\",\"script\":\"not executable\"}")),
                    new ToolUseBlock("failure", "Workflow", json("{\"name\":\"review-changes\",\"args\":{\"changes\":\"code\"}}")));
            var results = request.messages().getLast().blocksOfType(ToolResultBlock.class);
            assertEquals(List.of("unknown", "code", "failure"), results.stream().map(ToolResultBlock::toolUseId).toList());
            assertTrue(results.stream().allMatch(ToolResultBlock::isError));
            assertEquals("failed", json(results.getLast().content()).path("status").asText());
            return reply(new TextBlock("handled"));
        }, PermissionState.defaults(), ExecutionObserver.NONE)) {
            runtime.run(new ArrayList<>(List.of(Message.userText("review"))));
            assertEquals(2, formal.get()); assertTrue(steps.get() >= 1 && steps.get() <= 4);
        }
    }

    /** 关闭真正 Session 会同时取消它持有的 Workflow，结果不能再写入成功步骤或唤起主模型。 */
    @Test void sessionCloseStopsWorkflowAndCompletesPendingFuture() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger formal = new AtomicInteger();
        var runtime = session(request -> {
            if (!request.tools().isEmpty()) {
                formal.incrementAndGet(); return call("work", json("{\"name\":\"review-changes\",\"args\":{\"changes\":\"code\"}}"));
            }
            entered.countDown();
            try { release.await(4, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new LlmException("cancelled", error); }
            return reply(new TextBlock("{\"findings\":[]}"));
        }, PermissionState.defaults(), ExecutionObserver.NONE);
        try {
            runtime.start(false); var answer = runtime.submit("review", null);
            assertTrue(entered.await(3, TimeUnit.SECONDS)); runtime.close(); release.countDown();
            assertThrows(ExecutionException.class, () -> answer.get(3, TimeUnit.SECONDS)); assertEquals(1, formal.get());
            try (var files = Files.walk(com.jadecode.support.TestPaths.workspace(workspace).workflows())) {
                for (Path path : files.filter(p -> p.getFileName().toString().equals("journal.jsonl")).toList()) assertEquals("", Files.readString(path));
            }
        } finally { release.countDown(); runtime.close(); }
    }
}
