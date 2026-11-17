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
 * 按持久事件顺序将恢复结果转换为界面历史条目。正文、工具调用与结果生成对应卡片，通知生成提示；压缩保留已显示内容，
 * 回滚裁剪本轮条目，恢复补齐最后消息。缺失结果保留未执行或中断状态，
 * 局部投影错误作为 warning 条目附在已生成内容后。
 */
public final class BackendHistoryProjector {
    /**
     * 工具可信身份的可传输形式；未知来源为 null。
     *
     * @param kind 宿主工具身份种类 BUILTIN 或 MCP
     * @param serverName MCP 服务的原始名称
     * @param originalName MCP 工具在原服务中的名称
     * @param hostReadOnly 宿主是否把该工具声明为只读能力
     */
    public record ToolInfo(String kind, String serverName, String originalName, boolean hostReadOnly) {
        /**
         * 将宿主工具身份转换为界面使用的类型、MCP 服务名、原工具名和只读标记；身份为空时返回 null。
         *
         * @param identity 宿主工具目录提供的可信工具身份
         * @return 从宿主身份转换的界面工具资料；原身份为 null 时为 null
         */
        static ToolInfo of(ToolIdentity identity) {
            return identity == null ? null
                    : new ToolInfo(identity.kind().name(), identity.serverName(),
                            identity.originalName(), identity.hostReadOnly());
        }
    }

