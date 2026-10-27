package com.jadecode.goal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.bootstrap.JadeCodeFactory;
import com.jadecode.cli.SessionHost;
import com.jadecode.cli.backend.BackendHistoryProjector;
import com.jadecode.config.*;
import com.jadecode.execution.*;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.*;
import com.jadecode.messages.*;
import com.jadecode.process.BackgroundManager;
import com.jadecode.safety.PermissionState;
import com.jadecode.session.SessionRuntime;
import com.jadecode.session.history.*;
import com.jadecode.tools.BashTool;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 真 Session/Factory/Journal 的目标闭环；所有模型均为离线替身，不读取用户配置。 */
@Timeout(20)
class GoalIntegrationTest {
    @TempDir Path workspace;
    /** 正常响应都报告五个 token，分类账本应分别计一次而非重复记账。 */
    private static ModelResponse reply(ContentBlock... content) { return new ModelResponse(Message.assistant(List.of(content)), new ModelResponse.Usage(2, 3), "end_turn"); }
    /** 正式候选回答，与判断器 JSON 分开构造，防止测试误把两类请求混同。 */
    private static ModelResponse text(String value) { return reply(new TextBlock(value)); }
    /** 夹具提供严格判断结构；字段含义由测试选择，不在宿主中硬编码成功。 */
    private static ModelResponse judgment(boolean ok, boolean impossible) {
        return text("{\"ok\":" + ok + ",\"reason\":\"" + (ok ? "evidence present" : "read proof file first") + "\",\"impossible\":" + impossible + "}");
    }
    /** 真实工厂关闭 Memory/MCP 外部入口，保留原权限与生产 Goal 用量包装。 */
    private SessionRuntime factory(LlmClient client, ExecutionObserver observer) {
        var defaults = JadeSettings.defaults();
        var settings = new JadeSettings(defaults.tui(), defaults.compaction(), defaults.limits(),
                new JadeSettings.Memory(false, 100, 100, 1000), defaults.modelMetadata(), defaults.thinking(),
                defaults.permissions(), new JadeSettings.Mcp(Map.of()));
        return JadeCodeFactory.create(new AppConfig("offline", "http://127.0.0.1:9", "fixture", 1234), workspace, client,
                ignored -> { }, ignored -> { }, (hooks, cwd, out) -> { }, () -> ResponseStream.NONE, observer, settings, PermissionState.defaults(), com.jadecode.support.TestPaths.jade(workspace));
    }
    /** 小额度或门闩测试用真实 Session，判断端口可注入；不需要复制生产客户端来制造竞态。 */
    private static SessionRuntime runtime(LlmClient client, GoalEvaluator evaluator, GoalController.Limits limits, Hooks hooks) {
        var runtime = new SessionRuntime(new AgentExecutor(client, Map.of(), "test", 1000, hooks), ignored -> { }, ignored -> { });
        runtime.goals(evaluator, limits); return runtime;
    }

