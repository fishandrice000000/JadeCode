package com.jadecode.subagent;

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
import com.jadecode.context.CompactionPolicy;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子代理上下文压缩与有限恢复(7D)的集成测试。
 *
 * <p>
 * 覆盖的 Agent 行为:一次委派从全新历史起步,但子任务自己会读文件、跑构建,所以子会话同样要
 * "请求前自动整理 → 批次闭合后的主动压缩 → 正式请求被判定超限时的有限补救";同时保证
 * <b>子任务目标是入口 prompt 原文</b>(不被 Stop 注入顶替、不与另一次委派串味)、
 * <b>轮次只按成功回复计</b>(失败尝试与辅助摘要不占轮)、以及 START / END 恰好各一次。
 * </p>
 *
 * <p>
 * 假客户端把请求分成<b>正式请求</b>(带工具表)与<b>摘要请求</b>(空工具表),两者都保存请求<b>快照</b>
 * (子代理内部历史不对外暴露,所以只能从请求快照观察它看到了什么)并记录先后顺序;
 * 正式请求按脚本弹出"回复或异常",所以第几次失败、第几次成功完全可控。
 * </p>
 */
class SubagentRuntimeCompactionTest {

    /** 摘要输出额度:由 {@link #assertSummaryShape} 真正断言,确保摘要请求用的是本测试注入的阈值 */
    private static final int SUMMARY_MAX_TOKENS = 77;

    @TempDir
    Path workspace;

    private Path transcriptDir;
    private Path toolResultsDir;