    /**
     * 一条显示条目。
     *
     * @param entryId 稳定身份（同一次投影内不变），Node 按它做增量卡片更新
     * @param kind USER / REPLY / THINKING / TOOL / NOTICE
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param title 用户选择的会话标题或当前界面条目的标题
     * @param body 正文或工具结果
     * @param arguments 工具参数（仅 TOOL）
     * @param status 真实状态；HISTORICAL 不等于成功
     * @param requestId 流式请求 ID（仅活动回复）
     * @param callId 工具调用 ID（仅 TOOL）
     * @param blockIndex 工具调用在模型内容块中的下标，用于把参数片段追加到同一个预览块
     * @param elapsedMs 界面显示的耗时，单位毫秒
     * @param historical 条目是否来自持久历史恢复
     * @param tool 本条目已固定的宿主工具身份资料
     */
    public record Entry(String entryId, String kind, String actor, String title, String body,
            String arguments, String status, String requestId, String callId, Integer blockIndex,
            Long elapsedMs, boolean historical, ToolInfo tool) {
        /**
         * 生成一个不可变的字段替换副本，避免在投影过程中共享可变对象。
         *
         * @param next 此历史条目需要替换为的新状态
         * @return 沿用原身份、正文和耗时，仅更新 status 的新条目
         */
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

    /**
     * 按持久日志顺序重建历史显示：追加和替换消息生成正文或工具条目，通知与 Goal 生成提示，回滚裁剪本轮条目，
     * 压缩保留已显示内容并补充提示。最后采用恢复补齐消息和警告；局部投影异常转成历史显示告警。
     *
     * @param restored 由持久日志恢复的历史、元数据、Todo 和告警
     */
    private void run(RestoredSession restored) {
        int turnMark = 0;
        Message lastModel = null;
        Integer lastUser = null;
        try {
            for (JournalEntry entry : restored.entries()) {
                switch (entry.kind()) {
                    case TURN_STARTED -> turnMark = entries.size();
                    case TURN_FINISHED, TURN_FAILED, USAGE, RENAMED, THINKING -> {
                    }
                    case GOAL -> notice("System", "Goal", entry.goal().status() + ": " + entry.goal().condition()
                            + "\n" + entry.goal().reason(),
                            entry.goal().status() == com.jadecode.goal.GoalState.Status.IMPOSSIBLE);
                    case APPEND -> {
                        lastModel = entry.messages().getFirst();
                        // 自动继续虽使用 USER wire role，来源仍是宿主，不能污染用户输入历史。
                        if (entry.reason().equals("goal_continue")) {
                            notice("System", "Goal", lastModel.extractText(), false);
                            lastUser = null;
                        } else if (entry.reason().equals("notification")) {
                            // 参考消息保持独立后，首条通知可能是 APPEND；按宿主通知显示。
                            notice("System", "Incoming", lastModel.extractText(), false);
                        } else
                            lastUser = message(lastModel, lastUser);
                    }
                    case REPLACE_LAST -> {
                        Message replacement = entry.messages().getFirst();
                        if ("notification".equals(entry.reason())) {
                            String previous = lastModel == null ? "" : lastModel.extractText();
                            String text = replacement.extractText();
                            String added = text.startsWith(previous) ? text.substring(previous.length()).strip() : text;
                            if (!added.isBlank())
                                notice("System", "Incoming", added, false);
                        }
                        // 通知是注入模型的系统资料，不是用户新输入；不能污染输入历史。
                        else
                            lastUser = message(replacement, lastUser);
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
                                if (!entry.messages().isEmpty())
                                    lastUser = message(entry.messages().getLast(), lastUser);
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
        for (String warning : restored.warnings())
            notice("System", "Warning", warning, true);
    }

    /**
     * 回滚到本轮开始前的显示长度，并丢弃已经失效的工具索引。
     *
     * @param size 回滚后需要保留的条目数量
     */
    private void truncateTo(int size) {
        while (entries.size() > size)
            entries.removeLast();
        toolIndex.clear();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.callId() != null)
                toolIndex.put(entry.callId(), i);
        }
    }

    /**
     * 把一条模型消息展开成用户文字、回答、思考与工具卡；返回当前用户条目下标。
     *
     * @param message 待编码、校验或投影的完整消息
     * @param lastUser 最近用户文本条目的下标，尚无对应项时为 null
     * @return 当前用户文字条目的下标；这条消息没有关联用户项时为 null
     */
    private Integer message(Message message, Integer lastUser) {
        // 召回资料是模型上下文，恢复时不伪装成用户输入，也不进入输入历史。
        if (message.source() == Message.Source.MEMORY_REFERENCE)
            return null;
        Integer user = null;
        if (message.role() == Role.USER) {
            String text = message.extractText();
            if (!text.isBlank()) {
                if (lastUser == null || lastUser >= entries.size())
                    user = add("USER", "System", "Request", text, "HISTORICAL");
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

    /**
     * 保留条目身份、类型、工具关联和历史状态，仅替换正文，返回新的历史条目。
     *
     * @param entry 本次需要入队、投影或发送的完整条目
     * @param body 从持久历史恢复的条目正文
     * @return 保留原身份与关联字段、更新正文的条目副本
     */
    private Entry replaceBody(Entry entry, String body) {
        return new Entry(entry.entryId(), entry.kind(), entry.actor(), entry.title(), body, entry.arguments(),
                entry.status(), entry.requestId(), entry.callId(), entry.blockIndex(), entry.elapsedMs(),
                entry.historical(), entry.tool());
    }

    /**
     * 创建带递增展示 ID 的历史条目，追加到 entries 并返回其下标；null 正文转为空串。
     *
     * @param kind 展示条目种类，如 USER、REPLY、THINKING、TOOL 或 NOTICE
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param title 用户选择的会话标题或当前界面条目的标题
     * @param body 从持久历史恢复的条目正文
     * @param status 本次业务或展示条目的明确状态
     * @return 新条目加入 entries 后的下标
     */
    private Integer add(String kind, String actor, String title, String body, String status) {
        Entry entry = new Entry("e" + (++sequence), kind, actor, title, body == null ? "" : body, "",
                status, null, null, null, null, true, null);
        entries.add(entry);
        return entries.size() - 1;
    }

    /**
     * 把系统或恢复提示追加为 NOTICE 条目，按 error 选择 ERROR 或 HISTORICAL 状态。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param title 用户选择的会话标题或当前界面条目的标题
     * @param body 从持久历史恢复的条目正文
     * @param error 本次执行或条目是否失败
     */
    private void notice(String actor, String title, String body, boolean error) {
        add("NOTICE", actor, title, body, error ? "ERROR" : "HISTORICAL");
    }

    /**
     * 历史工具卡：先建立参数与 HISTORICAL 状态，等真实 tool_result 再收尾。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param callId 模型返回的工具调用 ID，用于关联工具结果
     * @param name 工具调用中的实际工具名称
     * @param arguments 供工具卡片展示的参数文本
     */
    private void addTool(String actor, String callId, String name, String arguments) {
        Entry entry = new Entry("tool-" + (callId == null || callId.isBlank() ? "e" + (++sequence) : callId),
                "TOOL", actor, name == null ? "Tool" : name, "", arguments == null ? "" : arguments,
                "NOT_RUN", null, callId, null, null, true, null);
        entries.add(entry);
        if (callId != null)
            toolIndex.put(callId, entries.size() - 1);
    }

    /**
     * 按调用 ID 找到此前的工具条目，用结果正文和错误状态替换其显示内容；对应工具条目不存在时结束处理。
     *
     * @param callId 模型返回的工具调用 ID，用于关联工具结果
     * @param result 与原调用 ID 配对的工具结果文本
     * @param error 本次执行或条目是否失败
     */
    private void completeTool(String callId, String result, boolean error) {
        Integer index = callId == null ? null : toolIndex.get(callId);
        if (index == null || index >= entries.size())
            return;
        Entry current = entries.get(index);
        entries.set(index, new Entry(current.entryId(), current.kind(), current.actor(), current.title(),
                result == null ? "" : result, current.arguments(), error ? "ERROR" : "HISTORICAL",
                current.requestId(), current.callId(), current.blockIndex(), current.elapsedMs(),
                current.historical(), current.tool()));
    }
}