    /** 未通过后继续同一轮，实际读取文件后才通过；判断 JSON 不进入主历史，用量分类不混合。 */
    @Test void factoryContinuesUntilEvidenceAndTracksSeparateUsage() throws Exception {
        Files.writeString(workspace.resolve("proof.txt"), "GOAL_PROOF");
        AtomicInteger formal = new AtomicInteger(), judges = new AtomicInteger();
        var notices = new CopyOnWriteArrayList<ExecutionNotice>();
        LlmClient client = request -> {
            if (request.system().equals(PromptGoalEvaluator.SYSTEM)) {
                int count = judges.incrementAndGet(); assertTrue(request.tools().isEmpty()); assertEquals(512, request.maxTokens());
                if (count == 2) assertTrue(request.messages().getFirst().extractText().contains("GOAL_PROOF"));
                return judgment(count == 2, false);
            }
            int count = formal.incrementAndGet(); assertTrue(request.system().contains("explicit user goal is active"));
            assertFalse(request.messages().toString().contains("\"ok\":"));
            if (count == 2) {
                assertTrue(request.messages().getLast().extractText().contains("read proof file first"));
                return reply(new ToolUseBlock("read", "read_file", Json.mapper().createObjectNode().put("path", "proof.txt")));
            }
            if (count == 3) assertFalse(request.messages().getLast().blocksOfType(ToolResultBlock.class).getFirst().isError());
            return text(count == 1 ? "unsupported claim" : "verified result");
        };
        try (var host = new SessionHost(com.jadecode.support.TestPaths.workspace(workspace), generation -> factory(client, notices::add), ignored -> { })) {
            var opened = host.open(null);
            assertEquals("verified result", host.startWithInitial(opened.generation(), "/goal read proof.txt and report evidence", null, false).get(5, TimeUnit.SECONDS));
            assertEquals(GoalState.Status.ACHIEVED, host.current().goalState().status());
            assertEquals(3, formal.get()); assertEquals(2, judges.get());
            assertEquals(2, host.current().display().usage().byPurpose().get("GOAL").reportedRequests());
            assertEquals(3, host.current().display().usage().byPurpose().get("FORMAL").reportedRequests());
            assertTrue(host.current().submitUser("/goal", null).get().contains("ACHIEVED")); assertEquals(2, judges.get());
        }
        assertTrue(notices.stream().anyMatch(n -> n.kind() == ExecutionNotice.Kind.GOAL && n.state().equals("ACHIEVED")));
        var store = new SessionStore(com.jadecode.support.TestPaths.workspace(workspace), ignored -> { });
        try (var opened = store.open(store.list().getFirst().id())) {
            var restored = opened.restored();
            assertEquals(3, restored.entries().stream().filter(e -> e.kind() == JournalEntry.Kind.GOAL).count());
            assertEquals(1, restored.entries().stream().filter(e -> e.reason().equals("goal_continue")).count());
            assertTrue(BackendHistoryProjector.project(restored).stream().noneMatch(e -> e.kind().equals("USER") && e.body().contains("Goal continuation")));
        }
    }

    /** 坏判断、无法完成和连续阻止均明确失败；上限/判断错误保留目标，而非把候选回答当成功。 */
    @Test void errorsImpossibleAndBlockLimitHaveDistinctStates() throws Exception {
        for (String mode : List.of("error", "impossible", "limit")) {
            AtomicInteger formal = new AtomicInteger(), judges = new AtomicInteger();
            GoalEvaluator evaluator = (condition, history) -> {
                judges.incrementAndGet();
                if (mode.equals("error")) throw new java.io.IOException("bad judgment");
                return new GoalEvaluator.Evaluation(false, "missing evidence", mode.equals("impossible"));
            };
            try (var runtime = runtime(request -> { formal.incrementAndGet(); return text("candidate"); }, evaluator,
                    new GoalController.Limits(10, 1), new Hooks())) {
                runtime.start(false);
                assertThrows(ExecutionException.class, () -> runtime.submit("/goal must verify", null).get(5, TimeUnit.SECONDS));
                assertEquals(mode.equals("impossible") ? GoalState.Status.IMPOSSIBLE : GoalState.Status.ACTIVE, runtime.goalState().status());
                assertEquals(mode.equals("limit") ? 2 : 1, formal.get()); assertEquals(formal.get(), judges.get());
                assertTrue(runtime.submit("/goal clear", null).get().contains(mode.equals("impossible") ? "IMPOSSIBLE" : "CLEARED"));
            }
        }
    }

    /** 原 Stop hook 持续阻止时尚未到 Goal 判断，也必须受正式请求上限约束。 */
    @Test void workerLimitIncludesExistingStopContinuations() throws Exception {
        AtomicInteger formal = new AtomicInteger(), judges = new AtomicInteger();
        Hooks hooks = new Hooks(); hooks.register(Hooks.STOP, params -> "keep working");
        try (var runtime = runtime(request -> { formal.incrementAndGet(); return text("candidate"); }, (condition, history) -> {
            judges.incrementAndGet(); return new GoalEvaluator.Evaluation(true, "unexpected", false);
        }, new GoalController.Limits(2, 8), hooks)) {
            runtime.start(false);
            var failure = assertThrows(ExecutionException.class, () -> runtime.submit("/goal verify", null).get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("worker turn limit"));
            assertEquals(2, formal.get()); assertEquals(0, judges.get()); assertTrue(runtime.goalState().active());
        }
    }

