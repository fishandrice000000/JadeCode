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
 * ToolResultFitter 定向行为测试:候选选择与排序、目标判定与停止、可恢复落盘与可信路径复用、
 * 严格缩短守卫、不可变重建,以及失败语义。
 *
 * <p>
 * 全部使用 @TempDir 临时目录与内存消息,不调用真实 LLM,也不在仓库根留下 .task_outputs/。
 * </p>
 */
class ToolResultFitterTest {

    /** 落盘预览外壳的固定三段,断言时用来从外壳里取出预览正文 */
    private static final String SHELL_HEAD = "<persisted-output>\nFull output: ";
    private static final String SHELL_PREVIEW = "Preview:\n";
    private static final String SHELL_TAIL = "\n</persisted-output>";

    @TempDir
    Path workspace;

    /** 本次测试专属的 tool-results 目录(可能始终不存在:用来断言"目标已满足时无 IO") */
    private Path outputDir;

    private ToolOutputStore store;

    @BeforeEach
    void setUp() {
        outputDir = workspace.resolve("outputs");
        store = new ToolOutputStore(outputDir);
    }

    /**
     * 用指定存储与预览长度造 fitter:其余阈值沿用课程默认值,
     * 这样测试不用为了触发 fit 去构造几十万字符的输入。
     */
    private static ToolResultFitter fitterFor(ToolOutputStore store, int previewChars) {
        CompactionLimits defaults = CompactionLimits.courseDefaults();
        CompactionLimits limits = new CompactionLimits(
                defaults.contextCharLimit(), defaults.toolResultBatchCharLimit(),
                defaults.largeResultCharLimit(), defaults.summaryInputCharLimit(),
                defaults.keepRecentResults(), defaults.keepRecentMessages(),
                defaults.microResultMinChars(), defaults.budgetPreviewChars(),
                previewChars, defaults.summaryMaxOutputTokens(), defaults.snipMaxMessages(),
                defaults.snipHeadMessages(), defaults.targetRatio());
        return new ToolResultFitter(limits, store);
    }

    /** 共用本测试落盘目录的 fitter */
    private ToolResultFitter fitter(int previewChars) {
        return fitterFor(store, previewChars);
    }

    /** 生产装配:预览长度取课程默认值 */
    private ToolResultFitter defaultFitter() {
        return new ToolResultFitter(CompactionLimits.courseDefaults(), store);
    }

    /** 造一个 tool_use 块(参数留空对象,fitter 只关心 ID 与文本) */
    private static ToolUseBlock use(String id) {
        return new ToolUseBlock(id, "bash", Json.mapper().createObjectNode());
    }

    /**
     * 造"一次工具交换":一条 assistant 调用消息 + 一条 user 结果消息。
     * 外层与 content 都用不可变列表,顺带覆盖 fit 对不可变输入的兼容。
     */
    private static List<Message> exchange(String... outputs) {
        List<ContentBlock> calls = new ArrayList<>();
        List<ContentBlock> results = new ArrayList<>();
        for (int i = 0; i < outputs.length; i++) {
            calls.add(use("call-" + i));
            results.add(new ToolResultBlock("call-" + i, outputs[i]));
        }
        return List.of(Message.assistant(List.copyOf(calls)), Message.userBlocks(List.copyOf(results)));
    }

    /** 取某条消息里的某个工具结果块 */
    private static ToolResultBlock resultAt(List<Message> messages, int messageIndex, int blockIndex) {
        return (ToolResultBlock) messages.get(messageIndex).content().get(blockIndex);
    }

