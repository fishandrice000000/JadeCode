package com.jadecode.context;

import java.util.List;

import com.jadecode.messages.Message;
import com.jadecode.messages.ToolExchangeProtocol;

/**
 * ToolExchangeBoundary -- 工具交换的配对判定与安全切点(context 包内入口)。
 *
 * <p>
 * snip / 响应式 / 手动压缩三处切点判断的<b>唯一入口</b>。配对规则本体已集中到
 * {@link ToolExchangeProtocol}(messages 包);本类的三个判定方法只作<b>等价委托</b>,
 * 不复制算法,切点语义不变。
 * </p>
 *
 * <p>
 * 本类<b>无状态</b>,全部是 static helper,不需要构造实例。
 * </p>
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
     * 判断 {@code messages[assistantIndex]} 与其后一条消息是否构成一个<b>闭合的工具交换</b>。
     *
     * <p>
     * 判定条件见作业单 §工具交换不变式;实现与契约在
     * {@link ToolExchangeProtocol#isClosedToolExchange(List, int)}。
     * </p>
     *
     * @param messages       消息列表
     * @param assistantIndex 候选 assistant 消息下标
     * @return true = 闭合的工具交换
     */
    static boolean isClosedToolExchange(List<Message> messages, int assistantIndex) {
        return ToolExchangeProtocol.isClosedToolExchange(messages, assistantIndex);
    }

    /**
     * 把尾部起点往前挪,避免切点落在闭合工具交换的两条消息之间(那会制造孤立 tool_use)。
     *
     * <p>
     * TODO(s08-user): 实现本助手,并让 snip / 响应式两处都用它。
     * </p>
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
     * 把头部末端往后挪,避免头部在闭合工具交换的中间被切断(那会制造孤立 tool_result)。
     *
     * <p>
     * TODO(s08-user): 实现本助手,并让 snip 层用它。
     * </p>
     *
     * @param messages 消息列表
     * @param headEnd  候选头部末端(不含)
     * @return 修正后的头部末端
     */
    static int safeHeadEnd(List<Message> messages, int headEnd) {
        if (isClosedToolExchange(messages, headEnd - 1)) {
            return headEnd + 1;
        }
        return headEnd;
    }
}
