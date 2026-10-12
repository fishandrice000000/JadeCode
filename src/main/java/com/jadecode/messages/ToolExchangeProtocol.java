package com.jadecode.messages;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ToolExchangeProtocol -- 工具调用交换的共享协议规则:配对判定与历史校验。
 *
 * <p>
 * 本类是工具交换规则的<b>唯一实现位置</b>:
 * </p>
 * <ul>
 * <li>工具 ID 的构造前置校验({@link #requireValidToolId});</li>
 * <li>配对判定({@link #hasToolUse} / {@link #isToolResult} /
 * {@link #isClosedToolExchange});</li>
 * <li>批次内调用 ID 唯一性({@link #requireDistinctCallIds});</li>
 * <li>既有历史的结构校验({@link #validateHistory})。</li>
 * </ul>
 *
 * <p>
 * 压缩管线({@code ToolExchangeBoundary})与父、子循环只<b>委托/调用</b>这里,
 * 不各写一份 ID 或配对算法。本类<b>无状态</b>,全部是 static helper,不需要构造实例。
 * </p>
 */
public final class ToolExchangeProtocol {

    /** 纯静态工具类,禁止实例化 */
    private ToolExchangeProtocol() {
    }

    /**
     * 工具 ID 的构造前置校验:null / 空白一律拒绝。
     *
     * <p>
     * {@link ToolUseBlock} 与 {@link ToolResultBlock} 的紧凑构造器共用本方法,
     * 让"ID 必须有效"成为消息进入系统的构造级不变量,配对方法不再需要重复防御。
     * </p>
     *
     * @param id    工具调用 ID
     * @param field 出错信息里的字段名(如 {@code tool_use id})
     * @throws IllegalArgumentException id 为 null 或空白
     */
    static void requireValidToolId(String id, String field) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为 null 或空白");
        }
    }

    /**
     * 是否为"含至少一个 tool_use 的 assistant 消息"。
     *
     * @param message 一条消息
     * @return true = 含 tool_use 的 assistant 消息
     */
    public static boolean hasToolUse(Message message) {
        if (message.role() != Role.ASSISTANT) {
            return false;
        }

        for (ContentBlock c : message.content()) {
            if (c instanceof ToolUseBlock) {
                return true;
            }
        }

        return false;
    }

    /**
     * 是否为"含至少一个 tool_result 的 user 消息"。
     *
     * @param message 一条消息
     * @return true = 含 tool_result 的 user 消息
     */
    public static boolean isToolResult(Message message) {
        if (message.role() != Role.USER) {
            return false;
        }

        for (ContentBlock c : message.content()) {
            if (c instanceof ToolResultBlock) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断 {@code messages[assistantIndex]} 与其后一条消息是否构成一个<b>闭合的工具交换</b>。
     *
     * <p>
     * 判定条件见作业单 §工具交换不变式。要点:assistant 消息含一个或多个 tool_use(可同时含 text);
     * 紧随其后的 user 消息对每个 tool use 都有且只有一个同 ID 的 ToolResultBlock,顺序一致;
     * 结果消息允许同时带 reminder 等非 tool-result 的 text 块;缺失、重复、错序、错 ID、
     * 孤立 result 一律不算闭合。
     * </p>
     *
     * @param messages       消息列表
     * @param assistantIndex 候选 assistant 消息下标
     * @return true = 闭合的工具交换
     */
    public static boolean isClosedToolExchange(List<Message> messages, int assistantIndex) {
        // 边界条件 1: assistantIndex 不合法
        if (assistantIndex < 0 || assistantIndex > messages.size() - 1) {
            return false;
        }
        // 边界条件 2: assistantIndex 为最后一条消息
        if (assistantIndex == messages.size() - 1) {
            return false;
        }

        Message assistantMessage = messages.get(assistantIndex);
        Message userMessage = messages.get(assistantIndex + 1);

        // 边界条件 3: assistantIndex 没有工具调用
        if (!hasToolUse(assistantMessage)) {
            return false;
        }
        // 边界条件 4: assistantIndex + 1 没有工具结果
        if (!isToolResult(userMessage)) {
            return false;
        }

        List<ToolUseBlock> calls = assistantMessage.blocksOfType(ToolUseBlock.class);
        List<ToolResultBlock> results = userMessage.blocksOfType(ToolResultBlock.class);

        // 边界条件 5: 调用和结果数不一致
        if (calls.size() != results.size()) {
            return false;
        }
        // 边界条件 6: 批次内存在重复调用 ID
        if (firstDuplicateCallId(calls) != null) {
            return false;
        }

        for (int i = 0; i < calls.size(); i++) {
            // 边界条件 7: 错序或者错 id
            if (!calls.get(i).id().equals(results.get(i).toolUseId())) {
                return false;
            }
        }

        return true;
    }

    /**
     * 拒绝同一批次(一条 assistant 消息)内的重复 tool_use ID。
     *
     * <p>
     * 父、子循环在追加 assistant 消息与执行任何工具<b>之前</b>调用本方法:
     * 重复 ID 会让"每个调用恰好一个结果"的配对契约无法成立,必须明确失败,
     * 不能留下半截 assistant 消息,也不能产生工具副作用。
     * </p>
     *
     * @param calls 本次模型回复里的全部 tool_use 块
     * @throws IllegalArgumentException 存在重复 ID(信息里带重复的那个 ID)
     */
    public static void requireDistinctCallIds(List<ToolUseBlock> calls) {
        String duplicateId = firstDuplicateCallId(calls);
        if (duplicateId != null) {
            throw new IllegalArgumentException("模型回复包含重复的 tool_use id: " + duplicateId);
        }
    }

    /**
     * 校验一份"既有历史"可以直接发给模型。
     *
     * <p>
     * 规则:工具调用/结果必须相邻(assistant tool_use 紧跟一条 user 结果消息)、角色正确
     * (tool_use 只能在 assistant,tool_result 只能在 user)、数量/顺序/ID 一一对应且调用 ID
     * 不重复;孤立、缺失、错序或错误角色的工具块都会被明确拒绝。普通文本消息(包括压缩管线
     * 写出的 archive marker 这类 user 文本)不参与配对检查,始终允许。
     * </p>
     *
     * @param messages 待校验的完整历史
     * @throws IllegalArgumentException 历史中存在畸形的工具交换
     */
    public static void validateHistory(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            List<ToolUseBlock> uses = message.blocksOfType(ToolUseBlock.class);
            List<ToolResultBlock> results = message.blocksOfType(ToolResultBlock.class);

            if (message.role() == Role.ASSISTANT) {
                // assistant 只能发起调用,不能携带结果
                if (!results.isEmpty()) {
                    throw new IllegalArgumentException(
                            "历史非法:第 " + i + " 条 assistant 消息不得包含 tool_result");
                }
                if (uses.isEmpty()) {
                    continue; // 普通 assistant 文本
                }

                requireDistinctCallIds(uses);
                if (i + 1 >= messages.size()) {
                    throw new IllegalArgumentException(
                            "历史非法:第 " + i + " 条 assistant 消息的 tool_use 缺少结果消息");
                }

                Message resultMessage = messages.get(i + 1);
                if (resultMessage.role() != Role.USER) {
                    throw new IllegalArgumentException(
                            "历史非法:第 " + i + " 条 assistant 消息的 tool_use 后必须紧跟 user 结果消息");
                }
                if (!resultMessage.blocksOfType(ToolUseBlock.class).isEmpty()) {
                    throw new IllegalArgumentException(
                            "历史非法:第 " + (i + 1) + " 条 user 消息不得包含 tool_use");
                }

                List<ToolResultBlock> resultBlocks = resultMessage.blocksOfType(ToolResultBlock.class);
                if (resultBlocks.size() != uses.size()) {
                    throw new IllegalArgumentException(
                            "历史非法:第 " + i + " 条 assistant 的 tool_use 数量与第 " + (i + 1)
                                    + " 条 user 的 tool_result 数量不一致");
                }
                for (int k = 0; k < uses.size(); k++) {
                    if (!uses.get(k).id().equals(resultBlocks.get(k).toolUseId())) {
                        throw new IllegalArgumentException(
                                "历史非法:第 " + i + " 条 assistant 的 tool_use id " + uses.get(k).id()
                                        + " 与第 " + (i + 1) + " 条 user 的第 " + k + " 个 tool_result id "
                                        + resultBlocks.get(k).toolUseId() + " 不匹配");
                    }
                }
                i++; // 配对的 user 结果消息已消费,跳过
            } else {
                // user 只能回灌结果,不能发起调用
                if (!uses.isEmpty()) {
                    throw new IllegalArgumentException(
                            "历史非法:第 " + i + " 条 user 消息不得包含 tool_use");
                }
                // 走到这里说明它不是任何 assistant tool_use 的配对消息
                if (!results.isEmpty()) {
                    throw new IllegalArgumentException(
                            "历史非法:第 " + i + " 条 user 消息包含孤立的 tool_result");
                }
            }
        }
    }

    /**
     * 找出第一个重复的调用 ID。
     *
     * @param calls tool_use 块列表
     * @return 重复出现的 ID;无重复时返回 null
     */
    private static String firstDuplicateCallId(List<ToolUseBlock> calls) {
        Set<String> seen = new HashSet<>();
        for (ToolUseBlock call : calls) {
            if (!seen.add(call.id())) {
                return call.id();
            }
        }
        return null;
    }
}
