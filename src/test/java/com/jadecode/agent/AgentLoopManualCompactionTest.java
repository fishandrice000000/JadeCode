package com.jadecode.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.context.CompactionLimits;
import com.jadecode.context.ContextCompactor;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.LlmClient;
import com.jadecode.llm.LlmException;
import com.jadecode.llm.ModelResponse;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.tools.CompactTool;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentLoop 主动压缩(compact 工具)的父循环集成测试。
 *
 * <p>
 * 覆盖的核心 Agent 行为:成功 compact 调用 → 整批结果正常回灌(交换闭合)→ harness 才调用摘要压缩器 →
 * 新历史原地写回 → 下一次正常请求看到压缩结果。用真实的 {@link ContextCompactor}(注入小阈值)、
 * 假 LlmClient 与 @TempDir,不调用真实 API、不读密钥。
 * </p>
 */
class AgentLoopManualCompactionTest {

    /** 精简摘要输出额度,用来断言摘要请求确实用了注入值 */
    private static final int SUMMARY_MAX_TOKENS = 77;

    /** 与 AgentLoop.REMINDER_MESSAGE 同文案:用于核对 reminder 是否随整段历史进入摘要与归档 */
    private static final String REMINDER = "<reminder>Update your todos.</reminder>";

    @TempDir
    Path workspace;

    private Path transcriptDir;
    private Path toolResultsDir;

    @BeforeEach
    void setUp() {
        transcriptDir = workspace.resolve("transcripts");
        toolResultsDir = workspace.resolve("tool-results");
    }

    /* ---------------- 假客户端与假工具 ---------------- */

    /** 内存假工具:记录调用次数并返回预置输出 */
    private static final class FakeTool implements Tool {
        private final String name;
        private final String output;
        private int calls;

        FakeTool(String name, String output) {
            this.name = name;
            this.output = output;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "fake " + name;
        }

        @Override
        public Map<String, String> properties() {
            return Map.of();
        }

        @Override
        public List<String> required() {
            return List.of();
        }

        @Override
        public String execute(ObjectNode input) {
            calls++;
            return output;
        }
    }

    /**
     * 按"是不是摘要请求"分流的假客户端:
     * 摘要请求(tools 为空)被记录下来并返回预置摘要文本;正常请求按脚本弹出回复。
     * 两者都保存请求<b>快照</b>(messages 是可变列表,不复制会读到跑完之后的状态)。
     */
    private static final class FakeClient implements LlmClient {
        private final List<ModelResponse> replies = new ArrayList<>();
        private final List<CompleteRequest> normalRequests = new ArrayList<>();
        private final List<CompleteRequest> summaryRequests = new ArrayList<>();
        private Runnable onSummary = () -> { };
        private LlmException summaryFailure;

        FakeClient(ModelResponse... replies) {
            this.replies.addAll(List.of(replies));
        }

        @Override
        public ModelResponse complete(CompleteRequest request) throws LlmException {
            CompleteRequest snapshot = new CompleteRequest(request.system(), List.copyOf(request.messages()),
                    request.maxTokens(), request.tools());
            if (request.tools().isEmpty()) {
                summaryRequests.add(snapshot);
                if (summaryFailure != null) {
                    throw summaryFailure;
                }
                onSummary.run();
                return new ModelResponse(Message.assistant(List.of(new TextBlock("摘要"))),
                        new ModelResponse.Usage(0, 0));
            }
            normalRequests.add(snapshot);
            if (replies.isEmpty()) {
                throw new LlmException("假客户端脚本耗尽");
            }
            return replies.removeFirst();
        }
    }

    /* ---------------- 造数据 ---------------- */

    /** 只改本测试关心的阈值:最新批次预算门槛、单项落盘门槛、预览长度与摘要输出额度 */
    private static CompactionLimits limits(int batchLimit, int largeLimit, int previewChars) {
        CompactionLimits d = CompactionLimits.courseDefaults();
        return new CompactionLimits(d.contextCharLimit(), batchLimit, largeLimit, d.summaryInputCharLimit(),
                d.keepRecentResults(), d.keepRecentMessages(), d.microResultMinChars(), previewChars,
                d.fitPreviewChars(), SUMMARY_MAX_TOKENS, d.snipMaxMessages(), d.snipHeadMessages(),
                d.targetRatio());
    }

    private static CompactionLimits defaultLimits() {
        return limits(200_000, 30_000, 2_000);
    }

    private ContextCompactor compactor(LlmClient client) {
        return new ContextCompactor(client, transcriptDir, toolResultsDir, defaultLimits());
    }

    private static ToolUseBlock use(String id, String name) {
        return new ToolUseBlock(id, name, Json.mapper().createObjectNode());
    }

    private static ModelResponse assistant(ContentBlock... blocks) {
        return new ModelResponse(Message.assistant(List.of(blocks)), new ModelResponse.Usage(0, 0));
    }

    /** 取某条消息里的 tool_result 块(按出现顺序) */
    private static List<ToolResultBlock> resultsOf(Message message) {
        return message.blocksOfType(ToolResultBlock.class);
    }

    private List<String> archiveLines() throws IOException {
        try (var files = Files.list(transcriptDir)) {
            Path file = files.findFirst().orElseThrow(() -> new AssertionError("没有 transcript 文件"));
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        }
    }

