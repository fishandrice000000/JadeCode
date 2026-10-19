package com.jadecode.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.jadecode.llm.LlmException;
import com.jadecode.llm.ModelResponse;
import com.jadecode.llm.RecordingLlmClient;
import com.jadecode.messages.Message;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolExchangeProtocol;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 请求前管线的组合证据：前层能解决问题就不付出后层的摘要成本，前层输出正确交给下一层。
 * <p>各层内部算法已有定向测试；这里只检查衔接、原历史保真与可恢复材料，不复制完整矩阵。</p>
 */
class ContextCompactorPreparationTest {
    @TempDir
    Path workspace;
    private Path transcriptDir;
    private Path outputDir;

    /** 每例的归档与正文都在临时工作区，不读取真实 CLI 配置。 */
    @BeforeEach
    void setUp() {
        transcriptDir = workspace.resolve("transcripts");
        outputDir = workspace.resolve("outputs");
    }

    /** 显式注入预算和保留数；短预览让“可恢复替换”确实缩短，不受随机目录长度干扰。 */
    private static CompactionLimits limits(int contextLimit, int batchLimit, int keepRecent, int snipMax) {
        CompactionLimits d = CompactionLimits.courseDefaults();
        return new CompactionLimits(contextLimit, batchLimit, 1000, d.summaryInputCharLimit(), keepRecent,
                d.keepRecentMessages(), 120, 16, 16, d.summaryMaxOutputTokens(), snipMax, 2, 0.8);
    }

    /** 工具名与输入不是压缩判断重点，ID 的闭合关系必须是真实有效的。 */
    private static ToolUseBlock call(String id) {
        return new ToolUseBlock(id, "bash", Json.mapper().createObjectNode());
    }

    /** 当确定性层应当足够时，任何摘要调用都直接使测试失败，不以空响应掩盖错误分支。 */
    private ContextCompactor deterministic(CompactionLimits limits) {
        return new ContextCompactor(request -> {
            fail("确定性层已经足够，不应调用摘要模型");
            return null;
        }, transcriptDir, outputDir, limits, CompactionPolicy.coursePipeline());
    }

    /** 整体字符判断必须使用 JSON 长度：转义内容与协议字段也占据请求空间。 */
    @Test
    void exactJsonBoundaryIsNoOpAndOneCharOverTriggersSummary() throws LlmException {
        List<Message> input = List.of(Message.userText("引号\"与\\以及\n换行"));
        String before = ContextSerialization.toJson(input);
        int exact = before.length();

        assertSame(input, deterministic(limits(exact, 20000, 3, 50)).prepare(input, "当前任务"));
        assertFalse(Files.exists(transcriptDir));
        assertFalse(Files.exists(outputDir));

        RecordingLlmClient client = new RecordingLlmClient(request ->
                new ModelResponse(Message.assistant(List.of(new TextBlock("固定摘要"))), new ModelResponse.Usage(0, 0)));
        ContextCompactor over = new ContextCompactor(client, transcriptDir, outputDir,
                limits(exact - 1, 20000, 3, 50));
        List<Message> actual = over.prepare(input, "当前任务");

        assertEquals(1, client.requests().size());
        assertTrue(client.requests().getFirst().tools().isEmpty());
        assertTrue(actual.getFirst().extractText().startsWith("[Compacted]"));
        assertTrue(actual.getFirst().extractText().contains("当前任务"));
        assertEquals(before, ContextSerialization.toJson(input));
    }

    /** 最新批次先转存再 snip：归档保存的是前层候选，原大正文仍从独立结果文件完整恢复。 */
    @Test
    void budgetRunsBeforeSnipAndBothRecoveryArtifactsRemainComplete() throws Exception {
        List<Message> mutable = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            mutable.add(Message.userText("历史-" + i));
        }
        mutable.add(Message.assistant(List.of(call("fresh"))));
        String original = "x".repeat(8000);
        mutable.add(Message.userBlocks(List.of(new ToolResultBlock("fresh", original))));
        List<Message> input = List.copyOf(mutable);
        String before = ContextSerialization.toJson(input);

        List<Message> actual = deterministic(limits(2000, 3000, 3, 8)).prepare(input, "任务");

