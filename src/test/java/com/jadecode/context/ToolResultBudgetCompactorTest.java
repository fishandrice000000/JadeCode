package com.jadecode.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.jadecode.messages.ContentBlock;
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
 * 最新批次预算的 Agent 契约：可以改变结果表示，但不能改变结果 ID、交换顺序或旧历史。
 * <p>小阈值用于精确检查边界；完整工具交换用于确认处理后仍能合法提交给模型。</p>
 */
class ToolResultBudgetCompactorTest {
    @TempDir
    Path workspace;
    private Path outputDir;
    private ToolOutputStore store;

    /** 每例独立真实存储，落盘内容与输入对象图分别验证。 */
    @BeforeEach
    void setUp() {
        outputDir = workspace.resolve("outputs");
        store = new ToolOutputStore(outputDir);
    }

    /** 仅缩小批次 / 单项门槛和预览，保持本层以外的课程策略不变。 */
    private ToolResultBudgetCompactor compactor(int batchLimit, int largeLimit) {
        CompactionLimits d = CompactionLimits.courseDefaults();
        CompactionLimits limits = new CompactionLimits(d.contextCharLimit(), batchLimit, largeLimit,
                d.summaryInputCharLimit(), d.keepRecentResults(), d.keepRecentMessages(), d.microResultMinChars(),
                16, d.fitPreviewChars(), d.summaryMaxOutputTokens(), d.snipMaxMessages(), d.snipHeadMessages(),
                d.targetRatio());
        return new ToolResultBudgetCompactor(limits, store);
    }

    /** 按 block 位置生成匹配调用；排序处理候选不能改变这些协议顺序。 */
    private static List<Message> batch(String... outputs) {
        List<ContentBlock> calls = new ArrayList<>();
        List<ContentBlock> results = new ArrayList<>();
        for (int i = 0; i < outputs.length; i++) {
            String id = "call-" + i;
            calls.add(new ToolUseBlock(id, "bash", Json.mapper().createObjectNode()));
            results.add(new ToolResultBlock(id, outputs[i]));
        }
        return List.of(Message.userText("任务"), Message.assistant(List.copyOf(calls)),
                Message.userBlocks(List.copyOf(results)));
    }

    /** 获取最新消息中的结果；fixture 的结果位置与 ID 均应在处理后保留。 */
    private static ToolResultBlock result(List<Message> history, int index) {
        return (ToolResultBlock) history.getLast().content().get(index);
    }

    /** 服务报告的失败不能因正文转存而变成成功；原结果和磁盘正文仍保留。 */
    @Test
    void persistenceKeepsExplicitErrorFlag() {
        var failed = new ToolResultBlock("failed", "x".repeat(5000), true);
        var history = List.of(Message.userBlocks(List.of(failed)));
        assertTrue(result(compactor(500, 100).persistLargeResults(history), 0).isError());
        assertTrue(result(compactor(500, 100).toolResultBudget(history), 0).isError());
        assertEquals(5000, failed.content().length());
    }

    /** 批次总正文恰好达标时，即使某一项超过单项门槛，第一层也不启动。 */
    @Test
    void exactBatchBoundaryDoesNotPersistLargeIndividualResult() {
        List<Message> input = batch("a".repeat(300), "b".repeat(400));

        assertSame(input, compactor(700, 300).toolResultBudget(input));
        assertFalse(Files.exists(outputDir));
    }

    /** 批次超限但每项都不超过单项门槛，本层不能擅自改写普通小结果。 */
    @Test
    void exactIndividualBoundaryIsNotEligibleEvenWhenBatchExceedsLimit() {
        List<Message> input = batch("a".repeat(300), "b".repeat(300), "c".repeat(300));

        assertSame(input, compactor(700, 300).toolResultBudget(input));
        assertFalse(Files.exists(outputDir));
    }

    /** 第一层只统计 tool_result 正文；用户附带的大 reminder 不属于工具结果批次预算。 */
    @Test
    void excludesNonResultTextFromBatchBudget() {
        List<Message> messages = new ArrayList<>(batch("x".repeat(200)));
        messages.set(2, Message.userBlocks(List.of(result(messages, 0), new TextBlock("r".repeat(2000)))));
        List<Message> input = List.copyOf(messages);

        assertSame(input, compactor(300, 100).toolResultBudget(input));
        assertFalse(Files.exists(outputDir));
    }

