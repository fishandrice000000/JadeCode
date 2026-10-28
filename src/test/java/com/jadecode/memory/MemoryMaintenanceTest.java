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
        return new MemoryStore(new com.jadecode.config.WorkspacePaths(workspace, new com.jadecode.config.JadePaths(temporary.resolve("home/.jadecode"))), warnings::add);
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
        var second = new MemoryStore(new com.jadecode.config.WorkspacePaths(Files.createDirectory(temporary.resolve("other")), first.paths().jade()), warnings::add);
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

    @Test void projectLocalMemoryIsIgnoredAndLeftUntouched() throws Exception {
        var store = store(); Path legacy = Files.createDirectories(store.workspace().resolve(".jadecode/memory"));
        String original = "用户似乎喜欢 Python。其实只让演示了一段 Python。";
        Files.writeString(legacy.resolve("python.md"), original);
        try (var memory = service(store, req -> { throw new AssertionError(); })) { memory.reference(); }
        assertEquals(original, Files.readString(legacy.resolve("python.md")));
        assertFalse(Files.exists(store.root().resolve("legacy")));
        assertEquals(MemoryStore.EMPTY_HANDBOOK, store.snapshot().handbook()); assertTrue(store.materials().isEmpty());
    }

    @Test void symlinkedRootAndSourceTraversalAreRejected() throws Exception {
        Path real = Files.createDirectory(temporary.resolve("real")); Path link = temporary.resolve("link"); Files.createSymbolicLink(link, real);
        var store = new MemoryStore(new com.jadecode.config.WorkspacePaths(temporary, new com.jadecode.config.JadePaths(link)), warnings::add);
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

    /** 无知识增量只确认基线：Markdown 的内容和修改时间不变，完成状态可以跨启动恢复。 */
    @Test void noChangeAcknowledgesInputsWithoutRewritingKnowledge() throws Exception {
        var store = store(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var memory = service(store, request -> { calls.incrementAndGet(); return reply("No durable change."); })) {
            memory.begin("session", "turn", "这是一次临时演示，没有长期约束");
            memory.note("remember", "Temporary demo", "turn");
            Path handbook = store.root().resolve("MEMORY.md"), summary = store.root().resolve("memory_summary.md");
            var originalTime = java.nio.file.attribute.FileTime.fromMillis(1_000);
            Files.setLastModifiedTime(handbook, originalTime); Files.setLastModifiedTime(summary, originalTime);
            memory.maintain();
            assertEquals(MemoryStore.EMPTY_HANDBOOK, Files.readString(handbook));
            assertEquals(MemoryStore.EMPTY_SUMMARY, Files.readString(summary));
            assertEquals(originalTime, Files.getLastModifiedTime(handbook));
            assertEquals(originalTime, Files.getLastModifiedTime(summary));
            assertEquals(1, store.snapshot().processedNotes().size());
            assertEquals("completed", store.maintenanceState().path("status").asText());
            assertFalse(store.maintenanceState().path("content_changed").asBoolean());
            memory.maintain(); assertEquals(1, calls.get());
            // 模拟已发布后、完成状态落盘前退出；再次启动依据发布基线修复，不重发请求。
            var interrupted = store.maintenanceState().put("status", "running"); store.maintenanceState(interrupted);
            memory.maintain(); assertEquals(1, calls.get());
            assertEquals("completed", store.maintenanceState().path("status").asText());
        }
    }

    /** 已处理来源仍可按名读取，但下一批输入只包含新增元数据，避免逐轮重讲旧任务。 */
    @Test void consolidationReceivesOnlyNewSourceMetadata() throws Exception {
        var store = store(); var fake = new Curator();
        try (var memory = service(store, fake)) {
            enqueue(memory, "first"); memory.maintain();
            String first = store.snapshot().processedMaterials().getFirst();
            enqueue(memory, "second"); memory.maintain();
            var starts = fake.requests.stream().filter(r -> !r.tools().isEmpty() && r.messages().size() == 1).toList();
            assertEquals(2, starts.size());
            var input = Json.mapper().readTree(starts.getLast().messages().getFirst().extractText());
            assertEquals(1, input.path("sources").size());
            assertNotEquals(first, input.path("sources").get(0).path("id").asText());
            assertTrue(input.path("available_sources").toString().contains(first));
            assertEquals(2, store.snapshot().processedMaterials().size());
        }
    }

    /** 重现真实故障：另一个项目应能保留并按清单阅读已发布的全局纠正来源。 */
    @Test void inheritedGlobalPendingReferenceCanBeReadAndRepublishedAcrossProjects() throws Exception {
        var first = store(); first.initialize();
        String noteId = MemoryStore.hash("global-preference"), inherited = "pending/" + noteId + ".json";
        first.note(noteId, "correct", "Simple replies should be brief", "以后简单问题只给结论", "session", "turn");
        String global = "Use Chinese and brief simple replies. [correction](" + inherited + ")";
        first.publish(first.snapshot(), Map.of("global", new MemoryStore.Artifacts("Global response preference", global),
                first.scope(), new MemoryStore.Artifacts("First project", "First project must stay offline")), "first", List.of(inherited));
        String hiddenId = MemoryStore.hash("unpublished-foreign-note");
        first.note(hiddenId, "remember", "Unpublished foreign note", "只对第一个项目保留", "session", "other");
        var second = new MemoryStore(new com.jadecode.config.WorkspacePaths(Files.createDirectory(temporary.resolve("second-project")), first.paths().jade()), warnings::add);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var memory = service(second, request -> {
            int step = calls.incrementAndGet();
            try {
                var input = Json.mapper().readTree(request.messages().getFirst().extractText());
                assertTrue(input.path("available_sources").toString().contains(inherited));
                assertFalse(input.path("available_sources").toString().contains(hiddenId));
                if (step == 1) return call("read-global", "read_source", Json.mapper().createObjectNode().put("source", inherited));
                if (step == 2) {
                    assertTrue(request.messages().getLast().content().toString().contains("以后简单问题只给结论"));
                    String local = "pending/" + input.path("pending_notes").get(0).path("id").asText() + ".json";
                    return new ModelResponse(Message.assistant(List.of(
                            new ToolUseBlock("global", "stage_memory", Json.mapper().createObjectNode().put("scope", "global")
                                    .put("summary", "Global response preference").put("handbook", global)),
                            new ToolUseBlock("local", "stage_memory", Json.mapper().createObjectNode().put("scope", "workspace")
                                    .put("summary", "Second project database").put("handbook", "Use PostgreSQL here. [decision](" + local + ")"))
                    )), null, "tool_use");
                }
                return reply("Completed");
            } catch (IOException failure) { throw new AssertionError(failure); }
        })) {
            memory.begin("second-session", "second-turn", "第二个项目用 PostgreSQL");
            memory.note("remember", "Use PostgreSQL in second project", "second-turn"); memory.maintain();
            assertTrue(second.snapshot().workspaceHandbook().contains("PostgreSQL"));
            assertEquals(global, second.snapshot().globalHandbook());
            assertEquals("First project must stay offline", first.snapshot().workspaceHandbook());
            assertEquals("completed", second.maintenanceState().path("status").asText());
        }
    }

    /** 未发布的其它项目候选仍不允许引用，失败保留旧知识并明确记录重试耗尽。 */
    @Test void unknownPendingReferenceIsRejectedAndFailureExhaustionIsVisible() throws Exception {
        var store = store(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var memory = service(store, request -> {
            calls.incrementAndGet();
            return call("unknown", "stage_memory", Json.mapper().createObjectNode().put("scope", "global")
                    .put("summary", "must not publish").put("handbook", "[unknown](pending/" + "f".repeat(64) + ".json)"));
        })) {
            memory.begin("session", "turn", "Remember a project fact"); memory.note("remember", "A project fact", "turn");
            for (int attempt = 1; attempt <= 3; attempt++) {
                assertThrows(IOException.class, memory::maintain);
                assertEquals(attempt < 3 ? "retry" : "exhausted", store.maintenanceState().path("status").asText());
                assertEquals(MemoryStore.EMPTY_HANDBOOK, store.snapshot().handbook());
                assertTrue(store.snapshot().processedNotes().isEmpty());
                // 测试主动推进持久退避时间，验证状态转移而不真实等待三分钟。
                var state = store.maintenanceState().put("retry_at", 0); store.maintenanceState(state);
            }
            memory.maintain(); assertEquals(3, calls.get());
        }
    }

    /** pending 读取同样拒绝伪造身份与符号链接，不能因修复继承而扩大文件访问。 */
    @Test void pendingSourceIdentityAndSymlinkAreChecked() throws Exception {
        var store = store(); store.initialize(); String id = MemoryStore.hash("note");
        String ref = "pending/" + id + ".json";
        store.note(id, "correct", "Use Chinese", "请用中文", "session", "turn");
        assertTrue(store.readSource(ref).contains("请用中文"));
        Path file = store.root().resolve(ref);
        var invalid = (ObjectNode) Json.mapper().readTree(Files.readString(file)); invalid.put("id", "f".repeat(64));
        Files.writeString(file, invalid.toString()); assertThrows(IOException.class, () -> store.readSource(ref));
        Files.delete(file); Path outside = Files.writeString(temporary.resolve("outside.json"), "must-not-read");
        Files.createSymbolicLink(file, outside); assertThrows(IOException.class, () -> store.readSource(ref));
    }

    /** 即使提取模型误写了要求，整理器也拿得到独立原话，能区分非零协议与助手选定的 2。 */
    @Test void curatorGetsPrimaryUserEvidenceIndependentlyOfExtractedClaims() throws Exception {
        var store = store(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var sessions = new SessionStore(store.paths(), warnings::add);
        try (var opened = sessions.create("offline"); var memory = service(store, request -> {
            calls.incrementAndGet();
            if (request.tools().isEmpty()) return reply("{\"rollout_summary\":\"The user required exit code 2.\",\"rollout_slug\":\"incorrect-claim\"}");
            try {
                var input = Json.mapper().readTree(request.messages().getFirst().extractText());
                String source = input.path("sources").get(0).path("source").asText();
                var human = input.path("source_user_requests").path(source);
                assertTrue(human.path("complete").asBoolean());
                assertTrue(human.path("requests").toString().contains("只要求非零"));
                assertFalse(human.path("requests").toString().contains("必须是 2"));
                return reply("The report overstates the user's contract; no durable change.");
            } catch (IOException failure) { throw new AssertionError(failure); }
        })) {
            var journal = opened.journal(); long start = journal.sequence();
            journal.append(JournalEntry.Kind.APPEND, List.of(Message.userText("外部协议只要求非零，具体失败码由实现选择")), Json.mapper().createArrayNode(), "request");
            journal.append(JournalEntry.Kind.APPEND, List.of(Message.assistant(List.of(new TextBlock("我选择 2，但并不是用户说必须是 2")))), Json.mapper().createArrayNode(), "reply");
            memory.enqueue("session", "task", journal.eventsPath(), start, journal.sequence(), List.of(), List.of(), "completed");
            memory.maintain(); assertEquals(2, calls.get());
            assertEquals(MemoryStore.EMPTY_HANDBOOK, store.snapshot().handbook());
        }
    }

    @Test void closingDuringModelCallPreventsLatePublication() throws Exception {
        var store = store(); MemoryMaintenance[] holder = new MemoryMaintenance[1];
        holder[0] = service(store, request -> { holder[0].close(); return reply("{\"rollout_summary\":\"Late reply\",\"rollout_slug\":\"late\"}"); });
        enqueue(holder[0], "late"); holder[0].maintain();
        assertTrue(store.materials().isEmpty()); assertEquals("running", store.jobs().getFirst().path("status").asText());
    }
}