    private long toolResultFileCount() {
        if (!Files.isDirectory(toolResultsDir)) {
            return 0;
        }
        try (var files = Files.list(toolResultsDir)) {
            return files.count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 取唯一的工具结果落盘文件。
     *
     * <p>
     * 断言"恰好一个"而不是"取第一个":这样测试若意外多写出文件会立即失败,而不是静默读到别的文件。
     * </p>
     */
    private Path onlyToolResultFile() throws IOException {
        try (var files = Files.list(toolResultsDir)) {
            List<Path> all = files.toList();
            assertEquals(1, all.size(), "应当恰好只有一个工具结果落盘文件");
            return all.getFirst();
        }
    }

    /** 归档行数的非受检版本:便于放进 Runnable 形式的摘要回调里,在摘要调用那一刻读取。 */
    private List<String> archiveLinesUnchecked() {
        try {
            return archiveLines();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 是否有任一归档文件包含给定文本(用于核对 reminder 等只出现在某一轮归档里的内容)。 */
    private boolean archiveFilesContain(String needle) throws IOException {
        if (!Files.isDirectory(transcriptDir)) {
            return false;
        }
        try (var files = Files.list(transcriptDir)) {
            for (Path file : files.toList()) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 历史末尾两条是否是"与 {@code calls} 一一对应的闭合交换"。
     *
     * <p>
     * 比对倒数第二条 assistant 里的 tool_use ID 与末条 user 里的 tool_result ID 是否都等于期望的调用
     * 顺序。用它可以在摘要调用那一刻直接确认"结果已经回灌、交换已经闭合",而不只看消息条数。
     * </p>
     */
    private static boolean closedTailMatches(List<Message> messages, List<ToolUseBlock> calls) {
        if (messages.size() < 2 || messages.getLast().role() != Role.USER) {
            return false;
        }
        List<String> expectedIds = calls.stream().map(ToolUseBlock::id).toList();
        List<String> assistantIds = messages.get(messages.size() - 2).blocksOfType(ToolUseBlock.class).stream()
                .map(ToolUseBlock::id).toList();
        List<String> resultIds = resultsOf(messages.getLast()).stream().map(ToolResultBlock::toolUseId).toList();
        return assistantIds.equals(expectedIds) && resultIds.equals(expectedIds);
    }

    /* ---------------- 1. 纯 compact 批次 ---------------- */

    @Test
    void pureCompactBatchCompressesWholeHistoryAfterExchangeCloses() throws Exception {
        FakeClient client = new FakeClient(
                assistant(use("c1", "compact")), // 第一轮:模型请求压缩
                assistant(new TextBlock("完成"))); // 第二轮:压缩后的下一次正常请求
        List<Message> messages = new ArrayList<>(List.of(Message.userText("开始")));
        List<Message> callerList = messages;

        // compactAll 内部先归档、再摘要;摘要回调运行时"历史回填"还没发生,正好可以观察两个事实:
        //  ①整批结果是否已经回灌(交换闭合);②归档文件此刻是否已经写出且内容完整。
        AtomicInteger historySizeWhenSummarizing = new AtomicInteger(-1);
        AtomicBoolean closedExchangeVisibleWhenSummarizing = new AtomicBoolean(false);
        AtomicReference<List<String>> archiveWhenSummarizing = new AtomicReference<>();
        client.onSummary = () -> {
            historySizeWhenSummarizing.set(messages.size());
            closedExchangeVisibleWhenSummarizing.set(closedTailMatches(messages, List.of(use("c1", "compact"))));
            archiveWhenSummarizing.set(archiveLinesUnchecked());
        };
        AgentLoop loop = new AgentLoop(client, Map.of("compact", new CompactTool()), "test-system", 8000,
                new Hooks(), compactor(client));

        loop.run(messages);

        // 摘要调用那一刻的现场:历史里已经是"闭合"的完整交换(assistant 调用 + 对应 tool_result),
        // 说明结果先回灌、压缩动作在闭合之后才发生。
        assertEquals(3, historySizeWhenSummarizing.get(), "摘要调用时本批结果必须已经回灌(交换闭合)");
        assertTrue(closedExchangeVisibleWhenSummarizing.get(),
                "摘要调用时末尾应是调用与结果一一对应的闭合交换");
        // 摘要调用那一刻归档已经写出,且逐行就是那三条完整消息(不是空文件,也不是部分内容)。
        List<String> archiveAtSummary = archiveWhenSummarizing.get();
        assertNotNull(archiveAtSummary);
        assertEquals(3, archiveAtSummary.size(), "摘要调用时归档就已包含完整闭合交换");
        assertTrue(archiveAtSummary.get(1).contains("\"compact\""));
        assertTrue(archiveAtSummary.get(2).contains(CompactTool.OUTPUT));

        // 摘要调用恰好一次,且请求形状正确(专用 prompt / 单条 user / 空 tools / 注入的输出额度)。
        assertEquals(1, client.summaryRequests.size());
        CompleteRequest summary = client.summaryRequests.getFirst();
        assertTrue(summary.system().contains("factual state"));
        assertTrue(summary.tools().isEmpty());
        assertEquals(SUMMARY_MAX_TOKENS, summary.maxTokens());
        assertEquals(1, summary.messages().size());
        assertEquals(Role.USER, summary.messages().getFirst().role());

        // 归档的是"闭合后的完整交换":user → assistant(compact) → user(tool_result),与摘要那一刻一致。
        List<String> lines = archiveLines();
        assertEquals(3, lines.size(), "纯 compact 把整段历史(含刚才那对交换)归档");
        assertEquals(archiveAtSummary, lines, "摘要之后归档内容不应再变化");
        assertTrue(lines.get(1).contains("\"compact\""));
        assertTrue(lines.get(2).contains(CompactTool.OUTPUT));

        // 下一次正常请求看到的是"单条压缩历史"。
        assertEquals(2, client.normalRequests.size());
        List<Message> secondRequest = client.normalRequests.get(1).messages();
        assertEquals(1, secondRequest.size());
        assertTrue(secondRequest.getFirst().extractText().startsWith("[Compacted]"));
        assertTrue(secondRequest.getFirst().extractText().contains("Current user request:\n开始"));

        // 调用方列表身份不变,最终历史 = 压缩消息 + 最后的正常回复。
        assertSame(callerList, messages);
        assertEquals(2, messages.size());
        assertTrue(messages.getFirst().extractText().startsWith("[Compacted]"));
        assertEquals("完成", messages.get(1).extractText());
    }

    /* ---------------- 2. 混合批次:compact 在首 / 中 / 尾 ---------------- */

    @Test
    void mixedBatchKeepsEveryToolResultInOrderAndSummarizesOnlyPrefix() throws Exception {
        // 三种顺序都跑一遍:普通工具在前 / 中间 / 在后。
        for (List<String> order : List.of(List.of("bash", "compact"), List.of("compact", "bash"),
                List.of("bash", "compact", "bash"))) {
            transcriptDir = workspace.resolve("transcripts-" + String.join("-", order));
            toolResultsDir = workspace.resolve("tool-results-" + String.join("-", order));
            List<ToolUseBlock> calls = callsInOrder(order);
            ModelResponse firstReply = new ModelResponse(Message.assistant(List.copyOf(calls)),
                    new ModelResponse.Usage(0, 0));
            FakeClient client = new FakeClient(firstReply, assistant(new TextBlock("完成")));
            FakeTool bash = new FakeTool("bash", "普通输出");
            List<Message> messages = new ArrayList<>(List.of(Message.userText("开始")));

            // 摘要调用那一刻抓现场:普通工具是否已全部执行、整批结果是否都已回灌(历史已闭合)。
            AtomicInteger bashCallsWhenSummarizing = new AtomicInteger(-1);
            AtomicBoolean closedExchangeVisibleWhenSummarizing = new AtomicBoolean(false);
            AtomicReference<List<Message>> liveHistoryWhenSummarizing = new AtomicReference<>();
            client.onSummary = () -> {
                bashCallsWhenSummarizing.set(bash.calls);
                closedExchangeVisibleWhenSummarizing.set(closedTailMatches(messages, calls));
                liveHistoryWhenSummarizing.set(List.copyOf(messages));
            };
            AgentLoop loop = new AgentLoop(client, Map.of("bash", bash, "compact", new CompactTool()),
                    "test-system", 8000, new Hooks(), compactor(client));

            loop.run(messages);

            assertEquals(1, client.summaryRequests.size(), "混合批次也要压缩一次");
            // 普通工具各执行一次;结果顺序与调用顺序一致。
            long expectedBashCalls = order.stream().filter("bash"::equals).count();
            assertEquals(expectedBashCalls, bash.calls);
            List<String> ids = resultsOf(messages.get(2)).stream().map(ToolResultBlock::toolUseId).toList();
            assertEquals(calls.stream().map(ToolUseBlock::id).toList(), ids, "回灌结果的 ID 必须与调用顺序一致");
            // 每个 tool_use 的结果文本必须与它自己的工具对应(compact 得到稳定信号,普通工具是普通输出)。
            for (int i = 0; i < calls.size(); i++) {
                String expected = calls.get(i).name().equals("compact") ? CompactTool.OUTPUT : "普通输出";
                assertEquals(expected, resultsOf(messages.get(2)).get(i).content(), "第 " + i + " 个结果的文本");
            }

            // 摘要那一刻:普通工具已经全部执行、整批结果已经回灌,历史是闭合交换。
            assertEquals(expectedBashCalls, bashCallsWhenSummarizing.get(), "摘要必须发生在全部普通工具执行之后");
            assertTrue(closedExchangeVisibleWhenSummarizing.get(), "摘要时历史已是调用与结果对应的闭合交换");
            List<Message> live = liveHistoryWhenSummarizing.get();
            assertNotNull(live);
            assertEquals(3, live.size(), "摘要时历史形态是 [人类请求, assistant, 结果]");
            Message liveResultMessage = live.getLast();
            assertEquals(calls.stream().map(ToolUseBlock::id).toList(),
                    resultsOf(liveResultMessage).stream().map(ToolResultBlock::toolUseId).toList());

            // 摘要输入必须"精确等于"前缀(这里前缀就是那条人类请求)的 JSON,不多不少,
            // 并且不含当前整批新鲜调用的 tool_use 与结果内容。
            assertEquals(1, client.summaryRequests.getFirst().messages().size());
            String summaryInput = client.summaryRequests.getFirst().messages().getFirst().extractText();
            JsonNode expectedPrefix = Json.mapper().readTree(
                    Json.mapper().writeValueAsString(List.of(Message.userText("开始"))));
            assertEquals(expectedPrefix, Json.mapper().readTree(summaryInput), "摘要输入必须精确等于前缀 JSON");
            assertFalse(summaryInput.contains("tool_use"), "新鲜调用的 tool_use 不得进入摘要输入");
            assertFalse(summaryInput.contains(CompactTool.OUTPUT), "本批新鲜结果不得进入摘要输入");
            assertEquals(3, archiveLines().size(), "归档包含完整闭合交换");

            // 尾部(原 assistant + 原结果消息)原引用保留在摘要后面。
            assertEquals(4, messages.size());
            assertTrue(messages.getFirst().extractText().startsWith("[Compacted]"),
                    "混合批次只允许普通 [Compacted] 标签,不得出现响应式标签");
            assertSame(firstReply.message(), messages.get(1), "尾部 assistant 保持原对象");
            assertSame(liveResultMessage, messages.get(2), "尾部结果消息保持原对象");
            assertSame(liveResultMessage.content(), messages.get(2).content(), "结果消息的 content 保持原引用");
            assertEquals(ids, resultsOf(messages.get(2)).stream().map(ToolResultBlock::toolUseId).toList());
            for (int i = 0; i < calls.size(); i++) {
                assertSame(resultsOf(liveResultMessage).get(i), resultsOf(messages.get(2)).get(i),
                        "第 " + i + " 个结果块保持原引用");
            }

            // 下一次正常请求(压缩后的那一轮)看到的也是同样的原引用尾部。
            List<Message> secondRequest = client.normalRequests.get(1).messages();
            assertEquals(3, secondRequest.size());
            assertTrue(secondRequest.getFirst().extractText().startsWith("[Compacted]"));
            assertSame(firstReply.message(), secondRequest.get(1), "下一次请求的 assistant 保持原对象");
            assertSame(liveResultMessage, secondRequest.get(2), "下一次请求的结果消息保持原对象");
            assertSame(liveResultMessage.content(), secondRequest.get(2).content(), "content 列表保持原引用");
            for (int i = 0; i < calls.size(); i++) {
                assertSame(resultsOf(liveResultMessage).get(i), resultsOf(secondRequest.get(2)).get(i),
                        "下一次请求的第 " + i + " 个结果块保持原引用");
            }
        }
    }

    /** 按名字顺序生成一批 tool_use:同一个名字出现多次时用序号区分 ID */
    private static List<ToolUseBlock> callsInOrder(List<String> names) {
        List<ToolUseBlock> calls = new ArrayList<>();
        Map<String, Integer> seen = new java.util.LinkedHashMap<>();
        for (String name : names) {
            int index = seen.merge(name, 1, Integer::sum);
            calls.add(use(name + "-" + index, name));
        }
        return calls;
    }

    /* ---------------- 3. 一次压缩 + 跨批/跨 run 不残留标志 ---------------- */

    @Test
    void multipleCompactCallsCompressOnceAndFlagDoesNotLeakAcrossBatchesOrRuns() throws Exception {
        FakeClient client = new FakeClient(
                assistant(use("c1", "compact"), use("c2", "compact")), // 同一批两个 compact
                assistant(use("b1", "bash")),                          // 下一批只有普通工具
                assistant(new TextBlock("完成")),
                assistant(new TextBlock("第二轮的回复")));
        FakeTool bash = new FakeTool("bash", "输出");
        AgentLoop loop = new AgentLoop(client, Map.of("bash", bash, "compact", new CompactTool()),
                "test-system", 8000, new Hooks(), compactor(client));
        List<Message> messages = new ArrayList<>(List.of(Message.userText("开始")));

        loop.run(messages);

        assertEquals(1, client.summaryRequests.size(), "一批多个成功 compact 只压缩一次");
        assertEquals(1, bash.calls);
        assertEquals(3, client.normalRequests.size());

        // 同一个 loop 第二次 run:标志不残留(没有 compact 调用 → 不再压缩)。
        messages.add(Message.userText("第二个请求"));
        loop.run(messages);

        assertEquals(1, client.summaryRequests.size(), "第二次 run 不得继承上一轮的压缩请求");
        assertEquals(4, client.normalRequests.size());
    }

    /* ---------------- 4. 拦截与同文案误触发 ---------------- */

    @Test
    void blockedCompactAndSameTextFromOtherToolsNeverTriggerCompaction() throws Exception {
        // (a) 被 PreToolUse 拦截的 compact:即使拒绝文案恰好等于 OUTPUT,也不触发。
        FakeClient blockedClient = new FakeClient(assistant(use("c1", "compact")),
                assistant(new TextBlock("完成")));
        Hooks blockedHooks = new Hooks();
        blockedHooks.register(Hooks.PRE_TOOL_USE, params -> CompactTool.OUTPUT); // 拦截文案 == 稳定输出
        AgentLoop blockedLoop = new AgentLoop(blockedClient, Map.of("compact", new CompactTool()),
                "test-system", 8000, blockedHooks, compactor(blockedClient));
        List<Message> blockedMessages = new ArrayList<>(List.of(Message.userText("开始")));

        blockedLoop.run(blockedMessages);

        assertEquals(0, blockedClient.summaryRequests.size(), "被拦截的 compact 不得触发压缩");
        assertEquals(CompactTool.OUTPUT, resultsOf(blockedMessages.get(2)).getFirst().content());
        assertFalse(Files.exists(transcriptDir), "拦截路径不得写归档");

        // (b) 别的工具返回与 OUTPUT 完全相同的文案:不触发。
        transcriptDir = workspace.resolve("transcripts-other");
        FakeClient otherClient = new FakeClient(assistant(use("b1", "bash")), assistant(new TextBlock("完成")));
        AgentLoop otherLoop = new AgentLoop(otherClient,
                Map.of("bash", new FakeTool("bash", CompactTool.OUTPUT), "compact", new CompactTool()),
                "test-system", 8000, new Hooks(), compactor(otherClient));
        List<Message> otherMessages = new ArrayList<>(List.of(Message.userText("开始")));

        otherLoop.run(otherMessages);

        assertEquals(0, otherClient.summaryRequests.size(), "普通工具返回同样文案不得误触发");

        // (c) compact 名下返回非期望文案:不触发。
        transcriptDir = workspace.resolve("transcripts-wrong-output");
        FakeClient wrongClient = new FakeClient(assistant(use("c1", "compact")), assistant(new TextBlock("完成")));
        AgentLoop wrongLoop = new AgentLoop(wrongClient,
                Map.of("compact", new FakeTool("compact", "别的输出")), "test-system", 8000, new Hooks(),
                compactor(wrongClient));
        List<Message> wrongMessages = new ArrayList<>(List.of(Message.userText("开始")));

        wrongLoop.run(wrongMessages);

        assertEquals(0, wrongClient.summaryRequests.size(), "输出不匹配时不得触发压缩");
    }

    /* ---------------- 5. 重复 ID 仍在执行前拒绝 ---------------- */

    @Test
    void duplicateCallIdsAreStillRejectedBeforeAnyToolOrSummary() {
        FakeClient client = new FakeClient(assistant(use("dup", "compact"), use("dup", "compact")));
        AgentLoop loop = new AgentLoop(client, Map.of("compact", new CompactTool()), "test-system", 8000,
                new Hooks(), compactor(client));
        List<Message> messages = new ArrayList<>(List.of(Message.userText("开始")));

        assertThrows(IllegalArgumentException.class, () -> loop.run(messages));

        assertEquals(1, messages.size(), "不得留下半截 assistant 消息");
        assertEquals(0, client.summaryRequests.size());
        assertFalse(Files.exists(transcriptDir));
    }

    /* ---------------- 6. 混合批次 + 超大新鲜输出 ---------------- */

    @Test
    void mixedBatchPersistsOversizedFreshOutputBeforeSummarizingPrefix() throws Exception {
        String huge = "x".repeat(5000);
        FakeClient client = new FakeClient(
                assistant(use("b1", "bash"), use("c1", "compact")),
                assistant(new TextBlock("完成")));
        FakeTool bash = new FakeTool("bash", huge);
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir,
                limits(200, 100, 10)); // 批次门槛 200、单项门槛 100、预览 10
        AgentLoop loop = new AgentLoop(client, Map.of("bash", bash, "compact", new CompactTool()),
                "test-system", 8000, new Hooks(), compactor);
        List<Message> messages = new ArrayList<>(List.of(Message.userText("开始")));

        loop.run(messages);

        // 新鲜的大结果先落盘为可恢复外壳:文件含完整原文,上下文里只剩路径 + 10 字符预览。
        assertEquals(1, toolResultFileCount());
        ToolResultBlock persisted = resultsOf(messages.get(2)).getFirst();
        assertTrue(persisted.content().startsWith("<persisted-output>"),
                "超大新鲜结果应换成可恢复外壳,而不是被摘要替代");
        assertEquals(huge.substring(0, 10), persisted.content()
                .substring(persisted.content().indexOf("Preview:\n") + "Preview:\n".length())
                .split("\n")[0]);
        assertEquals(huge, Files.readString(Path.of(persisted.content()
                .substring("<persisted-output>\nFull output: ".length()).split("\n")[0])));

        // 摘要输入必须"精确等于"前缀(那条人类请求)的 JSON,不含当前整批新鲜调用与结果。
        assertEquals(1, client.summaryRequests.getFirst().messages().size());
        String summaryInput = client.summaryRequests.getFirst().messages().getFirst().extractText();
        JsonNode expectedPrefix = Json.mapper().readTree(
                Json.mapper().writeValueAsString(List.of(Message.userText("开始"))));
        assertEquals(expectedPrefix, Json.mapper().readTree(summaryInput), "摘要输入必须精确等于前缀 JSON");
        assertFalse(summaryInput.contains("tool_use"), "新鲜调用的 tool_use 不得进入摘要输入");
        assertFalse(summaryInput.contains("<persisted-output>"), "新鲜结果的落盘外壳不得进入摘要输入");
    }

    /* ---------------- 7. Stop hook 注入后仍用入口请求 ---------------- */

    @Test
    void stopHookInjectedMessageDoesNotReplaceTheCapturedRequest() throws Exception {
        FakeClient client = new FakeClient(
                assistant(use("c1", "compact")),   // 第一次压缩
                assistant(new TextBlock("先停一下")), // 触发 Stop hook 注入
                assistant(use("c2", "compact")),   // 第二次压缩(发生在注入之后)
                assistant(new TextBlock("完成")));
        Hooks hooks = new Hooks();
        java.util.concurrent.atomic.AtomicBoolean firstStop = new java.util.concurrent.atomic.AtomicBoolean(true);
        hooks.register(Hooks.STOP, params -> firstStop.getAndSet(false) ? "继续执行" : null);
        AgentLoop loop = new AgentLoop(client, Map.of("compact", new CompactTool()), "test-system", 8000,
                hooks, compactor(client));
        List<Message> messages = new ArrayList<>(List.of(Message.userText("开始")));

        loop.run(messages);

        assertEquals(2, client.summaryRequests.size(), "Stop hook 注入后再次 compact 应再压缩一次");
        // 第二次压缩发生在 Stop hook 注入之后:压缩消息里的 Current user request 仍是入口捕获的那句,
        // 而不是循环中途注入的 synthetic 文本。
        assertEquals("开始", entryRequestOf(messages, 0));
        assertFalse(messages.getFirst().extractText().contains("继续执行"),
                "压缩消息的请求分区不得被 synthetic 注入替换");
    }

    /** 从压缩消息里取出 Current user request 一行(测试辅助) */
    private static String entryRequestOf(List<Message> messages, int index) {
        String text = messages.get(index).extractText();
        int start = text.indexOf("Current user request:\n") + "Current user request:\n".length();
        return text.substring(start, text.indexOf("\n\n", start));
    }

    /* ---------------- 8. 启用路径的入口校验、旧路径与配置错误 ---------------- */

    @Test
    void enabledPathValidatesEntryAndLegacyPathAndConfigErrorsBehaveAsSpecified() throws LlmException {
        // 启用路径:非法入口(空白请求)在第一次模型调用前拒绝。
        FakeClient client = new FakeClient(assistant(new TextBlock("不该被调用")));
        AgentLoop enabled = new AgentLoop(client, Map.of("compact", new CompactTool()), "test-system", 8000,
                new Hooks(), compactor(client));
        List<Message> blankEntry = new ArrayList<>(List.of(Message.userText("   ")));
        assertThrows(IllegalArgumentException.class, () -> enabled.run(blankEntry));
        assertEquals(0, client.normalRequests.size(), "非法入口不得调用模型");

        // 旧路径:五参构造器 + 不含 compact 的工具表 → 保持既有行为(不捕获请求、不压缩)。
        FakeClient legacyClient = new FakeClient(assistant(new TextBlock("旧路径回复")));
        AgentLoop legacy = new AgentLoop(legacyClient, Map.of("bash", new FakeTool("bash", "x")),
                "test-system", 8000, new Hooks());
        List<Message> legacyMessages = new ArrayList<>(List.of(Message.userText("旧请求")));
        legacy.run(legacyMessages);
        assertEquals("旧路径回复", legacyMessages.getLast().extractText());
        assertEquals(0, legacyClient.summaryRequests.size());

        // 配置错误:注册了 compact 却没注入压缩器 → 构造时明确拒绝。
        FakeClient misconfigured = new FakeClient();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AgentLoop(misconfigured, Map.of("compact", new CompactTool()), "test-system", 8000,
                        new Hooks()));
        assertTrue(error.getMessage().contains("ContextCompactor"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentLoop(misconfigured, Map.of("compact", new CompactTool()), "test-system", 8000,
                        new Hooks(), null));
    }

    /* ---------------- 9. 失败语义 ---------------- */

    @Test
    void archiveFailureAndSummaryFailurePropagateAndKeepClosedHistory() throws Exception {
        // (a) 归档失败:带 cause 传播,摘要调用为 0,已闭合的交换仍完整留在历史里。
        Files.writeString(transcriptDir, "占位文件");
        FakeClient archiveFailClient = new FakeClient(assistant(use("c1", "compact")),
                assistant(new TextBlock("完成")));
        AgentLoop archiveFailLoop = new AgentLoop(archiveFailClient, Map.of("compact", new CompactTool()),
                "test-system", 8000, new Hooks(), compactor(archiveFailClient));
        List<Message> archiveFailMessages = new ArrayList<>(List.of(Message.userText("开始")));

        IllegalStateException archiveError = assertThrows(IllegalStateException.class,
                () -> archiveFailLoop.run(archiveFailMessages));

        assertNotNull(archiveError.getCause());
        assertEquals(0, archiveFailClient.summaryRequests.size(), "归档失败时不得调用摘要");
        assertEquals(3, archiveFailMessages.size(), "闭合的工具交换必须完整保留");
        assertEquals(CompactTool.OUTPUT, resultsOf(archiveFailMessages.get(2)).getFirst().content());

        // (b) 摘要失败:固定异常原样传播,恰好一次摘要调用、已写归档保留、不继续下一次正常请求。
        transcriptDir = workspace.resolve("transcripts-summary-fail");
        LlmException boom = new LlmException("summary boom");
        FakeClient summaryFailClient = new FakeClient(assistant(use("b1", "bash"), use("c1", "compact")),
                assistant(new TextBlock("不该走到这里")));
        summaryFailClient.summaryFailure = boom;
        FakeTool bash = new FakeTool("bash", "普通输出");
        AgentLoop summaryFailLoop = new AgentLoop(summaryFailClient,
                Map.of("bash", bash, "compact", new CompactTool()), "test-system", 8000, new Hooks(),
                compactor(summaryFailClient));
        List<Message> summaryFailMessages = new ArrayList<>(List.of(Message.userText("开始")));

        LlmException thrown = assertThrows(LlmException.class,
                () -> summaryFailLoop.run(summaryFailMessages));

        assertSame(boom, thrown, "摘要异常必须原样传播");
        assertEquals(1, summaryFailClient.summaryRequests.size());
        assertEquals(1, summaryFailClient.normalRequests.size(), "失败后不得继续下一次正常请求");
        assertEquals(1, bash.calls, "摘要失败不得重跑已经完成的工具");
        // 历史仍是那条闭合交换:消息数、闭合 ID 与原始内容都要逐项核对,不能只看条数。
        assertEquals(3, summaryFailMessages.size(), "失败后历史仍是那条闭合交换");
        assertEquals(List.of("b1", "c1"), resultsOf(summaryFailMessages.get(2)).stream()
                .map(ToolResultBlock::toolUseId).toList(), "闭合交换的调用 ID 与顺序必须保留");
        assertEquals(List.of("普通输出", CompactTool.OUTPUT), resultsOf(summaryFailMessages.get(2)).stream()
                .map(ToolResultBlock::content).toList(), "原始结果内容必须原样保留");
        // 归档已成功写出且保留:逐行核对,包含原始工具名与原文,而不是只看文件是否存在。
        List<String> archive = archiveLines();
        assertEquals(3, archive.size(), "归档已成功写出且保留完整闭合交换");
        assertTrue(archive.get(1).contains("\"bash\""));
        assertTrue(archive.get(1).contains("\"compact\""));
        assertTrue(archive.get(2).contains("普通输出"));
        assertTrue(archive.get(2).contains(CompactTool.OUTPUT));
    }

    /* ---------------- 10. 预算候选已生成后摘要失败:原历史不半提交 ---------------- */

    @Test
    void budgetedCandidateIsNotCommittedWhenSummaryFails() throws Exception {
        // 混合批次 + 超大新鲜输出:第一层会先把大结果落盘、生成"路径 + 预览"候选,随后摘要抛固定异常。
        // 这是普通短输出失败用例覆盖不到的一步:候选已经造出来、落盘文件也已经写出,但最终动作失败,
        // 内存里的历史必须仍是失败前的完整原文,而不是半提交的预览外壳。
        String huge = "y".repeat(5000);
        LlmException boom = new LlmException("candidate summary boom");
        FakeClient client = new FakeClient(
                assistant(use("b1", "bash"), use("c1", "compact")),
                assistant(new TextBlock("不该走到这里")));
        client.summaryFailure = boom;
        FakeTool bash = new FakeTool("bash", huge);
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir,
                limits(200, 100, 10)); // 批次门槛 200、单项门槛 100、预览 10:必然产生落盘候选
        AgentLoop loop = new AgentLoop(client, Map.of("bash", bash, "compact", new CompactTool()),
                "test-system", 8000, new Hooks(), compactor);
        List<Message> messages = new ArrayList<>(List.of(Message.userText("开始")));

        LlmException thrown = assertThrows(LlmException.class, () -> loop.run(messages));

        assertSame(boom, thrown, "摘要异常必须原样传播");
        assertEquals(1, client.summaryRequests.size(), "恰好一次摘要调用");
        assertEquals(1, client.normalRequests.size(), "失败后不得继续下一次正常请求");
        assertEquals(1, bash.calls, "失败不得重跑已经完成的工具");

        // 内存历史:仍是失败前的完整闭合交换,大结果仍是完整原文(绝不是半提交的预览外壳)。
        assertEquals(3, messages.size());
        List<ToolResultBlock> results = resultsOf(messages.get(2));
        assertEquals("b1", results.getFirst().toolUseId());
        assertEquals(huge, results.getFirst().content(), "失败后大结果必须仍是完整原文,不能被半提交为预览");
        assertEquals(CompactTool.OUTPUT, results.get(1).content());

        // 副作用保留:工具结果落盘文件里存的仍是完整原文,可以按路径完整取回。
        assertEquals(1, toolResultFileCount());
        assertEquals(huge, Files.readString(onlyToolResultFile(), StandardCharsets.UTF_8));

        // 归档也已写出(它由预算后的候选生成,所以尾部允许是可恢复 marker,不是全量大正文)。
        List<String> archive = archiveLines();
        assertEquals(3, archive.size(), "归档包含整段候选历史");
        assertTrue(archive.get(2).contains("<persisted-output>"),
                "归档来自预算后的候选:尾部是可恢复 marker");
    }

    /* ---------------- 11. 启用路径的生命周期顺序与被拦工具的分类 ---------------- */

    @Test
    void enabledPathRunsToolLifecycleBeforeSummaryAndKeepsBlockedToolInMixedBatch() throws Exception {
        // (a) 全部工具正常执行:把 Pre/Post 事件按发生顺序记进日志,摘要调用必须排在整批生命周期之后。
        List<String> events = new ArrayList<>();
        Hooks hooks = new Hooks();
        hooks.register(Hooks.PRE_TOOL_USE, params -> {
            events.add("pre:" + ((ToolUseBlock) params.get(Hooks.PARAM_BLOCK)).name());
            return null; // 放行
        });
        hooks.register(Hooks.POST_TOOL_USE, params -> {
            events.add("post:" + ((ToolUseBlock) params.get(Hooks.PARAM_BLOCK)).name());
            return null;
        });
        FakeClient lifecycleClient = new FakeClient(
                assistant(use("b1", "bash"), use("c1", "compact")),
                assistant(new TextBlock("完成")));
        lifecycleClient.onSummary = () -> events.add("summary");
        FakeTool lifecycleBash = new FakeTool("bash", "普通输出");
        AgentLoop lifecycleLoop = new AgentLoop(lifecycleClient,
                Map.of("bash", lifecycleBash, "compact", new CompactTool()), "test-system", 8000, hooks,
                compactor(lifecycleClient));
        List<Message> lifecycleMessages = new ArrayList<>(List.of(Message.userText("开始")));

        lifecycleLoop.run(lifecycleMessages);

        // 摘要排在最后:每个工具都走完 Pre → 执行 → Post,整批生命周期结束后才轮到压缩动作。
        assertEquals(List.of("pre:bash", "post:bash", "pre:compact", "post:compact", "summary"), events,
                "摘要调用必须发生在全部正常工具生命周期之后");
        assertEquals(1, lifecycleClient.summaryRequests.size());

        // (b) 一个普通工具被 PreToolUse 拦截、另一项 compact 成功:仍按混合策略处理。
        //     被拦工具不执行、没有 Post;拒绝结果与 compact 结果一起闭合保留,不能误按纯批次丢掉当前交换。
        events.clear();
        transcriptDir = workspace.resolve("transcripts-blocked-bash");
        String refusal = "被权限闸门拒绝";
        Hooks blocking = new Hooks();
        blocking.register(Hooks.PRE_TOOL_USE, params -> {
            ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            events.add("pre:" + block.name());
            return block.name().equals("bash") ? refusal : null; // 只拦 bash,放行 compact
        });
        blocking.register(Hooks.POST_TOOL_USE, params -> {
            events.add("post:" + ((ToolUseBlock) params.get(Hooks.PARAM_BLOCK)).name());
            return null;
        });
        FakeClient blockedClient = new FakeClient(
                assistant(use("b1", "bash"), use("c1", "compact")),
                assistant(new TextBlock("完成")));
        blockedClient.onSummary = () -> events.add("summary");
        FakeTool blockedBash = new FakeTool("bash", "不该执行");
        AgentLoop blockedLoop = new AgentLoop(blockedClient,
                Map.of("bash", blockedBash, "compact", new CompactTool()), "test-system", 8000, blocking,
                compactor(blockedClient));
        List<Message> blockedMessages = new ArrayList<>(List.of(Message.userText("开始")));

        blockedLoop.run(blockedMessages);

        assertEquals(0, blockedBash.calls, "被 PreToolUse 拦截的工具不得执行");
        assertEquals(1, blockedClient.summaryRequests.size(), "被拦普通工具 + 成功 compact 仍是混合批次");
        assertEquals(List.of("pre:bash", "pre:compact", "post:compact", "summary"), events,
                "被拦工具没有执行也没有 Post;摘要仍排在其余工具生命周期之后");
        // 拒绝结果与 compact 结果一起闭合保留在摘要之后,没有被当成"纯 compact 整段丢掉"。
        assertEquals(4, blockedMessages.size());
        assertTrue(blockedMessages.getFirst().extractText().startsWith("[Compacted]"));
        assertEquals(List.of("b1", "c1"), resultsOf(blockedMessages.get(2)).stream()
                .map(ToolResultBlock::toolUseId).toList());
        assertEquals(List.of(refusal, CompactTool.OUTPUT), resultsOf(blockedMessages.get(2)).stream()
                .map(ToolResultBlock::content).toList());
    }

    /* ---------------- 12. reminder 文本块不改变纯 compact 批次分类 ---------------- */

    @Test
    void reminderTextBlockDoesNotTurnPureCompactBatchIntoMixed() throws Exception {
        // 连续三轮不用 todo_write:按循环规则,第三轮的结果里会追加 reminder 文本块。
        // 第三轮整批只有 compact 调用,分类只看调用名 → 仍是纯 compact(compactAll),
        // reminder 只是被一起归档/摘要的文本块,不能被误判成"新鲜结果"而改成混合策略。
        FakeClient client = new FakeClient(
                assistant(use("c1", "compact")),
                assistant(use("c2", "compact")),
                assistant(use("c3", "compact")), // 这一轮的结果里会追加 reminder
                assistant(new TextBlock("完成")));
        AgentLoop loop = new AgentLoop(client, Map.of("compact", new CompactTool()), "test-system", 8000,
                new Hooks(), compactor(client));
        List<Message> messages = new ArrayList<>(List.of(Message.userText("开始")));

        loop.run(messages);

        // 三轮都是纯 compact:每轮一次摘要,共 3 次;摘要调用不计入 todo 轮次,正常请求仍是 4 次。
        assertEquals(3, client.summaryRequests.size());
        assertEquals(4, client.normalRequests.size(), "摘要调用不得被算成一个额外的 todo 轮次");
        // 第三轮按"整段历史"摘要(纯批次走 compactAll):输入同时含新调用 c3 与 reminder 文本块。
        String thirdInput = client.summaryRequests.get(2).messages().getFirst().extractText();
        assertTrue(thirdInput.contains("\"c3\""), "第三轮按整段历史摘要,而不是只摘要前缀");
        assertTrue(thirdInput.contains(REMINDER), "reminder 文本块随整段历史进入摘要输入");
        // reminder 被归档:某个 transcript 文件里能读到它(证明它随整段历史一起被归档)。
        assertTrue(archiveFilesContain(REMINDER), "reminder 随整段历史写入归档");

        // 第三轮仍按纯 compact 收尾:只剩压缩消息 + 最后的正常回复,reminder 没有被留在尾部。
        assertEquals(2, messages.size());
        assertTrue(messages.getFirst().extractText().startsWith("[Compacted]"));
        assertEquals("完成", messages.getLast().extractText());
    }
}
