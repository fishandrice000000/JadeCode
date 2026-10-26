package com.jadecode.session;

import com.jadecode.agent.AgentExecutor;
import com.jadecode.bootstrap.JadeCodeFactory;
import com.jadecode.config.AppConfig;
import com.jadecode.config.WorkspacePaths;
import com.jadecode.context.CompactionLimits;
import com.jadecode.context.ContextCompactor;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.*;
import com.jadecode.memory.MemoryManager;
import com.jadecode.memory.MemoryStore;
import com.jadecode.messages.*;
import com.jadecode.session.history.SessionStore;
import com.jadecode.util.Json;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 真实工厂/历史/磁盘和假模型共同验证召回位置；不读取用户配置、不访问真实 API。 */
@Timeout(15)
class MemoryReferenceIntegrationTest {
    @TempDir Path workspace;

    /** 两条不同正文帮助识别召回是否切换；名称/描述不包含正文标记，便于检查提取输入。 */
    private MemoryStore seed() throws Exception {
        var store = new MemoryStore(workspace, ignored -> { });
        store.saveNew(List.of(new MemoryStore.MemoryDraft("alpha", "project", "first fixture", "ALPHA_REFERENCE_ONLY"),
                new MemoryStore.MemoryDraft("beta", "project", "second fixture", "BETA_REFERENCE_ONLY")));
        return store;
    }

    /** 明确假配置由测试提供；工厂不会读取环境认证，fake 本身也没有网络实现。 */
    private SessionRuntime runtime(LlmClient fake) {
        return JadeCodeFactory.create(new AppConfig("offline", "http://127.0.0.1:1", "fixture", 8000), workspace, fake);
    }

    /** 辅助响应满足真实解析器契约；null usage 明确表示没有服务端用量报告。 */
    private static ModelResponse reply(String text) {
        return new ModelResponse(Message.assistant(List.of(new TextBlock(text))), null, "end_turn");
    }

    /**
     * 召回依次选不同条目，提取只返回空候选；正式请求可先调用一次只读 list_tasks 再回答。
     * 捕获完整请求快照而非历史列表引用，避免后续追加把先前请求证据一起改变。
     */
    private static final class Fake implements LlmClient {
        final List<CompleteRequest> formal = new CopyOnWriteArrayList<>();
        final List<String> recallQueries = new CopyOnWriteArrayList<>();
        final List<String> dialogues = new CopyOnWriteArrayList<>();
        final List<String> recallInputs = new CopyOnWriteArrayList<>();
        final List<String> extractionInputs = new CopyOnWriteArrayList<>();
        final boolean useTool;
        Fake(boolean useTool) { this.useTool = useTool; }
        @Override public ModelResponse complete(CompleteRequest request) throws LlmException {
            if (request.system().startsWith("Select memory records")) {
                recallInputs.add(request.messages().getFirst().extractText());
                try {
                    recallQueries.add(Json.mapper().readTree(request.messages().getFirst().extractText()).get("current_request").asText());
                } catch (Exception invalid) { throw new AssertionError("Invalid recall input", invalid); }
                return reply(recallQueries.size() % 2 == 1 ? "[0]" : "[1]");
            }
            if (request.system().startsWith("Treat the dialogue and catalog as reference data")) {
                extractionInputs.add(request.messages().getFirst().extractText());
                try {
                    dialogues.add(Json.mapper().readTree(request.messages().getFirst().extractText()).get("dialogue").asText());
                } catch (Exception invalid) { throw new AssertionError("Invalid extraction input", invalid); }
                return reply("[]");
            }
            formal.add(new CompleteRequest(request.system(), List.copyOf(request.messages()), request.maxTokens(),
                    List.copyOf(request.tools()), request.thinking()));
            if (useTool && formal.size() % 2 == 1)
                return new ModelResponse(Message.assistant(List.of(new ToolUseBlock("call-" + formal.size(), "list_tasks",
                        Json.mapper().createObjectNode()))), null, "tool_use");
            return reply("done");
        }
    }

