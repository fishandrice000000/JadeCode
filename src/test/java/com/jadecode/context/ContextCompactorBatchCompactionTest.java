package com.jadecode.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.LlmClient;
import com.jadecode.llm.ModelResponse;
import com.jadecode.messages.Message;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.tools.CompactTool;
import com.jadecode.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextCompactor#compactAfterBatch} 的直接契约测试。
 *
 * <p>
 * 这个入口回答的是"模型主动请求压缩、且整批结果已经闭合之后,这次要牺牲哪些历史"。
 * 它<strong>不</strong>执行工具、<strong>不</strong>触发 hook、<strong>不</strong>回灌结果,也不改动调用方会话;
 * 因此可以脱离 AgentExecutor 直接调用:给一段闭合历史 + 本批调用集合,断言它返回什么候选、写了哪些归档、
 * 有没有动输入。循环侧的时机与生命周期由 {@code AgentExecutorManualCompactionTest} 覆盖,本文件只固定
 * "策略边界"这一半职责。
 * </p>
 *
 * <p>
 * 用真实 {@link ContextCompactor}(课程默认阈值)、假 {@link LlmClient} 与 {@code @TempDir}:
 * 不调真实 API、不读密钥,归档与落盘都落在临时目录里。
 * </p>
 */
class ContextCompactorBatchCompactionTest {

    @TempDir
    Path workspace;

    private Path transcriptDir;
    private Path toolResultsDir;

    @BeforeEach
    void setUp() {
        transcriptDir = workspace.resolve("transcripts");
        toolResultsDir = workspace.resolve("tool-results");
    }

    /* ---------------- 假客户端与造数据 ---------------- */

    /**
     * 只服务摘要请求的假客户端:记录每次请求的<b>快照</b>,并返回固定摘要文本。
     *
     * <p>
     * 必须复制 {@code messages}:请求里的列表是可变对象,不复制就会读到测试跑完之后的状态。
     * 本入口只可能发起摘要调用(没有工具表、只有一条 user 输入),所以这里不需要按 tools 分流。
     * </p>
     */
    private static final class FakeClient implements LlmClient {
        private final List<CompleteRequest> summaryRequests = new ArrayList<>();

        @Override
        public ModelResponse complete(CompleteRequest request) {
            summaryRequests.add(new CompleteRequest(request.system(), List.copyOf(request.messages()),
                    request.maxTokens(), request.tools()));
            return new ModelResponse(Message.assistant(List.of(new TextBlock("摘要文本"))),
                    new ModelResponse.Usage(0, 0));
        }
    }

    private ContextCompactor compactor(LlmClient client) {
        return new ContextCompactor(client, transcriptDir, toolResultsDir, CompactionLimits.courseDefaults());
    }

