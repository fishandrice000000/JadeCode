package com.jadecode.context;

import com.jadecode.llm.*;
import com.jadecode.messages.*;
import com.jadecode.util.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 验证生产预算、完整交换、摘要失败保护；只用离线客户端，不请求真实模型。 */
class ProductionCompactorTest {
    @TempDir Path dir;

    /** 记录每份真实投影的快照，避免调用方继续追加列表污染断言。 */
    private static final class Client implements LlmClient {
        ModelMetadata metadata = new ModelMetadata(new ModelMetadata.Limits(1048576, null, 393216), "test");
        List<CompleteRequest> requests = new ArrayList<>();
        ModelResponse reply = new ModelResponse(Message.assistant(List.of(new TextBlock("verified summary"))), null, "end_turn");
        @Override public ModelMetadata metadata() { return metadata; }
        @Override public ModelResponse complete(CompleteRequest r) {
            requests.add(new CompleteRequest(r.system(), List.copyOf(r.messages()), r.maxTokens(), List.copyOf(r.tools()), r.thinking()));
            return reply;
        }
    }

    /** 用小保留区验证边界；其余预算仍与默认生产路径一致。 */
    private ProductionCompactionSettings settings(int keep, Integer input) {
        return new ProductionCompactionSettings(true, 16384, keep, 32768, input, 50000, 2000);
    }

    /** 每例使用独立临时目录；创建服务本身不摘要或建文件。 */
    private ContextCompactor compactor(Client client, ProductionCompactionSettings settings) {
        return ContextCompactor.production(client, dir.resolve("transcripts"), dir.resolve("results"), settings);
    }

    /** 固定 system 和工具目录，令断言只比较本例改变的历史或设置。 */
    private CompleteRequest request(List<Message> messages) {
        return new CompleteRequest("stable system", messages, 393216,
                List.of(new ToolDefinition("read_file", "read", Json.mapper().createObjectNode().put("type", "object"))));
    }

    private Message assistant(String value) { return Message.assistant(List.of(new TextBlock(value))); }

    /** 普通输入、缓存和输出组成锚点；只估算新增参考消息，投影变化或显式清除后整份重估。 */
    @Test void reportedAnchorCountsCachesAndOutputOnceAndInvalidatesChangedProjection() {
        var tracker = new ContextTokenTracker();
        var original = request(List.of(Message.userText("goal")));
        var answer = assistant("answer including output");
        tracker.accepted(original, new ModelResponse(answer, new ModelResponse.Usage(100, 30, 200, 50)));
        var history = new ArrayList<>(original.messages()); history.add(answer);
        assertEquals(new ContextTokenTracker.Estimate(380, false), tracker.estimate(request(history)));
        var delta = Message.memoryReference("recalled data"); history.add(delta);
        assertEquals(380 + ContextTokenTracker.message(delta), tracker.estimate(request(history)).tokens());
        assertTrue(tracker.estimate(request(history)).estimated());
        var changed = new CompleteRequest("changed system", history, 393216, original.tools());
        assertEquals(ContextTokenTracker.projected(changed), tracker.estimate(changed).tokens());
        var changedTools = new CompleteRequest(original.system(), history, 393216, List.of());
        assertEquals(ContextTokenTracker.projected(changedTools), tracker.estimate(changedTools).tokens());
        var changedThinking = request(history).withThinking(ThinkingSettings.OFF);
        assertEquals(ContextTokenTracker.projected(changedThinking), tracker.estimate(changedThinking).tokens());
        assertEquals(ContextTokenTracker.projected(request(history)), new ContextTokenTracker().estimate(request(history)).tokens());
        // 缺 usage 和被截断响应都不能覆盖最近的合法锚点。
        tracker.accepted(original, new ModelResponse(answer, null));
        tracker.accepted(original, new ModelResponse(answer, new ModelResponse.Usage(9999, 2), "max_tokens"));
        assertEquals(380 + ContextTokenTracker.message(delta), tracker.estimate(request(history)).tokens());
        tracker.invalidate();
        assertEquals(ContextTokenTracker.projected(request(history)), tracker.estimate(request(history)).tokens());
    }

    /** 文本超过旧字符门槛但远未用完 1M 窗口时，生产不能付费摘要或建 transcript。 */
    @Test void largeTextBelowTokenWindowDoesNotTriggerOldCharacterThreshold() throws Exception {
        var client = new Client();
        var history = List.of(Message.userText("code ".repeat(80000)), assistant("done"));
        assertSame(history, compactor(client, ProductionCompactionSettings.defaults()).prepare(request(history), "goal", new ContextTokenTracker(), ""));
        assertTrue(client.requests.isEmpty());
        assertFalse(Files.exists(dir.resolve("transcripts")));
    }

