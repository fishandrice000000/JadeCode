package com.jadecode.cli.tui.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jadecode.execution.ExecutionNotice;

/**
 * 有序显示转录：消息、流式块、工具卡及它们的稳定关联；不拥有模型 history/调度器/账本。
 *
 * <p>只在 UI 线程修改。流式 Thinking/Text 交替时保留块顺序；同一请求的同类连续片段合并，
 * 类型变化另开卡。工具预览键包含 actor/request/call，执行 notice 复用同一前缀，因此重试、
 * 同 actor 重复 callId、跨 actor 同名工具不会串卡。压缩只追加通知，不删除已显示正文。</p>
 */
public final class TranscriptModel {
    private final List<TranscriptEntry> entries = new ArrayList<>();
    private final Map<String, List<TranscriptEntry>> streams = new LinkedHashMap<>();
    /** 每个请求的最后一块，用于只合并“紧邻同类”增量，保留 text/thinking 交替顺序。 */
    private final Map<String, TranscriptEntry> lastStream = new LinkedHashMap<>();
    private final Map<String, ToolDisplayState> tools = new LinkedHashMap<>();
    private final Map<String, ToolDisplayState> previewsByBlock = new LinkedHashMap<>();
    private long generation;
    private long revision;
    private long idCounter;
    private String lastActorReply = "";

    /** 切换会话：清空全部显示关联并安装新 generation；旧异步结果由调用方按版本丢弃。 */
    public void reset(long newGeneration) {
        entries.clear(); streams.clear(); lastStream.clear(); tools.clear(); previewsByBlock.clear();
        generation = newGeneration; revision++; lastActorReply = "";
    }

    public long generation() { return generation; }
    public long revision() { return revision; }
    public List<TranscriptEntry> entries() { return List.copyOf(entries); }
    public List<ToolDisplayState> tools() { return List.copyOf(tools.values()); }

    /** 新增用户请求卡（Request）。 */
    public TranscriptEntry addUser(String text) {
        TranscriptEntry entry = create("You", TranscriptEntry.Kind.USER, "Request");
        entry.replace(text); entry.status("DONE"); return entry;
    }

    /** 新增程序通知；自有外层已由调用方用英文提供，正文原文不翻译。 */
    public TranscriptEntry addNotice(String actor, String title, String text, boolean error) {
        TranscriptEntry entry = create(actor, TranscriptEntry.Kind.NOTICE, title);
        entry.replace(text); entry.status(error ? "ERROR" : "DONE"); return entry;
    }

    /** 新增历史条目（恢复用），不参与实时流关联。 */
    public TranscriptEntry addHistorical(String actor, TranscriptEntry.Kind kind, String title, String text,
            String status) {
        TranscriptEntry entry = create(actor, kind, title);
        entry.replace(text); entry.status(status); entry.source("historical"); return entry;
    }

    /**
     * 采用一次显示请求的正文/思考增量。request 标识一次正式请求尝试；同请求同类连续片段
     * 合并，类型切换创建新块，保持流式顺序。
     */
    public TranscriptEntry delta(String request, String actor, TranscriptEntry.Kind kind, String text) {
        if (kind != TranscriptEntry.Kind.REPLY && kind != TranscriptEntry.Kind.THINKING)
            throw new IllegalArgumentException("Only Reply/Thinking support streaming deltas");
        TranscriptEntry entry = lastStream.get(request);
        if (entry == null || entry.kind() != kind) {
            entry = create(actor, kind, kind == TranscriptEntry.Kind.REPLY ? "Reply" : "Thinking");
            entry.status("STREAMING");
            streams.computeIfAbsent(request, ignored -> new ArrayList<>()).add(entry);
            lastStream.put(request, entry);
        }
        entry.append(text);
        if (kind == TranscriptEntry.Kind.REPLY) lastActorReply = actor;
        return entry;
    }

