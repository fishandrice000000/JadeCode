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
 * 父循环"正式请求前自动整理"(prepare 接入)的集成测试。
 *
 * <p>
 * 覆盖的 Agent 行为:模型一直不主动请求 compact 时,harness 仍然在<b>每一次正式请求之前</b>
 * 按预算与历史规模整理上下文——先转存超大的最新结果,再按条数归档中段,整体仍过大时才逐层进入
 * micro / fit 与摘要;候选只有在 prepare 正常返回后才被采用,失败则不发起后面的正式请求、
 * 也不半提交或清空会话。用真实 {@link ContextCompactor}(注入小阈值)、假 {@code LlmClient}
 * 与 {@code @TempDir},不调真实 API、不读密钥。
 * </p>
 *
 * <p>
 * 假客户端把自己收到的请求分成两类:<b>正式请求</b>(带工具表)与<b>摘要请求</b>(空工具表),
 * 两者都保存<b>请求快照</b>并记录先后顺序,所以可以断言"摘要在正式请求之前发生",而不是只看最后状态。
 * 工具表里始终注册一个 {@code bash} 假工具,让正式请求天然带工具,和真实装配一致。
 * </p>
 */
class AgentLoopAutomaticPreparationTest {

    /** 摘要输出额度;只用来断言摘要请求确实来自本测试注入的阈值 */
    private static final int SUMMARY_MAX_TOKENS = 77;

    @TempDir
    Path workspace;

    private Path transcriptDir;
    private Path toolResultsDir;

    /** 请求先后顺序:每次 client.complete 追加一项("summary" / "normal") */
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
     * 按"有没有工具表"分流的假客户端。
     *
     * @param events  请求顺序记录(与测试共享的可变列表)
     * @param replies 正式请求按顺序弹出的回复
     */
    private static final class FakeClient implements LlmClient {
        private final List<ModelResponse> replies = new ArrayList<>();
        private final List<String> events;
        private final List<CompleteRequest> normalRequests = new ArrayList<>();
        private final List<CompleteRequest> summaryRequests = new ArrayList<>();
        private LlmException summaryFailure;

        FakeClient(List<String> events, ModelResponse... replies) {
            this.events = events;
            this.replies.addAll(List.of(replies));
        }

        @Override
        public ModelResponse complete(CompleteRequest request) throws LlmException {
            // 快照:请求里的 messages 是可变的,不复制会读到跑完之后的状态。
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
            if (replies.isEmpty()) {
                throw new LlmException("假客户端脚本耗尽");
            }
            return replies.removeFirst();
        }
    }

    /* ---------------- 阈值与造数据 ---------------- */

    /** 只改本测试关心的阈值,其余沿用课程默认值 */
    private static CompactionLimits limits(int contextCharLimit, int batchLimit, int largeLimit, int previewChars,
            int snipMaxMessages, int summaryInputCharLimit) {
        CompactionLimits d = CompactionLimits.courseDefaults();
        return new CompactionLimits(contextCharLimit, batchLimit, largeLimit, summaryInputCharLimit,
                d.keepRecentResults(), d.keepRecentMessages(), d.microResultMinChars(), previewChars,
                d.fitPreviewChars(), SUMMARY_MAX_TOKENS, snipMaxMessages, d.snipHeadMessages(), d.targetRatio());
    }

    /** 阈值不介入的"小历史"场景:一切门槛都放大,prepare 应当 no-op */
    private static CompactionLimits noOpLimits() {
        return limits(200_000, 200_000, 30_000, 2_000, 50, 80_000);
    }

