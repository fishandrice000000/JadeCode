package com.jadecode.config;

import com.jadecode.bootstrap.JadeCodeFactory;
import com.jadecode.execution.ExecutionObserver;
import com.jadecode.llm.*;
import com.jadecode.messages.*;
import com.jadecode.safety.*;
import com.jadecode.safety.rules.PermissionRule;
import com.jadecode.session.history.SessionStore;
import com.jadecode.tasks.TaskStore;
import com.jadecode.team.TeamMailbox;
import com.jadecode.cron.CronScheduler;
import com.jadecode.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 真实文件和离线客户端验证全局布局、项目隔离、恢复与权限；HOME 不会被重设。 */
class GlobalStorageTest {
    @TempDir Path temporary;

    private WorkspacePaths paths(String name) throws IOException {
        return new WorkspacePaths(Files.createDirectories(temporary.resolve(name)), new JadePaths(temporary.resolve("home/.jadecode")));
    }

    @Test void sameNamedProjectsAndWorktreesHaveSeparatePartitions() throws Exception {
        var first = paths("a/project"); var second = paths("b/project");
        assertNotEquals(first.id(), second.id()); assertEquals(first.jade(), second.jade());
        Path alias = temporary.resolve("alias"); Files.createSymbolicLink(alias, first.workspace());
        assertEquals(first.root(), new WorkspacePaths(alias, first.jade()).root());
        first.register();
        var registry = Json.mapper().readTree(Files.readString(first.root().resolve("workspace.json")));
        assertEquals(first.workspace().toString(), registry.path("workspace").asText());
        assertFalse(Files.exists(first.workspace().resolve(".jadecode")));
    }

    @Test void persistentStoresUseOneGlobalPartitionAndSessionReopens() throws Exception {
        var paths = paths("project"); var sessions = new SessionStore(paths, ignored -> { }); String id;
        try (var opened = sessions.create("offline")) { id = opened.journal().metadata().id(); }
        try (var reopened = sessions.open(id)) { assertEquals(paths.workspace().toString(), reopened.restored().metadata().workspace()); }
        var task = new TaskStore(paths).create("fixture", "project task");
        var incoming = new TeamMailbox(paths).prepare("leader", "agent", task.id(), null, "progress", "summary", "body", false, false);
        new CronScheduler(paths).schedule("0 * * * *", "fixture", true, true);
        assertTrue(Files.exists(paths.tasks().resolve(task.id() + ".json")));
        assertTrue(Path.of(incoming.bodyPath()).startsWith(paths.teams()));
        assertTrue(Files.exists(paths.cronFile()));
        assertFalse(Files.exists(paths.workspace().resolve(".jadecode")));
    }

    @Test void oldProjectDataIsIgnoredAndLeftUntouched() throws Exception {
        var paths = paths("project"); Path old = Files.createDirectories(paths.workspace().resolve(".jadecode/sessions/old"));
        Files.writeString(old.resolve("events.jsonl"), "UNSUPPORTED_OLD_DATA");
        assertTrue(new SessionStore(paths, ignored -> { }).list().isEmpty());
        assertEquals("UNSUPPORTED_OLD_DATA", Files.readString(old.resolve("events.jsonl")));
        assertFalse(Files.exists(paths.root().resolve("import.json")));
    }

    @Test void invalidRegistryAndSymlinkedPartitionAreRejected() throws Exception {
        var first = paths("first"); first.register();
        Files.writeString(first.root().resolve("workspace.json"), "{\"id\":\"foreign\",\"workspace\":\"foreign\"}");
        assertThrows(IOException.class, first::register);
        var second = paths("second"); Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(second.root(), outside);
        assertThrows(IOException.class, second::register);
        assertFalse(Files.exists(outside.resolve("workspace.json")));
    }

    @Test void onlyCurrentProjectArtifactsReceiveImplicitReadAccess() throws Exception {
        var first = paths("first"); var second = paths("second");
        first.directory(first.transcripts(), true); second.directory(second.transcripts(), true);
        Path readable = first.transcripts().resolve("transcript-fixture.jsonl"); Files.writeString(readable, "fixture");
        Path other = second.transcripts().resolve("transcript-fixture.jsonl"); Files.writeString(other, "other");
        var state = PermissionState.defaults();
        var gate = new PermissionGate(new PathResolver(first.workspace()), state, (com.jadecode.safety.approval.Approval) null);
        gate.runtimeData(first);
        assertNull(gate.check("read_file", Json.mapper().createObjectNode().put("path", readable.toString())));
        assertNotNull(gate.check("read_file", Json.mapper().createObjectNode().put("path", other.toString())));
        assertNotNull(gate.check("read_file", Json.mapper().createObjectNode().put("path", first.root().resolve("workspace.json").toString())));
        state.select(PermissionProfile.FULL_ACCESS);
        assertTrue(gate.check("write_file", Json.mapper().createObjectNode().put("path", other.toString()).put("content", "bad")).contains("read-only"));
        Path link = first.transcripts().resolve("linked.jsonl"); Files.createSymbolicLink(link, other);
        assertFalse(first.readableArtifact(link));
    }

    @Test void explicitDenyWinsOverArtifactAllowance() throws Exception {
        var paths = paths("project"); paths.directory(paths.toolResults(), true);
        Path artifact = paths.toolResults().resolve("fixture.txt"); Files.writeString(artifact, "fixture");
        var deny = new PermissionRule("deny.read", PermissionRule.Effect.DENY, PermissionRule.Kind.TOOL, "read_file", "", "", "", false, List.of());
        var state = new PermissionState(PermissionProfile.WORKSPACE, List.of(deny), Set.of(), null);
        var gate = new PermissionGate(new PathResolver(paths.workspace()), state, (com.jadecode.safety.approval.Approval) null);
        gate.runtimeData(paths);
        assertTrue(gate.check("read_file", Json.mapper().createObjectNode().put("path", artifact.toString())).contains("deny.read"));
    }

    @Test void actualFactoryKeepsShellInProjectWhileStateLivesUnderHome() throws Exception {
        var paths = paths("project"); var d = JadeSettings.defaults(); var calls = new AtomicInteger();
        var settings = new JadeSettings(d.tui(), d.compaction(), d.limits(), new JadeSettings.Memory(false, 100, 100, 1000));
        LlmClient fake = request -> {
            if (calls.incrementAndGet() == 1) return new ModelResponse(Message.assistant(List.of(new ToolUseBlock("pwd", "bash",
                    Json.mapper().createObjectNode().put("command", "pwd")))), null, "tool_use");
            assertTrue(request.messages().getLast().content().toString().contains(paths.workspace().toString()));
            return new ModelResponse(Message.assistant(List.of(new TextBlock("done"))), null, "end_turn");
        };
        try (var runtime = JadeCodeFactory.create(new AppConfig("offline", "http://127.0.0.1:1", "fixture", 100), paths.workspace(), fake,
                ignored -> { }, ignored -> { }, (hooks, cwd, output) -> { }, () -> ResponseStream.NONE,
                ExecutionObserver.NONE, settings, settings.permissions().state(null), paths.jade())) {
            runtime.run(new ArrayList<>(List.of(Message.userText("show cwd"))));
        }
        assertEquals(2, calls.get()); assertTrue(Files.exists(paths.root().resolve("workspace.json")));
        assertFalse(Files.exists(paths.workspace().resolve(".jadecode")));
    }
}
