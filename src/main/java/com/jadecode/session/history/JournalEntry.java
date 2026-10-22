package com.jadecode.session.history;

import java.util.List;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jadecode.messages.Message;
import com.jadecode.llm.ThinkingSettings;
import com.jadecode.llm.UsageRecord;

/**
 * 一次已经发生的历史变化，不是可以重新执行的 SessionEvent。
 * 不包含 ExecutionContext、审批函数、线程、future 或模型客户端。
 * messages 的含义由 kind 决定；todos 是当时真正生效的计划，不是工具调用建议。
 */
public record JournalEntry(int version, String sessionId, long sequence, String time, Kind kind,
        List<Message> messages, ArrayNode todos, String reason, UsageRecord usage, String title, ThinkingSettings thinking) {
    public enum Kind { APPEND, REPLACE_LAST, REPLACE_HISTORY, TURN_STARTED, TURN_FINISHED, TURN_FAILED, USAGE, RENAMED, THINKING }

    /** 原有历史事件没有额外元数据。版本 1 JSON 的新增可空字段也会按 null 读取。 */
    public JournalEntry(int version, String sessionId, long sequence, String time, Kind kind,
            List<Message> messages, ArrayNode todos, String reason) {
        this(version, sessionId, sequence, time, kind, messages, todos, reason, null, null, null);
    }

    /** 元数据事件不会覆盖模型历史和 Todo，更不会在恢复时成为 USER 消息。 */
    public boolean metadataOnly() { return kind == Kind.USAGE || kind == Kind.RENAMED || kind == Kind.THINKING; }

    /** 检查日志操作本身的形状；历史中的工具配对在全部重放后检查。 */
    public JournalEntry {
        SessionMetadata.requireId(sessionId);
        if (version < 1 || version > SessionMetadata.VERSION || sequence < 1 || kind == null || reason == null)
            throw new IllegalArgumentException("Invalid session journal fields");
        boolean metadataOnly = kind == Kind.USAGE || kind == Kind.RENAMED || kind == Kind.THINKING;
        if (metadataOnly && version < 2) throw new IllegalArgumentException("Version 1 does not contain metadata events");
        if (!metadataOnly && todos == null || metadataOnly && todos != null)
            throw new IllegalArgumentException("History events require Todo; metadata events cannot carry Todo");
        if ((kind == Kind.USAGE) != (usage != null) || (kind == Kind.RENAMED) != (title != null)
                || (kind == Kind.THINKING) != (thinking != null)) throw new IllegalArgumentException("Metadata event body does not match");
        if (title != null) title = SessionMetadata.validTitle(title);
        java.time.Instant.parse(time);
        messages = List.copyOf(messages);
        if (todos != null) todos = todos.deepCopy();
        if (metadataOnly && !messages.isEmpty()) throw new IllegalArgumentException("Metadata events cannot carry model messages");
        if ((kind == Kind.APPEND || kind == Kind.REPLACE_LAST) && messages.size() != 1)
            throw new IllegalArgumentException("A single history operation must contain exactly one message");
        if (kind.name().startsWith("TURN_") && !messages.isEmpty())
            throw new IllegalArgumentException("Turn records cannot carry messages");
        for (Message message : messages) {
            if (message.role() == null || message.content() == null || message.content().stream().anyMatch(java.util.Objects::isNull))
                throw new IllegalArgumentException("Invalid history message content");
            for (var block : message.content()) {
                if (block instanceof com.jadecode.messages.TextBlock text && text.text() == null
                        || block instanceof com.jadecode.messages.ToolResultBlock result && result.content() == null
                        || block instanceof com.jadecode.messages.ToolUseBlock use && (use.input() == null || use.name() == null || use.name().isBlank()))
                    throw new IllegalArgumentException("History content block is missing text or tool arguments");
            }
        }
    }

    /** 不把可修改的计划数组交给调用者，避免 UI 或恢复代码改动原始记录。 */
    @Override public ArrayNode todos() { return todos == null ? null : todos.deepCopy(); }
}