    /** 请求结束：所有该请求的流式块收尾；失败标 INCOMPLETE，保留已显示片段。 */
    public void endStream(String request, boolean failed, String stopReason) {
        boolean any = false;
        for (TranscriptEntry entry : streams.getOrDefault(request, List.of())) {
            entry.status(failed ? "INCOMPLETE" : stopReason.equals("max_tokens") ? "TRUNCATED" : "DONE");
            entry.version(); any = true;
        }
        streams.remove(request); lastStream.remove(request);
        if (any) revision++;
    }

    /** 正式请求开始：清理同 ID 的旧流（重试新 ID，不应复用）。 */
    public void requestStarted(String request, String actor) {
        streams.remove(request); lastStream.remove(request);
        revision++;
    }

    /** 建立工具参数预览卡；空初始对象与后续片段分开，不能拼成 `{}{...}`。 */
    public ToolDisplayState previewStarted(String request, String actor, int blockIndex, String callId,
            String name, String initialInput, com.jadecode.execution.ToolIdentity identity) {
        var state = new ToolDisplayState(actor, request, callId, name, identity);
        state.previewArguments(initialInput == null ? "" : initialInput);
        String key = state.key();
        // 同一请求/调用重复开始（协议重发）时沿用原卡，不插入第二张。
        ToolDisplayState existing = tools.get(key);
        if (existing != null) state = existing;
        else tools.put(key, state);
        entryForTool(state).status("PREVIEW");
        previewsByBlock.put(request + "/" + blockIndex, state);
        return state;
    }

    /** 追加参数片段；只更新预览，不执行工具。 */
    public ToolDisplayState previewDelta(String request, int blockIndex, String fragment) {
        ToolDisplayState state = previewsByBlock.get(request + "/" + blockIndex);
        if (state == null) return null;
        state.appendArguments(fragment);
        entryForTool(state).status("PREVIEW");
        return state;
    }

    /** 参数块 JSON 已通过校验；仍等整响应与执行。 */
    public ToolDisplayState previewClosed(String request, int blockIndex) {
        ToolDisplayState state = previewsByBlock.remove(request + "/" + blockIndex);
        if (state == null) return null;
        state.closeArguments();
        return state;
    }

    /**
     * 采用真实执行事实。preview→QUEUED→RUNNING→RETURNED 更新同一张卡；没有预览的旧调用
     * 也创建卡，但不把 RETURNED 当成功。关闭时运行中的卡按事实收尾。
     */
    public void execution(ExecutionNotice notice) {
        if (notice.kind() == ExecutionNotice.Kind.SESSION && notice.state().equals("CLOSED")) {
            tools.values().stream().filter(ToolDisplayState::active)
                    .forEach(state -> { state.interrupted(state.status() == ToolDisplayState.Status.QUEUED
                            ? ToolDisplayState.Status.NOT_RUN : ToolDisplayState.Status.INTERRUPTED,
                            "Session closed before a result was recorded."); entryForTool(state).status(state.status().name()); });
            revision++;
            return;
        }
        if (notice.kind() != ExecutionNotice.Kind.TOOL_START && notice.kind() != ExecutionNotice.Kind.TOOL_END) return;
        String key = notice.actor() + "/" + notice.id();
        ToolDisplayState state = tools.get(key);
        if (state == null) {
            // execution id 的格式为 requestId/callId；拆出 request 便于显示/未来关联，但不据此猜配。
            String id = notice.id();
            int slash = id.indexOf('/');
            String request = slash > 0 ? id.substring(0, slash) : "";
            String callId = slash > 0 ? id.substring(slash + 1) : id;
            state = new ToolDisplayState(notice.actor(), request, callId, notice.name(), notice.identity());
            tools.put(key, state);
            entryForTool(state);
        }
        if (notice.identity() != null) {
            // 可信身份只在宿主提供时采用；模型参数无法填写这个字段。
            if (state.identity() == null) state = replaceIdentity(state, notice.identity());
        }
        TranscriptEntry entry = entryForTool(state);
        if (notice.kind() == ExecutionNotice.Kind.TOOL_START) {
            ToolDisplayState.Status next = notice.state().equals("QUEUED") ? ToolDisplayState.Status.QUEUED
                    : ToolDisplayState.Status.RUNNING;
            state.executing(next, notice.detail());
            entry.status(next.name());
        } else {
            ToolDisplayState.Status next = switch (notice.state()) {
                case "RETURNED" -> ToolDisplayState.Status.RETURNED;
                case "ERROR" -> ToolDisplayState.Status.ERROR;
                case "BLOCKED" -> ToolDisplayState.Status.BLOCKED;
                case "NOT_RUN" -> ToolDisplayState.Status.NOT_RUN;
                case "STOP_REQUESTED" -> ToolDisplayState.Status.INTERRUPTED;
                default -> ToolDisplayState.Status.INTERRUPTED;
            };
            state.finished(next, notice.detail(), notice.elapsedNanos());
            entry.status(next.name());
            entry.elapsed(notice.elapsedNanos());
        }
        entry.replace(state.arguments() + (state.result().isEmpty() ? "" : "\n" + state.result()));
        entry.version();
        revision++;
    }

