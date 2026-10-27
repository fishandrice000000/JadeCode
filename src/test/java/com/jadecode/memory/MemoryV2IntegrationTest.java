package com.jadecode.memory;

import com.jadecode.bootstrap.JadeCodeFactory;
import com.jadecode.config.*;
import com.jadecode.execution.ExecutionObserver;
import com.jadecode.llm.*;
import com.jadecode.messages.*;
import com.jadecode.session.history.SessionStore;
import com.jadecode.util.Json;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 同一生产装配使用可注入根目录和假客户端，检查工具权限、真实日志与异步后台闭环。 */
@Timeout(15)
class MemoryV2IntegrationTest {
    @TempDir Path temporary;

    @Test void productionAssemblyQueuesOneJobAfterToolsAndUsesGlobalReadOnlyMemory() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace")), root = temporary.resolve("home/.jadecode/memory");
        var warnings = new CopyOnWriteArrayList<String>();
        var store = new MemoryV2Store(root, workspace, warnings::add); store.initialize();
        store.publish(store.snapshot(), Map.of(store.scope(), new MemoryV2Store.Artifacts("v2\nProject uses Java 21; read MEMORY.md", "Known project fact")), "seed", List.of());
        var requests = new CopyOnWriteArrayList<CompleteRequest>(); var extractionInput = new CompletableFuture<String>();
        var completed = new CountDownLatch(1);
        LlmClient fake = request -> {
            requests.add(request);
            if (request.tools().isEmpty()) {
                extractionInput.complete(request.messages().getFirst().extractText());
                return text("{\"rollout_summary\":\"\",\"rollout_slug\":\"\"}");
            }
            long formal = requests.stream().filter(r -> !r.tools().isEmpty()).count();
            if (formal == 1) {
                assertTrue(request.messages().stream().anyMatch(m -> m.source() == Message.Source.MEMORY_REFERENCE
                        && m.extractText().contains("Project uses Java 21")));
                assertTrue(request.tools().stream().anyMatch(t -> t.name().equals("memory_note")));
                return new ModelResponse(Message.assistant(List.of(
                        new ToolUseBlock("read", "read_file", Json.mapper().createObjectNode().put("path", root.resolve("MEMORY.md").toString())),
                        new ToolUseBlock("write", "write_file", Json.mapper().createObjectNode().put("path", root.resolve("MEMORY.md").toString()).put("content", "MUST_NOT_WRITE"))
                )), null, "tool_use");
            }
            String results = request.messages().getLast().content().toString();
            assertTrue(results.contains("Known project fact")); assertTrue(results.contains("read-only"));
            completed.countDown(); return text("[Source](" + root.resolve("MEMORY.md") + ") Verified.");
        };
        var settings = JadeSettings.defaults();
        var sessions = new SessionStore(new WorkspacePaths(workspace), warnings::add);
        try (var opened = sessions.create("offline"); var runtime = JadeCodeFactory.create(
                new AppConfig("offline", "http://127.0.0.1:1", "fixture", 8000), workspace, fake,
                ignored -> { }, warnings::add, (hooks, cwd, output) -> { }, () -> ResponseStream.NONE,
                ExecutionObserver.NONE, settings, settings.permissions().state(null), root)) {
            runtime.restore(opened.journal(), opened.restored()); runtime.start(false);
            assertTrue(runtime.submit("Check the project fact", null).get(5, TimeUnit.SECONDS).contains("Verified"));
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            String evidence = extractionInput.get(5, TimeUnit.SECONDS);
            assertTrue(evidence.contains("Check the project fact"));
            assertFalse(evidence.contains("Memory root:"), "注入路由不重新进入提取证据");
            assertFalse(evidence.contains("Known project fact"), "读取旧手册不是新的人类证据");
            assertTrue(evidence.contains(":event="));
            assertEquals(1, store.jobs().size(), "工具续跑不创建第二项用户任务");
            assertTrue(store.feedback().stream().anyMatch(n -> n.path("kind").asText().equals("read")));
            assertTrue(store.feedback().stream().anyMatch(n -> n.path("kind").asText().equals("cited")));
            assertFalse(store.snapshot().handbook().contains("MUST_NOT_WRITE"));
            assertFalse(Files.exists(workspace.resolve(".jadecode/memory")), "新库不能跟随项目目录创建");
        }
        assertTrue(warnings.stream().noneMatch(w -> w.contains("[Memory]")), warnings.toString());
    }

    @Test void disabledMemoryCreatesNoLibraryAndMakesNoAuxiliaryRequests() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace")), root = temporary.resolve("global-memory");
        var d = JadeSettings.defaults();
        var settings = new JadeSettings(d.tui(), d.compaction(), d.limits(), new JadeSettings.Memory(false, 8000, 2048, 16384, 128000, 128000),
                d.modelMetadata(), d.thinking(), d.permissions(), d.mcp(), d.contextCompaction());
        try (var runtime = JadeCodeFactory.create(new AppConfig("offline", "http://127.0.0.1:1", "fixture", 8000), workspace,
                request -> { assertFalse(request.tools().isEmpty()); return text("done"); }, ignored -> { }, ignored -> { },
                (hooks, cwd, output) -> { }, () -> ResponseStream.NONE, ExecutionObserver.NONE, settings,
                settings.permissions().state(null), root)) {
            runtime.run(new ArrayList<>(List.of(Message.userText("hello"))));
        }
        assertFalse(Files.exists(root));
    }

    @Test void ordinaryExitRetainsInterruptedTaskForNextMaintenance() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace")), root = temporary.resolve("memory");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var settings = JadeSettings.defaults();
        var sessions = new SessionStore(new WorkspacePaths(workspace), ignored -> { });
        try (var opened = sessions.create("offline")) {
            var runtime = JadeCodeFactory.create(new AppConfig("offline", "http://127.0.0.1:1", "fixture", 8000), workspace,
                    request -> {
                        entered.countDown();
                        try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        return text("Late response");
                    }, ignored -> { }, ignored -> { }, (hooks, cwd, output) -> { }, () -> ResponseStream.NONE,
                    ExecutionObserver.NONE, settings, settings.permissions().state(null), root);
            try {
                runtime.restore(opened.journal(), opened.restored()); runtime.start(false);
                runtime.submit("An unfinished human task", null); assertTrue(entered.await(2, TimeUnit.SECONDS));
            } finally { runtime.close(); release.countDown(); }
        }
        var store = new MemoryV2Store(root, workspace, ignored -> { });
        assertEquals(1, store.jobs().size()); assertEquals("interrupted", store.jobs().getFirst().path("outcome").asText());
        try (var maintenance = new MemoryV2(store, request -> {
            assertTrue(request.messages().getFirst().extractText().contains("An unfinished human task"));
            return text("{\"rollout_summary\":\"\",\"rollout_slug\":\"\"}");
        }, settings.memory(), ignored -> { }, ignored -> { }, false)) { maintenance.maintain(); }
        assertEquals("done", store.jobs().getFirst().path("status").asText());
    }

    private static ModelResponse text(String body) { return new ModelResponse(Message.assistant(List.of(new TextBlock(body))), null, "end_turn"); }
}
