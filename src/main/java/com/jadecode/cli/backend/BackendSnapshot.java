package com.jadecode.cli.backend;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.config.JadeSettings;
import com.jadecode.llm.ModelMetadata;
import com.jadecode.mcp.McpManager;
import com.jadecode.safety.PermissionProfile;
import com.jadecode.safety.PermissionState;
import com.jadecode.session.ContextUsage;
import com.jadecode.session.SessionRuntime;
import com.jadecode.session.SessionUsage;
import com.jadecode.util.Json;

/**
 * 一次会话的只读资料快照：Node 的全部业务显示都来自这里加增量事件。
 *
 * <p>边界：它只组合现有事实（Display、usage、context、permission、Team/MCP 快照、设置
 * 白名单字段），不序列化整个 {@code AppConfig}、模型客户端、认证或可变领域对象。未知值
 * 保持 null，不用 0 伪造“未知用量/上下文”。</p>
 *
 * <p>为什么用 record + {@link #toJson()} 而不是把运行对象直接交给 Jackson：Node 只应看到
 * 稳定字段名；Java 内部重构不能悄悄改变 wire format。字段集合同时写在
 * {@code protocol/tui-bridge-v1.schema.json} 里。</p>
 */
public record BackendSnapshot(String sessionId, String title, long generation, String workspace, String gitBranch,
        String model, String modelSource, Integer contextWindowTokens, Integer maxInputTokens, Integer maxOutputTokens,
        String thinking, String thinkingLabel, List<String> thinkingChoices,
        Usage usage, Context context, Permission permission, List<Actor> actors, boolean teamActive,
        List<Mcp> mcp, Map<String, String> background, String bufferSummary, String bufferState,
        Tui tui, long configRevision, Capabilities capabilities, Map<String, DisplayClock.Tick> clocks) {

    /** 累计用量；null 字段表示“没有报告”，不是 0。 */
    public record Usage(Long inputTokens, Long outputTokens, Long cacheReadTokens, Long cacheWriteTokens,
            Long reportedRequests, Long missingRequests) { }

    /** 最近一次响应的上下文占用；window 未知时整体为 null。 */
    public record Context(Long occupiedTokens, Integer windowTokens, Integer remainingPercent) { }

    /** 权限资料：档位、revision、默认档位与规则概要（不传完整规则/凭据）。 */
    public record Permission(String profile, String label, long revision, String defaultProfile,
            List<String> ruleDescriptions, int disabledDefaultCount) { }

    /** 一个真实 Agent/队友的状态；单人会话的 leader 不会被渲染成 Team 身份。 */
    public record Actor(String id, String state) { }

    /** MCP 只读服务资料；不连接、不认证、不改配置。 */
    public record Mcp(String name, boolean enabled, boolean connected, List<String> tools) { }

    /** 能力开关：Node 用它隐藏后端不存在的能力，而不是显示假入口。 */
    public record Capabilities(boolean thinking, boolean permission, boolean resume, boolean mcp,
            boolean externalEditor, boolean browserImages) { }

    /** 已批准的 TUI 设置快照（18 个有效菜单字段 + 非菜单项）。 */
    public record Tui(String theme, String lightTheme, String darkTheme, String mode, String fullscreenExitOutput,
            String fullscreenScrollbar, boolean fullscreenCopyOnSelect, int fullscreenWheelScrollLines,
            boolean showHardwareCursor, int editorPaddingX, int outputPad, int autocompleteMaxVisible,
            boolean thinkingVisible, boolean toolsExpanded, boolean commandSuggestions, boolean clearOnShrink,
            boolean showTerminalProgress, boolean quietStartup, String codeBlockIndent, String externalEditor,
            int escapeTimeoutMillis, Map<String, List<String>> keybindings,
            boolean showImages, int imageWidthCells, String mermaidRendering) { }

    /**
     * 组合一份快照。
     *
     * @param display     会话公开资料；未打开会话时为 null
     * @param permission  权限快照；未打开会话时为 null
     * @param settings    本次启动采用的设置
     * @param configRevision 已持久化的配置版本，Node 用它拒绝陈旧 patch
     */
    public static BackendSnapshot capture(long generation, SessionRuntime.Display display, PermissionState.Snapshot permission,
            JadeSettings settings, long configRevision, List<McpManager.ServerSnapshot> mcp,
            Map<String, String> actors, String bufferSummary, String bufferState,
            Map<String, String> background, Map<String, DisplayClock.Tick> clocks) {
        JadeSettings.Tui tui = settings.tui();
        Usage usage = display == null ? new Usage(null, null, null, null, null, null) : usage(display.usage());
        Context context = display == null ? null : context(display.context());
        Permission permissionView = permission == null ? null
                : new Permission(permission.profile().value(), permission.profile().label(), permission.revision(),
                        settings.permissions().defaultProfile().value(),
                        permission.rules().stream().map(rule -> rule.description()).toList(),
                        permission.disabledDefaults().size());
        List<Actor> actorList = actors.entrySet().stream().map(e -> new Actor(e.getKey(), e.getValue())).toList();
        boolean teamActive = actors.keySet().stream().anyMatch(id -> id.startsWith("agent_")
                && !id.contains("/") && !"CLOSED".equals(actors.get(id)));
        List<Mcp> servers = mcp == null ? List.of() : mcp.stream()
                .map(server -> new Mcp(server.name(), server.enabled(), server.connected(), server.tools())).toList();
        ModelMetadata metadata = display == null ? null : display.metadata();
        return new BackendSnapshot(
                display == null ? "" : display.id(),
                display == null ? "" : display.title(),
                generation,
                Paths.workspace(),
                Paths.gitBranch(),
                display == null ? "" : display.model(),
                metadata == null ? "" : metadata.source(),
                metadata == null ? null : metadata.limits().contextWindowTokens(),
                metadata == null ? null : metadata.limits().maxInputTokens(),
                metadata == null ? null : metadata.limits().maxOutputTokens(),
                display == null ? "" : display.thinking().value(),
                display == null ? "" : display.thinkingLabel(),
                display == null ? List.of() : display.thinkingChoices().stream().map(value -> value.value()).toList(),
                usage, context, permissionView, actorList, teamActive, servers, Map.copyOf(background),
                bufferSummary == null ? "" : bufferSummary, bufferState == null ? "" : bufferState,
                new Tui(tui.theme(), tui.lightTheme(), tui.darkTheme(), tui.mode(), tui.fullscreenExitOutput(),
                        tui.fullscreenScrollbar(), tui.fullscreenCopyOnSelect(), tui.fullscreenWheelScrollLines(),
                        tui.showHardwareCursor(), tui.editorPaddingX(), tui.outputPad(), tui.autocompleteMaxVisible(),
                        tui.thinkingVisible(), tui.toolsExpanded(), tui.commandSuggestions(), tui.clearOnShrink(),
                        tui.showTerminalProgress(), tui.quietStartup(), tui.codeBlockIndent(), tui.externalEditor(),
                        tui.escapeTimeoutMillis(), new LinkedHashMap<>(tui.keybindings()),
                        tui.showImages(), tui.imageWidthCells(), tui.mermaidRendering()),
                configRevision,
                new Capabilities(display != null && !display.thinkingChoices().isEmpty(), true, true,
                        !servers.isEmpty(), !tui.externalEditor().isBlank(), false),
                Map.copyOf(clocks == null ? Map.of() : clocks));
    }

    private static Usage usage(SessionUsage.Snapshot snapshot) {
        if (snapshot == null) return new Usage(null, null, null, null, null, null);
        SessionUsage.Totals total = snapshot.total();
        return new Usage(total.inputTokens(), total.outputTokens(), total.cacheReadTokens(),
                total.cacheWriteTokens(), total.reportedRequests(), total.missingRequests());
    }

    private static Context context(ContextUsage value) {
        return value == null ? null : new Context(value.occupiedTokens(), value.windowTokens(), value.remainingPercent());
    }

    /** 组装 wire 载荷；字段名是版本化契约，重命名必须同步 schema 与 Node 校验。 */
    public ObjectNode toJson() {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("sessionId", sessionId);
        node.put("title", title);
        node.put("generation", Long.toString(generation));
        node.put("workspace", workspace);
        node.put("gitBranch", gitBranch);
        node.put("model", model);
        node.put("modelSource", modelSource);
        putNullable(node, "contextWindowTokens", contextWindowTokens);
        putNullable(node, "maxInputTokens", maxInputTokens);
        putNullable(node, "maxOutputTokens", maxOutputTokens);
        node.put("thinking", thinking);
        node.put("thinkingLabel", thinkingLabel);
        ArrayNode choices = node.putArray("thinkingChoices");
        thinkingChoices.forEach(choices::add);
        ObjectNode usageNode = node.putObject("usage");
        putNullable(usageNode, "inputTokens", usage.inputTokens());
        putNullable(usageNode, "outputTokens", usage.outputTokens());
        putNullable(usageNode, "cacheReadTokens", usage.cacheReadTokens());
        putNullable(usageNode, "cacheWriteTokens", usage.cacheWriteTokens());
        putNullable(usageNode, "reportedRequests", usage.reportedRequests());
        putNullable(usageNode, "missingRequests", usage.missingRequests());
        if (context == null) node.putNull("context");
        else {
            ObjectNode contextNode = node.putObject("context");
            putNullable(contextNode, "occupiedTokens", context.occupiedTokens());
            putNullable(contextNode, "windowTokens", context.windowTokens());
            putNullable(contextNode, "remainingPercent", context.remainingPercent());
        }
        if (permission == null) node.putNull("permission");
        else {
            ObjectNode permissionNode = node.putObject("permission");
            permissionNode.put("profile", permission.profile());
            permissionNode.put("label", permission.label());
            permissionNode.put("revision", Long.toString(permission.revision()));
            permissionNode.put("defaultProfile", permission.defaultProfile());
            ArrayNode rules = permissionNode.putArray("rules");
            permission.ruleDescriptions().forEach(rules::add);
            permissionNode.put("disabledDefaultCount", permission.disabledDefaultCount());
        }
        ArrayNode actorArray = node.putArray("actors");
        for (Actor actor : actors) {
            ObjectNode actorNode = actorArray.addObject();
            actorNode.put("id", actor.id());
            actorNode.put("state", actor.state());
        }
        node.put("teamActive", teamActive);
        ArrayNode mcpArray = node.putArray("mcp");
        for (Mcp server : mcp) {
            ObjectNode serverNode = mcpArray.addObject();
            serverNode.put("name", server.name());
            serverNode.put("enabled", server.enabled());
            serverNode.put("connected", server.connected());
            ArrayNode tools = serverNode.putArray("tools");
            server.tools().forEach(tools::add);
        }
        ObjectNode backgroundNode = node.putObject("background");
        background.forEach(backgroundNode::put);
        node.put("bufferSummary", bufferSummary);
        node.put("bufferState", bufferState);
        ObjectNode tuiNode = node.putObject("tui");
        tuiNode.put("theme", tui.theme());
        tuiNode.put("lightTheme", tui.lightTheme());
        tuiNode.put("darkTheme", tui.darkTheme());
        tuiNode.put("mode", tui.mode());
        tuiNode.put("fullscreenExitOutput", tui.fullscreenExitOutput());
        tuiNode.put("fullscreenScrollbar", tui.fullscreenScrollbar());
        tuiNode.put("fullscreenCopyOnSelect", tui.fullscreenCopyOnSelect());
        tuiNode.put("fullscreenWheelScrollLines", tui.fullscreenWheelScrollLines());
        tuiNode.put("showHardwareCursor", tui.showHardwareCursor());
        tuiNode.put("editorPaddingX", tui.editorPaddingX());
        tuiNode.put("outputPad", tui.outputPad());
        tuiNode.put("autocompleteMaxVisible", tui.autocompleteMaxVisible());
        tuiNode.put("thinkingVisible", tui.thinkingVisible());
        tuiNode.put("toolsExpanded", tui.toolsExpanded());
        tuiNode.put("commandSuggestions", tui.commandSuggestions());
        tuiNode.put("clearOnShrink", tui.clearOnShrink());
        tuiNode.put("showTerminalProgress", tui.showTerminalProgress());
        tuiNode.put("quietStartup", tui.quietStartup());
        tuiNode.put("codeBlockIndent", tui.codeBlockIndent());
        tuiNode.put("externalEditor", tui.externalEditor());
        tuiNode.put("escapeTimeoutMillis", tui.escapeTimeoutMillis());
        ObjectNode keysNode = tuiNode.putObject("keybindings");
        tui.keybindings().forEach((action, keys) -> {
            ArrayNode values = keysNode.putArray(action);
            keys.forEach(values::add);
        });
        tuiNode.put("showImages", tui.showImages());
        tuiNode.put("imageWidthCells", tui.imageWidthCells());
        tuiNode.put("mermaidRendering", tui.mermaidRendering());
        node.put("configRevision", Long.toString(configRevision));
        ObjectNode capabilitiesNode = node.putObject("capabilities");
        capabilitiesNode.put("thinking", capabilities.thinking());
        capabilitiesNode.put("permission", capabilities.permission());
        capabilitiesNode.put("resume", capabilities.resume());
        capabilitiesNode.put("mcp", capabilities.mcp());
        capabilitiesNode.put("externalEditor", capabilities.externalEditor());
        capabilitiesNode.put("browserImages", capabilities.browserImages());
        ObjectNode clocksNode = node.putObject("clocks");
        clocks.forEach((actor, tick) -> {
            ObjectNode clock = clocksNode.putObject(actor);
            clock.put("runId", tick.runId());
            clock.put("elapsedMs", tick.elapsedMs());
            clock.put("running", tick.running());
            clock.put("paused", tick.paused());
            clock.put("displayStopped", tick.displayStopped());
            clock.put("revision", Long.toString(tick.revision()));
        });
        return node;
    }

    private static void putNullable(ObjectNode node, String field, Long value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    private static void putNullable(ObjectNode node, String field, Integer value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }

    /** 工作目录与 git 分支的只读读取；不执行 git 命令、不修改仓库。 */
    static final class Paths {
        private Paths() { }

        static String workspace() {
            return java.nio.file.Path.of("").toAbsolutePath().normalize().toString();
        }

        /** 读 .git/HEAD：分支名不需要调用外部进程，失败时返回空串。 */
        static String gitBranch() {
            try {
                java.nio.file.Path head = java.nio.file.Path.of(".git", "HEAD");
                if (!java.nio.file.Files.isRegularFile(head)) return "";
                String text = java.nio.file.Files.readString(head).strip();
                return text.startsWith("ref: refs/heads/") ? text.substring("ref: refs/heads/".length()) : "";
            } catch (java.io.IOException | RuntimeException ignored) {
                return "";
            }
        }
    }

    /** 供 §显示层使用：权限档位的英文值。 */
    public static String profileValue(PermissionProfile profile) { return profile == null ? "" : profile.value(); }
}