    /** 让"整理历史"必须发生:总字符上限很小,摘要输入上限仍足够放下旧历史 */
    private static CompactionLimits smallWindowLimits() {
        return limits(1_000, 200_000, 30_000, 2_000, 50, 80_000);
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

    /** 造一段可控长度的旧历史文本(不含工具块,所以只有"整体过大"这一条路能压到它) */
    private static String filler(int chars) {
        return "旧".repeat(chars);
    }

    /* ---------------- 磁盘观察 ---------------- */

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

    /**
     * 把一段序列化后的消息数组拆成"一行一条消息"。
     *
     * <p>
     * 摘要输入是一个 JSON 数组,而归档是 JSONL(一行一条)。拆成同样的形状之后,
     * 下面两个辅助就能共用:同一份断言既能描述"摘要收到了什么",也能描述"归档存了什么"。
     * </p>
     */
    private static List<String> linesOf(JsonNode array) {
        List<String> lines = new ArrayList<>();
        for (JsonNode element : array) {
            lines.add(element.toString());
        }
        return lines;
    }

    /** 每行消息的角色序列(用来核对"谁说了什么"的完整输入,而不只是条数) */
    private static List<String> rolesOf(List<String> lines) throws IOException {
        List<String> roles = new ArrayList<>();
        for (String line : lines) {
            roles.add(Json.mapper().readTree(line).get("role").asText());
        }
        return roles;
    }

    /** 每行消息的正文:取 content 里第一条 text 块(本测试只用纯文本消息) */
    private static List<String> textsOf(List<String> lines) throws IOException {
        List<String> texts = new ArrayList<>();
        for (String line : lines) {
            String text = "";
            for (JsonNode block : Json.mapper().readTree(line).get("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text = block.get("text").asText();
                    break;
                }
            }
            texts.add(text);
        }
        return texts;
    }

    /* ---------------- 1. 小历史:prepare no-op,不丢历史 ---------------- */

    @Test
    void smallHistoryIsLeftUntouchedBeforeTheFirstRequest() throws Exception {
        // 门槛都放大的正常对话:自动整理什么都不该做,尤其不能因为"同列表回填"把会话清空。
        FakeClient client = new FakeClient(events, assistant(new TextBlock("完成")));
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir, noOpLimits());
        AgentLoop loop = new AgentLoop(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks(), compactor);
        Message opening = Message.userText("你好");
        List<Message> messages = new ArrayList<>(List.of(opening));
        List<Message> callerList = messages;

        loop.run(messages);

        // 正式请求照常发生;一次摘要、一个归档文件都没有。
        assertEquals(1, client.normalRequests.size());
        assertEquals(0, client.summaryRequests.size(), "小历史不应当触发摘要");
        assertFalse(Files.exists(transcriptDir), "小历史不应当写归档");
        // 历史只是被追加了模型的回复;原来那条人类请求仍是同一个对象,列表身份也没变。
        assertSame(callerList, messages);
        assertEquals(2, messages.size());
        assertSame(opening, messages.getFirst(), "no-op 回填不得重建或清掉原消息");
        assertEquals("完成", messages.getLast().extractText());
    }

    /* ---------------- 2. 条数超 snip 门槛,而总字符仍然很小 ---------------- */

