package com.jadecode.team;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.execution.*;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.*;
import com.jadecode.messages.*;
import com.jadecode.safety.approval.ApprovalRequest;
import com.jadecode.session.SessionRuntime;
import com.jadecode.support.TestPaths;
import com.jadecode.tasks.*;
import com.jadecode.tools.*;
import com.jadecode.util.Json;

/** 真正的两个会话通过插入通知协调审批；等待期间不占用 Team 锁，不发网络请求。 */
class TeamScopeRequestTest {
    @TempDir
    Path dir;

    @Test
    void leaderResolvesInsertedRequestAndCannotReplaceOriginalOperation() throws Exception {
        exercise(false);
    }

    @Test
    void closingMemberEndsPendingRequestAndRejectsLateAnswer() throws Exception {
        exercise(true);
    }

    /** 用明确同步点覆盖正常审批与关闭，不以 sleep 猜测队友是否正在等待。 */
    private void exercise(boolean cancel) throws Exception {
        var paths = TestPaths.workspace(dir);
        var tasks = new TaskStore(paths);
        var task = tasks.create("Inspect", "Inspect scope");
        var team = new TeamManager(paths, tasks, ignored -> {
        });
        var member = new AtomicReference<String>();
        var origin = new AtomicReference<ExecutionContext>();
        var workerContext = new AtomicReference<ExecutionContext>();
        var requestId = "scope_fixture";
        var notified = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var scopeResult = new AtomicReference<TeamManager.ScopeDecision>();
        var scopeFailure = new AtomicReference<Exception>();
        var leaderCalls = new AtomicInteger();
        var workerCalls = new AtomicInteger();
        var resolving = new AtomicBoolean();
        Tool dispatch = tool("dispatch", (input, context) -> {
            origin.set(context);
            member.set(team.spawn("Explorer\nInspect scope."));
            return team.assign(member.get(), task.id(), "Inspect", "Inspect", context, AssignmentScope.EMPTY);
        });
        Tool ask = tool("ask", (input, context) -> {
            workerContext.set(context);
            var operation = Json.mapper().createObjectNode().put("path", "fixed.txt");
            var request = new ApprovalRequest(requestId, member.get(), context.id(), "read_file", operation, dir,
                    "outside scope", 0, null);
            operation.put("path", "changed.txt"); // 等待前就改原 JSON，登记的申请仍必须固定。
            try {
                scopeResult.set(team.requestScope(request, context));
            } catch (Exception failure) {
                scopeFailure.set(failure);
            } finally {
                returned.countDown();
            }
            return "request ended";
        });
        var tools = new LinkedHashMap<String, Tool>();
        tools.put("dispatch", dispatch);
        TeamTools.forActor(team, TeamManager.LEADER).forEach(t -> tools.put(t.name(), t));
        try (var leader = session(request -> {
            if (leaderCalls.getAndIncrement() == 0)
                return call("dispatch", "{}");
            boolean hasRequest = request.messages().stream()
                    .anyMatch(m -> m.extractText().contains("[Incoming scope_request]"));
            if (hasRequest && resolving.compareAndSet(false, true)) {
                notified.countDown();
                if (!cancel)
                    return call("resolve_scope_request",
                            "{\"request_id\":\"scope_fixture\",\"decision\":\"approve_once\",\"reason\":\"Required read\"}");
            }
            return text("Candidate");
        }, tools)) {
            team.bind(leader,
                    (id, role) -> session(
                            request -> workerCalls.getAndIncrement() == 0 ? call("ask", "{}") : text("Available"),
                            Map.of("ask", ask)));
            leader.onClose(team::close);
            leader.start();
            var run = leader.submit("Inspect", null);
            assertTrue(notified.await(5, TimeUnit.SECONDS));
            assertFalse(TeamTools.forActor(team, member.get()).stream()
                    .anyMatch(t -> t.name().equals("resolve_scope_request")));
            if (cancel)
                team.shutdown(member.get());
            assertTrue(returned.await(5, TimeUnit.SECONDS));
            if (cancel) {
                assertNotNull(scopeFailure.get());
                assertNull(scopeResult.get());
            } else {
                assertNull(scopeFailure.get());
                assertEquals(TeamManager.ScopeDecision.APPROVE_ONCE, scopeResult.get());
            }
            assertNotNull(run.get(5, TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class,
                    () -> team.resolveScope(requestId, TeamManager.ScopeDecision.APPROVE_ONCE, "Late", origin.get()));
            Path record;
            try (var files = Files.walk(paths.teams())) {
                record = files.filter(p -> p.getFileName().toString().equals(requestId + ".json")).findFirst()
                        .orElseThrow();
            }
            var saved = Json.mapper().readTree(Files.readString(record));
            assertEquals(cancel ? "CANCELLED" : "APPROVE_ONCE", saved.path("state").asText());
            assertEquals("fixed.txt", saved.path("request").path("input").path("path").asText());
            assertEquals(workerContext.get().assignment().id(), saved.path("assignment_id").asText());
        } finally {
            team.close();
        }
    }

    @FunctionalInterface
    private interface Action {
        String execute(ObjectNode input, ExecutionContext context) throws Exception;
    }

    /** 小型业务工具只发起真实管理器调用，不复制 Inbox 或审批算法。 */
    private static Tool tool(String name, Action action) {
        return new Tool() {
            public String name() {
                return name;
            }

            public String description() {
                return "Fixture action";
            }

            public Map<String, String> properties() {
                return Map.of();
            }

            public List<String> required() {
                return List.of();
            }

            public String execute(ObjectNode input) {
                throw new IllegalStateException("Context required");
            }

            public String execute(ObjectNode input, ExecutionContext context) {
                try {
                    return action.execute(input, context);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
    }

    private static SessionRuntime session(LlmClient client, Map<String, Tool> tools) {
        return new SessionRuntime(new AgentExecutor(client, tools, "fixture", 1000, new Hooks()), ignored -> {
        }, ignored -> {
        });
    }

    private static ModelResponse text(String value) {
        return new ModelResponse(Message.assistant(List.of(new TextBlock(value))), new ModelResponse.Usage(0, 0),
                "end_turn");
    }

    private static ModelResponse call(String name, String input) {
        try {
            return new ModelResponse(Message.assistant(List.of(new ToolUseBlock("call_" + UUID.randomUUID(), name,
                    (ObjectNode) Json.mapper().readTree(input)))), new ModelResponse.Usage(0, 0), "tool_use");
        } catch (Exception failure) {
            throw new IllegalArgumentException(failure);
        }
    }
}