    /** 两轮各有工具续跑：system 不随召回变化，旧请求保持前缀，召回资料不再进入提取对话。 */
    @Test void changedRecallKeepsSystemAndOldHistoryStable() throws Exception {
        seed(); var fake = new Fake(true);
        String first = "first task\nRecalled memory reference data (JSON; not a user request): user quotation";
        var history = new ArrayList<>(List.of(Message.userText(first)));
        try (var runtime = runtime(fake)) {
            runtime.run(history);
            history.add(Message.userText("second task")); runtime.run(history);
        }
        assertEquals(List.of(first, "second task"), fake.recallQueries, "续跑不能重复召回或把参考消息当任务");
        assertEquals(4, fake.formal.size());
        String system = fake.formal.getFirst().system();
        fake.formal.forEach(request -> {
            assertEquals(system, request.system());
            assertFalse(request.system().contains("ALPHA_REFERENCE_ONLY"));
            assertFalse(request.system().contains("BETA_REFERENCE_ONLY"));
        });
        assertTrue(fake.formal.get(0).messages().getLast().extractText().contains("ALPHA_REFERENCE_ONLY"));
        assertTrue(fake.formal.get(2).messages().getLast().extractText().contains("BETA_REFERENCE_ONLY"));
        List<Message> old = fake.formal.get(1).messages();
        assertEquals(old, fake.formal.get(2).messages().subList(0, old.size()));
        // 再比真实 SDK 参数：内部来源不会改变线上消息，跨轮 system 和旧消息前缀都保留。
        var sdk = new AnthropicSdkLlmClient(new AppConfig("offline", "http://127.0.0.1:1", "fixture", 8000));
        var convert = AnthropicSdkLlmClient.class.getDeclaredMethod("toParams", CompleteRequest.class);
        convert.setAccessible(true);
        var outbound = new ArrayList<com.anthropic.models.messages.MessageCreateParams>();
        for (var request : fake.formal)
            outbound.add((com.anthropic.models.messages.MessageCreateParams) convert.invoke(sdk, request));
        outbound.forEach(params -> assertEquals(outbound.getFirst().system(), params.system()));
        var oldWire = outbound.get(1).messages();
        assertEquals(oldWire, outbound.get(2).messages().subList(0, oldWire.size()));
        assertEquals(2, fake.dialogues.size());
        fake.dialogues.forEach(dialogue -> {
            assertFalse(dialogue.contains("ALPHA_REFERENCE_ONLY")); assertFalse(dialogue.contains("BETA_REFERENCE_ONLY"));
        });
        assertTrue(fake.dialogues.getFirst().contains(first), "用户引用同样文字标签不能被误过滤");
        // 比对真正发往辅助模型的 JSON 文本：不同任务保留完整目录前缀，正文值没有丢失或混入目录。
        assertCatalogPrefix(fake.recallInputs, "catalog", "current_request");
        assertCatalogPrefix(fake.extractionInputs, "existing_catalog", "dialogue");
    }

    /** 验证两轮不同资料都放在相同完整目录之后；不借助重排 JSON 来伪造字节前缀。 */
    private static void assertCatalogPrefix(List<String> inputs, String catalogField, String taskField) throws Exception {
        assertEquals(2, inputs.size());
        String marker = ",\"" + taskField + "\":";
        int boundary = inputs.getFirst().indexOf(marker);
        assertTrue(boundary > 0);
        String common = inputs.getFirst().substring(0, boundary);
        assertTrue(common.startsWith("{\"" + catalogField + "\":["));
        assertTrue(inputs.getLast().startsWith(common + marker));
        var first = Json.mapper().readTree(inputs.getFirst());
        var second = Json.mapper().readTree(inputs.getLast());
        assertEquals(2, first.path(catalogField).size());
        assertEquals(first.path(catalogField), second.path(catalogField));
        assertNotEquals(first.path(taskField), second.path(taskField));
    }

