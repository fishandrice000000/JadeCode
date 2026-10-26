package com.jadecode.cli.backend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jadecode.execution.ToolIdentity;
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
 * 把已保存的会话日志投影成“可显示的纯资料”，交给 Node 重建转录。
 *
 * <p>它替代旧 {@code HistoryViewMapper}，但不再依赖 {@code TuiState}/Editor/组件：输入是
 * Journal entries，输出是稳定的 {@link Entry} 列表。语义必须保留：</p>
 *
 * <ul>
 *   <li>压缩只追加说明，不删除已经显示过的正文（界面历史 ≠ 模型 history）；</li>
 *   <li>{@code rollback} 撤销该轮候选显示；{@code recovery} 追加修复后的最后一条；</li>
 *   <li>有 tool_use 没有 tool_result 时标 NOT_RUN/INTERRUPTED，不补假成功；</li>
 *   <li>没有真实耗时/成功资料时保持 HISTORICAL，不补 0 或 DONE；</li>
 *   <li>用户输入历史只来自真实 USER 文本，系统/工具/摘要不算用户输入。</li>
 * </ul>
 *
 * <p>日志损坏不应让整个恢复失败：投影异常时保留已投影部分并追加 warning 条目。</p>
 */
public final class BackendHistoryProjector {
    /** 工具可信身份的可传输形式；未知来源为 null。 */
    public record ToolInfo(String kind, String serverName, String originalName, boolean hostReadOnly) {
        static ToolInfo of(ToolIdentity identity) {
            return identity == null ? null : new ToolInfo(identity.kind().name(), identity.serverName(),
                    identity.originalName(), identity.hostReadOnly());
        }
    }

    /**
     * 一条显示条目。
     *
     * @param entryId    稳定身份（同一次投影内不变），Node 按它做增量卡片更新
     * @param kind       USER / REPLY / THINKING / TOOL / NOTICE
     * @param body       正文或工具结果
     * @param arguments  工具参数（仅 TOOL）
     * @param status     真实状态；HISTORICAL 不等于成功
     * @param requestId  流式请求 ID（仅活动回复）
     * @param callId     工具调用 ID（仅 TOOL）
     */
    public record Entry(String entryId, String kind, String actor, String title, String body,
            String arguments, String status, String requestId, String callId, Integer blockIndex,
            Long elapsedMs, boolean historical, ToolInfo tool) {
        /** 生成一个不可变的字段替换副本，避免在投影过程中共享可变对象。 */
        Entry withStatus(String next) {
            return new Entry(entryId, kind, actor, title, body, arguments, next, requestId, callId,
                    blockIndex, elapsedMs, historical, tool);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Integer> toolIndex = new HashMap<>();
    private int sequence;

    /**
     * 执行投影。
     *
     * @param restored 已加载的会话；只读，不会被修改
     * @return 可显示条目列表
     */
    public static List<Entry> project(RestoredSession restored) {
        BackendHistoryProjector projector = new BackendHistoryProjector();
        projector.run(restored);
        return List.copyOf(projector.entries);
    }

    private void run(RestoredSession restored) {
        int turnMark = 0;
        Message lastModel = null;
        Integer lastUser = null;
        try {
            for (JournalEntry entry : restored.entries()) {
                switch (entry.kind()) {
                    case TURN_STARTED -> turnMark = entries.size();
                    case TURN_FINISHED, TURN_FAILED, USAGE, RENAMED, THINKING -> { }
                    case GOAL -> notice("System", "Goal", entry.goal().status() + ": " + entry.goal().condition()
                            + "\n" + entry.goal().reason(), entry.goal().status() == com.jadecode.goal.GoalState.Status.IMPOSSIBLE);
                    case APPEND -> {
                        lastModel = entry.messages().getFirst();
                        // 自动继续虽使用 USER wire role，来源仍是宿主，不能污染用户输入历史。
                        if (entry.reason().equals("goal_continue")) {
                            notice("System", "Goal", lastModel.extractText(), false); lastUser = null;
                        } else if (entry.reason().equals("notification")) {
                            // 参考消息保持独立后，首条通知可能是 APPEND；按宿主通知显示。
                            notice("System", "Incoming", lastModel.extractText(), false);
                        } else lastUser = message(lastModel, lastUser);
                    }
                    case REPLACE_LAST -> {
                        Message replacement = entry.messages().getFirst();
                        if ("notification".equals(entry.reason())) {
                            String previous = lastModel == null ? "" : lastModel.extractText();
                            String text = replacement.extractText();
                            String added = text.startsWith(previous) ? text.substring(previous.length()).strip() : text;
                            if (!added.isBlank()) notice("System", "Incoming", added, false);
                        }
                        // 通知是注入模型的系统资料，不是用户新输入；不能污染输入历史。
                        else lastUser = message(replacement, lastUser);
                        lastModel = replacement;
                    }
                    case REPLACE_HISTORY -> {
                        lastModel = entry.messages().isEmpty() ? null : entry.messages().getLast();
                        switch (entry.reason()) {
                            case "rollback" -> {
                                // 定时任务回滚：撤销该轮候选显示，不新增模型消息。
                                truncateTo(turnMark);
                                lastUser = null;
                            }
                            case "recovery" -> {
                                if (!entry.messages().isEmpty()) lastUser = message(entry.messages().getLast(), lastUser);
                            }
                            default -> {
                                notice("System", "Context",
                                        "Model history was compacted; earlier display is preserved.", false);
                                lastUser = null;
                            }
                        }
                    }
                }
            }
            if (restored.repairedHistory() && !restored.history().isEmpty())
                message(restored.history().getLast(), lastUser);
        } catch (RuntimeException failure) {
            notice("System", "Warning", "History display is partially available: " + failure.getMessage(), true);
        }
        for (String warning : restored.warnings()) notice("System", "Warning", warning, true);
    }

    /** 回滚到本轮开始前的显示长度，并丢弃已经失效的工具索引。 */
    private void truncateTo(int size) {
        while (entries.size() > size) entries.removeLast();
        toolIndex.clear();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.callId() != null) toolIndex.put(entry.callId(), i);
        }
    }

