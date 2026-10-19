package com.jadecode.session.history;

import java.util.List;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jadecode.messages.Message;

/**
 * 一次已经发生的历史变化，不是可以重新执行的 SessionEvent。
 * 不包含 ExecutionContext、审批函数、线程、future 或模型客户端。
 * messages 的含义由 kind 决定；todos 是当时真正生效的计划，不是工具调用建议。
 */
public record JournalEntry(int version, String sessionId, long sequence, String time, Kind kind,
        List<Message> messages, ArrayNode todos, String reason) {
    public enum Kind { APPEND, REPLACE_LAST, REPLACE_HISTORY, TURN_STARTED, TURN_FINISHED, TURN_FAILED }

    /** 检查日志操作本身的形状；历史中的工具配对在全部重放后检查。 */
    public JournalEntry {
        SessionMetadata.requireId(sessionId);
        if (version != SessionMetadata.VERSION || sequence < 1 || kind == null || todos == null || reason == null)
            throw new IllegalArgumentException("会话日志字段无效");
        java.time.Instant.parse(time);
        messages = List.copyOf(messages);
        todos = todos.deepCopy();
        if ((kind == Kind.APPEND || kind == Kind.REPLACE_LAST) && messages.size() != 1)
            throw new IllegalArgumentException("单条历史操作必须只有一条消息");
        if (kind.name().startsWith("TURN_") && !messages.isEmpty())
            throw new IllegalArgumentException("轮次记录不能夹带消息");
        for (Message message : messages) {
            if (message.role() == null || message.content() == null || message.content().stream().anyMatch(java.util.Objects::isNull))
                throw new IllegalArgumentException("历史消息内容无效");
            for (var block : message.content()) {
                if (block instanceof com.jadecode.messages.TextBlock text && text.text() == null
                        || block instanceof com.jadecode.messages.ToolResultBlock result && result.content() == null
                        || block instanceof com.jadecode.messages.ToolUseBlock use && (use.input() == null || use.name() == null || use.name().isBlank()))
                    throw new IllegalArgumentException("历史内容块缺少正文或工具参数");
            }
        }
    }

    /** 不把可修改的计划数组交给调用者，避免 UI 或恢复代码改动原始记录。 */
    @Override public ArrayNode todos() { return todos.deepCopy(); }
}
