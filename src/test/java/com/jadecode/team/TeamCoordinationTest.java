package com.jadecode.team;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.execution.AssignmentScope;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.LlmClient;
import com.jadecode.llm.ModelResponse;
import com.jadecode.messages.Message;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.session.SessionRuntime;
import com.jadecode.support.TestPaths;
import com.jadecode.tasks.Task;
import com.jadecode.tasks.TaskStore;
import com.jadecode.tools.TeamTools;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;

/** 真正会话/邮箱/任务存储的离线集成；用确定性的模型与同步点检查并发边界，不访问 HOME/API。 */
class TeamCoordinationTest {
    @TempDir Path dir;

    /** 格式失败发生在成员工厂、名册写入、线程启动之前；CRLF 可以正常解析。 */
    @Test void validatesRoleBeforeAnyMemberSideEffect() throws Exception {
        var paths = TestPaths.workspace(dir); var team = new TeamManager(paths, new TaskStore(paths), fail -> fail(fail));
        var created = new AtomicInteger();
        try (var leader = session(request -> text("done"), Map.of())) {
            team.bind(leader, (id, role) -> { created.incrementAndGet(); return session(request -> text("done"), Map.of()); });
            for (String invalid : List.of("Explorer", "Explorer\n ", "\u001b[31mExplorer\nWork", "探索员\nWork"))
                assertThrows(IllegalArgumentException.class, () -> team.spawn(invalid));
            assertEquals(0, created.get()); assertTrue(team.list().isEmpty());
            assertEquals(new TeammateRole("Explorer", "Read code.\nReport findings."),
                    TeammateRole.parse("Explorer\r\nRead code.\r\nReport findings."));
        } finally { team.close(); }
    }