    /** 造一个 tool_use(空入参:本入口只看名字) */
    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, Json.mapper().createObjectNode());
    }

    /** 造一条 assistant 消息,正文就是给出的这些 tool_use */
    private static Message assistant(ToolUseBlock... calls) {
        return Message.assistant(List.of(calls));
    }

    /** 造一条可变的会话历史(本入口要求"闭合",但历史本身是普通列表) */
    private static List<Message> history(Message... messages) {
        return new ArrayList<>(List.of(messages));
    }

    /** 摘要请求的输入文本(整个入口只会发这一种调用) */
    private static String summaryInputOf(FakeClient client) {
        assertEquals(1, client.summaryRequests.size(), "本入口应当恰好发起一次摘要调用");
        return client.summaryRequests.getFirst().messages().getFirst().extractText();
    }

    /** 把文本解析成 JSON 树,便于做"结构相等"而不是子串比较 */
    private static JsonNode json(String text) throws IOException {
        return Json.mapper().readTree(text);
    }

    /** 归档文件的行(一行一条消息) */
    private List<String> archiveLines() throws IOException {
        try (var files = Files.list(transcriptDir)) {
            Path file = files.findFirst().orElseThrow(() -> new AssertionError("没有 transcript 文件"));
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        }
    }

    /* ---------------- 1. 纯 compact:整段历史换成一条摘要 ---------------- */

    @Test
    void pureCompactBatchSummarizesWholeClosedHistory() throws Exception {
        FakeClient client = new FakeClient();
        ContextCompactor compactor = compactor(client);
        Message request = Message.userText("开始");
        Message assistant = assistant(call("c1", "compact"));
        Message results = Message.userBlocks(List.of(new ToolResultBlock("c1", CompactTool.OUTPUT)));
        List<Message> messages = history(request, assistant, results);
        List<Message> before = List.copyOf(messages);

        List<Message> candidate = compactor.compactAfterBatch(List.of(call("c1", "compact")), messages, "开始");

        // 形态:整段替换成一条 [Compacted] 摘要消息(不再是原来的三条)。
        assertEquals(1, candidate.size());
        assertTrue(candidate.getFirst().extractText().startsWith("[Compacted]"));
        assertTrue(candidate.getFirst().extractText().contains("Current user request:\n开始"));

        // 摘要范围 = 全部闭合历史:输入正好是那三条消息的 JSON,一条不少也一条不多。
        assertEquals(json(Json.mapper().writeValueAsString(before)),
                json(summaryInputOf(client)), "纯批次应摘要整段闭合历史");
        // 准备被替换掉的整段历史先归档,回查时能看到原文。
        assertEquals(3, archiveLines().size(), "应先归档整段历史");

        // 输入对象图不变:本入口只返回候选,不替调用方"采用"。
        assertEquals(3, messages.size());
        assertSame(request, messages.getFirst());
        assertSame(assistant, messages.get(1));
        assertSame(results, messages.get(2));
    }

    /* ---------------- 2. 混合:只摘要旧前缀,最新交换原样保留 ---------------- */

    @Test
    void mixedBatchSummarizesOnlyOldPrefixAndKeepsLatestExchange() throws Exception {
        FakeClient client = new FakeClient();
        ContextCompactor compactor = compactor(client);
        Message request = Message.userText("开始");
        Message assistant = assistant(call("b1", "bash"), call("c1", "compact"));
        // 结果都远小于默认预算门槛,所以这一轮不会发生预算替换,尾部应保持原引用。
        Message results = Message.userBlocks(List.of(
                new ToolResultBlock("b1", "普通输出"),
                new ToolResultBlock("c1", CompactTool.OUTPUT)));
        List<Message> messages = history(request, assistant, results);

        List<Message> candidate = compactor.compactAfterBatch(
                List.of(call("b1", "bash"), call("c1", "compact")), messages, "开始");

        // 形态:摘要 + 原尾部两条(刚生成的 assistant 调用与它的结果),顺序不变。
        assertEquals(3, candidate.size());
        assertTrue(candidate.getFirst().extractText().startsWith("[Compacted]"));
        assertSame(assistant, candidate.get(1), "最新交换的 assistant 必须按原引用保留");
        assertSame(results, candidate.get(2), "最新交换的结果消息必须按原引用保留");
        assertSame(results.content(), candidate.get(2).content(), "结果消息的 content 也不重建");

        // 摘要只覆盖旧前缀(那条人类请求),新鲜调用与结果都不得进入摘要输入。
        JsonNode prefix = json(Json.mapper().writeValueAsString(List.of(request)));
        assertEquals(prefix, json(summaryInputOf(client)), "混合批次只摘要旧前缀");
        String input = summaryInputOf(client);
        assertFalse(input.contains("tool_use"), "新鲜调用不得进入摘要输入");
        assertFalse(input.contains("普通输出"), "新鲜结果不得进入摘要输入");
        assertEquals(3, archiveLines().size(), "归档仍应保存整段历史(含最新交换)");

        // 输入历史不变。
        assertEquals(3, messages.size());
        assertSame(request, messages.getFirst());
        assertSame(assistant, messages.get(1));
        assertSame(results, messages.get(2));
    }

    /* ---------------- 3. 混合但没有可摘要前缀:原样返回,什么都不做 ---------------- */

    @Test
    void mixedBatchWithoutSummarizablePrefixReturnsInputUnchanged() throws Exception {
        FakeClient client = new FakeClient();
        ContextCompactor compactor = compactor(client);
        // 历史恰好就是这一条闭合交换(没有更早的对话):整段都属于"要保护的尾部"。
        Message assistant = assistant(call("b1", "bash"), call("c1", "compact"));
        Message results = Message.userBlocks(List.of(
                new ToolResultBlock("b1", "普通输出"),
                new ToolResultBlock("c1", CompactTool.OUTPUT)));
        List<Message> messages = history(assistant, results);

        List<Message> candidate = compactor.compactAfterBatch(
                List.of(call("b1", "bash"), call("c1", "compact")), messages, "开始");

        // 没有可安全裁掉的前缀 → 返回入参本身:零摘要调用、零归档,调用方拿到同一个引用就知道"没变化"。
        assertSame(messages, candidate, "无可摘要前缀时必须返回入参本身");
        assertEquals(0, client.summaryRequests.size(), "不得发起摘要调用");
        assertFalse(Files.exists(transcriptDir), "不得写归档");
    }
}
