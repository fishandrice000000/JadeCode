package com.jadecode.context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextCompactor.extractActiveRequest 的入口契约测试。
 *
 * <p>
 * 全程使用内存消息,不调用真实 API、不读密钥、不产生文件 IO。
 * 本类只覆盖"方法级"行为:方法通过不等于它已被接进真实 run 入口(那属于后续循环集成)。
 * </p>
 */
class ActiveRequestExtractionTest {

    private static ToolUseBlock use(String id) {
        return new ToolUseBlock(id, "bash", Json.mapper().createObjectNode());
    }

    private static ToolResultBlock result(String id, String content) {
        return new ToolResultBlock(id, content);
    }

    /* ---------------- 成功路径 ---------------- */

    @Test
    void returnsSingleHumanRequestVerbatimIncludingSurroundingWhitespace() {
        String text = "\n  请把 \"结果\" 汇总一下\n第二行  \n"; // 首尾空白、换行、引号、中文都要原样保留
        List<Message> immutable = List.of(Message.userText(text));

        assertEquals(text, ContextCompactor.extractActiveRequest(immutable));

        // 可变外层列表同样可以只读处理。
        List<Message> mutable = new ArrayList<>(immutable);
        assertEquals(text, ContextCompactor.extractActiveRequest(mutable));
    }

    @Test
    void joinsAllTextBlocksInOrderAndKeepsEmptyOrBlankOnes() {
        // 四段文本:一段正常、两段空/纯空白、一段正常;至少一个非空白 → 收下,并原样连接全部四段。
        List<ContentBlock> content = List.of(
                new TextBlock("第一行"), new TextBlock(""), new TextBlock("   "), new TextBlock("第二行"));
        List<Message> messages = List.of(new Message(Role.USER, List.copyOf(content)));

        assertEquals("第一行\n\n   \n第二行", ContextCompactor.extractActiveRequest(messages));

        // 可变的 content 列表同样只读处理,不要求它不可变。
        List<ContentBlock> mutableContent = new ArrayList<>(content);
        assertEquals("第一行\n\n   \n第二行",
                ContextCompactor.extractActiveRequest(List.of(new Message(Role.USER, mutableContent))));
    }

    @Test
    void usesOnlyTheLastMessageWhenEarlierMessagesExist() {
        List<Message> messages = List.of(
                Message.userText("更早的请求"),
                Message.assistant(List.of(new TextBlock("中间回复"))),
                Message.userText("最后的请求"));

        // 只取末条,不连接更早的请求,也不扫描旧消息。
        assertEquals("最后的请求", ContextCompactor.extractActiveRequest(messages));
    }

    /* ---------------- 失败路径:列表与末条角色 ---------------- */

