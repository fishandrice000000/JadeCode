package com.jadecode.session.history;

import java.util.List;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jadecode.messages.Message;

/**
 * 加载结果：history 给下一次模型请求，entries 给历史界面，两者不能混用。
 * validBytes 是完整日志前缀长度；hasPartialTail 提醒存储层先备份尾部再续写。
 * 此对象不启动任务，warnings 必须展示给用户，不能假装恢复了旧后台进程。
 */
public record RestoredSession(SessionMetadata metadata, List<Message> history, ArrayNode todos,
        List<JournalEntry> entries, List<String> warnings, long sequence, long validBytes,
        boolean hasPartialTail, boolean repairedHistory) {
    public RestoredSession {
        history = List.copyOf(history); entries = List.copyOf(entries);
        warnings = List.copyOf(warnings); todos = todos.deepCopy();
    }
    @Override public ArrayNode todos() { return todos.deepCopy(); }
}
