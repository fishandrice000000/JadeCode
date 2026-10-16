package com.jadecode.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.context.CompactionLimits;
import com.jadecode.context.ContextCompactor;
import com.jadecode.hooks.Hooks;
import com.jadecode.session.SessionRuntime;
import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.LlmClient;
import com.jadecode.llm.LlmException;
import com.jadecode.llm.ModelResponse;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 父循环"正式请求超限后的有限补救"(reactiveCompact 接入)的集成测试。
 *
 * <p>
 * 覆盖的 Agent 行为:自动 prepare 用的是本地字符估算,服务端仍可能拒收——当<b>正式请求</b>抛出
 * 被判定为"上下文过长"的异常时,harness 在本次请求的额度内先做一次有损补救(旧前缀摘要 + 原样保留
 * 近期尾部),再重新发<b>同一次</b>正式请求;命中不了分类的失败(认证 / 限流 / 网络)不靠压缩处理;
 * 补救自身失败原样传播;重试不重跑已经执行过的工具,也不算新业务轮。
 * </p>
 *
 * <p>
 * 假客户端把请求分成<b>正式请求</b>(带工具表)与<b>摘要请求</b>(空工具表),两者都保存请求<b>快照</b>
 * 并记录先后顺序;正式请求按脚本「回复或异常」弹出,所以"第几次请求失败、哪一次成功"完全可控,
 * 断言看的是每次请求真正看到的历史,而不是跑完之后的最终列表。
 * </p>
 *
 * <p>
 * 阈值刻意分两档:{@link #wideLimits()} 让 prepare 永远不摘要(隔离出补救这一条分支),
 * {@link #narrowLimits()} 则让 prepare 自己就会走摘要(用来验证 prepare 的失败不会被误判成正式请求超限)。
 * </p>
 */
class AgentExecutorReactiveCompactionTest {

    /** 摘要输出额度;只用来断言摘要请求确实来自本测试注入的阈值 */
    private static final int SUMMARY_MAX_TOKENS = 77;

    @TempDir
    Path workspace;

    private Path transcriptDir;
    private Path toolResultsDir;

    /** 请求先后顺序:每次 client.complete 追加一项("normal" / "summary") */
    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transcriptDir = workspace.resolve("transcripts");
        toolResultsDir = workspace.resolve("tool-results");
        events.clear();
    }

    /* ---------------- 假工具与假客户端 ---------------- */

    /** 内存假工具:记录被调用次数,返回预置输出 */
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
     * 脚本化假客户端:正式请求按顺序弹出 {@code ModelResponse} 或 {@link LlmException},摘要请求返回固定文本。
     *
     * @param events 请求顺序记录(与测试共享的可变列表)
     * @param script 正式请求的剧本元素:要么是 {@code ModelResponse}(成功回复),要么是
     *               {@code LlmException}(这次正式请求失败)
     */
    private static final class FakeClient implements LlmClient {
        private final List<Object> script = new ArrayList<>();
        private final List<String> events;
        private final List<CompleteRequest> normalRequests = new ArrayList<>();
        private final List<CompleteRequest> summaryRequests = new ArrayList<>();
        private LlmException summaryFailure;

        FakeClient(List<String> events, Object... script) {
            this.events = events;
            this.script.addAll(List.of(script));
        }

        @Override
        public ModelResponse complete(CompleteRequest request) throws LlmException {
            // 快照:请求里的 messages 是可变列表,不复制会读到跑完之后的状态。
            CompleteRequest snapshot = new CompleteRequest(request.system(), List.copyOf(request.messages()),
                    request.maxTokens(), request.tools());
            if (request.tools().isEmpty()) {
                events.add("summary");
                summaryRequests.add(snapshot);
                if (summaryFailure != null) {
                    throw summaryFailure;
                }
                return new ModelResponse(Message.assistant(List.of(new TextBlock("摘要文本"))),
                        new ModelResponse.Usage(0, 0));
            }
            events.add("normal");
            normalRequests.add(snapshot);
            if (script.isEmpty()) {
                throw new LlmException("假客户端正式请求脚本耗尽");
            }
            Object next = script.removeFirst();
            if (next instanceof LlmException failure) {
                throw failure;
            }
            return (ModelResponse) next;
        }
    }

    /* ---------------- 阈值与造数据 ---------------- */

    /** 只改本测试关心的阈值,其余沿用课程默认值 */
    private static CompactionLimits limits(int contextCharLimit) {
        CompactionLimits d = CompactionLimits.courseDefaults();
        return new CompactionLimits(contextCharLimit, 200_000, 30_000, 80_000,
                d.keepRecentResults(), d.keepRecentMessages(), d.microResultMinChars(), 2_000,
                d.fitPreviewChars(), SUMMARY_MAX_TOKENS, 50, d.snipHeadMessages(), d.targetRatio());
    }

    /** prepare 永不摘要:把"补救"这一条分支单独隔离出来观察 */
    private static CompactionLimits wideLimits() {
        return limits(200_000);
    }

    /** prepare 自己就会走到摘要层(本地窗口很小),用来验证它的失败不会被当成正式请求超限 */
    private static CompactionLimits narrowLimits() {
        return limits(200);
    }

    private static ModelResponse assistant(ContentBlock... blocks) {
        return new ModelResponse(Message.assistant(List.of(blocks)), new ModelResponse.Usage(0, 0));
    }

    private static ToolUseBlock use(String id, String name) {
        return new ToolUseBlock(id, name, Json.mapper().createObjectNode());
    }

    private static List<ToolResultBlock> resultsOf(Message message) {
        return message.blocksOfType(ToolResultBlock.class);
    }

    /**
     * 造一段合法历史:请求1 / 回复2 / 请求3 / … 共 {@code count} 条(必须为奇数,最后一条是 user,
     * 满足入口 activeRequest 的契约);纯文本消息,所以 prepare 的第一层不会改动它。
     */
    private static List<Message> legalHistory(int count) {
        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            messages.add(i % 2 == 1
                    ? Message.userText("请求" + i)
                    : Message.assistant(List.of(new TextBlock("回复" + i))));
        }
        return messages;
    }

    /* ---------------- 磁盘与 JSON 观察 ---------------- */

    private List<Path> filesIn(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var files = Files.list(dir)) {
            return files.sorted().toList();
        }
    }

    private List<String> archiveLines() throws IOException {
        List<Path> files = filesIn(transcriptDir);
        assertEquals(1, files.size(), "应当恰好有一份 transcript 归档");
        return Files.readAllLines(files.getFirst(), StandardCharsets.UTF_8);
    }

    /** 把一段序列化后的消息数组拆成"一行一条消息",与归档行共用同一套断言辅助 */
    private static List<String> linesOf(JsonNode array) {
        List<String> lines = new ArrayList<>();
        for (JsonNode element : array) {
            lines.add(element.toString());
        }
        return lines;
    }

    private static List<String> rolesOf(List<String> lines) throws IOException {
        List<String> roles = new ArrayList<>();
        for (String line : lines) {
            roles.add(Json.mapper().readTree(line).get("role").asText());
        }
        return roles;
    }

    private static List<String> textsOf(List<String> lines) throws IOException {
        List<String> texts = new ArrayList<>();
        for (String line : lines) {
            List<String> parts = new ArrayList<>();
            for (JsonNode block : Json.mapper().readTree(line).get("content")) {
                if ("text".equals(block.path("type").asText())) {
                    parts.add(block.get("text").asText());
                }
            }
            texts.add(String.join("", parts));
        }
        return texts;
    }

    /** 第 {@code index} 次摘要请求的输入,被拆成一行行消息 */
    private static List<String> summaryInputLines(FakeClient client, int index) throws IOException {
        return linesOf(Json.mapper().readTree(
                client.summaryRequests.get(index).messages().getFirst().extractText()));
    }

    /* ---------------- 1. 超限 → 一次补救 → 重试成功 ---------------- */

    @Test
    void overflowIsRecoveredOnceAndTheRetriedRequestSucceeds() throws Exception {
        LlmException firstFailure = new LlmException("prompt_too_long: 请求上下文过长");
        FakeClient client = new FakeClient(events, firstFailure, assistant(new TextBlock("完成")));
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits());
        AgentExecutor loop = new AgentExecutor(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks(), compactor);
        List<Message> messages = legalHistory(9);
        List<Message> callerList = messages;
        Message tailFirst = messages.get(4);   // 补救后应当原样保留的尾部第一条
        Message lastRequest = messages.getLast();

        SessionRuntime.runExisting(loop, messages);

        // 顺序:失败的正式请求 → 一次前缀摘要 → 重新发同一次正式请求(成功)。
        assertEquals(List.of("normal", "summary", "normal"), events);
        assertEquals(2, client.normalRequests.size());
        assertEquals(1, client.summaryRequests.size());

        // 第一次正式请求看到的就是入口那 9 条——失败的尝试不在历史里留下任何痕迹。
        assertEquals(9, client.normalRequests.getFirst().messages().size());
        // 摘要只覆盖旧前缀(默认保留最近 5 条,于是前缀是前 4 条),不是整段历史。
        List<String> prefix = summaryInputLines(client, 0);
        assertEquals(List.of("请求1", "回复2", "请求3", "回复4"), textsOf(prefix));
        // 归档保存的是补救前的完整历史(9 行),供回查。
        assertEquals(9, archiveLines().size());
        assertEquals(List.of("请求1", "回复2", "请求3", "回复4", "请求5", "回复6", "请求7", "回复8", "请求9"),
                textsOf(archiveLines()));

        // 第二次正式请求看到的是"响应式摘要 + 原样保留的 5 条尾部",而且尾部仍是原来的对象。
        List<Message> retried = client.normalRequests.get(1).messages();
        assertEquals(6, retried.size());
        String summary = retried.getFirst().extractText();
        assertTrue(summary.startsWith("[Reactive compact]"));
        assertTrue(summary.contains("Current user request:\n请求9"), "当前请求仍来自入口捕获");
        assertSame(tailFirst, retried.get(1), "尾部第一条必须按原引用保留");
        assertSame(lastRequest, retried.get(5), "尾部最后一条必须按原引用保留");
        // 失败尝试没有追加 assistant;成功之后才追加那条"完成"。
        assertSame(callerList, messages);
        assertEquals(7, messages.size());
        assertEquals("完成", messages.getLast().extractText());
        assertEquals(1, messages.stream().filter(m -> m.extractText().startsWith("[Reactive compact]")).count());
    }

    /* ---------------- 2. 命中不了分类的失败:零补救、零重试 ---------------- */

    @Test
    void nonMatchingFailureIsPropagatedWithoutAnyRecovery() throws Exception {
        // 认证 / 限流 / 网络这类错误压缩改变不了根因,所以一次补救都不该发生。
        LlmException unauthorized = new LlmException("401 unauthorized: invalid api key");
        FakeClient client = new FakeClient(events, unauthorized, assistant(new TextBlock("不该被调用")));
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits());
        AgentExecutor loop = new AgentExecutor(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks(), compactor);
        List<Message> messages = legalHistory(9);
        List<Message> before = List.copyOf(messages);

        LlmException thrown = assertThrows(LlmException.class, () -> SessionRuntime.runExisting(loop, messages));

        assertSame(unauthorized, thrown, "非匹配失败必须抛出原异常对象");
        assertEquals(List.of("normal"), events, "不得有补救或重试请求");
        assertEquals(0, client.summaryRequests.size());
        assertFalse(Files.exists(transcriptDir), "不归档、不摘要");
        assertEquals(before, messages, "会话必须保持原样");
    }

    /* ---------------- 3. 再次超限:只补救一次 ---------------- */

    @Test
    void secondOverflowThrowsTheSecondExceptionAfterExactlyOneRecovery() throws Exception {
        LlmException first = new LlmException("prompt_too_long: 第一次超限");
        LlmException second = new LlmException("prompt_too_long: 补救之后仍然超限");
        FakeClient client = new FakeClient(events, first, second, assistant(new TextBlock("不该被调用")));
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits());
        AgentExecutor loop = new AgentExecutor(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks(), compactor);
        List<Message> messages = legalHistory(9);

        LlmException thrown = assertThrows(LlmException.class, () -> SessionRuntime.runExisting(loop, messages));

        // 抛的是第二次实际异常对象(不是包装,也不是第一次那个)。
        assertSame(second, thrown);
        assertEquals(List.of("normal", "summary", "normal"), events, "只补救一次,不无限 continue");
        assertEquals(1, client.summaryRequests.size());
        // 已经采用的候选仍在,没有被回滚。
        assertEquals(6, messages.size());
        assertTrue(messages.getFirst().extractText().startsWith("[Reactive compact]"));
    }

    /* ---------------- 4. 空前缀 no-op:不 clear,额度照样消耗 ---------------- */

    @Test
    void emptyPrefixNoOpStillConsumesTheSingleRetryAndStops() throws Exception {
        // 历史只有 3 条,不足以切出"可安全裁掉的前缀":reactiveCompact 会返回入参本身。
        LlmException first = new LlmException("prompt_too_long: 第一次超限");
        LlmException second = new LlmException("prompt_too_long: 第二次超限");
        FakeClient client = new FakeClient(events, first, second, assistant(new TextBlock("不该被调用")));
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits());
        AgentExecutor loop = new AgentExecutor(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks(), compactor);
        List<Message> messages = legalHistory(3);
        List<Message> callerList = messages;
        List<Message> before = List.copyOf(messages);

        LlmException thrown = assertThrows(LlmException.class, () -> SessionRuntime.runExisting(loop, messages));

        assertSame(second, thrown, "no-op 也要消耗额度,第二次超限直接抛出");
        assertEquals(List.of("normal", "normal"), events, "两次正式请求之间没有摘要,也没有第三次尝试");
        assertEquals(0, client.summaryRequests.size(), "空前缀不写归档、不调模型");
        assertFalse(Files.exists(transcriptDir));
        // 关键:返回入参本身时绝不能 clear,否则这 3 条历史会被自己清空。
        assertSame(callerList, messages);
        assertEquals(before, messages);
    }

    /* ---------------- 5. 补救归档失败:停止,不发第二次正式请求 ---------------- */

    @Test
    void archiveFailureDuringRecoveryStopsBeforeAnyRetry() throws Exception {
        // 归档目录的位置上是一个普通文件:补救里的 writeTranscript 必然失败。
        Files.writeString(transcriptDir, "占位文件");
        LlmException overflow = new LlmException("prompt_too_long: 需要补救");
        FakeClient client = new FakeClient(events, overflow, assistant(new TextBlock("不该被调用")));
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits());
        AgentExecutor loop = new AgentExecutor(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks(), compactor);
        List<Message> messages = legalHistory(9);
        List<Message> before = List.copyOf(messages);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> SessionRuntime.runExisting(loop, messages));

        assertNotNull(error.getCause(), "归档失败必须保留 cause");
        assertEquals(List.of("normal"), events, "补救失败后不得再发正式请求");
        assertEquals(0, client.summaryRequests.size(), "归档失败时连摘要都不能调用");
        assertEquals(before, messages, "会话必须保持原样(不采用部分候选)");
    }

    /* ---------------- 6. 补救摘要失败:原样传播、归档保留、不发后续请求 ---------------- */

    @Test
    void summaryFailureDuringRecoveryPropagatesAndKeepsTheArchive() throws Exception {
        LlmException overflow = new LlmException("prompt_too_long: 需要补救");
        LlmException summaryBoom = new LlmException("补救摘要失败");
        FakeClient client = new FakeClient(events, overflow, assistant(new TextBlock("不该被调用")));
        client.summaryFailure = summaryBoom;
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits());
        AgentExecutor loop = new AgentExecutor(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks(), compactor);
        List<Message> messages = legalHistory(9);
        List<Message> before = List.copyOf(messages);

        LlmException thrown = assertThrows(LlmException.class, () -> SessionRuntime.runExisting(loop, messages));

        assertSame(summaryBoom, thrown, "补救内部的摘要异常原样传播");
        assertEquals(List.of("normal", "summary"), events, "摘要失败后不再发正式请求");
        // 归档在摘要之前就已经成功写出,必须保留下来供回查。
        assertEquals(9, archiveLines().size());
        assertEquals(before, messages, "不采用部分候选,会话保持原样");
    }

    /* ---------------- 7. 补救成功后工具照常执行;下一次请求额度已重置 ---------------- */

    @Test
    void retrySucceedsWithToolCallAndTheNextRequestGetsAFreshBudget() throws Exception {
        // 剧本:① 超限 → 补救 → ② 成功并请求工具 → 工具执行一次 → ③ 又超限(这是"下一次请求",
        // 额度已经因上一次成功而重置)→ 再补救一次 → ④ 成功收尾。
        LlmException first = new LlmException("prompt_too_long: 第一次超限");
        LlmException second = new LlmException("prompt_too_long: 工具之后的另一次超限");
        FakeClient client = new FakeClient(events,
                first,
                assistant(use("b1", "bash")),
                second,
                assistant(new TextBlock("完成")));
        FakeTool bash = new FakeTool("bash", "普通输出");
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits());
        AgentExecutor loop = new AgentExecutor(client, Map.of("bash", bash), "test-system", 8000, new Hooks(), compactor);
        List<Message> messages = legalHistory(9);
        List<Message> callerList = messages;

        SessionRuntime.runExisting(loop, messages);

        assertEquals(List.of("normal", "summary", "normal", "normal", "summary", "normal"), events,
                "两次超限各补救一次;中间那次成功把额度重置了");
        assertEquals(4, client.normalRequests.size());
        assertEquals(2, client.summaryRequests.size());
        // 工具只执行一次:第二次超限重试的是正式请求,不会重跑先前已经跑过的工具。
        assertEquals(1, bash.calls);
        // 第二次补救摘的是"第一次补救的摘要 + 之后两条历史",所以第一次的压缩消息(内含入口请求)
        // 仍原样待在第二次的摘要输入里——当前请求没有被重试重新提取过。
        List<String> secondPrefix = summaryInputLines(client, 1);
        assertTrue(textsOf(secondPrefix).getFirst().contains("Current user request:\n请求9"),
                "第二次补救时当前请求仍是入口那一句");
        // 最终历史的第一条就是第二次补救的压缩消息,请求分区同样来自入口。
        assertTrue(messages.getFirst().extractText().startsWith("[Reactive compact]"));
        assertTrue(messages.getFirst().extractText().contains("Current user request:\n请求9"));
        // 工具交换保持完整:一个 assistant(tool_use) 后面紧跟与之 ID 对应的 tool_result。
        assertEquals(1, messages.stream().filter(m -> !m.blocksOfType(ToolUseBlock.class).isEmpty()).count());
        for (int i = 0; i < messages.size(); i++) {
            List<ToolUseBlock> calls = messages.get(i).blocksOfType(ToolUseBlock.class);
            if (calls.isEmpty()) {
                continue;
            }
            assertEquals(List.of("b1"), calls.stream().map(ToolUseBlock::id).toList());
            assertEquals(List.of("b1"), resultsOf(messages.get(i + 1)).stream()
                    .map(ToolResultBlock::toolUseId).toList());
            assertEquals("普通输出", resultsOf(messages.get(i + 1)).getFirst().content());
        }
        // 两次失败的正式请求都没有被当成业务轮:只发生过一轮工具调用,不足三轮回灌 reminder。
        assertFalse(messages.stream().anyMatch(m -> m.extractText().contains("<reminder>")));
        assertSame(callerList, messages);
    }

    /* ---------------- 8. 跨 run 隔离:不继承额度与入口请求 ---------------- */

    @Test
    void secondRunGetsItsOwnBudgetAndRequest() throws Exception {
        LlmException firstRunFailure = new LlmException("prompt_too_long: 第一次 run");
        LlmException secondRunFailure = new LlmException("prompt_too_long: 第二次 run");
        FakeClient client = new FakeClient(events,
                firstRunFailure, assistant(new TextBlock("完成一")),
                secondRunFailure, assistant(new TextBlock("完成二")));
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits());
        AgentExecutor loop = new AgentExecutor(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks(), compactor);
        List<Message> messages = legalHistory(9);

        SessionRuntime.runExisting(loop, messages);
        // 第一次 run 的补救消息使用的是第一次 run 入口捕获的那句请求。
        assertTrue(messages.getFirst().extractText().startsWith("[Reactive compact]"));
        assertTrue(messages.getFirst().extractText().contains("Current user request:\n请求9"));
        // 同一个 loop 的第二次 run:如果额度被上一次 run 耗尽后没有重置,这里的超限就会直接抛出。
        messages.add(Message.userText("第二个请求"));
        SessionRuntime.runExisting(loop, messages);

        assertEquals(List.of("normal", "summary", "normal", "normal", "summary", "normal"), events);
        assertEquals(2, client.summaryRequests.size());
        // 第二次 run 的压缩消息用的是它自己入口捕获的请求,不继承上一个 run 的。
        assertTrue(messages.getFirst().extractText().contains("Current user request:\n第二个请求"),
                "第二次 run 不得沿用第一次 run 的 activeRequest");
        assertEquals("完成二", messages.getLast().extractText());
    }

    /* ---------------- 9. prepare 自己的超限异常不触发补救 ---------------- */

    @Test
    void overflowKeywordFromPreparationIsNotRecovered() throws Exception {
        // 本地窗口很小,所以第一次正式请求之前 prepare 自己就会摘要在先;让那次摘要抛一个带超限关键词的异常。
        LlmException fromPrepare = new LlmException("too many tokens while summarizing");
        FakeClient client = new FakeClient(events, assistant(new TextBlock("不该被调用")));
        client.summaryFailure = fromPrepare;
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir, narrowLimits());
        AgentExecutor loop = new AgentExecutor(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks(), compactor);
        List<Message> messages = legalHistory(9);
        List<Message> before = List.copyOf(messages);

        LlmException thrown = assertThrows(LlmException.class, () -> SessionRuntime.runExisting(loop, messages));

        // catch 只包住正式 client.complete,所以 prepare 里的摘要异常直接抛出,一个正式请求都没发。
        assertSame(fromPrepare, thrown, "prepare 的异常必须原样抛出,不得进入补救分支");
        assertEquals(List.of("summary"), events, "不得出现正式请求或第二次补救摘要");
        assertEquals(0, client.normalRequests.size());
        assertEquals(9, archiveLines().size(), "prepare 里已经成功写出的归档保留");
        assertEquals(before, messages);
    }

    /* ---------------- 10. 主动 compact 的摘要异常不触发补救、不重跑工具 ---------------- */

    @Test
    void overflowKeywordFromManualCompactionIsNotRecovered() throws Exception {
        LlmException fromManual = new LlmException("prompt_too_long while summarizing compact batch");
        FakeClient client = new FakeClient(events, assistant(use("c1", "compact")),
                assistant(new TextBlock("不该被调用")));
        client.summaryFailure = fromManual;
        FakeTool compact = new FakeTool("compact", com.jadecode.tools.CompactTool.OUTPUT);
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits());
        AgentExecutor loop = new AgentExecutor(client, Map.of("compact", compact), "test-system", 8000, new Hooks(),
                compactor);
        List<Message> messages = new ArrayList<>(List.of(Message.userText("请求9")));

        LlmException thrown = assertThrows(LlmException.class, () -> SessionRuntime.runExisting(loop, messages));

        // 主动压缩的摘要失败同样是"压缩失败",不是"正式请求超限":不嵌套补救、不包装。
        assertSame(fromManual, thrown);
        assertEquals(List.of("normal", "summary"), events, "只有一次正式请求 + 一次主动压缩摘要");
        assertEquals(1, client.normalRequests.size());
        assertEquals(1, compact.calls, "工具只执行一次,补救不会重跑它");
        // 整批闭合交换仍完整保留在历史里(摘要失败不半提交)。
        assertEquals(3, messages.size());
        assertEquals("c1", resultsOf(messages.getLast()).getFirst().toolUseId());
    }

    /* ---------------- 11. 五参 null 压缩器:旧路径不补救 ---------------- */

    @Test
    void legacyPathWithoutCompactorNeverRecovers() throws Exception {
        LlmException overflow = new LlmException("prompt_too_long: 但这条路径没有压缩器");
        FakeClient client = new FakeClient(events, overflow, assistant(new TextBlock("不该被调用")));
        AgentExecutor loop = new AgentExecutor(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks());
        List<Message> messages = new ArrayList<>(List.of(Message.userText("请求")));

        LlmException thrown = assertThrows(LlmException.class, () -> SessionRuntime.runExisting(loop, messages));

        assertSame(overflow, thrown, "未启用压缩器时异常原样传播");
        assertEquals(List.of("normal"), events);
        assertEquals(0, client.summaryRequests.size());
        assertFalse(Files.exists(transcriptDir));
    }
}