    /** 单候选历史:user 文本 + 一次工具调用 + 一条结果(便于按精确长度构造边界用例) */
    private static List<Message> singleResult(String text) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("hi"));
        messages.addAll(exchange(text));
        return List.copyOf(messages);
    }

    /** 从固定形状的外壳里取出预览正文 */
    private static String previewOf(String shell) {
        assertTrue(shell.startsWith(SHELL_HEAD) && shell.endsWith(SHELL_TAIL), "必须是标准预览外壳");
        int start = shell.indexOf(SHELL_PREVIEW, SHELL_HEAD.length()) + SHELL_PREVIEW.length();
        return shell.substring(start, shell.length() - SHELL_TAIL.length());
    }

    /**
     * 按生产外壳格式手工拼一个带指定预览的文本:路径行与 {@code Preview:} 之间必须有换行,
     * 否则存储层的"固定位置解析"识别不出来。
     */
    private static String shell(Path path, String preview) {
        return SHELL_HEAD + path + "\n" + SHELL_PREVIEW + preview + SHELL_TAIL;
    }

    /** 断言结果是可信占位,并且完整原文能按路径原样读回;返回该路径 */
    private String assertPersisted(ToolResultBlock result, String original) throws IOException {
        String path = store.persistedOutputPath(result.content());
        assertNotNull(path, "占位必须能被存储接口识别为可信路径");
        assertEquals(original, Files.readString(Path.of(path)), "完整原文必须能按路径读回");
        return path;
    }

    /** 数一数落盘目录里的文件数,用来断言"没有多写文件" */
    private long outputCount() throws IOException {
        if (!Files.isDirectory(outputDir)) {
            return 0;
        }
        try (var paths = Files.list(outputDir)) {
            return paths.count();
        }
    }

    @Test
    void emptyAndTextOnlyHistoriesAreNoOps() {
        List<Message> empty = List.of();
        List<Message> textOnly = List.of(
                Message.userText("开始"),
                Message.assistant(List.of(new TextBlock("好的"))),
                Message.userText("[3 messages archived at /tmp/history.jsonl]"));

        // 没有工具结果可以处理:即使目标很小,也只是返回入参本身,不产生任何落盘。
        assertSame(empty, fitter(10).fitToolResults(empty, 0));
        assertSame(textOnly, fitter(10).fitToolResults(textOnly, 1));
        assertFalse(Files.exists(outputDir), "没有候选时不得落盘");
    }

    @Test
    void atOrBelowTargetReturnsInputWithoutIo() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("开始"));
        messages.addAll(exchange("x".repeat(4000)));
        List<Message> input = List.copyOf(messages);
        int chars = ContextSerialization.estimateChars(input);

        // 恰好等于目标与低于目标都必须直接返回,不做任何落盘或预览读取。
        assertSame(input, fitter(10).fitToolResults(input, chars));
        assertSame(input, fitter(10).fitToolResults(input, chars + 1));
        assertFalse(Files.exists(outputDir), "目标已满足时不得落盘");
    }

    @Test
    void longestResultIsFittedFirstAndOtherCandidatesStayUntouched() throws IOException {
        String longest = "长".repeat(4000);
        String shorter = "短".repeat(2000);
        TextBlock reminder = new TextBlock("<reminder>Update your todos.</reminder>");
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("开始"));
        messages.add(Message.assistant(List.of(use("big"))));
        messages.add(Message.userBlocks(List.of(new ToolResultBlock("big", longest))));
        messages.add(Message.assistant(List.of(use("small"))));
        // 结果消息里混排的 reminder 文本必须原样留在原位,不能被替换或重排。
        messages.add(Message.userBlocks(List.of(new ToolResultBlock("small", shorter), reminder)));
        // archive marker 形状的普通 user 文本也不参与任何处理。
        messages.add(Message.userText("[7 messages archived at /tmp/history.jsonl]"));
        List<Message> input = List.copyOf(messages);
        String before = ContextSerialization.toJson(input);
        int chars = ContextSerialization.estimateChars(input);

        // 目标只比当前少 1 个字符:最大的那个候选落盘后就已经达标,第二个候选必须保持原样。
        List<Message> actual = fitter(10).fitToolResults(input, chars - 1);

        assertNotSame(input, actual);
        assertPersisted(resultAt(actual, 2, 0), longest);
        assertEquals("big", resultAt(actual, 2, 0).toolUseId());
        // 未替换的消息与 block 保持原引用:消息顺序、块顺序、ID 都没有被排序打乱。
        assertSame(input.get(4), actual.get(4));
        assertSame(input.get(4).content().get(0), actual.get(4).content().get(0));
        assertSame(reminder, actual.get(4).content().get(1));
        assertEquals("small", resultAt(actual, 4, 0).toolUseId());
        assertEquals(shorter, resultAt(actual, 4, 0).content());
        assertSame(input.get(0), actual.get(0));
        assertSame(input.get(5), actual.get(5));
        assertEquals(6, actual.size());
        assertEquals(1, outputCount());
        assertEquals(before, ContextSerialization.toJson(input), "原输入对象图不得被修改");
        assertDoesNotThrow(() -> ToolExchangeProtocol.validateHistory(actual));
    }

    @Test
    void equalLengthResultsKeepTraversalOrder() throws IOException {
        String first = "A".repeat(2000);
        String second = "B".repeat(2000); // 与 first 等长,排序时必须保持出现顺序
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("开始"));
        messages.addAll(exchange(first, second));
        List<Message> input = List.copyOf(messages);
        int chars = ContextSerialization.estimateChars(input);

        // 目标只允许替换一个:先遍历到的 call-0 应当被处理,而不是后来的 call-1。
        List<Message> actual = fitter(10).fitToolResults(input, chars - 1);

        assertPersisted(resultAt(actual, 2, 0), first);
        assertSame(input.get(2).content().get(1), actual.get(2).content().get(1));
        assertEquals(second, resultAt(actual, 2, 1).content());
        assertEquals(1, outputCount());
    }

    @Test
    void sameMessageMultipleReplacementsAccumulate() throws IOException {
        String a = "a".repeat(3000);
        String b = "b".repeat(2000);
        String c = "c".repeat(1000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("开始"));
        messages.add(Message.assistant(List.of(use("a"), use("b"), use("c"))));
        messages.add(Message.userBlocks(List.of(
                new ToolResultBlock("a", a), new ToolResultBlock("b", b), new ToolResultBlock("c", c))));
        List<Message> input = List.copyOf(messages);
        String before = ContextSerialization.toJson(input);

        // 目标 0 不可能达到,于是三个候选都会被处理:三次替换必须累积在同一条新消息上。
        List<Message> actual = fitter(5).fitToolResults(input, 0);

        assertNotSame(input.get(2), actual.get(2));
        assertPersisted(resultAt(actual, 2, 0), a);
        assertPersisted(resultAt(actual, 2, 1), b);
        assertPersisted(resultAt(actual, 2, 2), c);
        // ID 与 block 的顺序不因为处理顺序(按长度)而改变。
        assertEquals(List.of("a", "b", "c"), actual.get(2).content().stream()
                .map(block -> ((ToolResultBlock) block).toolUseId())
                .toList());
        assertEquals(3, outputCount());
        assertEquals(before, ContextSerialization.toJson(input), "原对象图不得被修改");
        assertSame(input.get(0), actual.get(0));
        assertSame(input.get(1), actual.get(1));
    }

    @Test
    void unseenFreshBatchCanBeFitted() throws IOException {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("开始"));
        messages.addAll(exchange("fresh".repeat(500))); // 末尾就是模型还没读到的最新批次
        List<Message> input = List.copyOf(messages);
        int chars = ContextSerialization.estimateChars(input);

        List<Message> actual = fitter(20).fitToolResults(input, chars - 1);

        assertNotSame(input, actual);
        assertPersisted(resultAt(actual, 2, 0), "fresh".repeat(500));
        assertEquals(1, outputCount());
        assertDoesNotThrow(() -> ToolExchangeProtocol.validateHistory(actual));
    }

    @Test
    void injectedPreviewLengthIsUsedExactly() throws IOException {
        String original = "0123456789".repeat(200);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("hi"));
        messages.addAll(exchange(original));
        List<Message> input = List.copyOf(messages);
        int chars = ContextSerialization.estimateChars(input);

        List<Message> actual = fitter(7).fitToolResults(input, chars - 1);

        // 预览就是原文开头 7 个 char:既不补齐,也不加省略号。
        assertEquals("0123456", previewOf(resultAt(actual, 2, 0).content()));
        assertPersisted(resultAt(actual, 2, 0), original);
    }

    @Test
    void zeroPreviewLengthIsValid() throws IOException {
        String original = "x".repeat(2000);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("hi"));
        messages.addAll(exchange(original));
        List<Message> input = List.copyOf(messages);
        int chars = ContextSerialization.estimateChars(input);

        List<Message> actual = fitter(0).fitToolResults(input, chars - 1);

        assertEquals("", previewOf(resultAt(actual, 2, 0).content()), "0 长度预览是空串");
        assertPersisted(resultAt(actual, 2, 0), original);
    }

    @Test
    void productionDefaultPreviewIsOneThousandJavaChars() throws IOException {
        assertEquals(1000, CompactionLimits.courseDefaults().fitPreviewChars(),
                "生产默认预览长度必须是课程规定的 1000 个 Java char");

        String original = "内容".repeat(2000); // 4000 个 char,远超默认预览
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("hi"));
        messages.addAll(exchange(original));
        List<Message> input = List.copyOf(messages);
        int chars = ContextSerialization.estimateChars(input);

        List<Message> actual = defaultFitter().fitToolResults(input, chars - 1);

        assertEquals(original.substring(0, 1000), previewOf(resultAt(actual, 2, 0).content()));
        assertEquals(1000, previewOf(resultAt(actual, 2, 0).content()).length());
        assertPersisted(resultAt(actual, 2, 0), original);
    }

    @Test
    void replacementThatDoesNotShrinkLeavesResultUntouched() throws IOException {
        String shortText = "很短的结果";
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("hi"));
        messages.addAll(exchange(shortText));
        List<Message> input = List.copyOf(messages);
        int chars = ContextSerialization.estimateChars(input);

        // 结果比预览还短:套上外壳只会更长,严格缩短守卫生效,整体返回原列表。
        List<Message> actual = fitter(1000).fitToolResults(input, chars - 1);

        assertSame(input, actual);
        assertEquals(shortText, resultAt(input, 2, 0).content());
        // 现有实现先落盘再比较长度,所以可能留下一个当前没被引用的文件(不丢数据,最坏多一个文件)。
        assertEquals(1, outputCount());
    }

    @Test
    void trustedPreviewIsNarrowedByReusingTheSameFile() throws IOException {
        String original = "y".repeat(3000);
        String wide = store.persistedPreview("call-0", original, 900); // 更早的层留下的宽预览
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("hi"));
        messages.addAll(exchange(wide));
        List<Message> input = List.copyOf(messages);
        int chars = ContextSerialization.estimateChars(input);

        List<Message> actual = fitter(10).fitToolResults(input, chars - 1);

        String shell = resultAt(actual, 2, 0).content();
        assertEquals(original.substring(0, 10), previewOf(shell));
        assertEquals(store.persistedOutputPath(wide), store.persistedOutputPath(shell),
                "必须复用同一份完整原文,不得重复落盘");
        assertPersisted(resultAt(actual, 2, 0), original);
        assertEquals(1, outputCount());
        assertEquals(wide, resultAt(input, 2, 0).content(), "原输入未被修改");
    }

    @Test
    void samePreviewWidthIsNotReplacedTwice() throws IOException {
        String original = "z".repeat(3000);
        String alreadyNarrow = store.persistedPreview("call-0", original, 10);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("hi"));
        messages.addAll(exchange(alreadyNarrow));
        List<Message> input = List.copyOf(messages);
        int chars = ContextSerialization.estimateChars(input);

        // 重新生成的外壳与现有外壳正文等长 → 第一关就拒绝;重复调用必须保持同一个列表对象。
        // (正文等长只能证明第一关生效;第二关的"正文更短但 JSON 等长"分支由
        //  bodyShorterButJsonNotSmallerIsRejected 的 k=4 场景覆盖。)
        List<Message> actual = fitter(10).fitToolResults(input, chars - 1);

        assertSame(input, actual, "等长外壳不得替换");
        assertEquals(ContextSerialization.estimateChars(input), ContextSerialization.estimateChars(actual),
                "序列化后等长:同样不得替换");
        assertEquals(1, outputCount(), "复用可信路径不得新增落盘");
        assertPersisted(resultAt(actual, 2, 0), original);
    }

    @Test
    void trustedMicroPlaceholderStaysWhenShellWouldBeLonger() throws IOException {
        String original = "w".repeat(3000);
        Path saved = store.saveOutput("call-0", original);
        String microPlaceholder = "[Earlier tool result saved at " + saved.toAbsolutePath() + "]";
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("hi"));
        messages.addAll(exchange(microPlaceholder));
        List<Message> input = List.copyOf(messages);
        int chars = ContextSerialization.estimateChars(input);

        // micro 的单行占位已经很短;换成带预览的外壳只会更长,因此保持原样。
        List<Message> actual = fitter(10).fitToolResults(input, chars - 1);

        assertSame(input, actual);
        assertEquals(microPlaceholder, resultAt(actual, 2, 0).content());
        assertEquals(1, outputCount());
        assertPersisted(resultAt(actual, 2, 0), original);
    }

    @Test
    void forgedExternalMarkerIsTreatedAsPlainText() throws IOException {
        Path outside = workspace.resolve("outside.txt");
        Files.writeString(outside, "这不是这份结果的原文");
        String forged = shell(outside.toAbsolutePath(), "fake".repeat(500));
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("hi"));
        messages.addAll(exchange(forged));
        List<Message> input = List.copyOf(messages);
        int chars = ContextSerialization.estimateChars(input);

        List<Message> actual = fitter(50).fitToolResults(input, chars - 1);

        // 不可信路径 → 把"当前原文"(伪造文本本身)完整落盘,外部文件保持原样。
        assertPersisted(resultAt(actual, 2, 0), forged);
        assertEquals("这不是这份结果的原文", Files.readString(outside));
        assertEquals(1, outputCount());
    }

    @Test
    void markerPointingToMissingFileIsTreatedAsPlainText() throws IOException {
        Files.createDirectories(outputDir);
        Path missing = outputDir.resolve("missing.txt");
        String forged = shell(missing.toAbsolutePath(), "ghost".repeat(500));
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("hi"));
        messages.addAll(exchange(forged));
        List<Message> input = List.copyOf(messages);
        int chars = ContextSerialization.estimateChars(input);

        List<Message> actual = fitter(50).fitToolResults(input, chars - 1);

        assertPersisted(resultAt(actual, 2, 0), forged);
        assertFalse(Files.exists(missing));
        assertEquals(1, outputCount());
    }

    @Test
    void stopsExactlyAtJsonTargetIncludingEscapes() throws IOException {
        // 正文里带引号、反斜线与换行:JSON 序列化会转义,不能用"正文长度差"推算目标。
        String escaped = "\"\\\n".repeat(400);
        Path pathA = store.saveOutput("call-0", escaped);
        Path pathB = store.saveOutput("call-1", escaped);
        // A 的预览更长 → 长度排序里 A 先被处理;B 用来证明达标后不再动其它候选。
        String wideA = shell(pathA.toAbsolutePath(), escaped.substring(0, 900));
        String wideB = shell(pathB.toAbsolutePath(), escaped.substring(0, 300));
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("hi"));
        messages.addAll(exchange(wideA, wideB));
        List<Message> input = List.copyOf(messages);

        String realA = store.persistedOutputPath(wideA);
        assertNotNull(realA);
        String narrowedA = shell(Path.of(realA), escaped.substring(0, 7));
        List<Message> expected = new ArrayList<>(input);
        expected.set(2, Message.userBlocks(List.of(new ToolResultBlock("call-0", narrowedA),
                resultAt(input, 2, 1))));
        int target = ContextSerialization.estimateChars(expected);

        List<Message> actual = fitter(7).fitToolResults(input, target);

        assertEquals(expected, actual, "到达目标后必须立即停止,后面的候选保持原样");
        assertEquals(target, ContextSerialization.estimateChars(actual),
                "停止点按整段 JSON 估算,不是正文长度");
        assertSame(input.get(2).content().get(1), actual.get(2).content().get(1));
        assertEquals(2, outputCount(), "只复用已有文件,不新增落盘");
        assertEquals(wideA, resultAt(input, 2, 0).content(), "原输入未被修改");
    }

    /**
     * 确定性边界:正文更短不代表 JSON 更短(标准外壳固定含 4 个换行)。
     *
     * <p>
     * 构造方式:工具结果目录里放一个短名字的真实文件,再放一个<b>名字很长</b>的符号链接指向它。
     * 旧正文是 micro 的单行占位,引用那个长路径(整行没有换行,JSON 只多两端的引号);
     * 新正文是同一份文件的可恢复外壳——外壳里的路径取 {@code toRealPath()} 之后的真实短路径。
     * 于是只要调节链接名长度,就能让"旧正文恰好比外壳长 k 个字符"精确成立,不依赖随机文件名。
     * </p>
     *
     * <p>
     * 四个场景分别断言,其中前两类正是旧的"只看正文长度"实现会错误采纳的:k=1..3 正文更短而
     * JSON 变长;k=4 正文更短而 JSON 等长;k=5 时 JSON 严格变短,必须正常采纳。
     * 所以本用例在旧实现上必然失败(窗口内会返回被改过的列表)。
     * </p>
     */
    @Test
    void bodyShorterButJsonNotSmallerIsRejected() throws IOException {
        Path dir = workspace.resolve("deterministic");
        Files.createDirectories(dir);
        Path real = Files.writeString(dir.resolve("r.txt"), "y".repeat(100)).toRealPath();
        String shellText = shell(real, ""); // 预览长度 0 时实现生成的外壳

        assertEquals(4, shellText.chars().filter(c -> c == '\n').count(),
                "标准外壳固定含 4 个换行,JSON 里每个会转义成两个字符");

        assertBoundaryCase(dir, real, shellText.length(), 1); // 正文短 1,JSON 长 3
        assertBoundaryCase(dir, real, shellText.length(), 2); // 正文短 2,JSON 长 2
        assertBoundaryCase(dir, real, shellText.length(), 3); // 正文短 3,JSON 长 1
        assertBoundaryCase(dir, real, shellText.length(), 4); // 正文短 4,JSON 等长 ← 第一关挡不住的那一档
        assertBoundaryCase(dir, real, shellText.length(), 5); // 正文短 5,JSON 短 1 → 应正常采纳
    }

    /**
     * 造一个"旧正文恰好比外壳长 k 个字符"的输入,断言实现是否采纳替换。
     *
     * <p>
     * 长度关系:旧正文是单行占位(0 个换行、路径更长),新外壳是 4 个换行、路径更短,
     * 所以正文差 {@code -k}、JSON 差 {@code 4-k}——{@code k<=4} 时 JSON 没有变小,必须拒绝;
     * {@code k>=5} 时才该采纳。
     * </p>
     *
     * @param dir         工具结果目录(占位路径与外壳路径都必须落在它下面才算可信)
     * @param real        目录内真实文件的规范化路径(外壳里真正写入的路径)
     * @param shellLength 预览长度为 0 时外壳正文的字符数
     * @param k           旧正文比外壳长的字符数
     */
    private void assertBoundaryCase(Path dir, Path real, int shellLength, int k) throws IOException {
        Path link = dir.resolve(longLinkName(dir, shellLength + k));
        Files.createSymbolicLink(link, real);
        String marker = "[Earlier tool result saved at " + link + "]";
        assertEquals(shellLength + k, marker.length(), "构造的旧正文长度必须精确");

        List<Message> input = singleResult(marker);
        String before = ContextSerialization.toJson(input);
        int target = ContextSerialization.estimateChars(input) - 1;

        List<Message> actual = fitterFor(new ToolOutputStore(dir), 0).fitToolResults(input, target);

        if (k >= 5) {
            assertNotSame(input, actual, "正文与 JSON 都变短时必须采纳(k=" + k + ")");
            assertTrue(ContextSerialization.estimateChars(actual)
                    < ContextSerialization.estimateChars(input), "被采纳的替换必须让 JSON 变小");
        } else {
            assertSame(input, actual, "正文更短但 JSON 不变小必须拒绝(k=" + k + ")");
            assertEquals(before, ContextSerialization.toJson(input), "被拒绝时原输入必须完整(k=" + k + ")");
        }
    }

    /**
     * 生成长度合适的符号链接名,使 micro 单行占位正好达到 {@code targetLength} 个字符。
     *
     * <p>
     * 占位文本 = 前缀 + 绝对路径 + {@code "]"},而绝对路径 = 目录 + {@code "/"} + 链接名,
     * 因此反推出链接名长度,再用 {@code "l" + "a"...} 凑出这个长度。
     * </p>
     *
     * @param dir          工具结果目录(必须是绝对路径)
     * @param targetLength 希望占位文本达到的总长度
     * @return 长度精确的链接名
     */
    private static String longLinkName(Path dir, int targetLength) {
        final String markerHead = "[Earlier tool result saved at ";
        assertTrue(dir.isAbsolute(), "目录必须是绝对路径,否则长度不可推算");
        int nameLength = targetLength - markerHead.length() - 1 /* "]" */ - dir.toString().length()
                - 1 /* "/" */;
        assertTrue(nameLength >= 1, "构造长度必须为正,实际 " + nameLength);
        return "l" + "a".repeat(nameLength - 1);
    }

    @Test
    void ioFailurePropagatesWithCauseAndLeavesInputIntact() throws IOException {
        // 把落盘目录的位置占成一个普通文件:建目录会失败。
        Files.writeString(outputDir, "这是文件,不能作为落盘目录");
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("hi"));
        messages.addAll(exchange("x".repeat(1000)));
        List<Message> input = List.copyOf(messages);
        String before = ContextSerialization.toJson(input);
        int chars = ContextSerialization.estimateChars(input);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> fitter(10).fitToolResults(input, chars - 1));

        assertNotNull(error.getCause(), "失败必须带 cause");
        assertEquals(before, ContextSerialization.toJson(input), "失败时原输入仍完整");
        assertEquals("这是文件,不能作为落盘目录", Files.readString(outputDir));
    }

    @Test
    void candidatesExhaustedWithoutTargetReturnsActualResult() throws IOException {
        String shrinkable = "x".repeat(2000);
        String stubborn = "短得无法再变短";
        List<Message> messages = new ArrayList<>();
        messages.add(Message.userText("hi"));
        messages.addAll(exchange(shrinkable, stubborn));
        List<Message> input = List.copyOf(messages);

        // 目标 0 不可能达到:能压的压掉,压不动的保持原样,不抛"必须达标"异常。
        List<Message> actual = fitter(10).fitToolResults(input, 0);

        assertNotSame(input, actual);
        assertPersisted(resultAt(actual, 2, 0), shrinkable);
        assertEquals(stubborn, resultAt(actual, 2, 1).content());
        assertTrue(ContextSerialization.estimateChars(actual) > 0);
        assertEquals("call-1", resultAt(actual, 2, 1).toolUseId());
        assertDoesNotThrow(() -> ToolExchangeProtocol.validateHistory(actual));
    }
}
