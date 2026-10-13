package com.jadecode.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.jadecode.messages.Message;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 历史快照的恢复契约：完整保存传入历史，路径有效后才能用 archive marker 替换中段。
 * <p>本类只验证现有快照存储，不把它误称为持续会话日志或重启恢复机制。</p>
 */
class TranscriptStoreTest {
    @TempDir
    Path workspace;
    private Path transcriptDir;
    private TranscriptStore store;

    /** 使用含方括号的合法目录，避免路径解析把正常工作区名称当成 marker 定界符。 */
    @BeforeEach
    void setUp() {
        transcriptDir = workspace.resolve("transcripts[demo]");
        store = new TranscriptStore(transcriptDir);
    }

    /** N 是被替换消息数，不要求等于整个快照行数；这里只构造协议外壳。 */
    private static Message marker(String count, Path path) {
        return Message.userText("[" + count + " messages archived at " + path + "]");
    }

    /** 原文的换行应被 JSON 转义，不能把一条消息拆成多个 JSONL 物理行。 */
    @Test
    void writesEveryMessageInOrderAsCompleteUtf8JsonLines() throws IOException {
        List<Message> history = List.of(Message.userText("中文🙂\n\"引号\"与\\"),
                Message.assistant(List.of(new ToolUseBlock("call", "bash", Json.mapper().createObjectNode()))),
                Message.userBlocks(List.of(new ToolResultBlock("call", "第一行\n第二行\n"),
                        new TextBlock("reminder"))));
        String before = ContextSerialization.toJson(history);

        Path saved = store.writeTranscript(history);
        List<String> lines = Files.readAllLines(saved, StandardCharsets.UTF_8);

        assertTrue(saved.isAbsolute());
        assertEquals(history.size(), lines.size());
        for (int i = 0; i < history.size(); i++) {
            // 解析回 JSON 比较结构与顺序，不仅检查文件里出现了几个关键词。
            assertEquals(Json.mapper().valueToTree(history.get(i)), Json.mapper().readTree(lines.get(i)));
        }
        assertEquals(before, ContextSerialization.toJson(history));
        assertTrue(store.isArchiveMarker(marker("1", saved)));
    }

    /** 同内容快照仍各自独立；空历史合法写出空文件，不伪造消息。 */
    @Test
    void repeatedAndEmptyArchivesNeverOverwriteExistingSnapshot() throws IOException {
        List<Message> history = List.of(Message.userText("原文"));
        Path first = store.writeTranscript(history);
        Path second = store.writeTranscript(history);
        Path empty = store.writeTranscript(List.of());

        assertNotEquals(first, second);
        assertEquals(Files.readString(first), Files.readString(second));
        assertEquals("", Files.readString(empty));
        try (var files = Files.list(transcriptDir)) {
            assertEquals(3, files.count());
        }
    }

    /** 幂等判断只能识别完整协议外壳，不能把普通用户正文当作已经归档。 */
    @Test
    void requiresUserSingleTextBlockExactShapeAndNonNegativeCount() throws IOException {
        Path saved = store.writeTranscript(List.of(Message.userText("原文")));
        String valid = marker("1", saved).extractText();
        for (Message candidate : List.of(
                Message.assistant(List.of(new TextBlock(valid))),
                Message.userBlocks(List.of(new TextBlock(valid), new TextBlock("额外块"))),
                Message.userBlocks(List.of(new ToolResultBlock("call", valid))),
                Message.userText("前言" + valid), Message.userText(valid + "尾随正文"),
                marker("-1", saved), marker("", saved), marker("2147483648", saved),
                Message.userText("[1 messages archived at " + saved + "\n额外正文]"))) {
            assertFalse(store.isArchiveMarker(candidate), candidate.toString());
        }
        assertFalse(store.isArchiveMarker(null));
        assertTrue(store.isArchiveMarker(marker("0", saved)));
    }

    /** 同名前缀的兄弟目录不在围栏内；缺失、相对路径与目录也不是可恢复快照。 */
    @Test
    void rejectsForeignMissingRelativeAndNonFileArchivePaths() throws IOException {
        store.writeTranscript(List.of());
        Path sibling = Files.createDirectories(workspace.resolve("transcripts[demo]-other"));
        Path foreign = Files.writeString(sibling.resolve("foreign.jsonl"), "外部文件");

        for (Path path : List.of(foreign, transcriptDir.resolve("missing.jsonl"), transcriptDir,
                Path.of("relative.jsonl"))) {
            assertFalse(store.isArchiveMarker(marker("1", path)));
        }
        // 非法路径要作为 marker 文本交给被测方法，不能由测试先调用 Path.of 抛出。
        assertFalse(store.isArchiveMarker(Message.userText("[1 messages archived at bad\u0000path]")));
    }

    /** 归档恢复与工具正文恢复一样，必须根据软链的真实目标判断目录归属。 */
    @Test
    void rejectsArchiveSymlinkEscapingInjectedDirectory() throws IOException {
        Path saved = store.writeTranscript(List.of(Message.userText("原文")));
        Path outside = Files.writeString(workspace.resolve("outside.jsonl"), "外部内容");
        Path insideLink = Files.createSymbolicLink(transcriptDir.resolve("inside.jsonl"), saved);
        Path outsideLink = Files.createSymbolicLink(transcriptDir.resolve("outside.jsonl"), outside);

        assertTrue(store.isArchiveMarker(marker("1", insideLink)));
        assertFalse(store.isArchiveMarker(marker("1", outsideLink)));
    }

    /** 建目录失败必须明确报告，不能把不存在的 archive 路径交给模型。 */
    @Test
    void directoryFailurePreservesBlockerAndReportsCause() throws IOException {
        Files.writeString(transcriptDir, "已有文件");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> store.writeTranscript(List.of(Message.userText("不能丢的历史"))));

        assertInstanceOf(IOException.class, error.getCause());
        assertEquals("已有文件", Files.readString(transcriptDir));
    }

    /** 专用于稳定制造 Jackson 序列化错误，不向共享 mapper 注册额外模块。 */
    public static final class UnserializableValue {
        /** Jackson 访问属性时失败，用来检查“已创建文件后失败”的收尾。 */
        public String getValue() {
            throw new IllegalStateException("预置序列化失败");
        }
    }

    /** 已写一行后失败也不得留下半份恢复副本，更不能删除之前成功的其它快照。 */
    @Test
    void serializationFailureRemovesOnlyItsOwnPartialArchive() throws IOException {
        Path previous = store.writeTranscript(List.of(Message.userText("已有归档")));
        String previousBody = Files.readString(previous);
        var brokenInput = Json.mapper().createObjectNode();
        brokenInput.putPOJO("broken", new UnserializableValue());
        List<Message> history = List.of(Message.userText("先写出的正常消息"),
                Message.assistant(List.of(new ToolUseBlock("bad", "bash", brokenInput))));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> store.writeTranscript(history));

        assertNotNull(error.getCause());
        assertEquals(previousBody, Files.readString(previous));
        try (var files = Files.list(transcriptDir)) {
            assertEquals(List.of(previous), files.toList(), "只保留此前完整归档，本次半成品必须收尾");
        }
    }
}
