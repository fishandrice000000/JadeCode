package com.jadecode.service;

import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.backend.BackendHistoryProjector.Entry;
import com.jadecode.cli.backend.BackendHistoryProjector.ToolInfo;
import com.jadecode.util.Json;

/**
 * 服务端当前转录投影；只解释宿主结构事件，不根据模型文字猜执行状态。
 * 它与模型 history 分离，压缩不会删除显示。缓存最多约 16M 字符和 10000 张卡，
 * 超限丢弃最早显示卡并显示说明；真实历史仍由 SessionJournal 保存。
 */
final class LiveDisplay {
    private final LinkedHashMap<String, Entry> rows = new LinkedHashMap<>();
    private final Map<String, String> active = new HashMap<>(), actors = new HashMap<>(), previews = new HashMap<>();
    private long ids, characters;

    /**
     * 安装一份完整展示基线：清空现有条目、活动回复、Agent、工具预览和字符计数，再逐项登记传入历史，
     * 供首次打开或重连同步使用。
     *
     * @param baseline 提示词与材料、笔记共同计算的整理基线哈希
     */
    void install(List<Entry> baseline) {
        rows.clear();
        active.clear();
        actors.clear();
        previews.clear();
        characters = 0;
        baseline.forEach(this::put);
    }

    /**
     * 返回当前展示条目的不可变列表快照，保留登记顺序，供会话快照发送使用。
     *
     * @return 当前投影按登记顺序排列的不可变条目快照
     */
    List<Entry> entries() {
        return List.copyOf(rows.values());
    }

    /**
     * 单个事件更新对应卡片；调用者已持有 SessionChannel 锁，不执行文件或网络 IO。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @param p 已采用事件的完整 JSON 载荷
     */
    void accept(String type, ObjectNode p) {
        String request = p.path("requestId").asText(), actor = p.path("actor").asText("leader");
        switch (type) {
            case "task.submitted" -> add("task-" + p.path("submissionId").asText(), "USER", "You", "Request",
                    p.path("text").asText(), "", "SUBMITTING", null, null, null, null);
            case "task.result", "task.failed" -> {
                String id = "task-" + p.path("submissionId").asText();
                Entry old = rows.get(id);
                if (old != null)
                    replace(old, old.body() + (type.equals("task.failed") ? "\n" + p.path("message").asText() : ""),
                            old.arguments(), type.equals("task.failed") ? "ERROR" : "DONE", old.elapsedMs(),
                            old.tool());
            }
            case "stream.started" -> {
                actors.put(request, actor);
                active.remove(request);
            }
            case "stream.delta" -> {
                String kind = p.path("channel").asText("REPLY"), id = active.get(request);
                Entry old = id == null ? null : rows.get(id);
                if (old == null || !old.kind().equals(kind)) {
                    id = "live-stream-" + (++ids);
                    active.put(request, id);
                    add(id, kind, actors.getOrDefault(request, actor), kind.equals("REPLY") ? "Reply" : "Thinking",
                            "", "", "STREAMING", request, null, null, null);
                    old = rows.get(id);
                }
                replace(old, old.body() + p.path("text").asText(), "", "STREAMING", null, null);
            }
            case "stream.ended" -> {
                boolean incomplete = p.path("failed").asBoolean() || p.path("stopReason").asText().equals("max_tokens");
                for (Entry row : List.copyOf(rows.values()))
                    if (request.equals(row.requestId()) && Set.of("REPLY", "THINKING").contains(row.kind()))
                        replace(row, row.body(), row.arguments(), incomplete ? "INCOMPLETE" : "DONE", row.elapsedMs(),
                                row.tool());
                active.remove(request);
                actors.remove(request);
            }
            case "stream.preview.started" -> {
                String call = p.path("callId").asText(),
                        id = "live-tool-" + request + "-" + p.path("blockIndex").asInt() + "-" + call;
                previews.put(request + "/" + p.path("blockIndex").asInt(), id);
                active.remove(request);
                add(id, "TOOL", actors.getOrDefault(request, actor), p.path("name").asText(),
                        "", p.path("initialInputJson").asText(), "PREVIEW", request, call, p.path("blockIndex").asInt(),
                        null);
            }
            case "stream.preview.delta" -> {
                Entry old = rows.get(previews.get(request + "/" + p.path("blockIndex").asInt()));
                if (old != null)
                    replace(old, old.body(), old.arguments() + p.path("fragment").asText(), old.status(), null, null);
            }
            case "execution.notice" -> notice(p);
            case "message" -> {
                String body = p.path("text").asText(), label = p.path("label").asText("Notice");
                // 整条 Reply 常用于确认同一份流式回复，避免重连后显示两份相同正文。
                String latest = rows.values().stream().filter(e -> e.actor().equals(actor) && e.kind().equals("REPLY"))
                        .reduce((a, b) -> b).map(Entry::requestId).orElse(null);
                String combined = rows.values().stream().filter(e -> e.kind().equals("REPLY") && e.actor().equals(actor)
                        && latest != null && latest.equals(e.requestId())).map(Entry::body).reduce("", String::concat);
                if (!(label.equals("Reply") && combined.strip().equals(body.strip())))
                    add("live-message-" + (++ids), label.equals("Reply") ? "REPLY" : "NOTICE", actor, label, body,
                            "", p.path("error").asBoolean() ? "ERROR" : label.equals("Reply") ? "DONE" : "HISTORICAL",
                            null, null, null, null);
            }
            default -> {
            }
        }
    }