    /** 请求顺序与 hook 顺序记录 */
    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transcriptDir = workspace.resolve("transcripts");
        toolResultsDir = workspace.resolve("tool-results");
        events.clear();
    }

    /* ---------------- 假工具与假客户端 ---------------- */

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
     * 脚本化假客户端:正式请求按顺序弹出 {@code ModelResponse} 或 {@link LlmException},摘要请求返回固定文本。
     *
     * @param events 顺序记录(与测试共享的可变列表)
     * @param script 正式请求剧本:成功回复或异常
     */
    private static final class ScriptedClient implements LlmClient {
        private final List<Object> script = new ArrayList<>();
        private final List<String> events;
        private final List<CompleteRequest> normalRequests = new ArrayList<>();
        private final List<CompleteRequest> summaryRequests = new ArrayList<>();
        private LlmException summaryFailure;

        ScriptedClient(List<String> events, Object... script) {
            this.events = events;
            this.script.addAll(List.of(script));
        }

        @Override
        public ModelResponse complete(CompleteRequest request) throws LlmException {
            // 快照:请求里的 messages 是可变列表,而子代理的内部历史不会对外暴露——
            // 只保存引用会在后续 clear / addAll 之后读到被改写的内容,所以必须复制。
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
                throw new LlmException("脚本耗尽");
            }
            Object next = script.removeFirst();
            if (next instanceof LlmException failure) {
                throw failure;
            }
            return (ModelResponse) next;
        }
    }

    /* ---------------- 阈值与造数据 ---------------- */

    private static CompactionLimits limits(int contextCharLimit, int batchLimit, int largeLimit, int previewChars) {
        CompactionLimits d = CompactionLimits.courseDefaults();
        return new CompactionLimits(contextCharLimit, batchLimit, largeLimit, 80_000,
                d.keepRecentResults(), d.keepRecentMessages(), d.microResultMinChars(), previewChars,
                d.fitPreviewChars(), SUMMARY_MAX_TOKENS, 50, d.snipHeadMessages(), d.targetRatio());
    }

    /** prepare 永不摘要:把"主动压缩 / 补救"这些分支单独隔离出来观察 */
    private static CompactionLimits wideLimits() {
        return limits(200_000, 200_000, 30_000, 2_000);
    }

    /** 本地窗口很小:prepare 自己就会摘要(用来验证请求前整理与它的失败边界) */
    private static CompactionLimits narrowLimits() {
        return limits(200, 200_000, 30_000, 2_000);
    }

    /** 小预算:最新工具结果一超 200 字符就被转存成"路径 + 预览" */
    private static CompactionLimits budgetLimits() {
        return limits(200_000, 200, 100, 10);
    }

    private static ModelResponse assistant(ContentBlock... blocks) {
        return new ModelResponse(Message.assistant(List.of(blocks)), new ModelResponse.Usage(0, 0));
    }

    private static ModelResponse toolReply(String id, String name) {
        return assistant(new ToolUseBlock(id, name, Json.mapper().createObjectNode()));
    }

    private static ModelResponse textReply(String text) {
        return assistant(new TextBlock(text));
    }

    /** 造一段长纯文本(用来把历史撑过很小的本地窗口) */
    private static String filler(int chars) {
        return "旧".repeat(chars);
    }

    /* ---------------- 记录与观察辅助 ---------------- */

    /** 登记一个收集 SUBAGENT_END 入参的 hook */
    private static List<Map<String, ?>> recordEnds(Hooks hooks) {
        List<Map<String, ?>> ends = new ArrayList<>();
        hooks.register(Hooks.SUBAGENT_END, params -> {
            ends.add(params);
            return null;
        });
        return ends;
    }

    /** 登记一个记录 SUBAGENT_START 次数的 hook:生命周期"每 run 一次"必须真的被断言,不能只写在注释里 */
    private static List<String> recordStarts(Hooks hooks) {
        List<String> starts = new ArrayList<>();
        hooks.register(Hooks.SUBAGENT_START, params -> {
            starts.add("start");
            return null;
        });
        return starts;
    }

    /** 摘要请求的形状:不带任何工具表,并使用注入的输出额度 */
    private static void assertSummaryShape(ScriptedClient client, int index) {
        assertTrue(client.summaryRequests.get(index).tools().isEmpty(), "摘要请求不得携带工具表");
        assertEquals(SUMMARY_MAX_TOKENS, client.summaryRequests.get(index).maxTokens(),
                "摘要请求应使用本测试注入的输出额度");
    }

    /**
     * 按结构比较"归档行"与期望的消息列表。
     *
     * <p>
     * 把两边都解析成 JSON 树再比:既校验角色、块类型与完整正文(含尾部),又不受序列化空白影响,
     * 比只数行数或查标签更能证明"归档保存的是这份历史"。
     * </p>
     */
    private static void assertArchivedMessages(List<Message> expected, List<String> archivedLines) throws IOException {
        assertEquals(Json.mapper().readTree(Json.mapper().writeValueAsString(expected)),
                Json.mapper().readTree("[" + String.join(",", archivedLines) + "]"));
    }

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

    /** 把一段序列化后的消息数组拆成"一行一条消息" */
    private static List<String> linesOf(JsonNode array) {
        List<String> lines = new ArrayList<>();
        for (JsonNode element : array) {
            lines.add(element.toString());
        }
        return lines;
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

    private static List<String> summaryInputTexts(ScriptedClient client, int index) throws IOException {
        return textsOf(linesOf(Json.mapper().readTree(
                client.summaryRequests.get(index).messages().getFirst().extractText())));
    }

    /** 每条消息的"可见内容":text 块正文与 tool_result 的 content 都算进来 */
    private static List<String> contentsOf(List<String> lines) throws IOException {
        List<String> contents = new ArrayList<>();
        for (String line : lines) {
            StringBuilder visible = new StringBuilder();
            for (JsonNode block : Json.mapper().readTree(line).get("content")) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    visible.append(block.get("text").asText());
                } else if ("tool_result".equals(type)) {
                    visible.append(block.get("content").asText());
                }
            }
            contents.add(visible.toString());
        }
        return contents;
    }

    /** 造 {@code n} 个"成功但还在调工具"的回复:用来把历史撑到足够长,让补救真的能切出前缀 */
    private static List<Object> toolRounds(int n) {
        List<Object> script = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            script.add(toolReply("b" + i, "bash"));
        }
        return script;
    }

    private static List<ToolResultBlock> resultsOf(Message message) {
        return message.blocksOfType(ToolResultBlock.class);
    }

    /* ---------------- 1. 装配与旧路径 ---------------- */

    @Test
    void legacyPathStillWorksAndMisconfigurationIsRejectedEarly() {
        // (a) 五参构造器 = 旧路径:没有压缩器,一切照旧,不归档、不摘要。
        ScriptedClient client = new ScriptedClient(events, textReply("旧路径结论"));
        Hooks hooks = new Hooks();
        List<Map<String, ?>> ends = recordEnds(hooks);
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("bash", new FakeTool("bash", "x")),
                "sub-system", 8000, hooks);

        assertEquals("旧路径结论", assertDoesNotThrowRun(runtime, "旧任务"));
        assertEquals(0, client.summaryRequests.size());
        assertFalse(Files.exists(transcriptDir));
        assertEquals(SubagentOutcome.DONE, ends.getFirst().get(Hooks.PARAM_OUTCOME));

        // (b) 注册了 compact 却没有压缩器:构造时就拒绝,不留到运行期静默失效。
        IllegalArgumentException misconfigured = assertThrows(IllegalArgumentException.class,
                () -> new SubagentRuntime(client, Map.of("compact", new FakeTool("compact", CompactTool.OUTPUT)),
                        "sub-system", 8000, hooks));
        assertTrue(misconfigured.getMessage().contains("ContextCompactor"));
    }

    /** 包一层:把受检异常转成断言失败,让"正常返回"的用例更短 */
    private static String assertDoesNotThrowRun(SubagentRuntime runtime, String prompt) {
        try {
            return runtime.run(prompt);
        } catch (LlmException e) {
            throw new AssertionError("本用例不应抛异常", e);
        }
    }

    /* ---------------- 2. 请求前自动整理:大工具结果 / Stop 大文本 ---------------- */

    @Test
    void oversizedToolResultIsPersistedBeforeTheNextSubagentRequest() throws Exception {
        // 注入压缩器但不注册 compact:自动整理这条能力独立生效。
        String huge = "x".repeat(5_000);
        ScriptedClient client = new ScriptedClient(events, toolReply("b1", "bash"), textReply("完成"));
        FakeTool bash = new FakeTool("bash", huge);
        Hooks hooks = new Hooks();
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("bash", bash), "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, budgetLimits(), CompactionPolicy.coursePipeline()));

        assertEquals("完成", runtime.run("跑一次构建"));

        assertEquals(1, bash.calls, "自动整理不得重跑工具");
        assertEquals(0, client.summaryRequests.size(), "只靠预算转存就够了");
        // 第二次正式请求看到的是可恢复外壳,而不是 5000 字原文;原文完整落盘。
        List<Message> seen = client.normalRequests.get(1).messages();
        assertEquals(3, seen.size());
        String shell = resultsOf(seen.getLast()).getFirst().content();
        assertTrue(shell.startsWith("<persisted-output>"));
        List<Path> persisted = filesIn(toolResultsDir);
        assertEquals(1, persisted.size());
        assertEquals(huge, Files.readString(persisted.getFirst(), StandardCharsets.UTF_8));
    }

    @Test
    void stopInjectedTextIsPreparedAndDoesNotReplaceTheSubagentGoal() throws Exception {
        // 入口 prompt 很短;Stop hook 注入一大段续跑文本,才把历史撑过很小的本地窗口。
        String continuation = "续跑要求:继续查。" + "细节".repeat(500);
        ScriptedClient client = new ScriptedClient(events, textReply("先停"), textReply("完成"));
        Hooks hooks = new Hooks();
        int[] stopCalls = {0};
        hooks.register(Hooks.STOP, params -> {
            stopCalls[0]++;
            return stopCalls[0] == 1 ? continuation : null;
        });
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("bash", new FakeTool("bash", "x")),
                "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, narrowLimits(), CompactionPolicy.coursePipeline()));

        assertEquals("完成", runtime.run("只查一个模块"));

        // 第一次请求前没有可整理的(短 prompt);摘要在续跑注入之后、第二次请求之前发生。
        assertEquals(List.of("normal", "summary", "normal"), events);
        assertEquals(1, client.summaryRequests.size());
        // 摘要覆盖的是"入口 prompt + 第一次短回复 + Stop 大文本"这一整段。
        assertTrue(summaryInputTexts(client, 0).getLast().equals(continuation));
        // 第二次正式请求看到压缩消息:当前请求是入口 prompt 原文,不是 Stop 文本。
        List<Message> seen = client.normalRequests.get(1).messages();
        String compacted = seen.getFirst().extractText();
        assertTrue(compacted.startsWith("[Compacted]"));
        assertTrue(compacted.contains("Current user request:\n只查一个模块"));
        assertFalse(compacted.contains("续跑要求"), "Stop 文本不得顶替入口子目标");
    }

    /* ---------------- 3. 主动压缩:纯批次 / 混合批次 / 不误触发 / 标志跨批清空 ---------------- */

    @Test
    void pureCompactBatchSummarizesWholeSubagentHistory() throws Exception {
        ModelResponse compactReply = toolReply("c1", "compact");
        ScriptedClient client = new ScriptedClient(events, compactReply, textReply("完成"));
        Hooks hooks = new Hooks();
        List<String> starts = recordStarts(hooks);
        List<Map<String, ?>> ends = recordEnds(hooks);
        SubagentRuntime runtime = new SubagentRuntime(client,
                Map.of("compact", new FakeTool("compact", CompactTool.OUTPUT)), "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        assertEquals("完成", runtime.run("阶段一"));

        assertEquals(1, client.summaryRequests.size(), "纯 compact 批次压缩一次");
        assertSummaryShape(client, 0);
        // 整段闭合历史:prompt + assistant(compact) + tool_result;第三条的正文在 tool_result 块里。
        List<Message> expected = List.of(Message.userText("阶段一"), compactReply.message(),
                Message.userBlocks(List.of(new ToolResultBlock("c1", CompactTool.OUTPUT))));
        List<String> summarized = contentsOf(linesOf(Json.mapper().readTree(
                client.summaryRequests.getFirst().messages().getFirst().extractText())));
        assertEquals(3, summarized.size());
        assertEquals("阶段一", summarized.getFirst());
        assertEquals(CompactTool.OUTPUT, summarized.getLast());
        // 摘要输入按结构等于整段历史(不是只看条数 / 前缀)。
        assertEquals(Json.mapper().readTree(Json.mapper().writeValueAsString(expected)),
                Json.mapper().readTree(client.summaryRequests.getFirst().messages().getFirst().extractText()));
        // 归档保存的就是整段历史,按结构核对(含尾部 tool_result)。
        assertArchivedMessages(expected, archiveLines());
        assertEquals(1, starts.size(), "一次委派只 START 一次");
        assertEquals(1, ends.size());
        assertEquals(SubagentOutcome.DONE, ends.getFirst().get(Hooks.PARAM_OUTCOME));
    }

    @Test
    void mixedCompactBatchKeepsLatestExchangeAndRunsHooksInOrder() throws Exception {
        ModelResponse mixedReply = assistant(
                new ToolUseBlock("b1", "bash", Json.mapper().createObjectNode()),
                new ToolUseBlock("c1", "compact", Json.mapper().createObjectNode()));
        ScriptedClient client = new ScriptedClient(events, mixedReply,
                textReply("完成"));
        Hooks hooks = new Hooks();
        hooks.register(Hooks.PRE_TOOL_USE, params -> {
            events.add("pre:" + ((ToolUseBlock) params.get(Hooks.PARAM_BLOCK)).name());
            return null;
        });
        hooks.register(Hooks.POST_TOOL_USE, params -> {
            events.add("post:" + ((ToolUseBlock) params.get(Hooks.PARAM_BLOCK)).name());
            return null;
        });
        hooks.register(Hooks.SUBAGENT_TOOL, params -> {
            events.add("sub:" + ((ToolUseBlock) params.get(Hooks.PARAM_BLOCK)).name());
            return null;
        });
        SubagentRuntime runtime = new SubagentRuntime(client,
                Map.of("bash", new FakeTool("bash", "普通输出"), "compact", new FakeTool("compact", CompactTool.OUTPUT)),
                "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        assertEquals("完成", runtime.run("阶段二"));

        // 事件顺序:工具生命周期逐个走完 → 整批结果回灌 → 才轮到压缩摘要 → 再发下一次正式请求。
        assertEquals(List.of("normal", "pre:bash", "post:bash", "sub:bash",
                "pre:compact", "post:compact", "sub:compact", "summary", "normal"), events);
        assertEquals(1, client.summaryRequests.size());
        assertSummaryShape(client, 0);
        // 混合批次只摘要旧前缀(prompt 那一条),最新交换留在摘要后面。
        assertEquals(List.of("阶段二"), summaryInputTexts(client, 0));
        // 下一次正式请求看到的是"摘要 + 原样保留的最新交换":角色、顺序、调用 / 结果 ID 与完整正文都要核对。
        List<Message> next = client.normalRequests.get(1).messages();
        assertEquals(3, next.size());
        assertTrue(next.get(0).extractText().startsWith("[Compacted]"));
        assertEquals(Role.ASSISTANT, next.get(1).role());
        assertSame(mixedReply.message(), next.get(1), "尾部 assistant 必须按原引用保留");
        assertEquals(List.of("b1", "c1"),
                next.get(1).blocksOfType(ToolUseBlock.class).stream().map(ToolUseBlock::id).toList());
        assertEquals(Role.USER, next.get(2).role());
        List<ToolResultBlock> tailResults = resultsOf(next.get(2));
        assertEquals(List.of("b1", "c1"), tailResults.stream().map(ToolResultBlock::toolUseId).toList());
        assertEquals(List.of("普通输出", CompactTool.OUTPUT),
                tailResults.stream().map(ToolResultBlock::content).toList());
        // 归档 = 压缩前的完整历史(含最新交换),按结构核对,不只数行。
        assertArchivedMessages(List.of(Message.userText("阶段二"), mixedReply.message(),
                Message.userBlocks(List.of(new ToolResultBlock("b1", "普通输出"),
                        new ToolResultBlock("c1", CompactTool.OUTPUT)))), archiveLines());
    }

    @Test
    void blockedOrFakeCompactDoesNotTriggerSummariesAndTheFlagDoesNotLeak() throws Exception {
        // (a) compact 被 PRE 拦截:即使拒绝文案恰好等于稳定输出,也不触发。
        ScriptedClient blocked = new ScriptedClient(events, toolReply("c1", "compact"), textReply("完成"));
        Hooks blocking = new Hooks();
        blocking.register(Hooks.PRE_TOOL_USE, params -> CompactTool.OUTPUT);
        SubagentRuntime blockedRuntime = new SubagentRuntime(blocked,
                Map.of("compact", new FakeTool("compact", CompactTool.OUTPUT)), "sub-system", 8000, blocking,
                new ContextCompactor(blocked, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));
        assertEquals("完成", blockedRuntime.run("被拦"));
        assertEquals(0, blocked.summaryRequests.size(), "被拦截的 compact 不得触发摘要");
        assertFalse(Files.exists(transcriptDir));

        // (b) 别的工具返回同样文案:不误触发。
        transcriptDir = workspace.resolve("transcripts-fake");
        ScriptedClient fake = new ScriptedClient(new ArrayList<>(), toolReply("b1", "bash"), textReply("完成"));
        SubagentRuntime fakeRuntime = new SubagentRuntime(fake,
                Map.of("bash", new FakeTool("bash", CompactTool.OUTPUT)), "sub-system", 8000, new Hooks(),
                new ContextCompactor(fake, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));
        assertEquals("完成", fakeRuntime.run("伪成功"));
        assertEquals(0, fake.summaryRequests.size(), "普通工具返回同样文案不得误触发");

        // (c) 一批成功 compact 之后再一批普通工具:标志不跨批残留。
        transcriptDir = workspace.resolve("transcripts-reset");
        List<String> resetEvents = new ArrayList<>();
        ScriptedClient reset = new ScriptedClient(resetEvents, toolReply("c1", "compact"),
                toolReply("b1", "bash"), textReply("完成"));
        SubagentRuntime resetRuntime = new SubagentRuntime(reset,
                Map.of("compact", new FakeTool("compact", CompactTool.OUTPUT), "bash", new FakeTool("bash", "输出")),
                "sub-system", 8000, new Hooks(),
                new ContextCompactor(reset, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));
        assertEquals("完成", resetRuntime.run("两批"));
        assertEquals(1, reset.summaryRequests.size(), "第二批复用上一批的压缩标志会多压一次");
    }

    /* ---------------- 4. 正式请求超限 → 有限补救 ---------------- */

    @Test
    void overflowIsRecoveredOnceAndTheDelegationStillEndsWithDone() throws Exception {
        // 先跑三轮工具回复把历史撑到"尾部之外还有可裁前缀"的长度,补救才会真的产生一次前缀摘要。
        LlmException overflow = new LlmException("prompt_too_long: 子任务上下文过长");
        List<Object> script = toolRounds(3);
        script.add(overflow);
        script.add(textReply("搞定"));
        ScriptedClient client = new ScriptedClient(events, script.toArray());
        Hooks hooks = new Hooks();
        List<String> starts = recordStarts(hooks);
        List<Map<String, ?>> ends = recordEnds(hooks);
        FakeTool bash = new FakeTool("bash", "小输出");
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("bash", bash),
                "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        assertEquals("搞定", runtime.run("查清楚这个模块"));

        // 补救成功不算"新起一个子代理":过程中没有提前 END,最后只 DONE 一次。
        assertEquals(List.of("normal", "normal", "normal", "normal", "summary", "normal"), events);
        assertEquals(1, starts.size(), "补救不新起委派:START 仍只有一次");
        assertEquals(1, ends.size(), "END 必须恰好一次,且不得在补救时提前触发");
        assertEquals(SubagentOutcome.DONE, ends.getFirst().get(Hooks.PARAM_OUTCOME));
        assertEquals(3, bash.calls, "失败的那次正式请求不执行任何工具");
        // 重试的请求看到的是响应式摘要 + 原样保留的尾部:安全切点会把整对工具交换拉进尾部,
        // 所以这里保住了 6 条(3 组 assistant/tool_result),摘要只覆盖最前面的 prompt。
        List<Message> retried = client.normalRequests.getLast().messages();
        assertEquals(7, retried.size());
        assertTrue(retried.getFirst().extractText().startsWith("[Reactive compact]"));
        for (int i = 1; i < retried.size(); i++) {
            assertEquals(i % 2 == 1 ? Role.ASSISTANT : Role.USER, retried.get(i).role());
        }
        // 尾部第一对仍是同一组调用 / 结果 ID。
        assertEquals(List.of("b1"),
                retried.get(1).blocksOfType(ToolUseBlock.class).stream().map(ToolUseBlock::id).toList());
        assertEquals(List.of("b1"),
                resultsOf(retried.get(2)).stream().map(ToolResultBlock::toolUseId).toList());
        // 归档保存补救前的完整历史(1 条 prompt + 三轮工具往返),按结构逐条核对。
        List<Message> expectedArchive = new ArrayList<>();
        expectedArchive.add(Message.userText("查清楚这个模块"));
        for (int i = 1; i <= 3; i++) {
            expectedArchive.add(assistant(new ToolUseBlock("b" + i, "bash", Json.mapper().createObjectNode()))
                    .message());
            expectedArchive.add(Message.userBlocks(List.of(new ToolResultBlock("b" + i, "小输出"))));
        }
        assertArchivedMessages(expectedArchive, archiveLines());
        assertSummaryShape(client, 0);
    }

    @Test
    void nonMatchingFailureAndExhaustedBudgetKeepOriginalExceptions() throws Exception {
        // (a) 命中不了分类的认证失败:零补救,异常原对象抛出,END(FAILED) 一次。
        LlmException unauthorized = new LlmException("401 unauthorized");
        ScriptedClient client = new ScriptedClient(events, unauthorized, textReply("不该被调用"));
        Hooks hooks = new Hooks();
        List<Map<String, ?>> ends = recordEnds(hooks);
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("bash", new FakeTool("bash", "x")),
                "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        LlmException thrown = assertThrows(LlmException.class, () -> runtime.run("任务"));

        assertSame(unauthorized, thrown);
        assertEquals(List.of("normal"), events);
        assertEquals(0, client.summaryRequests.size());
        assertEquals(1, ends.size());
        assertEquals(SubagentOutcome.FAILED, ends.getFirst().get(Hooks.PARAM_OUTCOME));
        assertEquals("Error: 401 unauthorized", ends.getFirst().get(Hooks.PARAM_OUTPUT));

        // (b) 补救之后再次超限:只补救一次,抛第二次那个异常对象。
        transcriptDir = workspace.resolve("transcripts-second");
        events.clear();
        LlmException first = new LlmException("prompt_too_long: 第一次");
        LlmException second = new LlmException("prompt_too_long: 第二次");
        // 同样先用三轮工具回复把历史撑长,否则空前缀 no-op 会把"只补救一次"的断言变成两条普通事件。
        List<Object> longScript = toolRounds(3);
        longScript.add(first);
        longScript.add(second);
        longScript.add(textReply("不该被调用"));
        ScriptedClient retry = new ScriptedClient(events, longScript.toArray());
        Hooks retryHooks = new Hooks();
        List<Map<String, ?>> retryEnds = recordEnds(retryHooks);
        SubagentRuntime retryRuntime = new SubagentRuntime(retry, Map.of("bash", new FakeTool("bash", "x")),
                "sub-system", 8000, retryHooks,
                new ContextCompactor(retry, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        LlmException secondThrown = assertThrows(LlmException.class, () -> retryRuntime.run("任务"));

        assertSame(second, secondThrown, "额度耗尽后抛当前这一次的异常对象");
        assertEquals(List.of("normal", "normal", "normal", "normal", "summary", "normal"), events);
        assertEquals(1, retry.summaryRequests.size());
        assertEquals(1, retryEnds.size(), "恢复过程与最终失败之间只能有一次 END");
        assertEquals(SubagentOutcome.FAILED, retryEnds.getFirst().get(Hooks.PARAM_OUTCOME));
        assertEquals("Error: prompt_too_long: 第二次", retryEnds.getFirst().get(Hooks.PARAM_OUTPUT));
    }

    /* ---------------- 5. 轮次单位:失败尝试与辅助摘要都不占轮 ---------------- */

    @Test
    void failedAttemptAndItsSummaryDoNotConsumeATurn() {
        // 29 个成功的工具回复 → 第 30 次正式请求超限 + 一次补救摘要 → 第 30 个成功回复给出最终答案。
        // 如果实现把失败尝试或辅助摘要算成一轮,这里就会在第 30 次回复之前 STOPPED,断言随之失败。
        List<Object> script = new ArrayList<>();
        for (int i = 1; i <= 29; i++) {
            script.add(toolReply("b" + i, "bash"));
        }
        script.add(new LlmException("prompt_too_long: 第 30 次请求超限"));
        script.add(textReply("最终结论"));
        ScriptedClient client = new ScriptedClient(events, script.toArray());
        Hooks hooks = new Hooks();
        List<Map<String, ?>> ends = recordEnds(hooks);
        FakeTool bash = new FakeTool("bash", "小输出");
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("bash", bash), "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        assertEquals("最终结论", assertDoesNotThrowRun(runtime, "长任务"));

        assertEquals(1, ends.size());
        assertEquals(SubagentOutcome.DONE, ends.getFirst().get(Hooks.PARAM_OUTCOME), "失败与摘要不得占轮");
        assertEquals(29, bash.calls, "每次工具回复执行一次;失败的那次不执行任何工具");
        assertEquals(1, client.summaryRequests.size(), "补救只摘要一次");
        assertEquals(31, client.normalRequests.size(), "29 次工具回复 + 1 次失败 + 1 次补救后的成功");
    }

    @Test
    void persistentToolRepliesStopAfterExactlyThirtySuccessfulTurns() {
        // 一直调工具:第 30 次成功回复的工具照常执行并闭合,但不再发第 31 次业务请求。
        List<Object> script = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            script.add(toolReply("b" + i, "bash"));
        }
        ScriptedClient client = new ScriptedClient(events, script.toArray());
        Hooks hooks = new Hooks();
        List<Map<String, ?>> ends = recordEnds(hooks);
        FakeTool bash = new FakeTool("bash", "小输出");
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("bash", bash), "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        String result = assertDoesNotThrowRun(runtime, "一直调工具");

        assertEquals("Subagent stopped after 30 turns without a final answer.", result);
        assertEquals(30, client.normalRequests.size(), "不得发第 31 次正式业务请求");
        assertEquals(30, bash.calls);
        assertEquals(1, ends.size());
        assertEquals(SubagentOutcome.STOPPED, ends.getFirst().get(Hooks.PARAM_OUTCOME));
    }

    /* ---------------- 6. 辅助摘要与归档失败:FAILED 恰一次、原对象传播 ---------------- */

    @Test
    void summaryAndArchiveFailuresDuringPreparationEndFailedExactlyOnce() throws Exception {
        // (a) prepare 里的摘要失败(普通文案):抛原对象,一个正式请求都没发。
        LlmException summaryBoom = new LlmException("prepare 摘要失败");
        ScriptedClient client = new ScriptedClient(events, textReply("不该被调用"));
        client.summaryFailure = summaryBoom;
        Hooks hooks = new Hooks();
        List<Map<String, ?>> ends = recordEnds(hooks);
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("bash", new FakeTool("bash", "x")),
                "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, narrowLimits(), CompactionPolicy.coursePipeline()));

        LlmException thrown = assertThrows(LlmException.class, () -> runtime.run(filler(500)));

        assertSame(summaryBoom, thrown);
        assertEquals(List.of("summary"), events, "摘要失败后不得再发正式请求");
        assertEquals(0, client.normalRequests.size());
        assertEquals(1, ends.size(), "END 恰好一次");
        assertEquals(SubagentOutcome.FAILED, ends.getFirst().get(Hooks.PARAM_OUTCOME));
        assertEquals("Error: prepare 摘要失败", ends.getFirst().get(Hooks.PARAM_OUTPUT));
        assertEquals(1, archiveLines().size(), "摘要之前写出的归档保留");

        // (b) prepare 里的归档失败:带 cause 的 IllegalStateException,同样只 END(FAILED) 一次。
        transcriptDir = workspace.resolve("transcripts-archive");
        Files.writeString(transcriptDir, "占位文件");
        events.clear();
        ScriptedClient archiveClient = new ScriptedClient(events, textReply("不该被调用"));
        Hooks archiveHooks = new Hooks();
        List<Map<String, ?>> archiveEnds = recordEnds(archiveHooks);
        SubagentRuntime archiveRuntime = new SubagentRuntime(archiveClient,
                Map.of("bash", new FakeTool("bash", "x")), "sub-system", 8000, archiveHooks,
                new ContextCompactor(archiveClient, transcriptDir, toolResultsDir, narrowLimits(), CompactionPolicy.coursePipeline()));

        IllegalStateException archiveError = assertThrows(IllegalStateException.class,
                () -> archiveRuntime.run(filler(500)));

        assertNotNull(archiveError.getCause());
        assertEquals(0, archiveClient.normalRequests.size());
        assertEquals(1, archiveEnds.size());
        assertEquals(SubagentOutcome.FAILED, archiveEnds.getFirst().get(Hooks.PARAM_OUTCOME));
    }

    @Test
    void summaryFailureWithOverflowKeywordIsNotRecoveredRecursively() throws Exception {
        // 摘要异常即使带着超限关键词,也只是"压缩失败",不能进恢复分支再补救一次。
        LlmException keyworded = new LlmException("prompt_too_long while summarizing");
        ScriptedClient client = new ScriptedClient(events, textReply("不该被调用"));
        client.summaryFailure = keyworded;
        Hooks hooks = new Hooks();
        List<Map<String, ?>> ends = recordEnds(hooks);
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("bash", new FakeTool("bash", "x")),
                "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, narrowLimits(), CompactionPolicy.coursePipeline()));

        LlmException thrown = assertThrows(LlmException.class, () -> runtime.run(filler(500)));

        assertSame(keyworded, thrown);
        assertEquals(List.of("summary"), events, "不得出现补救或正式请求");
        assertEquals(0, client.normalRequests.size());
        assertEquals(1, ends.size());
        assertEquals(SubagentOutcome.FAILED, ends.getFirst().get(Hooks.PARAM_OUTCOME));
        assertEquals("Error: prompt_too_long while summarizing", ends.getFirst().get(Hooks.PARAM_OUTPUT));
    }

    @Test
    void manualAndReactiveCompactionFailuresEndFailedOnceWithoutRerunningTools() throws Exception {
        // (a) 主动 compact 的摘要失败:工具不重跑,闭合历史保留,END(FAILED) 一次。
        LlmException manualBoom = new LlmException("主动压缩摘要失败");
        ScriptedClient client = new ScriptedClient(events, toolReply("c1", "compact"));
        client.summaryFailure = manualBoom;
        Hooks hooks = new Hooks();
        List<Map<String, ?>> ends = recordEnds(hooks);
        FakeTool compact = new FakeTool("compact", CompactTool.OUTPUT);
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("compact", compact), "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        LlmException thrown = assertThrows(LlmException.class, () -> runtime.run("阶段"));

        assertSame(manualBoom, thrown);
        assertEquals(1, compact.calls, "摘要失败不得重跑已经执行过的工具");
        assertEquals(1, ends.size());
        assertEquals(SubagentOutcome.FAILED, ends.getFirst().get(Hooks.PARAM_OUTCOME));

        // (b) 补救里的摘要失败:归档已写出并保留,不再发后续正式请求。
        transcriptDir = workspace.resolve("transcripts-reactive");
        events.clear();
        LlmException reactiveBoom = new LlmException("补救摘要失败");
        // 先三轮工具回复把历史撑长,补救才会真的走到"归档 → 摘要"这一步。
        List<Object> retryScript = toolRounds(3);
        retryScript.add(new LlmException("prompt_too_long: 需要补救"));
        retryScript.add(textReply("不该被调用"));
        ScriptedClient retry = new ScriptedClient(events, retryScript.toArray());
        retry.summaryFailure = reactiveBoom;
        Hooks retryHooks = new Hooks();
        List<Map<String, ?>> retryEnds = recordEnds(retryHooks);
        SubagentRuntime retryRuntime = new SubagentRuntime(retry, Map.of("bash", new FakeTool("bash", "x")),
                "sub-system", 8000, retryHooks,
                new ContextCompactor(retry, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        LlmException retryThrown = assertThrows(LlmException.class, () -> retryRuntime.run("任务"));

        assertSame(reactiveBoom, retryThrown);
        assertEquals(List.of("normal", "normal", "normal", "normal", "summary"), events);
        assertEquals(4, retry.normalRequests.size(), "补救失败后不得再发正式请求");
        assertEquals(1, retryEnds.size());
        assertEquals(SubagentOutcome.FAILED, retryEnds.getFirst().get(Hooks.PARAM_OUTCOME));
        assertEquals("Error: 补救摘要失败", retryEnds.getFirst().get(Hooks.PARAM_OUTPUT));
        assertEquals(7, archiveLines().size(), "归档(三轮工具往返后的完整历史)在摘要之前写出并保留");
    }

    /* ---------------- 7. 跨 run:子目标与历史不串 ---------------- */

    @Test
    void twoDelegationsKeepTheirOwnGoalAndHistory() throws Exception {
        String goalA = "任务甲" + filler(400);
        String goalB = "任务乙" + filler(400);
        ScriptedClient client = new ScriptedClient(events, textReply("结论甲"), textReply("结论乙"));
        Hooks hooks = new Hooks();
        List<String> starts = recordStarts(hooks);
        List<Map<String, ?>> ends = recordEnds(hooks);
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("bash", new FakeTool("bash", "x")),
                "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, narrowLimits(), CompactionPolicy.coursePipeline()));

        assertEquals("结论甲", runtime.run(goalA));
        assertEquals("结论乙", runtime.run(goalB));

        // 两次委派各自从一条新 prompt 起步:第二次的摘要输入里不会出现第一次的目标。
        assertTrue(summaryInputTexts(client, 0).getFirst().contains("任务甲"));
        List<String> secondSummary = summaryInputTexts(client, 1);
        assertTrue(secondSummary.getFirst().contains("任务乙"));
        assertTrue(secondSummary.stream().noneMatch(text -> text.contains("任务甲")),
                "第二次委派不得带上第一次的上下文");
        // 生命周期按 run 计:两次 START、两次 END,各恰一次。
        assertEquals(2, starts.size(), "每次 run 各一次 SUBAGENT_START");
        assertEquals(2, ends.size());
        assertEquals(2, client.summaryRequests.size());
    }

    /* ---------------- 8. 启用路径的协议边界 ---------------- */

    @Test
    void duplicateIdsFailOnceAndUnknownToolsStillShortCircuit() {
        // (a) 同一回复里重复调用 ID:工具不执行、END(FAILED) 恰一次。
        ScriptedClient client = new ScriptedClient(events,
                assistant(new ToolUseBlock("dup", "bash", Json.mapper().createObjectNode()),
                        new ToolUseBlock("dup", "bash", Json.mapper().createObjectNode())));
        Hooks hooks = new Hooks();
        List<Map<String, ?>> ends = recordEnds(hooks);
        FakeTool bash = new FakeTool("bash", "不该执行");
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("bash", bash), "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> runtime.run("任务"));

        assertTrue(thrown.getMessage().contains("dup"));
        assertEquals(0, bash.calls);
        assertEquals(1, ends.size());
        assertEquals(SubagentOutcome.FAILED, ends.getFirst().get(Hooks.PARAM_OUTCOME));

        // (b) 未知工具:先短路(不进 PRE hook),结果照常回灌,委派继续到 DONE。
        events.clear();
        ScriptedClient unknown = new ScriptedClient(events, toolReply("t1", "task"), textReply("完成"));
        Hooks unknownHooks = new Hooks();
        unknownHooks.register(Hooks.PRE_TOOL_USE, params -> {
            events.add("pre:" + ((ToolUseBlock) params.get(Hooks.PARAM_BLOCK)).name());
            return null;
        });
        SubagentRuntime unknownRuntime = new SubagentRuntime(unknown, Map.of("bash", new FakeTool("bash", "x")),
                "sub-system", 8000, unknownHooks,
                new ContextCompactor(unknown, workspace.resolve("transcripts-unknown"), toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        assertEquals("完成", assertDoesNotThrowRun(unknownRuntime, "任务"));

        assertEquals(List.of("normal", "normal"), events, "未知工具不得触发任何工具 hook");
        assertEquals("Error: Unknown tool: task", resultsOf(unknown.normalRequests.get(1).messages().getLast())
                .getFirst().content());
    }

    /* ---------------- 9. 空前缀 no-op:两次超限后停止 ---------------- */

    @Test
    void emptyPrefixNoOpEndsAfterTheSecondOverflow() {
        // 历史只有一条 prompt:reactiveCompact 切不出"可安全裁掉的前缀",返回入参本身(no-op)。
        // no-op 照样消耗额度,所以第二次正式请求再超限就直接抛出——不会无限重试,也不是脚本耗尽碰巧结束。
        LlmException first = new LlmException("prompt_too_long: 第一次");
        LlmException second = new LlmException("prompt_too_long: 第二次");
        ScriptedClient client = new ScriptedClient(events, first, second, textReply("不该被调用"));
        Hooks hooks = new Hooks();
        List<String> starts = recordStarts(hooks);
        List<Map<String, ?>> ends = recordEnds(hooks);
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("bash", new FakeTool("bash", "x")),
                "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        LlmException thrown = assertThrows(LlmException.class, () -> runtime.run("短任务"));

        assertSame(second, thrown, "no-op 也消耗额度:抛第二次那个异常对象");
        assertEquals(List.of("normal", "normal"), events, "只能有两次正式请求,中间没有摘要");
        assertEquals(0, client.summaryRequests.size(), "空前缀不写归档、不调模型");
        assertFalse(Files.exists(transcriptDir));
        // 两次请求看到的历史都是同一份入口历史:没有被清空,也没有被半替换。
        assertEquals(1, client.normalRequests.get(0).messages().size());
        assertEquals(1, client.normalRequests.get(1).messages().size());
        assertEquals("短任务", client.normalRequests.get(1).messages().getFirst().extractText());
        assertEquals(1, starts.size(), "一次委派只 START 一次");
        assertEquals(1, ends.size(), "END 恰一次");
        assertEquals(SubagentOutcome.FAILED, ends.getFirst().get(Hooks.PARAM_OUTCOME));
        assertEquals("Error: prompt_too_long: 第二次", ends.getFirst().get(Hooks.PARAM_OUTPUT));
    }

    /* ---------------- 10. 同一 run 内:下一次正式请求的额度已重置 ---------------- */

    @Test
    void budgetResetsForTheNextRequestWithinTheSameRun() {
        // 三轮工具把历史撑到可切前缀 → 第一次超限补救 → 成功的回复仍带工具 → 下一次正式请求再超限补救 → DONE。
        // 如果没有"成功即归零",第二次超限就会直接抛出,而不是再补救一次。
        List<Object> script = toolRounds(3);
        script.add(new LlmException("prompt_too_long: 第一次"));
        script.add(toolReply("b4", "bash"));
        script.add(new LlmException("prompt_too_long: 第二次"));
        script.add(textReply("最终结论"));
        ScriptedClient client = new ScriptedClient(events, script.toArray());
        Hooks hooks = new Hooks();
        List<String> starts = recordStarts(hooks);
        List<Map<String, ?>> ends = recordEnds(hooks);
        FakeTool bash = new FakeTool("bash", "小输出");
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("bash", bash), "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        assertEquals("最终结论", assertDoesNotThrowRun(runtime, "两段超限"));

        // 两次补救摘要;工具共 4 次(三轮 + 补救后那一轮),两次失败尝试一次都没有执行工具。
        assertEquals(2, client.summaryRequests.size(), "每次正式请求各有一次补救机会");
        assertEquals(4, bash.calls, "重试不得重跑先前已经执行过的工具");
        assertEquals(1, starts.size(), "整个过程仍是一次委派");
        assertEquals(1, ends.size(), "成功收尾只 END 一次");
        assertEquals(SubagentOutcome.DONE, ends.getFirst().get(Hooks.PARAM_OUTCOME));
        assertEquals(List.of("normal", "normal", "normal", "normal", "summary", "normal",
                "normal", "summary", "normal"), events);
    }

    /* ---------------- 11. 另外两个 IO 收尾分支:主动压缩 / 补救归档失败 ---------------- */

    @Test
    void compactAndReactiveArchiveFailuresEndFailedOnceWithoutExtraRequests() throws Exception {
        // (a) 主动 compact 的归档失败:阈值放宽,保证失败确实发生在 compactAfterBatch 的归档阶段
        //     (而不是 prepare 或摘要调用)。
        Files.writeString(transcriptDir, "占位文件");
        ScriptedClient client = new ScriptedClient(events, toolReply("c1", "compact"), textReply("不该被调用"));
        Hooks hooks = new Hooks();
        List<String> starts = recordStarts(hooks);
        List<Map<String, ?>> ends = recordEnds(hooks);
        FakeTool compact = new FakeTool("compact", CompactTool.OUTPUT);
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("compact", compact), "sub-system", 8000, hooks,
                new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        IllegalStateException compactError = assertThrows(IllegalStateException.class, () -> runtime.run("阶段"));

        assertNotNull(compactError.getCause(), "归档失败必须带 cause");
        assertEquals(1, compact.calls, "工具已经执行过一次,失败后不得重跑");
        assertEquals(0, client.summaryRequests.size(), "归档失败时连摘要都不能调用");
        assertEquals(1, client.normalRequests.size(), "不得再发正式请求");
        assertEquals(1, starts.size());
        assertEquals(1, ends.size());
        assertEquals(SubagentOutcome.FAILED, ends.getFirst().get(Hooks.PARAM_OUTCOME));

        // (b) 补救里的归档失败:同样带 cause、零摘要、无后续正式请求,已执行的工具不重跑。
        transcriptDir = workspace.resolve("transcripts-reactive-io");
        Files.writeString(transcriptDir, "占位文件");
        events.clear();
        List<Object> script = toolRounds(3);
        script.add(new LlmException("prompt_too_long: 需要补救"));
        script.add(textReply("不该被调用"));
        ScriptedClient retry = new ScriptedClient(events, script.toArray());
        Hooks retryHooks = new Hooks();
        List<String> retryStarts = recordStarts(retryHooks);
        List<Map<String, ?>> retryEnds = recordEnds(retryHooks);
        FakeTool retryBash = new FakeTool("bash", "小输出");
        SubagentRuntime retryRuntime = new SubagentRuntime(retry, Map.of("bash", retryBash), "sub-system", 8000,
                retryHooks, new ContextCompactor(retry, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        IllegalStateException reactiveError = assertThrows(IllegalStateException.class,
                () -> retryRuntime.run("任务"));

        assertNotNull(reactiveError.getCause());
        assertEquals(3, retryBash.calls, "失败后不得重跑已经执行过的工具");
        assertEquals(0, retry.summaryRequests.size(), "归档失败时连摘要都不能调用");
        assertEquals(4, retry.normalRequests.size(), "补救失败后不得再发正式请求");
        assertEquals(1, retryStarts.size());
        assertEquals(1, retryEnds.size());
        assertEquals(SubagentOutcome.FAILED, retryEnds.getFirst().get(Hooks.PARAM_OUTCOME));
    }

    /* ---------------- 12. 名为 compact 但输出不精确:不触发压缩 ---------------- */

    @Test
    void compactNameWithUnexpectedOutputDoesNotTriggerCompaction() {
        // 名字确实叫 compact(所以是已知工具、会被执行),但输出不是精确成功文案:
        // 不能置位"本批请求过压缩";工具 / 结果生命周期与结果回灌照常。
        String unexpected = "compact 但输出不是稳定信号";
        ScriptedClient client = new ScriptedClient(events, toolReply("c1", "compact"), textReply("完成"));
        Hooks hooks = new Hooks();
        List<Map<String, ?>> ends = recordEnds(hooks);
        hooks.register(Hooks.PRE_TOOL_USE, params -> {
            events.add("pre");
            return null;
        });
        hooks.register(Hooks.POST_TOOL_USE, params -> {
            events.add("post");
            return null;
        });
        hooks.register(Hooks.SUBAGENT_TOOL, params -> {
            events.add("sub");
            return null;
        });
        FakeTool compact = new FakeTool("compact", unexpected);
        SubagentRuntime runtime = new SubagentRuntime(client, Map.of("compact", compact), "sub-system", 8000,
                hooks, new ContextCompactor(client, transcriptDir, toolResultsDir, wideLimits(), CompactionPolicy.coursePipeline()));

        assertEquals("完成", assertDoesNotThrowRun(runtime, "阶段"));

        assertEquals(1, compact.calls, "已知工具照常执行一次");
        assertEquals(0, client.summaryRequests.size(), "输出不精确时不得触发压缩");
        assertFalse(Files.exists(transcriptDir));
        assertEquals(List.of("normal", "pre", "post", "sub", "normal"), events, "生命周期与顺序不变");
        // 结果仍按调用 ID 回灌,下一次请求看得到它的完整正文。
        List<ToolResultBlock> results = resultsOf(client.normalRequests.get(1).messages().getLast());
        assertEquals("c1", results.getFirst().toolUseId());
        assertEquals(unexpected, results.getFirst().content());
        assertEquals(1, ends.size());
        assertEquals(SubagentOutcome.DONE, ends.getFirst().get(Hooks.PARAM_OUTCOME));
    }
}