    /** 把一条模型消息展开成用户文字、回答、思考与工具卡；返回当前用户条目下标。 */
    private Integer message(Message message, Integer lastUser) {
        // 召回资料是模型上下文，恢复时不伪装成用户输入，也不进入输入历史。
        if (message.source() == Message.Source.MEMORY_REFERENCE) return null;
        Integer user = null;
        if (message.role() == Role.USER) {
            String text = message.extractText();
            if (!text.isBlank()) {
                if (lastUser == null || lastUser >= entries.size()) user = add("USER", "System", "Request", text, "HISTORICAL");
                else {
                    entries.set(lastUser, replaceBody(entries.get(lastUser), text));
                    user = lastUser;
                }
            }
        }
        for (ContentBlock block : message.content()) {
            if (block instanceof ToolUseBlock use) {
                addTool("leader", use.id(), use.name(), use.input() == null ? "" : use.input().toString());
            } else if (block instanceof ToolResultBlock result) {
                completeTool(result.toolUseId(), result.content(), result.isError());
            } else if (message.role() == Role.ASSISTANT && block instanceof TextBlock text && !text.text().isBlank()) {
                add("REPLY", "leader", "Reply", text.text(), "HISTORICAL");
            } else if (message.role() == Role.ASSISTANT && block instanceof ThinkingBlock thinking) {
                add("THINKING", "leader", "Thinking", thinking.thinking(), "HISTORICAL");
            }
        }
        return user;
    }

    private Entry replaceBody(Entry entry, String body) {
        return new Entry(entry.entryId(), entry.kind(), entry.actor(), entry.title(), body, entry.arguments(),
                entry.status(), entry.requestId(), entry.callId(), entry.blockIndex(), entry.elapsedMs(),
                entry.historical(), entry.tool());
    }

    private Integer add(String kind, String actor, String title, String body, String status) {
        Entry entry = new Entry("e" + (++sequence), kind, actor, title, body == null ? "" : body, "",
                status, null, null, null, null, true, null);
        entries.add(entry);
        return entries.size() - 1;
    }

    private void notice(String actor, String title, String body, boolean error) {
        add("NOTICE", actor, title, body, error ? "ERROR" : "HISTORICAL");
    }

    /** 历史工具卡：先建立参数与 HISTORICAL 状态，等真实 tool_result 再收尾。 */
    private void addTool(String actor, String callId, String name, String arguments) {
        Entry entry = new Entry("tool-" + (callId == null || callId.isBlank() ? "e" + (++sequence) : callId),
                "TOOL", actor, name == null ? "Tool" : name, "", arguments == null ? "" : arguments,
                "NOT_RUN", null, callId, null, null, true, null);
        entries.add(entry);
        if (callId != null) toolIndex.put(callId, entries.size() - 1);
    }

    private void completeTool(String callId, String result, boolean error) {
        Integer index = callId == null ? null : toolIndex.get(callId);
        if (index == null || index >= entries.size()) return;
        Entry current = entries.get(index);
        entries.set(index, new Entry(current.entryId(), current.kind(), current.actor(), current.title(),
                result == null ? "" : result, current.arguments(), error ? "ERROR" : "HISTORICAL",
                current.requestId(), current.callId(), current.blockIndex(), current.elapsedMs(),
                current.historical(), current.tool()));
    }
}