        assertEquals(8, actual.size());
        assertTrue(new TranscriptStore(transcriptDir).isArchiveMarker(actual.get(2)));
        assertSame(input.get(13), actual.get(6));
        ToolResultBlock persisted = (ToolResultBlock) actual.getLast().content().getFirst();
        Path saved = Path.of(new ToolOutputStore(outputDir).persistedOutputPath(persisted.content()));
        assertEquals(original, Files.readString(saved));
        try (var files = Files.list(transcriptDir)) {
            List<Path> archives = files.toList();
            assertEquals(1, archives.size());
            List<String> lines = Files.readAllLines(archives.getFirst());
            assertEquals(input.size(), lines.size());
            // snip 的完整输入已经是 budget 后候选，不能误要求 JSONL 再重复存一份大正文。
            for (int i = 0; i < 14; i++) {
                assertEquals(Json.mapper().valueToTree(input.get(i)), Json.mapper().readTree(lines.get(i)));
            }
            assertEquals(Json.mapper().valueToTree(actual.getLast()), Json.mapper().readTree(lines.getLast()));
        }
        assertEquals(before, ContextSerialization.toJson(input));
        assertDoesNotThrow(() -> ToolExchangeProtocol.validateHistory(actual));
    }

    /** micro 已经解决总体超限时，不能再由 fit 缩短受保护的最近旧结果或新鲜结果。 */
    @Test
    void stopsAfterMicroWhenContextFitsWithoutTouchingProtectedResults() throws Exception {
        List<Message> input = List.of(Message.userText("任务"),
                Message.assistant(List.of(call("old-a"), call("old-b"))),
                Message.userBlocks(List.of(new ToolResultBlock("old-a", "a".repeat(3000)),
                        new ToolResultBlock("old-b", "b".repeat(3000)))),
                Message.assistant(List.of(call("fresh"))),
                Message.userBlocks(List.of(new ToolResultBlock("fresh", "新结果"))));
        String before = ContextSerialization.toJson(input);

        List<Message> actual = deterministic(limits(5000, 20000, 1, 50)).prepare(input, "任务");

        ToolResultBlock oldest = (ToolResultBlock) actual.get(2).content().get(0);
        assertTrue(oldest.content().startsWith("[Earlier tool result saved at "));
        assertSame(input.get(2).content().get(1), actual.get(2).content().get(1));
        assertSame(input.getLast(), actual.getLast());
        assertEquals("a".repeat(3000), Files.readString(Path.of(
                new ToolOutputStore(outputDir).persistedOutputPath(oldest.content()))));
        assertTrue(ContextSerialization.estimateChars(actual) <= 5000);
        assertEquals(before, ContextSerialization.toJson(input));
        assertFalse(Files.exists(transcriptDir));
        try (var files = Files.list(outputDir)) {
            assertEquals(1, files.count());
        }
    }

    /** micro 不能碰未消费结果；仍超限时 fit 接手，以路径预览保存新鲜数据，避免不必要的有损摘要。 */
    @Test
    void fitReceivesMicroCandidateAndPersistsFreshResultWithoutSummary() throws Exception {
        List<Message> input = List.of(Message.userText("任务"),
                Message.assistant(List.of(call("old"))),
                Message.userBlocks(List.of(new ToolResultBlock("old", "a".repeat(3000)))),
                Message.assistant(List.of(call("fresh"))),
                Message.userBlocks(List.of(new ToolResultBlock("fresh", "b".repeat(8000)))));
        String before = ContextSerialization.toJson(input);

        List<Message> actual = deterministic(limits(1000, 20000, 0, 50)).prepare(input, "任务");

        ToolResultBlock old = (ToolResultBlock) actual.get(2).content().getFirst();
        ToolResultBlock fresh = (ToolResultBlock) actual.get(4).content().getFirst();
        ToolOutputStore store = new ToolOutputStore(outputDir);
        assertTrue(old.content().startsWith("[Earlier tool result saved at "));
        assertTrue(fresh.content().startsWith("<persisted-output>"));
        assertEquals("a".repeat(3000), Files.readString(Path.of(store.persistedOutputPath(old.content()))));
        assertEquals("b".repeat(8000), Files.readString(Path.of(store.persistedOutputPath(fresh.content()))));
        assertTrue(ContextSerialization.estimateChars(actual) <= 1000);
        assertEquals(before, ContextSerialization.toJson(input));
        assertFalse(Files.exists(transcriptDir));
        assertDoesNotThrow(() -> ToolExchangeProtocol.validateHistory(actual));
        try (var files = Files.list(outputDir)) {
            assertEquals(2, files.count());
        }
    }
}
