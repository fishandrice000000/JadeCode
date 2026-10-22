package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.cli.tui.UsageFormatter;
import com.jadecode.cli.tui.model.CommandPanelModel;
import com.jadecode.session.SessionUsage;

/**
 * /model 与 /session 的数据投影：真实只读字段、usage 分组和缺报告语义，未知不补 0。
 *
 * <p>不反解析 describe 字符串、不联网、不扩建模型池；页面由 CommandPanelView 渲染。</p>
 */
public final class SessionInfoView {
    /** @return /model 的结构化只读页面；已知限制才显示，未知保持缺报告。 */
    public CommandPanelModel panelModel(TuiState state, long generation, long sourceRevision) {
        var fields = new ArrayList<CommandPanelModel.Field>();
        String model = state == null ? "" : state.model;
        fields.add(new CommandPanelModel.Field("Model", model == null || model.isBlank() ? "unknown" : model));
        String thinking = state == null || state.session == null || state.session.thinking() == null
                ? "unknown" : state.session.thinking().value();
        fields.add(new CommandPanelModel.Field("Thinking", thinking));
        var metadata = state == null || state.session == null ? null : state.session.metadata();
        var limits = metadata == null ? null : metadata.limits();
        if (limits != null && limits.contextWindowTokens() != null)
            fields.add(new CommandPanelModel.Field("Context window", limits.contextWindowTokens() + " tokens"));
        if (limits != null && limits.maxInputTokens() != null)
            fields.add(new CommandPanelModel.Field("Max input", limits.maxInputTokens() + " tokens"));
        if (limits != null && limits.maxOutputTokens() != null)
            fields.add(new CommandPanelModel.Field("Max output", limits.maxOutputTokens() + " tokens"));
        if (limits == null || !limits.known())
            fields.add(new CommandPanelModel.Field("Limits", "No context/input/output limits reported."));
        fields.add(new CommandPanelModel.Field("Note", UiMessages.NO_MODEL_SWITCH));
        return new CommandPanelModel(CommandPanelModel.Kind.MODEL, "Model", generation, sourceRevision,
                List.of(), fields, List.of(), "No model metadata reported.");
    }

    /** @return /session 的结构化只读页面；保留身份、usage 分组与缺报告。 */
    public CommandPanelModel panelSession(TuiState state, long generation, long sourceRevision) {
        var fields = new ArrayList<CommandPanelModel.Field>();
        String title = state == null || state.session == null || state.session.title() == null
                || state.session.title().isBlank() ? "New session" : state.session.title();
        fields.add(new CommandPanelModel.Field("Title", title));
        if (state != null && state.sessionId != null && !state.sessionId.isBlank())
            fields.add(new CommandPanelModel.Field("ID", state.sessionId));
        if (state != null && state.model != null && !state.model.isBlank())
            fields.add(new CommandPanelModel.Field("Model", state.model));
        if (state == null || state.session == null) {
            fields.add(new CommandPanelModel.Field("Status", "No active session data reported."));
            return new CommandPanelModel(CommandPanelModel.Kind.SESSION, "Session", generation, sourceRevision,
                    List.of(), fields, List.of(), "No active session data reported.");
        }
        fields.add(new CommandPanelModel.Field("Thinking", state.session.thinking() == null
                ? "unknown" : state.session.thinking().value()));
        fields.add(new CommandPanelModel.Field("Permission", UiMessages.permission(state.permissionProfile)));
        var usage = state.session.usage();
        var total = usage == null ? null : usage.total();
        fields.add(new CommandPanelModel.Field("Tokens", total == null ? "none reported"
                : notEmpty(UsageFormatter.tokenCounts(total), "none reported")));
        String cache = total == null ? "" : UsageFormatter.cacheCounts(total);
        String hit = total == null ? "" : UsageFormatter.cacheHit(total);
        fields.add(new CommandPanelModel.Field("Cache", cache.isBlank() && hit.isBlank() ? "none reported"
                : (cache + (hit.isBlank() ? "" : " · " + hit)).strip()));
        if (total != null && total.missingRequests() > 0)
            fields.add(new CommandPanelModel.Field("Missing usage reports", Long.toString(total.missingRequests())));
        String context = state.session.context() == null ? "" : UsageFormatter.context(state.session.context());
        fields.add(new CommandPanelModel.Field("Context", context.isBlank() ? "unknown" : "estimated " + context));

        var groups = new ArrayList<CommandPanelModel.Group>();
        if (usage != null) {
            usageGroup(groups, "By actor", usage.byActor());
            usageGroup(groups, "By purpose", usage.byPurpose());
            usageGroup(groups, "By model", usage.byModel());
        }
        return new CommandPanelModel(CommandPanelModel.Kind.SESSION, "Session", generation, sourceRevision,
                groups, fields, List.of(), "No active session data reported.");
    }

    private static void usageGroup(List<CommandPanelModel.Group> groups, String title,
            Map<String, SessionUsage.Totals> values) {
        if (values == null || values.isEmpty()) return;
        var entries = new ArrayList<CommandPanelModel.Entry>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                entries.add(new CommandPanelModel.Entry(entry.getKey(),
                        notEmpty(UsageFormatter.tokenCounts(entry.getValue()), "none reported"), "")));
        groups.add(new CommandPanelModel.Group(title.toLowerCase(java.util.Locale.ROOT), title, entries));
    }

    private static String notEmpty(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