    /** 可信身份晚到时替换显示对象，同时保留已累计参数/状态。 */
    private ToolDisplayState replaceIdentity(ToolDisplayState old, com.jadecode.execution.ToolIdentity identity) {
        var replacement = new ToolDisplayState(old.actor(), old.requestId(), old.callId(), old.name(), identity);
        if (!old.arguments().isEmpty()) replacement.previewArguments(old.arguments());
        if (old.status() != ToolDisplayState.Status.PREVIEW) replacement.executing(
                old.status() == ToolDisplayState.Status.QUEUED ? ToolDisplayState.Status.QUEUED
                        : ToolDisplayState.Status.RUNNING, old.arguments());
        if (!old.result().isEmpty()) replacement.finished(old.status(), old.result(), old.elapsedNanos());
        String oldKey = old.key();
        tools.put(oldKey, replacement);
        previewsByBlock.replaceAll((key, value) -> value == old ? replacement : value);
        return replacement;
    }

    /** 查找工具卡对应的条目；每个工具状态只对应一个条目。 */
    private TranscriptEntry entryForTool(ToolDisplayState state) {
        String id = "tool:" + state.key();
        for (TranscriptEntry entry : entries) if (entry.id().equals(id)) return entry;
        TranscriptEntry entry = createWithId(id, state.actor(), TranscriptEntry.Kind.TOOL,
                state.name().isEmpty() ? "Tool" : state.name());
        entry.replace(state.arguments() + (state.result().isEmpty() ? "" : "\n" + state.result()));
        return entry;
    }

    /** 程序整条消息（非流式 Reply/Notice/Error）。 */
    public TranscriptEntry message(String actor, String label, String text, boolean error) {
        boolean reply = label.equals("Reply") && !error;
        TranscriptEntry entry = create(actor, reply ? TranscriptEntry.Kind.REPLY : TranscriptEntry.Kind.NOTICE,
                reply ? "Reply" : label);
        entry.replace(text); entry.status(error ? "ERROR" : "DONE");
        if (reply) lastActorReply = actor;
        return entry;
    }

