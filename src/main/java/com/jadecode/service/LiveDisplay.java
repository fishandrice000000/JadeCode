package com.jadecode.service;

import java.util.*;
import java.util.function.Function;
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
    void install(List<Entry> baseline) {
        rows.clear(); active.clear(); actors.clear(); previews.clear(); characters = 0;
        baseline.forEach(this::put);
    }
    List<Entry> entries() { return List.copyOf(rows.values()); }

    /** 单个事件更新对应卡片；调用者已持有 SessionChannel 锁，不执行文件或网络 IO。 */
    void accept(String type, ObjectNode p, Function<String,String> blob) {
        String request = p.path("requestId").asText(), actor = p.path("actor").asText("leader");
        switch (type) {
            case "task.submitted" -> add("task-" + p.path("submissionId").asText(), "USER", "You", "Request",
                    text(p, "text", "textBlobRef", blob), "", "SUBMITTING", null, null, null, null);
            case "task.result", "task.failed" -> {
                String id = "task-" + p.path("submissionId").asText();
                Entry old = rows.get(id);
                if (old != null) replace(old, old.body() + (type.equals("task.failed") ? "\n" + p.path("message").asText() : ""),
                        old.arguments(), type.equals("task.failed") ? "ERROR" : "DONE", old.elapsedMs(), old.tool());
            }
            case "stream.started" -> { actors.put(request, actor); active.remove(request); }
            case "stream.delta" -> {
                String kind = p.path("channel").asText("REPLY"), id = active.get(request);
                Entry old = id == null ? null : rows.get(id);
                if (old == null || !old.kind().equals(kind)) {
                    id = "live-stream-" + (++ids); active.put(request, id);
                    add(id, kind, actors.getOrDefault(request, actor), kind.equals("REPLY") ? "Reply" : "Thinking",
                            "", "", "STREAMING", request, null, null, null);
                    old = rows.get(id);
                }
                replace(old, old.body() + p.path("text").asText(), "", "STREAMING", null, null);
            }
            case "stream.ended" -> {
                boolean incomplete = p.path("failed").asBoolean() || p.path("stopReason").asText().equals("max_tokens");
                for (Entry row : List.copyOf(rows.values()))
                    if (request.equals(row.requestId()) && Set.of("REPLY","THINKING").contains(row.kind()))
                        replace(row, row.body(), row.arguments(), incomplete ? "INCOMPLETE" : "DONE", row.elapsedMs(), row.tool());
                active.remove(request); actors.remove(request);
            }
            case "stream.preview.started" -> {
                String call = p.path("callId").asText(), id = "live-tool-" + request + "-" + p.path("blockIndex").asInt() + "-" + call;
                previews.put(request + "/" + p.path("blockIndex").asInt(), id); active.remove(request);
                add(id, "TOOL", actors.getOrDefault(request, actor), p.path("name").asText(),
                        "", p.path("initialInputJson").asText(), "PREVIEW", request, call, p.path("blockIndex").asInt(), null);
            }
            case "stream.preview.delta" -> {
                Entry old = rows.get(previews.get(request + "/" + p.path("blockIndex").asInt()));
                if (old != null) replace(old, old.body(), old.arguments() + p.path("fragment").asText(), old.status(), null, null);
            }
            case "execution.notice" -> notice(p, blob);
            case "message" -> {
                String body = p.path("text").asText(), label = p.path("label").asText("Notice");
                // 整条 Reply 常用于确认同一份流式回复，避免重连后显示两份相同正文。
                String latest = rows.values().stream().filter(e -> e.actor().equals(actor) && e.kind().equals("REPLY"))
                        .reduce((a,b) -> b).map(Entry::requestId).orElse(null);
                String combined = rows.values().stream().filter(e -> e.kind().equals("REPLY") && e.actor().equals(actor)
                        && latest != null && latest.equals(e.requestId())).map(Entry::body).reduce("", String::concat);
                if (!(label.equals("Reply") && combined.strip().equals(body.strip())))
                    add("live-message-" + (++ids), label.equals("Reply") ? "REPLY" : "NOTICE", actor, label, body,
                            "", p.path("error").asBoolean() ? "ERROR" : label.equals("Reply") ? "DONE" : "HISTORICAL", null,null,null,null);
            }
            default -> { }
        }
    }

    /** 执行卡按真实调用 ID 关联；Team/工作流等显示为独立通知，不赋予任何权限。 */
    private void notice(ObjectNode p, Function<String,String> blob) {
        String kind = p.path("kind").asText(), state = p.path("state").asText(), actor = p.path("actor").asText("leader");
        String detail = text(p, "detail", "detailBlobRef", blob), execution = p.path("id").asText();
        if (kind.equals("TOOL_START") || kind.equals("TOOL_END")) {
            Entry old = rows.values().stream().filter(e -> e.kind().equals("TOOL") && e.actor().equals(actor)
                    && (e.entryId().equals("live-execution-" + execution)
                    || e.requestId() != null && e.callId() != null && execution.equals(e.requestId() + "/" + e.callId())))
                    .findFirst().orElse(null);
            ToolInfo tool = p.path("toolIdentity").isObject() ? Json.mapper().convertValue(p.path("toolIdentity"), ToolInfo.class) : null;
            if (old == null) {
                int slash = execution.indexOf('/');
                add("live-execution-" + execution, "TOOL", actor, p.path("name").asText(), "", "", state,
                        slash < 0 ? null : execution.substring(0,slash), slash < 0 ? null : execution.substring(slash+1), null, tool);
                old = rows.get("live-execution-" + execution);
            }
            replace(old, kind.equals("TOOL_END") ? detail : old.body(), kind.equals("TOOL_START") ? detail : old.arguments(),
                    state, p.has("elapsedMs") ? p.path("elapsedMs").asLong() : null, tool);
        } else if (Set.of("TEAM","GOAL","WORKFLOW","COMPACT","SUBAGENT","BACKGROUND").contains(kind) && !state.equals("UPDATED")) {
            add("live-notice-" + (++ids), "NOTICE", actor, p.path("name").asText(kind), detail, "",
                    state.equals("ERROR") ? "ERROR" : "HISTORICAL", null,null,null,null);
        }
    }
    private String text(ObjectNode p, String inline, String ref, Function<String,String> blob) {
        return p.hasNonNull(ref) ? blob.apply(p.path(ref).asText()) : p.path(inline).asText();
    }
    private void add(String id, String kind, String actor, String title, String body, String args, String status,
            String request, String call, Integer block, ToolInfo tool) {
        put(new Entry(id,kind,actor,title,body,args,status,request,call,block,null,false,tool));
    }
    private void replace(Entry old, String body, String args, String status, Long elapsed, ToolInfo tool) {
        put(new Entry(old.entryId(),old.kind(),old.actor(),old.title(),body,args,status,old.requestId(),
                old.callId(),old.blockIndex(),elapsed,old.historical(),tool));
    }
    /** 限额按正文与参数的字符数计；优先保留最新卡，裁剪旧卡时给出显式提示。 */
    private void put(Entry entry) {
        Entry previous = rows.put(entry.entryId(), entry);
        characters += entry.body().length() + entry.arguments().length()
                - (previous == null ? 0 : previous.body().length() + previous.arguments().length());
        boolean trimmed = false;
        var iterator = rows.entrySet().iterator();
        while ((rows.size() > 10_000 || characters > 16_000_000) && iterator.hasNext()) {
            Entry old = iterator.next().getValue();
            if (old.entryId().equals(entry.entryId())) continue;
            characters -= old.body().length() + old.arguments().length(); iterator.remove(); trimmed = true;
        }
        if (trimmed) rows.put("display-limit", new Entry("display-limit","NOTICE","System","Display limit",
                "Earlier display was omitted; saved conversation history remains available.","","HISTORICAL",
                null,null,null,null,true,null));
    }
}