    /** Leader 的最终候选回答不会使必要派工退出；后台等待不重复调用模型，报告随后继续原轮次。 */
    @Test void requiredReportResumesOriginalTurnAndInheritsFixedScope() throws Exception {
        var waiting = new CountDownLatch(1); var memberEntered = new CountDownLatch(1);
        var memberCalls = new AtomicInteger(); var leaderCalls = new AtomicInteger();
        var receivedScope = new AtomicReference<ExecutionContext>(); var firstMember = new AtomicReference<List<Message>>();
        var paths = TestPaths.workspace(dir); var tasks = new TaskStore(paths); var task = tasks.create("Trace call flow", "Read code");
        var team = new TeamManager(paths, tasks, ignored -> { }); var assignment = new AtomicReference<String>();
        Tool capture = new TestTool("capture") {
            @Override public String execute(com.fasterxml.jackson.databind.node.ObjectNode input, ExecutionContext context) {
                receivedScope.set(context); return "captured";
            }
        };
        Tool dispatch = new TestTool("dispatch") {
            @Override public String execute(com.fasterxml.jackson.databind.node.ObjectNode input, ExecutionContext context) {
                try {
                    String member = team.spawn("Explorer\nInspect the assigned source.");
                    assignment.set(team.assign(member, task.id(), "Trace flow", "Read source", context, true,
                            new AssignmentScope(List.of("src"), List.of("src/Flow.java"))));
                    return assignment.get();
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        };
        LlmClient parent = request -> switch (leaderCalls.getAndIncrement()) {
            case 0 -> call("dispatch", "{}");
            case 1 -> { await(memberEntered); yield text("Candidate answer"); }
            default -> {
                assertTrue(Json.mapper().valueToTree(request.messages()).toString().contains("Report evidence"));
                yield text("Final answer");
            }
        };
        try (var leader = session(parent, Map.of("dispatch", dispatch))) {
            leaderObserver(leader, waiting);
            team.bind(leader, (id, role) -> {
                var tools = new java.util.LinkedHashMap<String, Tool>();
                TeamTools.forActor(team, id).forEach(tool -> tools.put(tool.name(), tool)); tools.put("capture", capture);
                return session(request -> switch (memberCalls.getAndIncrement()) {
                    case 0 -> {
                        firstMember.set(List.copyOf(request.messages())); memberEntered.countDown(); await(waiting);
                        yield call("capture", "{}");
                    }
                    case 1 -> call("submit_task_result", "{\"assignment_id\":\"" + assignment.get()
                            + "\",\"status\":\"completed\",\"summary\":\"Report evidence\",\"body\":\"Checked the actual call chain.\"}");
                    default -> text("Available");
                }, tools);
            });
            leader.onClose(team::close); leader.start(false);
            assertEquals("Final answer", leader.submit("Leader-only background", null).get(5, TimeUnit.SECONDS));
            assertEquals(3, leaderCalls.get()); assertEquals(1, firstMember.get().size());
            assertFalse(firstMember.get().getFirst().extractText().contains("Leader-only background"));
            assertEquals(assignment.get(), receivedScope.get().assignment().id());
            assertEquals(List.of("src/Flow.java"), receivedScope.get().assignment().scope().writePaths());
            assertEquals(Task.Status.COMPLETED, tasks.load(task.id()).status());
            assertEquals("Checked the actual call chain.", leader.teamReport(assignment.get()));
        } finally { waiting.countDown(); team.close(); }
    }

    /** 缺报告与异常都是明确的终态说明，不能把一次性返回误当完成，也不能让 Leader 永久等待。 */
    @Test void missingReportAndExecutionFailureWakeLeader() throws Exception {
        for (boolean crash : List.of(false, true)) {
            Path workspace = Files.createDirectory(dir.resolve(crash ? "crash" : "missing"));
            var paths = TestPaths.workspace(workspace); var tasks = new TaskStore(paths);
            var task = tasks.create("Inspect", "Inspect code"); var team = new TeamManager(paths, tasks, ignored -> { });
            var calls = new AtomicInteger();
            Tool dispatch = new TestTool("dispatch") {
                @Override public String execute(com.fasterxml.jackson.databind.node.ObjectNode input, ExecutionContext context) {
                    try { String id = team.spawn("Explorer\nInspect code."); return team.assign(id, task.id(), "Inspect", "Inspect code", context); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                }
            };
            try (var leader = session(request -> calls.getAndIncrement() == 0 ? call("dispatch", "{}") : text("Handled"), Map.of("dispatch", dispatch))) {
                team.bind(leader, (id, role) -> session(request -> {
                    if (crash) throw new IllegalStateException("synthetic failure");
                    return text("Forgot the report");
                }, Map.of()));
                leader.onClose(team::close); leader.start(false);
                assertEquals("Handled", leader.submit("Inspect", null).get(5, TimeUnit.SECONDS));
                assertEquals(crash ? Task.Status.FAILED : Task.Status.BLOCKED, tasks.load(task.id()).status());
            } finally { team.close(); }
        }
    }

    /** 派工范围逐项保留；重新派工后旧报告不能更新新派工，查看不会自动登记核对。 */
    @Test void snapshotsPersistAcrossReassignmentAndReviewIsExplicit() throws Exception {
        var paths = TestPaths.workspace(dir); var tasks = new TaskStore(paths); var task = tasks.create("Inspect", "Read code");
        var team = new TeamManager(paths, tasks, ignored -> { });
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var leader = session(request -> text("done"), Map.of())) {
            team.bind(leader, (id, role) -> session(request -> { started.countDown(); await(release); return text("done"); }, Map.of()));
            String member = team.spawn("Explorer\nInspect code.");
            var origin = ExecutionContext.create(ExecutionContext.Source.USER, null);
            String first = team.assign(member, task.id(), "Inspect A", "A", origin, false, new AssignmentScope(List.of("a"), List.of("a/A.java")));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            String report = team.submitResult(member, first, Task.Status.BLOCKED, "Need input", "Question evidence");
            assertEquals(report, team.submitResult(member, first, Task.Status.BLOCKED, "Need input", "Question evidence"));
            assertEquals("Question evidence", team.reportText(first));
            team.review(first, report, "Checked the evidence."); team.reopen(task.id());
            String second = team.assign(member, task.id(), "Inspect B", "B", origin, false, new AssignmentScope(List.of("b"), List.of("b/B.java")));
            assertThrows(IllegalArgumentException.class, () -> team.submitResult(member, first, Task.Status.COMPLETED, "Old", "Old"));
            try (var records = Files.walk(paths.teams())) {
                var files = records.filter(file -> file.getFileName().toString().equals(first + ".json") || file.getFileName().toString().equals(second + ".json")).toList();
                assertEquals(2, files.size());
                for (Path file : files) {
                    var record = Json.mapper().readTree(Files.readString(file));
                    String expected = file.getFileName().toString().startsWith(first) ? "a/A.java" : "b/B.java";
                    assertEquals(expected, record.path("scope").path("writePaths").get(0).asText());
                }
            }
        } finally { release.countDown(); team.close(); }
    }

    /** 普通消息不能误消除问题；只有成功投递的关联答复才更新事实。 */
    @Test void questionReplyRequiresMatchingActorsAndAssignment() throws Exception {
        var paths = TestPaths.workspace(dir); var team = new TeamManager(paths, new TaskStore(paths), ignored -> { });
        var hold = new CountDownLatch(1);
        try (var leader = session(request -> { await(hold); return text("done"); }, Map.of())) {
            team.bind(leader, (id, role) -> session(request -> { await(hold); return text("done"); }, Map.of()));
            String member = team.spawn("Explorer\nInspect code."); var origin = ExecutionContext.create(ExecutionContext.Source.USER, null);
            String question = team.send(member, "leader", null, null, "question", "Need input", "Which path?", true, true, origin);
            team.send("leader", member, null, null, "message", "Progress", "Still checking", true, true, origin);
            assertEquals(1L, team.snapshot().getFirst().get("openQuestions"));
            assertThrows(IllegalArgumentException.class, () -> team.send(member, "leader", null, null, "message", "Wrong", "Wrong", true, true, origin, question));
            team.send("leader", member, null, null, "message", "Use src", "Read src", true, true, origin, question);
            assertEquals(0L, team.snapshot().getFirst().get("openQuestions"));
        } finally { hold.countDown(); team.close(); }
    }

    /** 关闭与磁盘写入失败都能产生明确结果，Leader 不会永远等待一份不可能到达的报告。 */
    @Test void shutdownAndReportRecordingFailureReleaseRequiredWait() throws Exception {
        for (boolean diskFailure : List.of(false, true)) {
            Path workspace = Files.createDirectory(dir.resolve(diskFailure ? "disk" : "close"));
            var paths = TestPaths.workspace(workspace); var tasks = new TaskStore(paths); var task = tasks.create("Inspect", "Inspect code");
            var waiting = new CountDownLatch(1); var release = new CountDownLatch(1); var member = new AtomicReference<String>();
            var team = new TeamManager(paths, tasks, ignored -> { }); var calls = new AtomicInteger();
            Tool dispatch = new TestTool("dispatch") {
                @Override public String execute(com.fasterxml.jackson.databind.node.ObjectNode input, ExecutionContext context) {
                    try { member.set(team.spawn("Explorer\nInspect code.")); return team.assign(member.get(), task.id(), "Inspect", "Inspect code", context); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                }
            };
            try (var leader = session(request -> calls.getAndIncrement() == 0 ? call("dispatch", "{}") : text("Handled"), Map.of("dispatch", dispatch))) {
                leaderObserver(leader, waiting);
                team.bind(leader, (id, role) -> session(request -> { await(release); return text("Returned without report"); }, Map.of()));
                leader.onClose(team::close); leader.start(false); var result = leader.submit("Inspect", null);
                assertTrue(waiting.await(5, TimeUnit.SECONDS));
                if (diskFailure) {
                    Path messages;
                    try (var files = Files.walk(paths.teams())) { messages = files.filter(p -> p.getFileName().toString().equals("messages")).findFirst().orElseThrow(); }
                    Files.move(messages, messages.resolveSibling("saved-messages")); Files.writeString(messages, "synthetic write failure");
                    release.countDown();
                } else team.shutdown(member.get());
                assertEquals("Handled", result.get(5, TimeUnit.SECONDS));
                assertEquals(diskFailure ? Task.Status.IN_PROGRESS : Task.Status.BLOCKED, tasks.load(task.id()).status());
            } finally { release.countDown(); team.close(); }
        }
    }

    /** 真正生产工厂接通 Team 工具、隔离历史、一次性子代理及其可信范围，关闭后没有活队友。 */
    @Test void productionFactoryCarriesAssignmentThroughNestedSubagent() throws Exception {
        Files.writeString(dir.resolve("proof.txt"), "offline evidence");
        var parent = new AtomicInteger(); var member = new AtomicInteger(); var child = new AtomicInteger();
        var agent = new AtomicReference<String>(); var task = new AtomicReference<String>(); var assignment = new AtomicReference<String>();
        var nestedContext = new AtomicReference<ExecutionContext>();
        LlmClient model = request -> {
            if (request.system().contains("Complete the given task")) {
                return child.getAndIncrement() == 0 ? call("read_file", "{\"path\":\"proof.txt\"}") : text("Verified evidence");
            }
            if (request.system().startsWith("You are JadeCode teammate.")) {
                if (member.getAndIncrement() == 0) {
                    assertEquals(1, request.messages().size());
                    var match = java.util.regex.Pattern.compile("assignment=(assignment_[a-z0-9-]+)").matcher(request.messages().getFirst().extractText());
                    assertTrue(match.find()); assignment.set(match.group(1));
                    return call("task", "{\"prompt\":\"Read proof.txt and report evidence.\"}");
                }
                if (member.get() == 2) return call("submit_task_result", "{\"assignment_id\":\"" + assignment.get()
                        + "\",\"status\":\"completed\",\"summary\":\"Evidence verified\",\"body\":\"Read proof.txt successfully.\"}");
                return text("Available");
            }
            int phase = parent.getAndIncrement();
            if (phase == 0) return call("spawn_teammate", "{\"role\":\"Explorer\\nVerify source evidence.\"}");
            try {
                if (phase == 1) {
                    agent.set(Json.mapper().readTree(((com.jadecode.messages.ToolResultBlock) request.messages().getLast().content().getFirst()).content()).path("agent_id").asText());
                    return call("create_task", "{\"subject\":\"Verify evidence\",\"description\":\"Read proof.txt.\"}");
                }
                if (phase == 2) {
                    task.set(Json.mapper().readTree(((com.jadecode.messages.ToolResultBlock) request.messages().getLast().content().getFirst()).content()).path("id").asText());
                    return call("assign_task", "{\"agent_id\":\"" + agent.get() + "\",\"task_id\":\"" + task.get()
                            + "\",\"summary\":\"Verify evidence\",\"body\":\"Use a task subagent.\",\"scope\":{\"read_paths\":[\"proof.txt\"],\"write_paths\":[]}}");
                }
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            return text("Final answer");
        };
        var defaults = com.jadecode.config.JadeSettings.defaults();
        var settings = new com.jadecode.config.JadeSettings(defaults.tui(), defaults.compaction(), defaults.limits(),
                new com.jadecode.config.JadeSettings.Memory(false, 8000, 16384, 128000));
        try (var runtime = com.jadecode.bootstrap.JadeCodeFactory.create(
                new com.jadecode.config.AppConfig("fixture", "http://localhost:1", "fixture", 4000), dir, model,
                ignored -> { }, ignored -> { }, (hooks, cwd, output) -> hooks.register(Hooks.PRE_TOOL_USE, params -> {
                    var call = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
                    if (call.name().equals("read_file")) nestedContext.set((ExecutionContext) params.get(Hooks.PARAM_CONTEXT));
                    return null;
                }), () -> com.jadecode.llm.ResponseStream.NONE, com.jadecode.execution.ExecutionObserver.NONE,
                settings, settings.permissions().state(null), TestPaths.jade(dir))) {
            runtime.start(false);
            assertEquals("Final answer", runtime.submit("Verify evidence using Team", null).get(5, TimeUnit.SECONDS));
            assertNotNull(nestedContext.get()); assertEquals(assignment.get(), nestedContext.get().assignment().id());
            assertEquals(List.of("proof.txt"), nestedContext.get().assignment().scope().readPaths());
            assertEquals("Explorer", runtime.display().team().getFirst().get("name"));
        }
    }

    /** 后台任务捕获启动时的范围；后续重建目标说明不能丢掉派工快照。 */
    @Test void backgroundCompletionRetainsAssignmentSnapshot() throws Exception {
        var scope = new AssignmentScope(List.of("src"), List.of("src/A.java"));
        var context = ExecutionContext.create(ExecutionContext.Source.USER, null).withAssignment(
                new com.jadecode.execution.AssignmentContext("assignment_1", "task_1", "agent_1", "run_leader", scope));
        var completed = new CompletableFuture<com.jadecode.process.BackgroundManager.Completion>();
        try (var background = new com.jadecode.process.BackgroundManager(dir)) {
            background.onResult(completed::complete);
            background.start("printf evidence", context.withGoal("Read evidence"), true, false, false);
            var result = completed.get(5, TimeUnit.SECONDS);
            assertEquals(context.assignment(), result.context().assignment());
            assertEquals(List.of("src/A.java"), result.context().assignment().scope().writePaths());
        }
    }

    /** 测试模型的调用同步点有超时，失败时给出明确原因，不靠 sleep 猜线程状态。 */
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test synchronization timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    private static SessionRuntime session(LlmClient client, Map<String, Tool> tools) {
        return new SessionRuntime(new AgentExecutor(client, tools, "test", 1000, new Hooks()), ignored -> { }, ignored -> { });
    }
    /** 观察器只记录等待状态，借此确定性释放队友的结果。 */
    private static void leaderObserver(SessionRuntime runtime, CountDownLatch waiting) throws Exception {
        var field = SessionRuntime.class.getDeclaredField("engine"); field.setAccessible(true);
        ((AgentExecutor) field.get(runtime)).observe(notice -> {
            if (notice.kind() == ExecutionNotice.Kind.SESSION && notice.state().equals("WAITING_TEAM")) waiting.countDown();
        });
    }
    private static ModelResponse text(String text) {
        return new ModelResponse(Message.assistant(List.of(new TextBlock(text))), new ModelResponse.Usage(0, 0), "end_turn");
    }
    private static ModelResponse call(String name, String input) {
        try { return new ModelResponse(Message.assistant(List.of(new ToolUseBlock("call_" + java.util.UUID.randomUUID(), name,
                (com.fasterxml.jackson.databind.node.ObjectNode) Json.mapper().readTree(input)))), new ModelResponse.Usage(0, 0), "tool_use"); }
        catch (Exception failure) { throw new IllegalArgumentException(failure); }
    }
    /** 注入一个真实会被 AgentExecutor 调用的小工具，验证上下文而非复制实现算法。 */
    private abstract static class TestTool implements Tool {
        private final String name;
        TestTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "Offline integration tool"; }
        @Override public Map<String, String> properties() { return Map.of(); }
        @Override public List<String> required() { return List.of(); }
        @Override public String execute(com.fasterxml.jackson.databind.node.ObjectNode input) { throw new IllegalStateException("Context required"); }
    }
}
