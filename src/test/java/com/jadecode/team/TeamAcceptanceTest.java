package com.jadecode.team;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.execution.*;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.*;
import com.jadecode.messages.*;
import com.jadecode.session.SessionRuntime;
import com.jadecode.support.TestPaths;
import com.jadecode.tasks.*;
import com.jadecode.util.Json;

/** 验收操作影响真实任务存储与依赖图；报告和返工意见在不同 Assignment 下保留。 */
class TeamAcceptanceTest {
    @TempDir Path dir;

    @Test void submittedDoesNotUnlockDependenciesAndRejectPreservesOldEvidence() throws Exception {
        var paths = TestPaths.workspace(dir); var tasks = new TaskStore(paths);
        var work = tasks.create("Implement", "Implement module");
        var dependent = tasks.create("Integrate", "Depends on reviewed work");
        tasks.updateDependencies(dependent.id(), List.of(work.id()));
        var team = new TeamManager(paths, tasks, ignored -> { });
        var hold = new CountDownLatch(1);
        try (var leader = session(request -> text("leader"))) {
            team.bind(leader, (id, role) -> session(request -> {
                try { hold.await(); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
                return text("available");
            }));
            String member = team.spawn("Developer\nImplement module.");
            var origin = ExecutionContext.create(ExecutionContext.Source.USER, null);
            String first = team.assign(member, work.id(), "Implement", "Instructions", origin, AssignmentScope.EMPTY);
            String report = team.submitResult(member, first, Task.Status.COMPLETED, "First report", "Original evidence");
            assertEquals(Task.Status.SUBMITTED, tasks.load(work.id()).status());
            assertThrows(IllegalArgumentException.class, () -> tasks.claim(dependent.id(), "other"));
            assertThrows(IllegalArgumentException.class, () -> tasks.complete(work.id(), member));
            assertThrows(IllegalArgumentException.class, () -> team.review(first, report, "note", "Inspected"));
            team.review(first, report, "reject", "Missing validation; add tests");
            assertEquals(Task.Status.PENDING, tasks.load(work.id()).status());
            String second = team.assign(member, work.id(), "Rework", "Add tests", origin, AssignmentScope.EMPTY);
            assertNotEquals(first, second);
            assertThrows(IllegalArgumentException.class, () -> team.submitResult(member, first, Task.Status.COMPLETED, "Late", "Wrong evidence"));
            String newer = team.submitResult(member, second, Task.Status.COMPLETED, "Validated", "New evidence");
            // 旧验收的相同重试不能接受或撤销新派工。
            team.review(first, report, "reject", "Missing validation; add tests");
            assertEquals(Task.Status.SUBMITTED, tasks.load(work.id()).status());
            team.review(second, newer, "accept", "Tests checked");
            team.review(second, newer, "accept", "Tests checked");
            assertEquals(Task.Status.COMPLETED, tasks.load(work.id()).status());
            assertEquals(Task.Status.IN_PROGRESS, tasks.claim(dependent.id(), "other").status());
            assertEquals("Original evidence", team.reportText(first));
            Path saved;
            try (var files = Files.walk(paths.teams())) {
                saved = files.filter(p -> p.getFileName().toString().equals(first + ".json")).findFirst().orElseThrow();
            }
            var old = Json.mapper().readTree(Files.readString(saved));
            assertEquals("reject", old.path("review_decision").asText());
            assertEquals("Missing validation; add tests", old.path("review_notes").asText());
        } finally { team.close(); hold.countDown(); }
    }

    private static SessionRuntime session(LlmClient client) {
        return new SessionRuntime(new AgentExecutor(client, Map.of(), "fixture", 1000, new Hooks()), ignored -> { }, ignored -> { });
    }
    private static ModelResponse text(String value) {
        return new ModelResponse(Message.assistant(List.of(new TextBlock(value))), new ModelResponse.Usage(0, 0), "end_turn");
    }
}