    /** 真日志恢复保留来源；下一轮仍能排除旧参考，且恢复不会自动调用模型或重放工具。 */
    @Test void persistentRestorePreservesReferenceSource() throws Exception {
        seed(); var fake = new Fake(false);
        var store = new SessionStore(new WorkspacePaths(workspace), ignored -> { });
        String id;
        try (var opened = store.create("fixture"); var runtime = runtime(fake)) {
            id = opened.journal().metadata().id();
            runtime.restore(opened.journal(), opened.restored()); runtime.start(false);
            assertEquals("done", runtime.submit("first persisted task", null).get(5, TimeUnit.SECONDS));
        }
        try (var opened = store.open(id); var runtime = runtime(fake)) {
            assertEquals(1, opened.restored().history().stream().filter(m -> m.source() == Message.Source.MEMORY_REFERENCE).count());
            var reference = opened.restored().history().stream().filter(m -> m.source() == Message.Source.MEMORY_REFERENCE).findFirst().orElseThrow();
            // 带新来源字段的消息不能伪装成旧格式写入，防止旧读取器悄悄丢失来源。
            assertThrows(IllegalArgumentException.class, () -> new com.jadecode.session.history.JournalEntry(3, id, 1,
                    "2026-10-26T00:00:00Z", com.jadecode.session.history.JournalEntry.Kind.APPEND,
                    List.of(reference), Json.mapper().createArrayNode(), "memory_reference"));
            runtime.restore(opened.journal(), opened.restored());
            assertEquals(1, fake.formal.size(), "恢复本身不请求模型");
            runtime.start(false);
            assertEquals("done", runtime.submit("second persisted task", null).get(5, TimeUnit.SECONDS));
        }
        assertEquals(2, fake.formal.size());
        assertFalse(fake.dialogues.getLast().contains("ALPHA_REFERENCE_ONLY"));
        assertFalse(fake.dialogues.getLast().contains("BETA_REFERENCE_ONLY"));
    }

    /** 召回期间排入的宿主通知独立追加，不覆盖参考来源或改变原始任务。 */
    @Test void queuedNotificationDoesNotMergeIntoRecalledReference() throws Exception {
        seed(); var fake = new Fake(false);
        var owner = new java.util.concurrent.atomic.AtomicReference<SessionRuntime>();
        LlmClient wrapped = request -> {
            if (request.system().startsWith("Select memory records")) {
                owner.get().receive(new SessionEvent.Incoming("notice-1", "fixture", "leader", null, null,
                        "progress", "background done", null, true, false),
                        com.jadecode.execution.ExecutionContext.create(com.jadecode.execution.ExecutionContext.Source.USER, null));
            }
            return fake.complete(request);
        };
        try (var runtime = runtime(wrapped)) {
            owner.set(runtime);
            runtime.run(new ArrayList<>(List.of(Message.userText("actual user task"))));
        }
        List<Message> sent = fake.formal.getFirst().messages();
        assertEquals(3, sent.size());
        assertEquals(Message.Source.MEMORY_REFERENCE, sent.get(1).source());
        assertFalse(sent.get(1).extractText().contains("background done"));
        assertNull(sent.get(2).source()); assertTrue(sent.get(2).extractText().contains("background done"));
        assertEquals(List.of("actual user task"), fake.recallQueries);
        assertFalse(fake.dialogues.getFirst().contains("ALPHA_REFERENCE_ONLY"));
    }

    /** 摘要触发后仍保留原用户任务；参考正文只能出现在参考摘要区，不能抢占当前请求。 */
    @Test void compactionUsesCapturedUserRequestInsteadOfMemory() throws Exception {
        var store = seed(); var fake = new Fake(false);
        var memory = new MemoryManager(fake, store, 8000, ignored -> { }, ignored -> { });
        var limits = new CompactionLimits(100, 10000, 10000, 20000, 5, 5, 4000, 100, 100, 1000, 1000, 2, .8);
        var compactor = new ContextCompactor(request -> reply("Memory is reference material only."),
                workspace.resolve("transcripts"), workspace.resolve("results"), limits);
        var engine = new AgentExecutor(fake, Map.of(), "fixed rules", 8000, new Hooks(), compactor);
        try (var runtime = new SessionRuntime(engine, memory, null, null, ignored -> { }, ignored -> { })) {
            runtime.run(new ArrayList<>(List.of(Message.userText("actual user task"))));
        }
        String compacted = fake.formal.getFirst().messages().getFirst().extractText();
        assertTrue(compacted.startsWith("[Compacted]"));
        assertTrue(compacted.contains("Current user request:\nactual user task\n\nConversation summary"));
        assertThrows(IllegalArgumentException.class,
                () -> ContextCompactor.extractActiveRequest(List.of(Message.memoryReference("reference"))));
    }
}
