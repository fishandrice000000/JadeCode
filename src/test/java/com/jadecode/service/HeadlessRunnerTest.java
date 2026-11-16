package com.jadecode.service;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.cli.LaunchOptions;
import com.jadecode.cli.ServiceCli;
import com.jadecode.cli.backend.RuntimeFactory;
import com.jadecode.config.*;
import com.jadecode.llm.*;
import com.jadecode.messages.Message;
import com.jadecode.safety.*;
import com.jadecode.session.history.*;
import com.jadecode.util.Json;

/** 原无界面契约迁到正式服务路径；stdout 的精确输出另由 ServiceProcessTest 验证真实 CLI。 */
class HeadlessRunnerTest {
    @TempDir
    Path temporary;
    private JadePaths paths;
    private final AtomicReference<PermissionState> permissions = new AtomicReference<>();

    /** 注入假模型与临时 HOME，配置和会话仍沿正式服务路径装配。 */
    private AgentService service(LlmClient client) throws Exception {
        paths = new JadePaths(temporary.resolve("data"));
        paths.directory(paths.config(), true);
        Path config = paths.config().resolve("config.json");
        Files.writeString(config, "{\"version\":1,\"memory\":{\"enabled\":false},\"sandbox\":{\"enabled\":false}}");
        var settings = new JadeConfigFile(config).load();
        return new AgentService(paths, "fixture-build", (workspace, state, model) -> {
            permissions.set(state);
            return new RuntimeFactory(settings, state,
                    new AppConfig("fixture", "http://127.0.0.1:9", "fixture", 128, ModelProvider.ANTHROPIC_COMPATIBLE),
                    client, paths);
        });
    }

    @Test
    void returnsOnlyTheFinalAnswerAndSavesAResumableSession() throws Exception {
        var client = new ScriptedLlmClient(new ModelResponse(
                Message.assistant(List.of(new com.jadecode.messages.TextBlock("Final answer from the fake model"))),
                new ModelResponse.Usage(5, 7, 0, 0), "end_turn"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        try (var service = service(client)) {
            var managed = service.open(Json.mapper().createObjectNode().put("workspace", workspace.toString())
                    .put("headless", true).put("prompt", "say hi"));
            try (var connection = new AgentServiceTest.Connection(managed)) {
                connection.handshake();
                var result = connection.await("task.result", p -> p.path("text").asText().contains("Final answer"));
                assertEquals("Final answer from the fake model", result.payload().path("text").asText());
                assertEquals(PermissionProfile.AUTO_REVIEW, permissions.get().snapshot().profile());
            }
        }
        var store = new SessionStore(new WorkspacePaths(workspace, paths), ignored -> {
        });
        var sessions = store.list();
        assertEquals(1, sessions.size());
        try (var opened = store.open(sessions.getFirst().id())) {
            assertEquals("Final answer from the fake model", opened.restored().history().getLast().extractText());
            assertTrue(Files.exists(new WorkspacePaths(workspace, paths).sessions()
                    .resolve(sessions.getFirst().id()).resolve("events.jsonl")));
        }
    }

    @Test
    void headlessDefaultsToAutoReviewAndHonoursAnExplicitOverride() throws Exception {
        try (var service = service((request, stream) -> {
            throw new AssertionError("不应请求模型");
        })) {
            for (PermissionProfile profile : PermissionProfile.values()) {
                Path workspace = Files.createDirectory(temporary.resolve(profile.name()));
                var managed = service.open(Json.mapper().createObjectNode().put("workspace", workspace.toString())
                        .put("headless", true).put("permission", profile.value()));
                assertEquals(profile, permissions.get().snapshot().profile());
                service.closeSession(managed.id);
            }
        }
    }

    @Test
    void blankPromptIsAUsageError() {
        assertThrows(LaunchOptions.UsageException.class,
                () -> LaunchOptions.parse(new String[] { "--headless", "   " }));
        var errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            assertEquals(2, ServiceCli.run(new String[] { "--headless", "" }));
        } finally {
            System.setErr(original);
        }
        assertFalse(errors.toString(StandardCharsets.UTF_8).isBlank());
    }
}
