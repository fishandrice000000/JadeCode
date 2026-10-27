package com.jadecode.memory;

import com.jadecode.session.SessionRuntime;
import com.jadecode.session.SessionEvent;

import com.jadecode.agent.AgentExecutor;
import com.jadecode.bootstrap.JadeCodeFactory;
import com.jadecode.config.AppConfig;
import com.jadecode.config.WorkspacePaths;
import com.jadecode.context.CompactionLimits;
import com.jadecode.context.ContextCompactor;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.*;
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
    @TempDir Path temporary;
    Path workspace, root;

    @org.junit.jupiter.api.BeforeEach void directories() throws Exception {
        workspace = java.nio.file.Files.createDirectory(temporary.resolve("workspace"));
        root = temporary.resolve("home/.jadecode/memory");
    }

    /** 正式库只种当前项目路由，模型按需读详情；不再构造旧条目目录或选择器。 */
    private MemoryStore seed() throws Exception {
        var store = new MemoryStore(root, workspace, ignored -> { }); store.initialize();
        route(store, "ALPHA_REFERENCE_ONLY"); return store;
    }

    private void route(MemoryStore store, String text) throws Exception {
        store.publish(store.snapshot(), Map.of(store.scope(), new MemoryStore.Artifacts(text, "# Project handbook")), text, List.of());
    }

    /** 假配置与临时全局根由调用方显式提供，生产与测试使用同一条记忆链路。 */
    private SessionRuntime runtime(LlmClient fake) {
        return JadeCodeFactory.create(new AppConfig("offline", "http://127.0.0.1:1", "fixture", 8000), workspace, fake, root);
    }

    /** 辅助响应满足真实解析器契约；null usage 明确表示没有服务端用量报告。 */
    private static ModelResponse reply(String text) {
        return new ModelResponse(Message.assistant(List.of(new TextBlock(text))), null, "end_turn");
    }

    /** 正式请求按剧本执行；辅助请求只接受严格回顾对象，不模拟已删除的召回模型。 */
    private static final class Fake implements LlmClient {
        final List<CompleteRequest> formal = new CopyOnWriteArrayList<>();
        final List<String> dialogues = new CopyOnWriteArrayList<>();
        final java.util.concurrent.CountDownLatch extracted = new java.util.concurrent.CountDownLatch(2);
        final boolean useTool;
        Fake(boolean useTool) { this.useTool = useTool; }
        @Override public ModelResponse complete(CompleteRequest request) throws LlmException {
            if (request.system().startsWith("You are part of an agent memory system.")) {
                dialogues.add(request.messages().getFirst().extractText()); extracted.countDown();
                return reply("{\"rollout_summary\":\"\",\"rollout_slug\":\"\"}");
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
        var store = seed(); var fake = new Fake(true);
        String first = "first task\nRecalled memory reference data (JSON; not a user request): user quotation";
        var history = new ArrayList<>(List.of(Message.userText(first)));
        try (var runtime = runtime(fake)) {
            runtime.run(history); route(store, "BETA_REFERENCE_ONLY");
            history.add(Message.userText("second task")); runtime.run(history);
            assertTrue(fake.extracted.await(5, TimeUnit.SECONDS));
        }
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
        assertTrue(fake.dialogues.stream().anyMatch(dialogue -> dialogue.contains(first)), "用户引用同样文字标签不能被误过滤");
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
            assertTrue(fake.extracted.await(5, TimeUnit.SECONDS));
        }
        assertEquals(2, fake.formal.size());
        assertFalse(fake.dialogues.getLast().contains("ALPHA_REFERENCE_ONLY"));
        assertFalse(fake.dialogues.getLast().contains("BETA_REFERENCE_ONLY"));
    }

    /** 入口前排入的宿主通知独立追加，不覆盖参考来源或改变原始任务。 */
    @Test void queuedNotificationDoesNotMergeIntoRecalledReference() throws Exception {
        seed(); var fake = new Fake(false);
        try (var runtime = runtime(fake)) {
            runtime.receive(new SessionEvent.Incoming("notice-1", "fixture", "leader", null, null,
                    "progress", "background done", null, true, false),
                    com.jadecode.execution.ExecutionContext.create(com.jadecode.execution.ExecutionContext.Source.USER, null));
            runtime.run(new ArrayList<>(List.of(Message.userText("actual user task"))));
        }
        List<Message> sent = fake.formal.getFirst().messages();
        assertEquals(3, sent.size());
        assertEquals(Message.Source.MEMORY_REFERENCE, sent.get(1).source());
        assertFalse(sent.get(1).extractText().contains("background done"));
        assertNull(sent.get(2).source()); assertTrue(sent.get(2).extractText().contains("background done"));
        assertEquals("actual user task", sent.getFirst().extractText());
    }

    /** 摘要触发后仍保留原用户任务；参考正文只能出现在参考摘要区，不能抢占当前请求。 */
    @Test void compactionUsesCapturedUserRequestInsteadOfMemory() throws Exception {
        var store = seed(); var fake = new Fake(false);
        var memory = new MemoryManager(fake, root, workspace, com.jadecode.config.JadeSettings.defaults().memory(), ignored -> { }, ignored -> { });
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