    /** 构造可信 usage 锚点检查严格大于边界；最大输出只限制单次请求，不改变触发线。 */
    @Test void exactThresholdDoesNotCompactButOneMoreTokenDoes() throws Exception {
        var client = new Client(); var tracker = new ContextTokenTracker();
        var original = request(List.of(Message.userText("past")));
        var answer = assistant("finished");
        var history = List.of(original.messages().getFirst(), answer);
        long threshold = 1048576 - 16384;
        tracker.accepted(original, new ModelResponse(answer, new ModelResponse.Usage(threshold - 1, 1)));
        var compactor = compactor(client, settings(1, null));
        assertSame(history, compactor.prepare(request(history), "goal", tracker, ""));
        // 配置最大输出 384K 不能抢走自动压缩预留；仅收紧当前请求的实际额度。
        assertEquals(15360, compactor.outputBudget(request(history), tracker));
        tracker.accepted(original, new ModelResponse(answer, new ModelResponse.Usage(threshold, 1)));
        var reduced = compactor.prepare(request(history), "goal", tracker, "{\"todos\":[]}");
        assertEquals(1, client.requests.size());
        var summary = client.requests.getFirst();
        assertEquals(original.system(), summary.system()); assertEquals(original.tools(), summary.tools());
        assertEquals(original.messages().getFirst(), summary.messages().getFirst());
        assertTrue(summary.messages().getLast().extractText().startsWith("[Harness compaction request]"));
        assertEquals(32768, summary.maxTokens());
        assertSame(answer, reduced.getLast());
        assertTrue(reduced.getFirst().extractText().contains("Runtime state"));
        assertEquals(List.of(original.messages().getFirst(), answer), history);
    }

    /** 零近期预算仍保护最新工具交换，摘要只能读取它之前的旧前缀。 */
    @Test void latestUnreadExchangeRemainsWholeEvenWithZeroRecentBudget() throws Exception {
        var client = new Client(); var history = new ArrayList<Message>();
        history.add(Message.userText("old goal")); history.add(assistant("old answer"));
        var call = Message.assistant(List.of(new ToolUseBlock("id", "read_file", Json.mapper().createObjectNode())));
        var result = Message.userBlocks(List.of(new ToolResultBlock("id", "fresh evidence", false)));
        history.add(call); history.add(result);
        var reduced = compactor(client, settings(0, null)).compactAfterBatch(List.of(), request(history), "goal", "");
        assertEquals(List.of(call, result), reduced.subList(1, reduced.size()));
        assertEquals(history.subList(0, 2), client.requests.getFirst().messages().subList(0, 2));
    }

    /** 小输入额度迫使分块；按顺序还原每块原文，证明没有丢中段或拆开调用与结果。 */
    @Test void overBudgetSummaryChunksCoverEveryMessageAndNeverSplitToolExchange() throws Exception {
        var client = new Client(); var history = new ArrayList<Message>();
        for (int i = 0; i < 8; i++) {
            history.add(Message.assistant(List.of(new ToolUseBlock("id" + i, "read_file", Json.mapper().createObjectNode().put("path", "file" + i)))));
            history.add(Message.userBlocks(List.of(new ToolResultBlock("id" + i, "unique-evidence-" + i + "x".repeat(900), false))));
        }
        history.add(assistant("recent protected answer"));
        compactor(client, settings(1, 900)).compactAfterBatch(List.of(), request(history), "goal", "");
        assertTrue(client.requests.size() > 1);
        List<Message> covered = new ArrayList<>();
        for (CompleteRequest chunk : client.requests) {
            assertTrue(chunk.tools().isEmpty()); assertTrue(ContextTokenTracker.projected(chunk) <= 900);
            var conversation = Json.mapper().readTree(chunk.messages().getFirst().extractText()).get("conversation");
            for (var node : conversation) covered.add(Json.mapper().treeToValue(node, Message.class));
            assertEquals(0, conversation.size() % 2, "块必须包含完整工具交换");
        }
        assertEquals(history.subList(0, 16), covered, "所有待压缩原文按原顺序覆盖，不省略中段");
    }

    /** 摘要截断、空白或返回工具调用时只失败一次；检查原列表未变且归档已落盘。 */
    @Test void rejectedSummaryLeavesOriginalHistoryAndArchivesBeforeCalling() throws Exception {
        for (ModelResponse invalid : List.of(
                new ModelResponse(assistant("cut off"), null, "max_tokens"),
                new ModelResponse(assistant("  "), null),
                new ModelResponse(Message.assistant(List.of(new ToolUseBlock("x", "bash", Json.mapper().createObjectNode()))), null))) {
            var client = new Client(); client.reply = invalid;
            var history = new ArrayList<>(List.of(Message.userText("evidence"), assistant("recent")));
            var before = List.copyOf(history);
            assertThrows(LlmException.class, () -> compactor(client, settings(1, null))
                    .compactAfterBatch(List.of(), request(history), "goal", ""));
            assertEquals(before, history); assertEquals(1, client.requests.size(), "不得隐藏重试");
        }
        try (var files = Files.list(dir.resolve("transcripts"))) { assertEquals(3, files.count()); }
    }

    /** 未知窗口不猜自动阈值；手动摘要也必须先得到可解释的整份输入预算。 */
    @Test void unknownWindowSkipsAutomaticButManualRequiresKnownInputBudget() throws Exception {
        var client = new Client(); client.metadata = new ModelMetadata(ModelMetadata.Limits.unknown(), "test");
        var history = List.of(Message.userText("old"), assistant("recent"));
        var noBudget = compactor(client, settings(1, null));
        assertSame(history, noBudget.prepare(request(history), "goal", new ContextTokenTracker(), ""));
        assertThrows(LlmException.class, () -> noBudget.compactAfterBatch(List.of(), request(history), "goal", ""));
        assertTrue(client.requests.isEmpty());
        compactor(client, settings(1, 10000)).compactAfterBatch(List.of(), request(history), "goal", "");
        assertEquals(1, client.requests.size());
    }
}
