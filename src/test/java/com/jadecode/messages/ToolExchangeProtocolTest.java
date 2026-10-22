package com.jadecode.messages;

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolExchangeProtocol 单元测试:配对/历史校验的单一实现。
 *
 * <p>不访问网络、真实 LLM 或文件系统,全部用内存消息构造。</p>
 */
class ToolExchangeProtocolTest {

    /** 造一个 tool_use(参数留空对象,校验只关心 ID 与结构) */
    private static ToolUseBlock use(String id) {
        ObjectNode input = Json.mapper().createObjectNode();
        return new ToolUseBlock(id, "bash", input);
    }

    private static ToolResultBlock result(String id, String content) {
        return new ToolResultBlock(id, content);
    }

    /** 合法历史:闭合交换 + reminder text + 普通文本 + archive marker 形状的 user 文本都允许 */
    @Test
    void validHistoryPasses() {
        List<Message> messages = List.of(
                Message.userText("开始"),
                Message.assistant(List.of(new TextBlock("先跑两条"), use("t1"), use("t2"))),
                Message.userBlocks(List.of(result("t1", "one"), result("t2", "two"),
                        new TextBlock("<reminder>Update your todos.</reminder>"))),
                Message.assistant(List.of(new TextBlock("完成"))),
                Message.userText("[3 messages archived at /tmp/transcript.jsonl]"),
                Message.userText("继续"));

        assertDoesNotThrow(() -> ToolExchangeProtocol.validateHistory(messages));
    }

    /** 孤立 tool_result:不是任何 assistant 调用的配对消息,拒绝 */
    @Test
    void orphanToolResultIsRejected() {
        List<Message> messages = List.of(
                Message.userText("hi"),
                Message.userBlocks(List.of(result("t1", "stray"))));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ToolExchangeProtocol.validateHistory(messages));
        assertTrue(e.getMessage().contains("orphan"));
    }

    /** 缺失结果:末尾 assistant(tool_use) 没有配对结果消息,拒绝 */
    @Test
    void missingResultIsRejected() {
        List<Message> messages = List.of(
                Message.userText("hi"),
                Message.assistant(List.of(use("t1"))));

        assertThrows(IllegalArgumentException.class,
                () -> ToolExchangeProtocol.validateHistory(messages));
    }

    /** 非相邻:tool_use 与 tool_result 之间夹了别的消息,拒绝 */
    @Test
    void nonAdjacentResultIsRejected() {
        List<Message> messages = List.of(
                Message.userText("hi"),
                Message.assistant(List.of(use("t1"))),
                Message.userText("先等等"),
                Message.userBlocks(List.of(result("t1", "late"))));

        assertThrows(IllegalArgumentException.class,
                () -> ToolExchangeProtocol.validateHistory(messages));
    }

    /** 错序/错 ID:数量一致但顺序不匹配,拒绝 */
    @Test
    void mismatchedOrderIsRejected() {
        List<Message> messages = List.of(
                Message.userText("hi"),
                Message.assistant(List.of(use("t1"), use("t2"))),
                Message.userBlocks(List.of(result("t2", "two"), result("t1", "one"))));

        assertThrows(IllegalArgumentException.class,
                () -> ToolExchangeProtocol.validateHistory(messages));
    }

    /** 数量不一致:两个调用只回一个结果,拒绝 */
    @Test
    void mismatchedCountIsRejected() {
        List<Message> messages = List.of(
                Message.userText("hi"),
                Message.assistant(List.of(use("t1"), use("t2"))),
                Message.userBlocks(List.of(result("t1", "one"))));

        assertThrows(IllegalArgumentException.class,
                () -> ToolExchangeProtocol.validateHistory(messages));
    }

    /** 错误角色:assistant 带 tool_result、user 带 tool_use 都拒绝 */
    @Test
    void wrongRoleToolBlocksAreRejected() {
        List<Message> resultInAssistant = List.of(
                Message.userText("hi"),
                Message.assistant(List.of(result("t1", "nope"))));
        assertThrows(IllegalArgumentException.class,
                () -> ToolExchangeProtocol.validateHistory(resultInAssistant));

        List<Message> useInUser = List.of(
                Message.userText("hi"),
                Message.userBlocks(List.of(use("t1"))));
        assertThrows(IllegalArgumentException.class,
                () -> ToolExchangeProtocol.validateHistory(useInUser));
    }

    /** 历史里的重复调用 ID:即使结果同 ID 同数量,也拒绝并报出重复 ID */
    @Test
    void duplicateCallIdsInHistoryAreRejected() {
        List<Message> messages = List.of(
                Message.userText("hi"),
                Message.assistant(List.of(use("t1"), use("t1"))),
                Message.userBlocks(List.of(result("t1", "one"), result("t1", "two"))));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ToolExchangeProtocol.validateHistory(messages));
        assertTrue(e.getMessage().contains("t1"));
    }

    /** 批次内重复 ID 的明确失败:信息里带重复 ID;无重复时放行 */
    @Test
    void requireDistinctCallIdsRejectsDuplicate() {
        assertDoesNotThrow(() -> ToolExchangeProtocol.requireDistinctCallIds(List.of(use("t1"), use("t2"))));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ToolExchangeProtocol.requireDistinctCallIds(List.of(use("t1"), use("t2"), use("t1"))));
        assertTrue(e.getMessage().contains("t1"));
    }
}
