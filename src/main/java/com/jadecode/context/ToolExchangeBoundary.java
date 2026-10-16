package com.jadecode.context;

import java.util.List;

import com.jadecode.messages.Message;
import com.jadecode.messages.ToolExchangeProtocol;

/**
 * 判断“工具调用和它的结果是不是一对”，并给出安全的切分位置。
 *
 * <p>snip、响应式补救和手动压缩三处都要切分历史，切的时候不能把 assistant 的 tool_use 和
 * 紧随其后的 user 的 tool_result 拆开。判断规则本身在 {@link ToolExchangeProtocol}，
 * 本类只做转发，三个判定方法不另写一套。本类全是静态方法，不保存状态。
 */
final class ToolExchangeBoundary {

    /** 纯静态工具类,禁止实例化 */
    private ToolExchangeBoundary() {
    }

    /**
     * 是否为"含至少一个 tool_use 的 assistant 消息"。
     *
     * @param message 一条消息
     * @return true = 含 tool_use 的 assistant 消息
     */
    static boolean hasToolUse(Message message) {
        return ToolExchangeProtocol.hasToolUse(message);
    }

    /**
     * 是否为"含至少一个 tool_result 的 user 消息"。
     *
     * @param message 一条消息
     * @return true = 含 tool_result 的 user 消息
     */
    static boolean isToolResult(Message message) {
        return ToolExchangeProtocol.isToolResult(message);
    }

    /**
     * 判断 {@code messages[assistantIndex]} 和它后面那条消息是不是一对完整的工具调用与结果。
     * 具体规则在 {@link ToolExchangeProtocol#isClosedToolExchange(List, int)}。
     *
     * @param messages       消息列表
     * @param assistantIndex 候选 assistant 消息下标
     * @return true = 这一对是完整的
     */
    static boolean isClosedToolExchange(List<Message> messages, int assistantIndex) {
        return ToolExchangeProtocol.isClosedToolExchange(messages, assistantIndex);
    }

    /**
     * 把尾部起点往前挪：如果切点正好落在某一对工具调用和结果的中间，
     * 就把起点挪到调用那一条，把整对拉进尾部，避免出现只有 tool_use 没有结果的半截历史。
     *
     * @param messages  消息列表
     * @param tailStart 候选尾部起点
     * @return 修正后的尾部起点
     */
    static int safeTailStart(List<Message> messages, int tailStart) {
        if (isClosedToolExchange(messages, tailStart - 1)) {
            return tailStart - 1;
        }
        return tailStart;
    }

    /**
     * 把头部末端往后挪：如果切点正好落在某一对的中间，就把末端推到结果那一条，
     * 避免出现只有 tool_result 没有调用的半截历史。
     *
     * @param messages 消息列表
     * @param headEnd  候选头部末端（不含这一条）
     * @return 修正后的头部末端
     */
    static int safeHeadEnd(List<Message> messages, int headEnd) {
        if (isClosedToolExchange(messages, headEnd - 1)) {
            return headEnd + 1;
        }
        return headEnd;
    }
}
