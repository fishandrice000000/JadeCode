package com.jadecode.cli.tui;

import java.util.List;
import com.jadecode.cli.tui.model.TranscriptEntry;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ThinkingBlock;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.session.history.JournalEntry;
import com.jadecode.session.history.RestoredSession;

/**
 * 把已保存消息投影为显示转录：保留压缩前正文、真实警告和工具状态，不重跑工具/模型。
 *
 * <p>缺少原耗时/成功信息时标 HISTORICAL，不从错误字符串猜业务结果；工具调用有 tool_use
 * 无 result 时标 INTERRUPTED/NOT_RUN，而不是补假成功。用户消息进入编辑器输入历史（只取
 * user 文本），系统/工具/压缩摘要不作为用户输入。压缩只追加说明，不删除已显示正文。</p>
 */
public final class HistoryViewMapper {
    private HistoryViewMapper() { }

    /** 在唯一 UI 线程重建显示；保留草稿/主题等偏好，清空旧运行状态。 */
    public static void restore(TuiState state, RestoredSession restored) {
        state.transcript.reset(state.generation);
        state.actors.put("leader", "IDLE");
        List<TranscriptEntry> beforeTurn = List.of();
        java.util.Map<String, List<TranscriptEntry>> beforeTools = java.util.Map.of();
        TranscriptEntry lastUser = null;
        Message lastModel = null;
        try {
            for (JournalEntry entry : restored.entries()) {
                switch (entry.kind()) {
                    case TURN_STARTED -> {
                        beforeTurn = state.transcript.entries();
                        beforeTools = snapshotTools(state);
                    }
                    case TURN_FINISHED, TURN_FAILED, USAGE, RENAMED, THINKING -> { }
                    case APPEND -> {
                        lastModel = entry.messages().getFirst();
                        lastUser = message(state, lastModel, null);
                    }
                    case REPLACE_LAST -> {
                        Message replacement = entry.messages().getFirst();
                        if (lastUser == null && entry.reason().equals("notification")) {
                            String previous = lastModel == null ? "" : lastModel.extractText();
                            String text = replacement.extractText();
                            String added = text.startsWith(previous) ? text.substring(previous.length()).strip() : text;
                            if (!added.isBlank()) state.transcript.addNotice("System", "Incoming", added, false);
                            message(state, Message.userBlocks(new java.util.ArrayList<>(
                                    replacement.blocksOfType(ToolResultBlock.class))), null);
                        } else lastUser = message(state, replacement, lastUser);
                        lastModel = replacement;
                    }
                    case REPLACE_HISTORY -> {
                        lastModel = entry.messages().isEmpty() ? null : entry.messages().getLast();
                        if (entry.reason().equals("rollback")) {
                            // cron 回滚：撤销该轮候选显示，不产生新的模型消息。
                            state.transcript.replaceEntries(beforeTurn, beforeTools);
                            lastUser = null;
                        } else if (entry.reason().equals("recovery")) {
                            if (!entry.messages().isEmpty()) message(state, entry.messages().getLast(), lastUser);
                        } else {
                            state.transcript.addNotice("System", "Context",
                                    "Model history was compacted; earlier display is preserved.", false);
                            lastUser = null;
                        }
                    }
                }
            }
            if (restored.repairedHistory() && !restored.history().isEmpty())
                message(state, restored.history().getLast(), lastUser);
        } catch (RuntimeException failure) {
            // 坏日志不应让整个恢复失败；保留已经投影的部分并报告真实问题。
            state.transcript.addNotice("System", "Warning", "History display is partially available: "
                    + failure.getMessage(), true);
        }
        for (String warning : restored.warnings())
            state.transcript.addNotice("System", "Warning", warning, true);
        // 用户输入历史只取真实 USER 文本；不把系统/工具/摘要当输入。
        for (var entry : state.transcript.entries())
            if (entry.kind() == TranscriptEntry.Kind.USER) state.editor.remember(entry.body());
    }

    /** 根据内容块还原正文/思考/工具卡；USER 合并只替换上一张文字卡。 */
    private static TranscriptEntry message(TuiState state, Message message, TranscriptEntry replaceUser) {
        TranscriptEntry user = null;
        if (message.role() == Role.USER) {
            String text = message.extractText();
            if (!text.isBlank()) {
                if (replaceUser == null) user = state.transcript.addUser(text);
                else { replaceUser.replace(text); user = replaceUser; }
            }
        }
        for (ContentBlock block : message.content()) {
            if (block instanceof ToolUseBlock use) {
                state.transcript.addHistoricalTool("leader", use.id(), use.name(),
                        use.input() == null ? "" : use.input().toString(), "", false, false);
            } else if (block instanceof ToolResultBlock result) {
                state.transcript.completeHistoricalTool("leader", result.toolUseId(),
                        result.content(), result.isError());
            } else if (message.role() == Role.ASSISTANT && block instanceof TextBlock text && !text.text().isBlank()) {
                state.transcript.addHistorical("leader", TranscriptEntry.Kind.REPLY, "Reply", text.text(), "HISTORICAL");
            } else if (message.role() == Role.ASSISTANT && block instanceof ThinkingBlock thinking) {
                state.transcript.addHistorical("leader", TranscriptEntry.Kind.THINKING, "Thinking",
                        thinking.thinking(), "HISTORICAL");
            }
        }
        return user;
    }

    private static java.util.Map<String, List<TranscriptEntry>> snapshotTools(TuiState state) {
        var map = new java.util.LinkedHashMap<String, List<TranscriptEntry>>();
        for (var tool : state.transcript.tools())
            map.put(tool.key(), List.of());
        return map;
    }
}
