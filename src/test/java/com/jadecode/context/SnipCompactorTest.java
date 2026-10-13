package com.jadecode.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 * snip 的结构保护：消息过多时归档中段，但不能给下一次模型请求留下半个工具交换。
 * <p>只使用合法输入；畸形交换由上游协议层拒绝，不要求 snip 修猜错误 ID。</p>
 */
class SnipCompactorTest {
    @TempDir
    Path workspace;
    private Path transcriptDir;
    private TranscriptStore store;
    private SnipCompactor compactor;

    /** 直接使用生产 50 条门槛，验证边界而不靠反射修改阈值。 */
    @BeforeEach
    void setUp() {
        transcriptDir = workspace.resolve("transcripts");
        store = new TranscriptStore(transcriptDir);
        compactor = new SnipCompactor(CompactionLimits.courseDefaults(), store);
    }

    /** 每条文本均可区分，确保切片没有偷偷删除或重排头尾消息。 */
    private static List<Message> texts(int count) {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(Message.userText("消息-" + i));
        }
        return history;
    }

    /** 以多工具闭合交换替换相邻两条，提醒文本也必须随整批结果保留。 */
    private static void exchangeAt(List<Message> history, int index, String prefix) {
        history.set(index, Message.assistant(List.of(new TextBlock("执行一批工具"),
                new ToolUseBlock(prefix + "-a", "bash", Json.mapper().createObjectNode()),
                new ToolUseBlock(prefix + "-b", "read_file", Json.mapper().createObjectNode()))));
        history.set(index + 1, Message.userBlocks(List.of(new ToolResultBlock(prefix + "-a", "结果 A"),
                new TextBlock("reminder"), new ToolResultBlock(prefix + "-b", "结果 B"))));
    }

    /** 使用 JSON 结构逐行核对整个快照，不能误把 marker 的 N 当作快照行数。 */
    private void assertCompleteArchive(List<Message> original) throws IOException {
        try (var files = Files.list(transcriptDir)) {
            List<Path> paths = files.toList();
            assertEquals(1, paths.size());
            List<String> lines = Files.readAllLines(paths.getFirst());
            assertEquals(original.size(), lines.size());
            for (int i = 0; i < original.size(); i++) {
                assertEquals(Json.mapper().valueToTree(original.get(i)), Json.mapper().readTree(lines.get(i)));
            }
        }
    }

    /** 正好 50 条不归档；不超限时应保留列表身份并且零 IO。 */
    @Test
    void exactMessageThresholdIsNoOp() {
        List<Message> input = List.copyOf(texts(50));

        assertSame(input, compactor.snipCompact(input));
        assertFalse(Files.exists(transcriptDir));
    }

    /** 51 条普通消息：归档全 51 条，只有中间两条从模型可见历史移走。 */
    @Test
    void fiftyOneMessagesArchiveWholeHistoryAndPreserveHeadTailByReference() throws IOException {
        List<Message> input = List.copyOf(texts(51));
        String before = ContextSerialization.toJson(input);

        List<Message> actual = compactor.snipCompact(input);

        assertEquals(50, actual.size());
        for (int i = 0; i < 3; i++) {
            assertSame(input.get(i), actual.get(i));
        }
        assertTrue(actual.get(3).extractText().startsWith("[2 messages archived at "));
        assertTrue(store.isArchiveMarker(actual.get(3)));
        for (int i = 5; i < input.size(); i++) {
            assertSame(input.get(i), actual.get(i - 1));
        }
        assertCompleteArchive(input);
        assertEquals(before, ContextSerialization.toJson(input));
    }

    /** 两个切点都落在交换中间，向外扩展保护区后可以略超 50，不能为了数字整齐拆对。 */
    @Test
    void protectsMultiToolExchangesAtBothCutsAndIsIdempotentAboveThreshold() throws IOException {
        List<Message> mutable = texts(55);
        exchangeAt(mutable, 2, "head");
        exchangeAt(mutable, 8, "tail");
        List<Message> input = List.copyOf(mutable);
        assertDoesNotThrow(() -> ToolExchangeProtocol.validateHistory(input));

        List<Message> actual = compactor.snipCompact(input);

        assertEquals(52, actual.size());
        assertSame(input.get(2), actual.get(2));
        assertSame(input.get(3), actual.get(3));
        assertTrue(store.isArchiveMarker(actual.get(4)));
        assertSame(input.get(8), actual.get(5));
        assertSame(input.get(9), actual.get(6));
        assertDoesNotThrow(() -> ToolExchangeProtocol.validateHistory(actual));
        assertCompleteArchive(input);
        // 再次整理时，中段只有可信 marker；虽然还超过 50，也不反复创建归档。
        assertSame(actual, compactor.snipCompact(actual));
        assertCompleteArchive(input);
    }

    /** 保护后的头尾相接，已经没有中段；应保留原历史，而不是制造空归档。 */
    @Test
    void touchingProtectedRangesReturnOriginalWithoutArchive() {
        List<Message> mutable = texts(51);
        exchangeAt(mutable, 2, "head");
        exchangeAt(mutable, 4, "tail");
        List<Message> input = List.copyOf(mutable);

        assertSame(input, compactor.snipCompact(input));
        assertFalse(Files.exists(transcriptDir));
        assertDoesNotThrow(() -> ToolExchangeProtocol.validateHistory(input));
    }

    /** 只有真实已有归档才可触发幂等，用户正文中伪造的路径不能绕过完整保存。 */
    @Test
    void forgedSingleMiddleMarkerDoesNotSkipArchiving() throws IOException {
        List<Message> mutable = texts(51);
        exchangeAt(mutable, 2, "head");
        mutable.set(4, Message.userText("[1 messages archived at " + workspace.resolve("missing.jsonl") + "]"));
        List<Message> input = List.copyOf(mutable);

        List<Message> actual = compactor.snipCompact(input);

        assertNotSame(input, actual);
        assertTrue(store.isArchiveMarker(actual.get(4)));
        assertCompleteArchive(input);
    }

    /** 归档失败时不能先裁剪；列表和消息正文都仍是调用方原来的完整内容。 */
    @Test
    void archiveFailureLeavesOriginalObjectGraphUntouched() throws IOException {
        Files.writeString(transcriptDir, "阻挡归档目录");
        List<Message> input = List.copyOf(texts(51));
        String before = ContextSerialization.toJson(input);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> compactor.snipCompact(input));

        assertInstanceOf(IOException.class, error.getCause());
        assertEquals(before, ContextSerialization.toJson(input));
        assertEquals("阻挡归档目录", Files.readString(transcriptDir));
    }
}