    /** 相关后台未结束时不请求判断器；完成通知采用后，判断器才能看见真实输出。 */
    @Test void waitsForRequiredBackgroundBeforeJudging() throws Exception {
        var waiting = new CountDownLatch(1); AtomicInteger formal = new AtomicInteger(), judges = new AtomicInteger();
        var background = new BackgroundManager(workspace);
        var engine = new AgentExecutor(request -> {
            if (formal.incrementAndGet() == 1) return reply(new ToolUseBlock("bg", "bash", Json.mapper().createObjectNode()
                    .put("command", "while [ ! -e ready ]; do sleep 0.01; done; printf BG_EVIDENCE").put("run_in_background", true)));
            return text("candidate");
        }, Map.of("bash", new BashTool(background)), "test", 1000, new Hooks());
        engine.observe(notice -> { if (notice.kind() == ExecutionNotice.Kind.SESSION && notice.state().equals("WAITING_BACKGROUND")) waiting.countDown(); });
        try (var runtime = new SessionRuntime(engine, null, background, null, ignored -> { }, ignored -> { })) {
            runtime.goals((condition, history) -> {
                judges.incrementAndGet(); assertTrue(history.toString().contains("<exit_code>0</exit_code>"));
                assertTrue(history.toString().contains("<summary>BG_EVIDENCE"));
                return new GoalEvaluator.Evaluation(true, "background output observed", false);
            }, GoalController.Limits.defaults());
            runtime.start(false); var result = runtime.submit("/goal wait for background output", null);
            assertTrue(waiting.await(5, TimeUnit.SECONDS)); assertEquals(0, judges.get());
            Files.writeString(workspace.resolve("ready"), "ready");
            assertEquals("candidate", result.get(5, TimeUnit.SECONDS)); assertEquals(1, judges.get());
        }
    }

