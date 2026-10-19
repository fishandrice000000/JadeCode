package com.jadecode.cli.tui;

import java.util.List;
import java.util.Map;
import com.jadecode.messages.*;
import com.jadecode.session.history.JournalEntry;
import com.jadecode.session.history.RestoredSession;

/**
 * 把已保存消息画成历史卡片，不请求模型、不执行工具、不恢复任何正在运行的状态。
 * 压缩只增加提示，不删除已显示正文；失败的 cron 回滚则撤销该轮的候选显示。
 */
public final class HistoryViewMapper {
    private HistoryViewMapper() { }

    /**
     * 在唯一 UI 线程重建显示。保留主题、编辑器草稿等界面偏好，清理旧会话的运行和用量信息。
     * 缺少原执行耗时/状态时标记 HISTORICAL，不从 Error 字符串猜测工具业务结果。
     */
    public static void restore(TuiState state, RestoredSession restored) {
        state.entries.clear(); state.tools.clear(); state.streams.clear(); state.actors.clear(); state.background.clear();
        state.resetTurns();
        state.approvals.clear(); state.approvalChoice = -1; state.pending = ""; state.pendingKind = "";
        state.session = null;
        state.model = ""; state.modelInfo = ""; state.actors.put("leader", "IDLE");
        List<TuiState.Entry> beforeTurn = List.of();
        Map<String, TuiState.Entry> beforeTools = Map.of();
        TuiState.Entry lastUser = null;
        TuiState.Entry beforeUser = null;
        Message lastModel = null;
        for (JournalEntry entry : restored.entries()) {
            switch (entry.kind()) {
                case TURN_STARTED -> {
                    beforeTurn = List.copyOf(state.entries); beforeTools = Map.copyOf(state.tools); beforeUser = lastUser;
                }
                case TURN_FINISHED, TURN_FAILED, USAGE, RENAMED, THINKING -> { /* 元数据不画成模型对话。 */ }
                case APPEND -> {
                    lastModel = entry.messages().getFirst(); lastUser = message(state, lastModel, null);
                }
                case REPLACE_LAST -> {
                    Message replacement = entry.messages().getFirst();
                    if (lastUser == null && entry.reason().equals("notification")) {
                        // 压缩后的末条 USER 不一定对应某张旧显示卡。只显示新增通知，不能改写旧正文。
                        String previous = lastModel == null ? "" : lastModel.extractText();
                        String text = replacement.extractText();
                        String added = text.startsWith(previous) ? text.substring(previous.length()).strip() : text;
                        if (!added.isBlank()) state.add("System", "Notice", "Incoming", added);
                        message(state, Message.userBlocks(new java.util.ArrayList<>(replacement.blocksOfType(ToolResultBlock.class))), null);
                    } else lastUser = message(state, replacement, lastUser);
                    lastModel = replacement;
                }
                case REPLACE_HISTORY -> {
                    lastModel = entry.messages().isEmpty() ? null : entry.messages().getLast();
                    if (entry.reason().equals("rollback")) {
                        state.entries.clear(); state.entries.addAll(beforeTurn); state.tools.clear(); state.tools.putAll(beforeTools); lastUser = beforeUser;
                    } else if (entry.reason().equals("recovery")) {
                        // 恢复写回的结果只收尾末批卡片，不能再次显示整份原对话。
                        if (!entry.messages().isEmpty()) message(state, entry.messages().getLast(), lastUser);
                    } else {
                        state.add("System", "Notice", "Context", "模型历史已压缩；此前显示记录保留。");
                        lastUser = null;
                    }
                }
            }
        }
        if (restored.repairedHistory() && !restored.history().isEmpty()) message(state, restored.history().getLast(), lastUser);
        // 旧卡片仍在 entries，但不参与新调用 ID 的查找。后端可能复用旧 ID，不能改到历史卡。
        state.tools.clear();
        for (String warning : restored.warnings()) state.add("System", "Warning", "Resume", warning);
    }

    /** 根据内容块分别还原正文/思考和工具卡；USER 合并只替换上一张文字卡，不重复增加工具结果。 */
    private static TuiState.Entry message(TuiState state, Message message, TuiState.Entry replaceUser) {
        TuiState.Entry user = null;
        if (message.role() == Role.USER) {
            String text = message.extractText();
            if (!text.isBlank()) {
                user = replaceUser == null ? state.add("You", "User", "Request", text) : replaceUser;
                if (replaceUser != null) user.replace(text);
            }
        }
        for (ContentBlock block : message.content()) {
            if (block instanceof ToolUseBlock use) {
                var card = state.add("leader", "Tool", use.name(), "上次调用结果未记录。");
                card.arguments = use.input().toString(); card.status = "INTERRUPTED"; card.expanded = state.toolsExpanded;
                state.tools.put("leader/" + use.id(), card);
            } else if (block instanceof ToolResultBlock result) {
                var card = state.tools.get("leader/" + result.toolUseId());
                if (card != null) { card.replace(result.content()); card.status = "HISTORICAL"; }
            } else if (message.role() == Role.ASSISTANT && block instanceof TextBlock text && !text.text().isBlank()) {
                var card = state.add("leader", "Reply", "Reply", text.text()); card.status = "HISTORICAL";
            } else if (message.role() == Role.ASSISTANT && block instanceof ThinkingBlock thinking) {
                var card = state.add("leader", "Thinking", "Thinking", thinking.thinking()); card.status = "HISTORICAL";
            }
        }
        return user;
    }
}