    @Test
    void snipRunsOnMessageCountEvenWhenTotalCharsAreSmall() throws Exception {
        // 这些消息每条只有几个字符:如果自动整理只在"总字符超限"时才进入,它永远不会触发。
        // 正是为了证明 prepare 会独立按消息条数调用第二层,这里把字符上限设得远大于实际字符数。
        CompactionLimits limits = limits(200_000, 200_000, 30_000, 2_000, 4, 80_000);
        FakeClient client = new FakeClient(events, assistant(new TextBlock("完成")));
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir, limits);
        AgentLoop loop = new AgentLoop(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks(), compactor);
        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            messages.add(Message.userText("m" + i));
        }
        List<Message> callerList = messages;

        loop.run(messages);

        // 正式请求看到的是"最初若干条 + 一条 archive marker":中段被归档,结构仍然合法。
        List<Message> seen = client.normalRequests.getFirst().messages();
        assertEquals(4, seen.size(), "10 条历史 + snip 门槛 4:保留头部 3 条并插入一条 marker");
        assertEquals(Role.USER, seen.getLast().role());
        String marker = seen.getLast().extractText();
        assertTrue(marker.startsWith("[7 messages archived at "), "marker 记录被换掉的中段条数与归档路径");
        assertTrue(marker.endsWith("]"));
        // marker 指向的归档必须真实存在,并保存了裁剪前的完整 10 条。
        assertEquals(10, archiveLines().size(), "归档保存裁剪前的完整历史");
        // 会话对象身份保留,调用方看到的就是整理后的内容。
        assertSame(callerList, messages);
        assertEquals(5, messages.size(), "整理后的 4 条 + 模型回复");
    }

    /* ---------------- 3. 首次正式请求之前就摘要 ---------------- */

    @Test
    void firstRequestSeesSummaryBuiltBeforeItAndKeepsTheCapturedRequest() throws Exception {
        // 旧历史是长纯文本(没有任何工具结果),所以预算 / micro / fit 都压不动它——只能走摘要。
        // 同时验证顺序:摘要在第一条正式请求之前完成,请求里看到的就是那条压缩消息。
        FakeClient client = new FakeClient(events, assistant(new TextBlock("完成")));
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir,
                smallWindowLimits());
        AgentLoop loop = new AgentLoop(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks(), compactor);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText(filler(4_000)));
        messages.add(Message.userText("继续任务"));

        loop.run(messages);

        assertEquals(List.of("summary", "normal"), events,
                "首次正式请求之前必须已经完成一次摘要;摘要是独立调用,不算工具轮");
        assertEquals(1, client.summaryRequests.size());
        List<Message> seen = client.normalRequests.getFirst().messages();
        assertEquals(1, seen.size(), "正式请求看到的是压缩后的单条摘要");
        String text = seen.getFirst().extractText();
        assertTrue(text.startsWith("[Compacted]"));
        assertTrue(text.contains("Current user request:\n继续任务"), "摘要里的当前请求必须是入口捕获的那一句");
        // 归档保存的是被摘要掉的完整旧历史,不是摘要本身。
        assertEquals(2, archiveLines().size());
    }

    /* ---------------- 4. 普通工具的大结果:下一次请求前转存,不依赖 compact ---------------- */

    @Test
    void oversizedToolResultIsPersistedBeforeTheNextRequestWithoutAnyCompactCall() throws Exception {
        // 模型只调了普通工具:没有任何 compact 信号,历史上却多了一条 5000 字符的新结果。
        // 下一次正式请求之前,harness 必须自己把这条结果换成"路径 + 预览",且工具只执行一次。
        String huge = "x".repeat(5_000);
        CompactionLimits limits = limits(200_000, 200, 100, 10, 50, 80_000);
        FakeClient client = new FakeClient(events,
                assistant(use("b1", "bash")),
                assistant(new TextBlock("完成")));
        FakeTool bash = new FakeTool("bash", huge);
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir, limits);
        AgentLoop loop = new AgentLoop(client, Map.of("bash", bash), "test-system", 8000, new Hooks(), compactor);
        List<Message> messages = new ArrayList<>(List.of(Message.userText("开始")));

        loop.run(messages);

        assertEquals(1, bash.calls, "自动整理不得重跑工具");
        assertEquals(0, client.summaryRequests.size(), "只靠预算转存就够了,不需要摘要");
        assertEquals(2, client.normalRequests.size());
        assertEquals(List.of("normal", "normal"), events);

        // 第二次正式请求看到的历史:人类请求 + assistant 调用 + 结果外壳,交换仍然一一对应。
        List<Message> seen = client.normalRequests.get(1).messages();
        assertEquals(3, seen.size());
        List<ToolUseBlock> calls = seen.get(1).blocksOfType(ToolUseBlock.class);
        List<ToolResultBlock> results = resultsOf(seen.get(2));
        assertEquals(List.of("b1"), calls.stream().map(ToolUseBlock::id).toList());
        assertEquals(List.of("b1"), results.stream().map(ToolResultBlock::toolUseId).toList());
        String shell = results.getFirst().content();
        assertTrue(shell.startsWith("<persisted-output>"), "超大新鲜结果应换成可恢复外壳");
        // 外壳里的完整原文确实落在盘上,可以按路径取回——这是"可恢复",不是"丢弃"。
        List<Path> persisted = filesIn(toolResultsDir);
        assertEquals(1, persisted.size());
        assertEquals(huge, Files.readString(persisted.getFirst(), StandardCharsets.UTF_8));
        assertEquals(huge.substring(0, 10),
                shell.substring(shell.indexOf("Preview:\n") + "Preview:\n".length()).split("\n")[0],
                "外壳里保留的应是输出开头");
        // 调用方会话被原地更新为整理后的形态(而不是被换成另一个列表)。
        assertEquals(4, messages.size());
        assertEquals(shell, resultsOf(messages.get(2)).getFirst().content());
    }

    /* ---------------- 5. Stop hook 续跑之后仍然整理,请求来源不变 ---------------- */

    @Test
    void stopContinuationIsPreparedBeforeTheSecondFormalRequest() throws Exception {
        // 这个场景的关键在于"需要整理的东西是 Stop 注入的,不是入口历史":
        // 入口只有一句短请求,所以第一次 prepare 无事可做;真正变大的,是 Stop hook 强制续跑时
        // 注入的那段纯文本。它只有在"续跑之后的正式请求同样经过请求边界"时才会被压下去——
        // 如果实现只在 run 入口整理一次,第二个正式请求会原样看到那段大文本、也不会发生摘要,
        // 下面的 events / summary / 请求形态断言都会失败。这样本用例就能区分正确实现与
        // "首次有整理、续跑后跳过整理"的错误实现,而不只是"已经存在的摘要消息还在"。
        String continuation = "续跑要求:请补完剩余用例。" + "细节".repeat(1_000);
        FakeClient client = new FakeClient(events,
                assistant(new TextBlock("我先看看。")),   // 第一次正式回复:短,且没有工具调用
                assistant(new TextBlock("完成")));        // 第二次正式回复:收尾
        Hooks hooks = new Hooks();
        int[] stopCalls = {0};
        hooks.register(Hooks.STOP, params -> {
            stopCalls[0]++;
            return stopCalls[0] == 1 ? continuation : null; // 第一次强制续跑,第二次放行结束
        });
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir,
                smallWindowLimits());
        AgentLoop loop = new AgentLoop(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                hooks, compactor);
        List<Message> messages = new ArrayList<>(List.of(Message.userText("请修复失败的测试")));

        loop.run(messages);

        // 顺序本身就是要害:第一次正式请求之前没有任何整理(短请求无需整理);
        // 摘要发生在"续跑注入之后、第二次正式请求之前"。
        assertEquals(List.of("normal", "summary", "normal"), events,
                "整理必须发生在第二次正式请求之前,而不是第一次之前");
        assertEquals(2, client.normalRequests.size(), "两次正式请求");
        assertEquals(1, client.summaryRequests.size(), "恰好一次摘要");
        // Stop hook 只被两条"没有工具调用"的正式回复触发;摘要调用不进 Stop 生命周期,也不算工具轮。
        assertEquals(2, stopCalls[0], "摘要调用不得触发 Stop hook");

        // 第二个正式请求看到的是新的压缩形态,而且当前请求仍是入口那句短请求——
        // 不是从 Stop 注入文本里重新提取出来的。
        List<Message> seen = client.normalRequests.get(1).messages();
        assertEquals(1, seen.size());
        String compacted = seen.getFirst().extractText();
        assertTrue(compacted.startsWith("[Compacted]"));
        assertTrue(compacted.contains("Current user request:\n请修复失败的测试"));
        assertFalse(compacted.contains("续跑要求"), "续跑文本不得被当成新的当前请求");
        // 压缩后的体量确实回到了小窗口内(说明走的是正常摘要路径,而不是被截断的摘要输入)。
        assertTrue(Json.mapper().writeValueAsString(seen).length() < 1_000, "压缩候选应小于 contextCharLimit");

        // 摘要请求收到的是"入口短请求 + 第一次短回复 + Stop 注入的大文本"这三条的原文。
        List<Message> summarized = client.summaryRequests.getFirst().messages();
        assertEquals(1, summarized.size());
        List<String> summarizedLines = linesOf(Json.mapper().readTree(summarized.getFirst().extractText()));
        assertEquals(3, summarizedLines.size(), "摘要输入应是续跑之后那三条完整历史");
        assertEquals(List.of("user", "assistant", "user"), rolesOf(summarizedLines));
        assertEquals(List.of("请修复失败的测试", "我先看看。", continuation), textsOf(summarizedLines),
                "摘要输入必须原样包含 Stop 注入的文本与之前的短回复");

        // 归档保存的同样是这三条:角色与完整正文逐条核对,而不是只看文件存在或行数。
        List<String> archived = archiveLines();
        assertEquals(3, archived.size());
        assertEquals(List.of("user", "assistant", "user"), rolesOf(archived));
        assertEquals(List.of("请修复失败的测试", "我先看看。", continuation), textsOf(archived));
    }

    /* ---------------- 6. 整理时归档失败:不带出半成品,也不发正式请求 ---------------- */

    @Test
    void archiveFailureStopsBeforeAnyFormalRequestAndKeepsTheSession() throws Exception {
        // 让归档目录的位置上是一个普通文件:写 transcript 必然失败。
        Files.writeString(transcriptDir, "占位文件");
        FakeClient client = new FakeClient(events, assistant(new TextBlock("不该被调用")));
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir,
                smallWindowLimits());
        AgentLoop loop = new AgentLoop(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks(), compactor);
        Message old = Message.userText(filler(4_000));
        Message request = Message.userText("继续任务");
        List<Message> messages = new ArrayList<>(List.of(old, request));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> loop.run(messages));

        assertNotNull(error.getCause(), "归档失败必须保留 cause");
        assertEquals(0, client.normalRequests.size(), "整理失败后不得发起正式请求");
        assertEquals(0, client.summaryRequests.size(), "归档失败时连摘要都不能调用");
        // 会话没有被半提交:还是入口那两条,而且是原来的对象。
        assertEquals(2, messages.size());
        assertSame(old, messages.getFirst());
        assertSame(request, messages.get(1));
    }

    /* ---------------- 7. 整理时摘要失败:原样传播,归档保留,会话不动 ---------------- */

    @Test
    void summaryFailureDuringPreparationPropagatesAndLeavesSessionUntouched() throws Exception {
        LlmException boom = new LlmException("prepare 摘要失败");
        FakeClient client = new FakeClient(events, assistant(new TextBlock("不该被调用")));
        client.summaryFailure = boom;
        ContextCompactor compactor = new ContextCompactor(client, transcriptDir, toolResultsDir,
                smallWindowLimits());
        AgentLoop loop = new AgentLoop(client, Map.of("bash", new FakeTool("bash", "x")), "test-system", 8000,
                new Hooks(), compactor);
        Message old = Message.userText(filler(4_000));
        Message request = Message.userText("继续任务");
        List<Message> messages = new ArrayList<>(List.of(old, request));

        LlmException thrown = assertThrows(LlmException.class, () -> loop.run(messages));

        assertSame(boom, thrown, "摘要异常必须原样传播");
        assertEquals(1, client.summaryRequests.size());
        assertEquals(0, client.normalRequests.size(), "摘要失败后不得发起正式请求");
        // 归档在摘要之前就已成功写出,保留下来供回查。
        assertEquals(2, archiveLines().size(), "先归档再摘要:已写出的归档必须保留");
        // 会话没有被清空或半替换。
        assertEquals(2, messages.size());
        assertSame(old, messages.getFirst());
        assertSame(request, messages.get(1));
    }
}
