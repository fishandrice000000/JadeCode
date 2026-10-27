package com.jadecode.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.config.JadeSettings;
import com.jadecode.llm.*;
import com.jadecode.messages.*;
import com.jadecode.session.history.*;
import com.jadecode.util.Json;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 真文件、假模型验证持久化与协议，不把替身测试当作提取语义质量评估。 */
class MemoryMaintenanceTest {
    @TempDir Path temporary;
    private final List<String> warnings = new ArrayList<>();

    /** 每个用例独占临时 HOME 等价目录；不读取真实用户记忆。 */
    private MemoryStore store() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("project"));
        return new MemoryStore(temporary.resolve("home/.jadecode/memory"), workspace, warnings::add);
    }
    private MemoryMaintenance service(MemoryStore store, LlmClient fake) {
        return new MemoryMaintenance(store, fake, JadeSettings.defaults().memory(), ignored -> { }, warnings::add, false);
    }
    private static ModelResponse reply(String body) { return new ModelResponse(Message.assistant(List.of(new TextBlock(body))), null, "end_turn"); }
    private static ModelResponse call(String id, String tool, ObjectNode input) {
        return new ModelResponse(Message.assistant(List.of(new ToolUseBlock(id, tool, input))), null, "tool_use");
    }
    private void enqueue(MemoryMaintenance memory, String task) throws Exception {
        memory.enqueue("session", task, null, 0, 0, List.of(Message.userText("Keep the project on Java 21"),
                Message.assistant(List.of(new TextBlock("Verified pom and compiler output")))), List.of(), "completed");
    }

    /** 提取后的工具循环由替身按真实 schema 回答，来源 ID 从输入读取，不硬编码磁盘路径。 */
    private static final class Curator implements LlmClient {
        int extractions, consolidations;
        final List<CompleteRequest> requests = new ArrayList<>();
        String extraction = "{\"rollout_summary\":\"# Task\\nUser requested Java 21 for this project; compiler verified.\",\"rollout_slug\":\"java21-project\"}";
        boolean badTool;
        public ModelResponse complete(CompleteRequest request) {
            requests.add(request);
            if (request.tools().isEmpty()) { extractions++; return reply(extraction); }
            consolidations++;
            try {
                JsonNode input = Json.mapper().readTree(request.messages().getFirst().extractText());
                String source = input.path("sources").isEmpty() ? "" : input.path("sources").get(0).path("source").asText();
                if (badTool) return call("bad", "bash", Json.mapper().createObjectNode().put("command", "must-not-run"));
                if (request.messages().size() == 1 && !source.isEmpty())
                    return call("read", "read_source", Json.mapper().createObjectNode().put("source", source));
                if (request.messages().size() <= 3) {
                    String link = source.isEmpty() ? "pending/" + input.path("pending_notes").get(0).path("id").asText() + ".json" : source;
                    return call("stage", "stage_memory", Json.mapper().createObjectNode().put("scope", "workspace")
                            .put("summary", "# Memory routes\nJava 21 applies to this workspace; see MEMORY.md.\n")
                            .put("handbook", "# Project\nUse Java 21. [evidence](" + link + ")\n"));
                }
                return reply("Completed");
            } catch (Exception invalid) { throw new AssertionError(invalid); }
        }
    }

    @Test void extractsConsolidatesAndSkipsUnchangedInputs() throws Exception {
        var store = store(); var fake = new Curator();
        try (var memory = service(store, fake)) {
            assertTrue(memory.reference().contains("Memory root:")); assertEquals(0, fake.extractions);
            enqueue(memory, "turn"); memory.maintain();
            assertEquals(1, fake.extractions); assertEquals(3, fake.consolidations);
            assertTrue(store.snapshot().workspaceHandbook().contains("Java 21"));
            assertEquals("done", store.jobs().getFirst().path("status").asText());
            assertTrue(fake.requests.getFirst().tools().isEmpty());
            assertTrue(fake.requests.getFirst().system().contains("Write task history, not a user profile"));
            memory.maintain(); assertEquals(1, fake.extractions); assertEquals(3, fake.consolidations);
        }
    }

    @Test void emptyExtractionIsSuccessfulAndDoesNotConsolidate() throws Exception {
        var store = store(); var fake = new Curator(); fake.extraction = "{\"rollout_summary\":\"\",\"rollout_slug\":\"\"}";
        try (var memory = service(store, fake)) {
            enqueue(memory, "empty"); memory.maintain();
            assertTrue(store.materials().isEmpty()); assertEquals(0, fake.consolidations);
            assertEquals("done", store.jobs().getFirst().path("status").asText());
        }
    }

    @Test void rejectsExtraFieldsAndPersistsRetryAcrossRestart() throws Exception {
        var store = store(); var fake = new Curator(); fake.extraction = "{\"rollout_summary\":\"\",\"rollout_slug\":\"\",\"preference\":\"invented\"}";
        try (var memory = service(store, fake)) { enqueue(memory, "bad"); memory.maintain(); }
        assertEquals("retry", store.jobs().getFirst().path("status").asText()); assertTrue(store.materials().isEmpty());
        try (var restarted = service(store, fake)) { restarted.maintain(); }
        assertEquals(1, fake.extractions, "持久退避阻止重启立即重发失败请求");
        var job = store.jobs().getFirst(); job.put("retry_at", 0); store.saveJob(job); fake.extraction = "{\"rollout_summary\":\"\",\"rollout_slug\":\"\"}";
        try (var restarted = service(store, fake)) { restarted.maintain(); }
        assertEquals("done", store.jobs().getFirst().path("status").asText()); assertEquals(2, fake.extractions);
    }

    @Test void rejectsTruncatedAndFencedExtraction() throws Exception {
        var store = store();
        for (boolean truncated : List.of(true, false)) {
            LlmClient fake = req -> new ModelResponse(Message.assistant(List.of(new TextBlock(truncated
                    ? "{\"rollout_summary\":\"\",\"rollout_slug\":\"\"}" : "```json\n{\"rollout_summary\":\"\",\"rollout_slug\":\"\"}\n```"))), null,
                    truncated ? "max_tokens" : "end_turn");
            try (var memory = service(store, fake)) { enqueue(memory, "bad-" + truncated); memory.maintain(); }
        }
        assertTrue(store.materials().isEmpty());
        assertTrue(store.jobs().stream().allMatch(n -> n.path("status").asText().equals("retry")));
    }

    @Test void unavailableToolCannotPublishAndMaintenanceRetryIsPersistent() throws Exception {
        var store = store(); var fake = new Curator(); fake.badTool = true;
        try (var memory = service(store, fake)) {
            enqueue(memory, "turn");
            assertThrows(IOException.class, memory::maintain);
            assertEquals(MemoryStore.EMPTY_HANDBOOK, store.snapshot().handbook());
            memory.maintain(); assertEquals(1, fake.consolidations);
        }
        try (var memory = service(store, fake)) { memory.maintain(); assertEquals(1, fake.consolidations); }
    }

    @Test void globalStorePreservesOtherWorkspaceAndRefusesStalePublication() throws Exception {
        var first = store(); first.initialize();
        var second = new MemoryStore(first.root(), Files.createDirectory(temporary.resolve("other")), warnings::add);
        second.initialize(); var stale = first.snapshot();
        second.publish(second.snapshot(), Map.of(second.scope(), new MemoryStore.Artifacts("# Memory routes\nOther index", "Other facts")), "other", List.of());
        assertThrows(IOException.class, () -> first.publish(stale, Map.of(), "stale", List.of()));
        first.publish(first.snapshot(), Map.of(first.scope(), new MemoryStore.Artifacts("# Memory routes\nFirst index", "First facts")), "first", List.of());
        assertEquals("Other facts", second.snapshot().workspaceHandbook());
        assertTrue(first.snapshot().handbook().contains("First facts")); assertTrue(first.snapshot().handbook().contains("Other facts"));
        try (var memory = service(first, req -> { throw new AssertionError("index must not call model"); })) {
            assertFalse(memory.reference().contains("Other index"));
        }
    }

    @Test void externalHandbookChangeIsPreserved() throws Exception {
        var store = store(); store.initialize(); var snapshot = store.snapshot();
        Files.writeString(store.root().resolve("MEMORY.md"), "manual correction");
        assertThrows(IOException.class, () -> store.publish(snapshot, Map.of(), "new", List.of()));
        assertEquals("manual correction", Files.readString(store.root().resolve("MEMORY.md")));
    }

    @Test void unrecognizedGlobalHandbookCannotBeOverwrittenByEmptyInitialization() throws Exception {
        var store = store(); Files.createDirectories(store.root());
        Files.writeString(store.root().resolve("MEMORY.md"), "Existing user handbook");
        assertThrows(IOException.class, store::initialize);
        assertEquals("Existing user handbook", Files.readString(store.root().resolve("MEMORY.md")));
        assertFalse(Files.exists(store.root().resolve("memory_manifest.json")));
    }

    @Test void stagedCandidateFollowedByTruncationDoesNotPublish() throws Exception {
        var store = store(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var memory = service(store, request -> calls.incrementAndGet() == 1
                ? call("stage", "stage_memory", Json.mapper().createObjectNode().put("scope", "workspace")
                    .put("summary", "# Memory routes\nCandidate").put("handbook", "Candidate fact"))
                : new ModelResponse(Message.assistant(List.of(new TextBlock("partial"))), null, "max_tokens"))) {
            memory.begin("session", "turn", "Remember a project fact"); memory.note("remember", "Candidate fact", "turn");
            assertThrows(IOException.class, memory::maintain);
            assertEquals(MemoryStore.EMPTY_HANDBOOK, store.snapshot().handbook());
            assertTrue(store.snapshot().processedNotes().isEmpty());
        }
    }

    @Test void interruptedTransactionRestoresWholePriorGeneration() throws Exception {
        var store = store(); store.initialize(); var old = store.snapshot();
        MemoryTransaction.prepare(store.root(), Map.of("memory_summary.md", old.summary(),
                        "memory_manifest.json", Files.readString(store.root().resolve("memory_manifest.json"))),
                Map.of("memory_summary.md", "partial", "memory_manifest.json", "{}"), "new handbook");
        Files.writeString(store.root().resolve("memory_summary.md"), "partial");
        assertEquals(old.summary(), store.snapshot().summary()); assertEquals(old.handbook(), store.snapshot().handbook());
        assertFalse(warnings.isEmpty());
    }

    @Test void legacyIsCopiedUnchangedAndNotAdmitted() throws Exception {
        var store = store(); Path legacy = Files.createDirectories(store.workspace().resolve(".jadecode/memory"));
        String original = "用户似乎喜欢 Python。其实只让演示了一段 Python。";
        Files.writeString(legacy.resolve("python.md"), original);
        try (var memory = service(store, req -> { throw new AssertionError(); })) { memory.reference(); }
        assertEquals(original, Files.readString(legacy.resolve("python.md")));
        assertEquals(original, Files.readString(store.root().resolve("legacy/" + store.scope() + "/" + MemoryStore.hash(original) + ".md")));
        assertEquals(MemoryStore.EMPTY_HANDBOOK, store.snapshot().handbook()); assertTrue(store.materials().isEmpty());
    }

    @Test void symlinkedRootAndSourceTraversalAreRejected() throws Exception {
        Path real = Files.createDirectory(temporary.resolve("real")); Path link = temporary.resolve("link"); Files.createSymbolicLink(link, real);
        var store = new MemoryStore(link.resolve("memory"), temporary, warnings::add);
        assertThrows(IOException.class, store::initialize);
        var regular = store(); regular.initialize(); assertThrows(IOException.class, () -> regular.readSource("../secret"));
        assertFalse(regular.readable(regular.root().resolve("state/jobs/x.json")));
    }

    @Test void notesPreserveActualRequestAndFeedbackIsDeduplicated() throws Exception {
        var store = store(); var fake = new Curator();
        try (var memory = service(store, fake)) {
            memory.reference(); memory.begin("session", "turn", "只对当前项目记住 Java 21，别把这当全局偏好");
            assertThrows(IOException.class, () -> memory.note("remember", "Java 21", "different-turn"));
            memory.note("remember", "Use Java 21 here", "turn"); memory.note("remember", "Use Java 21 here", "turn");
            assertEquals(1, store.notes().size()); assertEquals(1, store.feedback().size());
            assertTrue(store.notes().getFirst().path("user_request").asText().contains("别把这当全局偏好"));
            assertEquals(MemoryStore.EMPTY_HANDBOOK, store.snapshot().handbook());
            memory.maintain(); assertTrue(store.snapshot().workspaceHandbook().contains("Java 21"));
            var read = new ToolUseBlock("read-call", "read_file", Json.mapper().createObjectNode().put("path", store.root().resolve("MEMORY.md").toString()));
            Message success = Message.userBlocks(List.of(new ToolResultBlock("read-call", "Read handbook body", false)));
            memory.observe(List.of(read), success); memory.observe(List.of(read), success);
            memory.cited(Message.assistant(List.of(new TextBlock("[source](" + store.root().resolve("MEMORY.md") + ") [unread](rollout_summaries/" + "a".repeat(64) + ".md)"))));
            assertEquals(3, store.feedback().size());
        }
    }

    @Test void maintenanceLockPreventsDuplicateModelCalls() throws Exception {
        var store = store(); var fake = new Curator();
        try (var memory = service(store, fake)) {
            enqueue(memory, "turn");
            try (var channel = FileChannel.open(store.root().resolve(".maintenance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.lock()) { memory.maintain(); assertEquals(0, fake.extractions); }
            memory.maintain(); assertEquals(1, fake.extractions);
        }
    }

    @Test void closingDuringModelCallPreventsLatePublication() throws Exception {
        var store = store(); MemoryMaintenance[] holder = new MemoryMaintenance[1];
        holder[0] = service(store, request -> { holder[0].close(); return reply("{\"rollout_summary\":\"Late reply\",\"rollout_slug\":\"late\"}"); });
        enqueue(holder[0], "late"); holder[0].maintain();
        assertTrue(store.materials().isEmpty()); assertEquals("running", store.jobs().getFirst().path("status").asText());
    }
}