    /** 最大项先处理，达到批次预算立即停止；“处理优先级”不能变成“消息重排”。 */
    @Test
    void persistsLargestFirstStopsAfterBudgetAndPreservesBlocksAndInput() throws IOException {
        List<Message> base = batch("a".repeat(3000), "b".repeat(8000), "c".repeat(2000));
        TextBlock reminder = new TextBlock("<reminder>继续主任务</reminder>");
        List<ContentBlock> content = List.of(result(base, 0), reminder, result(base, 1), result(base, 2));
        List<Message> input = List.of(base.get(0), base.get(1), Message.userBlocks(content));
        String before = ContextSerialization.toJson(input);

        List<Message> actual = compactor(6000, 2500).toolResultBudget(input);

        assertSame(input.get(0), actual.get(0));
        assertSame(input.get(1), actual.get(1));
        assertSame(content.get(0), actual.getLast().content().get(0));
        assertSame(reminder, actual.getLast().content().get(1));
        assertSame(content.get(3), actual.getLast().content().get(3));
        ToolResultBlock persisted = result(actual, 2);
        assertEquals("call-1", persisted.toolUseId());
        Path saved = Path.of(store.persistedOutputPath(persisted.content()));
        assertEquals("b".repeat(8000), Files.readString(saved));
        assertTrue(persisted.content().contains("Preview:\n" + "b".repeat(16) + "\n"));
        assertEquals(before, ContextSerialization.toJson(input));
        assertDoesNotThrow(() -> ToolExchangeProtocol.validateHistory(actual));
        try (var files = Files.list(outputDir)) {
            assertEquals(1, files.count(), "第二大项虽然也超过门槛，但批次已达标，不能继续落盘");
        }
    }

    /** 后来的助手回复或普通用户请求，不应让第一层回头处理更早的工具结果。 */
    @Test
    void ignoresOldBatchesEmptyHistoryAndLatestNonResultMessages() {
        List<Message> old = batch("x".repeat(5000));
        List<Message> withAssistant = new ArrayList<>(old);
        withAssistant.add(Message.assistant(List.of(new TextBlock("已读取"))));
        List<Message> withUser = new ArrayList<>(withAssistant);
        withUser.add(Message.userText("继续"));
        ToolResultBudgetCompactor budget = compactor(500, 100);
        List<Message> empty = List.of();

        assertSame(empty, budget.toolResultBudget(empty));
        assertSame(withAssistant, budget.toolResultBudget(withAssistant));
        assertSame(withUser, budget.toolResultBudget(withUser));
        assertFalse(Files.exists(outputDir));
    }

    /** 单项 helper 的门槛也是严格大于，超过门槛才生成完整正文的恢复引用。 */
    @Test
    void individualPersistenceUsesStrictBoundaryAndFullOriginal() throws IOException {
        ToolResultBudgetCompactor budget = compactor(500, 300);
        String exact = "x".repeat(300);
        assertSame(exact, budget.persistLargeOutput("exact", exact));
        assertFalse(Files.exists(outputDir));

        String original = "中".repeat(301);
        String persisted = budget.persistLargeOutput("over", original);

        assertEquals(original, Files.readString(Path.of(store.persistedOutputPath(persisted))));
    }

    /** 已转存结果再次进入预算处理，可收窄预览，不能把外壳当正文生成套娃文件。 */
    @Test
    void reusesTrustedOutputInsteadOfPersistingShellAgain() throws IOException {
        String original = "x".repeat(4000);
        String wide = store.persistedPreview("call-0", original, 2000);
        List<Message> input = batch(wide);

        List<Message> actual = compactor(500, 300).toolResultBudget(input);

        assertEquals(store.persistedOutputPath(wide), store.persistedOutputPath(result(actual, 0).content()));
        assertEquals(original, Files.readString(Path.of(store.persistedOutputPath(wide))));
        try (var files = Files.list(outputDir)) {
            assertEquals(1, files.count());
        }
    }

    /** 没有成功保存就没有替换候选，调用方仍能看到未经删改的工具结果。 */
    @Test
    void persistenceFailureLeavesOriginalHistoryUntouched() throws IOException {
        Files.writeString(outputDir, "阻挡目录创建");
        List<Message> input = batch("x".repeat(5000));
        String before = ContextSerialization.toJson(input);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> compactor(500, 100).toolResultBudget(input));

        assertInstanceOf(IOException.class, error.getCause());
        assertEquals(before, ContextSerialization.toJson(input));
        assertEquals("阻挡目录创建", Files.readString(outputDir));
    }
}