    /**
     * 执行卡按真实调用 ID 关联；Team/工作流等显示为独立通知，不赋予任何权限。
     *
     * @param p 已采用事件的完整 JSON 载荷
     */
    private void notice(ObjectNode p) {
        String kind = p.path("kind").asText(), state = p.path("state").asText(),
                actor = p.path("actor").asText("leader");
        String detail = p.path("detail").asText(), execution = p.path("id").asText();
        if (kind.equals("TOOL_START") || kind.equals("TOOL_END")) {
            Entry old = rows.values().stream().filter(e -> e.kind().equals("TOOL") && e.actor().equals(actor)
                    && (e.entryId().equals("live-execution-" + execution)
                            || e.requestId() != null && e.callId() != null
                                    && execution.equals(e.requestId() + "/" + e.callId())))
                    .findFirst().orElse(null);
            ToolInfo tool = p.path("toolIdentity").isObject()
                    ? Json.mapper().convertValue(p.path("toolIdentity"), ToolInfo.class)
                    : null;
            if (old == null) {
                int slash = execution.indexOf('/');
                add("live-execution-" + execution, "TOOL", actor, p.path("name").asText(), "", "", state,
                        slash < 0 ? null : execution.substring(0, slash),
                        slash < 0 ? null : execution.substring(slash + 1), null, tool);
                old = rows.get("live-execution-" + execution);
            }
            replace(old, kind.equals("TOOL_END") ? detail : old.body(),
                    kind.equals("TOOL_START") ? detail : old.arguments(),
                    state, p.has("elapsedMs") ? p.path("elapsedMs").asLong() : null, tool);
        } else if (Set.of("TEAM", "GOAL", "WORKFLOW", "COMPACT", "SUBAGENT", "BACKGROUND").contains(kind)
                && !state.equals("UPDATED")) {
            add("live-notice-" + (++ids), "NOTICE", actor, p.path("name").asText(kind), detail, "",
                    state.equals("ERROR") ? "ERROR" : "HISTORICAL", null, null, null, null);
        }
    }

    /**
     * 用事件字段建立一条新的界面条目，交给 put 统一计量、替换和裁剪，初始耗时为空且标为实时条目。
     *
     * @param id 界面条目 ID，用于后续更新同一张卡片
     * @param kind 展示条目种类，如 USER、REPLY、THINKING、TOOL 或 NOTICE
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param title 用户选择的会话标题或当前界面条目的标题
     * @param body 当前界面条目显示的正文
     * @param args 工具参数的展示文本
     * @param status 本次业务或展示条目的明确状态
     * @param request 该卡片所属的模型请求观察 ID
     * @param call 该工具卡片关联的模型调用 ID
     * @param block 工具预览对应的内容块下标；其他条目可为 null
     * @param tool 本条目已固定的宿主工具身份资料
     */
    private void add(String id, String kind, String actor, String title, String body, String args, String status,
            String request, String call, Integer block, ToolInfo tool) {
        put(new Entry(id, kind, actor, title, body, args, status, request, call, block, null, false, tool));
    }

    /**
     * 沿用旧条目的身份、类型、标题、请求和调用关联，使用新的正文、参数、状态、耗时及工具信息重建条目，
     * 再交给 put 更新展示投影。
     *
     * @param old 需要用新内容替换的现有条目
     * @param body 当前界面条目显示的正文
     * @param args 工具参数的展示文本
     * @param status 本次业务或展示条目的明确状态
     * @param elapsed 当前卡片的实际耗时毫秒数；缺报时为 null
     * @param tool 本条目已固定的宿主工具身份资料
     */
    private void replace(Entry old, String body, String args, String status, Long elapsed, ToolInfo tool) {
        put(new Entry(old.entryId(), old.kind(), old.actor(), old.title(), body, args, status, old.requestId(),
                old.callId(), old.blockIndex(), elapsed, old.historical(), tool));
    }

    /**
     * 限额按正文与参数的字符数计；优先保留最新卡，裁剪旧卡时给出显式提示。
     *
     * @param entry 本次需要入队、投影或发送的完整条目
     */
    private void put(Entry entry) {
        Entry previous = rows.put(entry.entryId(), entry);
        characters += entry.body().length() + entry.arguments().length()
                - (previous == null ? 0 : previous.body().length() + previous.arguments().length());
        boolean trimmed = false;
        var iterator = rows.entrySet().iterator();
        while ((rows.size() > 10_000 || characters > 16_000_000) && iterator.hasNext()) {
            Entry old = iterator.next().getValue();
            if (old.entryId().equals(entry.entryId()))
                continue;
            characters -= old.body().length() + old.arguments().length();
            iterator.remove();
            trimmed = true;
        }
        if (trimmed)
            rows.put("display-limit", new Entry("display-limit", "NOTICE", "System", "Display limit",
                    "Earlier display was omitted; saved conversation history remains available.", "", "HISTORICAL",
                    null, null, null, null, true, null));
    }
}