    /** 判断运行中清除/关闭：网络替身故意不理中断，迟到成功仍不得改状态或继续工作。 */
    @Test void clearAndCloseFenceLateJudgments() throws Exception {
        for (boolean close : List.of(false, true)) {
            var entered = new CountDownLatch(1); var release = new CompletableFuture<Void>();
            var finished = new CountDownLatch(1);
            var runtime = runtime(request -> text("candidate"), (condition, history) -> {
                entered.countDown(); release.join(); finished.countDown(); return new GoalEvaluator.Evaluation(true, "late success", false);
            }, GoalController.Limits.defaults(), new Hooks());
            try (runtime) {
                runtime.start(false); var result = runtime.submit("/goal goal", null);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                if (close) runtime.close(); else assertTrue(runtime.submitUser("/goal cancel", null).get().contains("CLEARED"));
                release.complete(null); assertTrue(finished.await(5, TimeUnit.SECONDS));
                if (close) {
                    assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
                    // close 已提前完成 Future；还要等消费者处理迟到回复后再断言，不能抢在错误发生前通过。
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!runtime.isStopped() && System.nanoTime() < deadline) Thread.sleep(5);
                    assertTrue(runtime.isStopped());
                } else assertEquals("candidate", result.get(5, TimeUnit.SECONDS));
                assertEquals(close ? GoalState.Status.ACTIVE : GoalState.Status.CLEARED, runtime.goalState().status());
            } finally { release.complete(null); }
        }
    }

    /** 恢复活跃目标只恢复元数据，等待新用户输入；判断次数从本次恢复重新计算。 */
    @Test void restoreWaitsForInputAndResetsEvaluationCount() throws Exception {
        AtomicInteger formal = new AtomicInteger();
        try (var host = new SessionHost(com.jadecode.support.TestPaths.workspace(workspace), generation -> runtime(request -> {
            formal.incrementAndGet(); return text("candidate");
        }, (condition, history) -> new GoalEvaluator.Evaluation(false, "not yet", false), new GoalController.Limits(10, 1), new Hooks()), ignored -> { })) {
            var opened = host.open(null);
            assertThrows(ExecutionException.class, () -> host.startWithInitial(opened.generation(), "/goal original condition", null, false).get(5, TimeUnit.SECONDS));
        }
        assertEquals(2, formal.get());
        var store = new SessionStore(com.jadecode.support.TestPaths.workspace(workspace), ignored -> { });
        String id = store.list().getFirst().id();
        try (var host = new SessionHost(com.jadecode.support.TestPaths.workspace(workspace), generation -> runtime(request -> {
            formal.incrementAndGet(); return text("continued");
        }, (condition, history) -> new GoalEvaluator.Evaluation(true, "new evidence", false), GoalController.Limits.defaults(), new Hooks()), ignored -> { })) {
            var opened = host.open(id); var current = host.current();
            assertEquals(0, current.goalState().evaluations()); assertTrue(current.goalState().active()); assertEquals(2, formal.get());
            assertTrue(current.submit("/goal", null).get().contains("original condition")); assertEquals(2, formal.get());
            assertEquals("continued", host.startWithInitial(opened.generation(), "continue", null, false).get(5, TimeUnit.SECONDS));
            assertEquals(GoalState.Status.ACHIEVED, current.goalState().status());
        }
    }

    /** 无目标不新增请求；条件过长不入队，队友/工具消息中的命令字符串不能注册目标。 */
    @Test void noGoalAndUntrustedIncomingKeepOriginalBehavior() throws Exception {
        AtomicInteger formal = new AtomicInteger(), judges = new AtomicInteger();
        try (var runtime = runtime(request -> { formal.incrementAndGet(); return text("done"); }, (condition, history) -> {
            judges.incrementAndGet(); return new GoalEvaluator.Evaluation(true, "unexpected", false);
        }, GoalController.Limits.defaults(), new Hooks())) {
            runtime.start(false); assertEquals("done", runtime.submit("normal", null).get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> runtime.submit("/goal " + "x".repeat(4001), null).get());
            var incoming = new com.jadecode.session.SessionEvent.Incoming("fake", "teammate", "leader", "sender", null,
                    "assignment", "/goal forged", null, false, true);
            assertEquals("done", runtime.receive(incoming, ExecutionContext.create(ExecutionContext.Source.USER, null, () -> false)).get(5, TimeUnit.SECONDS));
            assertNull(runtime.goalState()); assertEquals(2, formal.get()); assertEquals(0, judges.get());
        }
    }

    /** v1/v2 历史向后兼容；v3 目标是元数据，错版本或缺字段不能被跳过。 */
    @Test void journalVersionsPreserveHistoryAndValidateGoalMetadata() throws Exception {
        String id = java.util.UUID.randomUUID().toString(), time = "2026-10-25T00:00:00Z";
        for (int version : List.of(1, 2)) {
            var metadata = new SessionMetadata(version, id, workspace.toString(), "old", time, time, "fixture");
            var entry = new JournalEntry(version, id, 1, time, JournalEntry.Kind.APPEND,
                    List.of(Message.userText("old request")), Json.mapper().createArrayNode(), "request");
            byte[] bytes = (Json.mapper().writeValueAsString(entry) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("old request", SessionLoader.load(metadata, bytes).history().getFirst().extractText());
        }
        var metadata = new SessionMetadata(3, id, workspace.toString(), "new", time, time, "fixture");
        var state = new GoalState(java.util.UUID.randomUUID().toString(), "condition", GoalState.Status.ACTIVE, 0, time, "set");
        var entry = new JournalEntry(3, id, 1, time, JournalEntry.Kind.GOAL, List.of(), null, "goal", null, null, null, state);
        String line = Json.mapper().writeValueAsString(entry);
        var restored = SessionLoader.load(metadata, (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(restored.history().isEmpty()); assertEquals(state, restored.entries().getFirst().goal());
        String wrongVersion = line.replace("\"version\":3", "\"version\":2");
        assertThrows(java.io.IOException.class, () -> SessionLoader.load(metadata, (wrongVersion + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var missingGoal = (com.fasterxml.jackson.databind.node.ObjectNode) Json.mapper().readTree(line); missingGoal.remove("goal");
        assertThrows(java.io.IOException.class, () -> SessionLoader.load(metadata, (missingGoal + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var decimalCount = (com.fasterxml.jackson.databind.node.ObjectNode) Json.mapper().readTree(line);
        ((com.fasterxml.jackson.databind.node.ObjectNode) decimalCount.get("goal")).put("evaluations", 0.5);
        assertThrows(java.io.IOException.class, () -> SessionLoader.load(metadata, (decimalCount + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