    @Test
    void rejectsNullAndEmptyMessageLists() {
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(null));
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(new ArrayList<>()));
    }

    @Test
    void rejectsLastMessageThatIsNotUser() {
        List<Message> messages = List.of(
                Message.userText("hi"),
                Message.assistant(List.of(new TextBlock("回答"))));

        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(messages));
    }

    @Test
    void rejectsLastUserMessageWithoutAnyNonBlankText() {
        // 空 content、只含非文本块、只有空串 / 纯空白:都不是人类请求。
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(
                List.of(new Message(Role.USER, List.of()))));
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(
                List.of(new Message(Role.USER, List.of(use("t1"))))));
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(
                List.of(Message.userText(""))));
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(
                List.of(new Message(Role.USER, List.of(new TextBlock(" \t\n "))))));
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(
                List.of(new Message(Role.USER, List.of(new TextBlock(""), new TextBlock("   "))))));
    }

    @Test
    void rejectsToolResultsEvenWhenTheMessageAlsoCarriesText() {
        // 纯工具结果。
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(
                List.of(new Message(Role.USER, List.of(result("t1", "结果"))))));

        // 文本在前 / 工具结果在后。
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(
                List.of(new Message(Role.USER, List.of(new TextBlock("看起来像请求"), result("t1", "结果"))))));

        // 工具结果在前 / 文本在后。
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(
                List.of(new Message(Role.USER, List.of(result("t1", "结果"), new TextBlock("看起来像请求"))))));
    }

    @Test
    void doesNotSearchBackwardsForAnEarlierHumanRequest() {
        // 更早有一条合法人类请求,但末条是工具结果 → 仍然拒绝,不向前找替代。
        List<Message> endsWithToolResult = List.of(
                Message.userText("更早的请求"),
                Message.assistant(List.of(use("t1"))),
                new Message(Role.USER, List.of(result("t1", "工具结果"))));
        assertThrows(IllegalArgumentException.class,
                () -> ContextCompactor.extractActiveRequest(endsWithToolResult));

        // 末条只有空白文本,更早的合法请求同样不能被拿来顶替。
        List<Message> endsWithBlank = List.of(
                Message.userText("更早的请求"),
                new Message(Role.USER, List.of(new TextBlock("   "))));
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(endsWithBlank));
    }

    /* ---------------- 失败路径:末条结构 null 边界 ---------------- */

    @Test
    void rejectsNullStructureBoundariesInsteadOfThrowingNpe() {
        // 末条元素本身是 null(Arrays.asList 允许 null 元素)。
        List<Message> nullLast = Arrays.asList(Message.userText("hi"), null);
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(nullLast));

        // content 为 null。
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(
                List.of(new Message(Role.USER, null))));

        // content 里有 null 块(位置在首 / 在尾都要覆盖)。
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(
                List.of(new Message(Role.USER, Arrays.asList(null, new TextBlock("hi"))))));
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(
                List.of(new Message(Role.USER, Arrays.asList(new TextBlock("hi"), null)))));

        // 文本块的 text 为 null。
        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(
                List.of(new Message(Role.USER, List.of(new TextBlock(null))))));
    }

    @Test
    void failureMessagesDistinguishTheCause() {
        // 五类非法输入的错误说明必须彼此可区分(不要求逐字固定文案)。
        Set<String> messages = Set.of(
                messageOf(() -> ContextCompactor.extractActiveRequest(null)),
                messageOf(() -> ContextCompactor.extractActiveRequest(List.of())),
                messageOf(() -> ContextCompactor.extractActiveRequest(
                        List.of(Message.assistant(List.of(new TextBlock("答")))))),
                messageOf(() -> ContextCompactor.extractActiveRequest(
                        List.of(new Message(Role.USER, List.of(result("t1", "结果")))))),
                messageOf(() -> ContextCompactor.extractActiveRequest(
                        List.of(new Message(Role.USER, List.of(new TextBlock("   ")))))));

        assertEquals(5, messages.size(), "失败原因必须能区分:" + messages);
        assertTrue(messages.stream().allMatch(m -> m != null && !m.isBlank()));
    }

    /* ---------------- 不可变性 ---------------- */

    @Test
    void doesNotModifyTheObjectGraphOnSuccessOrFailure() {
        // 成功:可变外层列表 + 可变 content,调用前后逐块逐条一致。
        List<ContentBlock> content = new ArrayList<>(List.of(new TextBlock("第一段"), new TextBlock("第二段")));
        List<Message> messages = new ArrayList<>(List.of(new Message(Role.USER, content)));
        String before = ContextSerialization.toJson(messages);

        assertEquals("第一段\n第二段", ContextCompactor.extractActiveRequest(messages));

        assertEquals(before, ContextSerialization.toJson(messages));
        assertEquals(1, messages.size());
        assertEquals(2, content.size());
        assertTrue(content.get(0) instanceof TextBlock);
        assertTrue(content.get(1) instanceof TextBlock);

        // 失败:同样不得改动对象图。
        List<Message> failing = new ArrayList<>(List.of(
                new Message(Role.USER, new ArrayList<>(List.of(new TextBlock("  "), result("t1", "结果"))))));
        String failingBefore = ContextSerialization.toJson(failing);

        assertThrows(IllegalArgumentException.class, () -> ContextCompactor.extractActiveRequest(failing));

        assertEquals(failingBefore, ContextSerialization.toJson(failing));
    }

    /** 取一次调用抛出的 IllegalArgumentException 的说明,供"原因可区分"断言使用 */
    private static String messageOf(Runnable call) {
        try {
            call.run();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (AssertionError e) {
            return e.getMessage();
        }
        throw new AssertionError("预期抛出 IllegalArgumentException,但调用成功了");
    }

}