    /** @return 最近一次助手回复正文（可跨请求），/copy 使用；无则空。 */
    public String lastReplyText() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            TranscriptEntry entry = entries.get(i);
            if (entry.kind() == TranscriptEntry.Kind.REPLY && !entry.body().isBlank()) return entry.body();
        }
        return "";
    }

    /** @return 最近助手回复的 actor；无则空。 */
    public String lastReplyActor() { return lastActorReply; }

    /** 全局展开/折叠工具卡；不删除原文。 */
    public void setToolsExpanded(boolean value) {
        tools.values().forEach(state -> state.setExpanded(value));
        entries.stream().filter(entry -> entry.kind() == TranscriptEntry.Kind.TOOL).forEach(entry -> entry.setExpanded(value));
        revision++;
    }

    /** 全局显示/隐藏思考块；原文保留。 */
    public void setThinkingExpanded(boolean value) {
        entries.stream().filter(entry -> entry.kind() == TranscriptEntry.Kind.THINKING).forEach(entry -> entry.setExpanded(!value));
        revision++;
    }

    /** 切换单个条目（工具/思考）展开；工具状态同步。 */
    public void toggleEntry(String entryId) {
        for (TranscriptEntry entry : entries) if (entry.id().equals(entryId)) { entry.toggleExpanded(); break; }
        tools.values().stream().filter(state -> ("tool:" + state.key()).equals(entryId))
                .forEach(ToolDisplayState::toggleExpanded);
        revision++;
    }

    /** @return 指定 ID 的条目；不存在 null。 */
    public TranscriptEntry entry(String id) {
        for (TranscriptEntry entry : entries) if (entry.id().equals(id)) return entry;
        return null;
    }

    /**
     * 恢复一张历史工具卡：不重跑工具、不补耗时；没有结果时明确 NOT_RUN/INTERRUPTED，
     * 不用 DONE/HISTORICAL 冒充成功。
     */
    public ToolDisplayState addHistoricalTool(String actor, String callId, String name, String arguments,
            String result, boolean error, boolean hasResult) {
        var state = new ToolDisplayState(actor, "historical-" + (++idCounter), callId, name, null);
        state.previewArguments(arguments == null ? "" : arguments);
        if (hasResult) state.historical(result == null ? "" : result, error);
        else state.interrupted(ToolDisplayState.Status.INTERRUPTED, "No result recorded for this historical call.");
        tools.put(state.key(), state);
        TranscriptEntry entry = entryForTool(state);
        entry.status(state.status().name());
        entry.source("historical");
        entry.replace(state.arguments() + (state.result().isEmpty() ? "" : "\n" + state.result()));
        entry.version();
        return state;
    }

    /** 给已恢复的工具调用补上真实 tool_result；不重跑工具、不补耗时。 */
    public void completeHistoricalTool(String actor, String callId, String result, boolean error) {
        ToolDisplayState state = tools.values().stream()
                .filter(candidate -> candidate.actor().equals(actor) && candidate.callId().equals(callId))
                .reduce((first, second) -> second).orElse(null);
        if (state == null) return;
        state.historical(result, error);
        TranscriptEntry entry = entryForTool(state);
        entry.status(state.status().name());
        entry.replace(state.arguments() + (state.result().isEmpty() ? "" : "\n" + state.result()));
        entry.version();
        revision++;
    }

    /**
     * cron 回滚：把显示条目恢复到该轮开始前，并移除不再有条目的工具状态。
     * 不修改模型 history/日志；只重建可显示投影。
     */
    public void replaceEntries(List<TranscriptEntry> snapshot, Map<String, List<TranscriptEntry>> ignored) {
        entries.clear(); entries.addAll(snapshot); streams.clear(); lastStream.clear();
        tools.entrySet().removeIf(entry -> entry(("tool:" + entry.getKey())) == null);
        previewsByBlock.clear();
        revision++;
    }

    /** 恢复时安装历史条目；版本不变，调用方重置 generation。 */
    public void installHistorical(List<TranscriptEntry> restored) {
        entries.clear(); streams.clear(); lastStream.clear(); tools.clear(); previewsByBlock.clear();
        entries.addAll(restored); revision++;
    }

    private TranscriptEntry create(String actor, TranscriptEntry.Kind kind, String title) {
        return createWithId("entry:" + (++idCounter), actor, kind, title);
    }

    private TranscriptEntry createWithId(String id, String actor, TranscriptEntry.Kind kind, String title) {
        var entry = new TranscriptEntry(id, actor, kind, title);
        entries.add(entry); revision++; return entry;
    }
}
