package com.jadecode.cli.tui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.tui.components.Editor;
import com.jadecode.tui.components.SelectList;
import com.jadecode.tui.text.StyledLine;

/**
 * 单一界面线程拥有的显示状态，不是 Session history，也不是另一个任务调度器。
 * 条目的原文在模型压缩后仍保留；缓存仅保存排版，不用于重新请求模型。
 */
public final class TuiState {
    /** 一条消息／卡片。只在界面线程修改，version 用于使排版缓存失效。 */
    public static final class Entry {
        public final String id, actor, kind;
        public final StringBuilder body = new StringBuilder();
        public String title, arguments = "", status = "";
        public boolean expanded;
        public long version;
        public int cacheWidth = -1;
        public String cacheActor = "";
        public long cacheVersion = -1;
        public List<StyledLine> cached = List.of();
        public long started = System.nanoTime(), elapsed;
        Entry(String id, String actor, String kind, String title) { this.id = id; this.actor = actor; this.kind = kind; this.title = title; }
        public void replace(String text) { body.setLength(0); body.append(text); version++; }
    }
    public final Editor editor = new Editor();
    public long generation;
    public boolean loadingResume;
    public String sessionId = "";
    public SessionPicker sessionPicker;
    public final List<Entry> entries = new ArrayList<>();
    public final Map<String, Entry> tools = new LinkedHashMap<>();
    public final Map<String, List<Entry>> streams = new LinkedHashMap<>();
    public final Map<String, String> actors = new LinkedHashMap<>();
    public final Map<String, String> background = new LinkedHashMap<>();
    public final ArrayDeque<UiEvent.ApprovalRequested> approvals = new ArrayDeque<>();
    public int approvalChoice = -1;
    public SelectList selector;
    /** 命令候选只辅助编辑，不与审批／主题选择器共用焦点；Esc 后等下一次编辑再显示。 */
    public SelectList commands;
    public boolean commandsDismissed, commandSuggestions = true;
    public String selectorKind = "", theme = "dark", pending = "", pendingKind = "", hint = "";
    public boolean thinkingVisible = true, toolsExpanded, closing;
    public long inputTokens, outputTokens;
    public long missingUsage;
    public boolean usageReported;
    public String model = "", branch = "", directory = "";
    public String modelInfo = "模型参数未取得；使用已有请求额度与压缩配置。";

    /** 增加一条界面消息，不回灌模型历史。 */
    public Entry add(String actor, String kind, String title, String body) {
        var entry = new Entry(java.util.UUID.randomUUID().toString(), actor, kind, title);
        entry.replace(body); entries.add(entry); return entry;
    }
    /**
     * 追加一次请求的片段。同类连续片段拼在当前块，类型变化时另开一块，以保留思考／正文顺序。
     * request 标识一次显示请求，不是工具调用 ID；新请求不能接到上一请求尚未完成的卡片里。
     */
    public void delta(UiEvent.Delta delta) {
        var blocks = streams.computeIfAbsent(delta.request(), ignored -> new ArrayList<>());
        Entry entry;
        if (blocks.isEmpty() || !blocks.getLast().kind.equals(delta.kind())) {
            entry = add("leader", delta.kind(), delta.kind(), ""); entry.status = "STREAMING"; blocks.add(entry);
        } else entry = blocks.getLast();
        entry.body.append(delta.text()); entry.version++;
    }
    /** 完整响应与失败分别收尾；失败片段仍显示，但不能写成成功回复。 */
    public void end(UiEvent.StreamEnd end) {
        for (Entry entry : streams.getOrDefault(end.request(), List.of())) {
            entry.status = end.failed() ? "INCOMPLETE" : "DONE"; entry.version++;
        }
        streams.remove(end.request());
    }
    /**
     * 采用运行组件发来的事实。这里只记录“已经发生什么”，不据此启动工具或推进任务图。
     * 工具按 Agent 身份加调用编号找回原卡；同名工具甚至相同模型 ID 也不会串到别的 Agent。
     * 结束状态由执行器提供，不通过结果字符串猜测；token 只累计 Leader 正式请求的已报告值。
     */
    public void execution(ExecutionNotice notice) {
        String actor = notice.actor();
        switch (notice.kind()) {
            case MODEL -> { if (actor.equals("leader")) modelInfo = notice.detail(); }
            case SESSION -> {
                actors.put(actor, notice.state());
                if (actor.equals("leader") && !notice.detail().isBlank()) model = notice.detail();
                if (notice.state().equals("CLOSED")) {
                    // 子代理异常可能没有正常工具结束事件，不能让它的卡片永久显示执行中。
                    tools.values().stream().filter(e -> e.actor.equals(actor) && (e.status.equals("RUNNING") || e.status.equals("QUEUED")))
                            .forEach(e -> { e.status = e.status.equals("QUEUED") ? "NOT_RUN" : "STOP_REQUESTED"; e.version++; });
                }
            }
            case TOOL_START, TOOL_END -> {
                // START 和 END 更新同一张卡，结果到达时不会再多插一份重复的调用记录。
                String key = actor + "/" + notice.id();
                Entry entry = tools.computeIfAbsent(key, ignored -> {
                    Entry created = add(actor, "Tool", notice.name(), ""); created.expanded = toolsExpanded; return created;
                });
                if (notice.kind() == ExecutionNotice.Kind.TOOL_START) entry.arguments = notice.detail();
                else { entry.replace(notice.detail()); entry.elapsed = System.nanoTime() - entry.started; }
                entry.status = notice.state(); entry.version++;
            }
            case USAGE -> {
                // 队友及辅助请求没有纳入这份口径；“已报告为零”和“没有报告”分别保存。
                if (actor.equals("leader")) {
                    if (notice.state().equals("UNREPORTED")) missingUsage++;
                    else { inputTokens += notice.inputTokens(); outputTokens += notice.outputTokens(); usageReported = true; }
                }
            }
            case COMPACT -> add(actor, "Notice", "Context", notice.detail());
            case BUFFER -> {
                // 终端输入交给 Leader。队友也会报告自己的空缓冲，不能因此清掉 Leader 尚未消费的输入。
                if (actor.equals("leader")) { pending = notice.detail(); pendingKind = notice.state(); }
            }
            case BACKGROUND -> background.put(actor + "/" + notice.id(), notice.state());
            case SUBAGENT -> add(actor, "Notice", "Subagent", notice.detail());
        }
    }
    public static String actorName(String actor) {
        return actor.equals("leader") ? "Leader" : actor.startsWith("leader/") ? "Leader / " + actor.substring(7) : actor;
    }

    /**
     * 仅在存在未关闭的常驻队友时显示 Team 身份。普通 task 子代理的 actor 含斜杠，
     * 不是 Team 成员；CLOSED 成员仍保留历史，但不让单人会话继续显示 Leader。
     * 这是展示判断，不创建／关闭 Team，也不改变真实身份和权限。
     */
    public boolean teamActive() {
        return actors.entrySet().stream().anyMatch(e -> e.getKey().startsWith("agent_")
                && !e.getKey().contains("/") && !e.getValue().equals("CLOSED"));
    }

    /** 单人模式省略主 Agent 前缀，子代理仍保留自己的名字；实际 actor ID 不被改写。 */
    public String displayActor(String actor) {
        if (teamActive()) return actorName(actor);
        return actor.equals("leader") ? "" : actor.startsWith("leader/") ? actor.substring(7) : actor;
    }
}
