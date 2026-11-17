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
 * 采集会话 Display、用量、上下文、权限、Team、MCP 和白名单设置，形成前端使用的状态快照。toJson 显式生成协议字段，
 * 缺少报告的统计保持 null；增量事件与该快照共同驱动 Node 的业务显示。
 *
 * @param sessionId 持久会话 ID，用于关联本次事件与历史
 * @param title 用户选择的会话标题或当前界面条目的标题
 * @param generation 客户端看到的会话代次，切换会话后随新代次更新
 * @param workspace 会话绑定的真实工作区目录
 * @param gitBranch 当前工作区的 Git 分支名，无法读取时为空串
 * @param model 用于请求与用量归属的模型标识
 * @param modelSource 模型参数元数据的来源说明
 * @param contextWindowTokens 模型总上下文窗口 token 数，未知时为 null
 * @param maxInputTokens 模型已知的最大输入 token 数，未知时为 null
 * @param maxOutputTokens 模型已知的最大输出 token 数，未知时为 null
 * @param thinking 本 Agent 的思考设置或当前值来源
 * @param thinkingLabel 当前思考档位的界面名称
 * @param thinkingChoices 模型实际支持的思考档位列表
 * @param usage 模型报告或会话累计的 token 用量
 * @param context 当前模型上下文占用的展示快照
 * @param permission 会话当前权限快照；会话未打开时可为 null
 * @param actors Agent 身份到当前运行状态的映射
 * @param teamActive 当前 Team 是否处于活动状态
 * @param mcp MCP 服务开关与服务定义表
 * @param background 会话共用的后台命令管理器，负责编号、交付和停止
 * @param bufferSummary 当前用户缓冲的展示摘要
 * @param bufferState 当前缓冲处于 QUEUED 还是 INSERTION
 * @param tui 当前终端展示设置快照
 * @param configRevision 已保存的配置版本，供前端提交补丁时核对
 * @param capabilities 当前后端实际提供的能力开关
 * @param clocks 各 Agent 的轮次显示时钟快照
 * @param resources 按组保存的可展示资源名
 * @param team 共享的 Team 管理器，负责派工、报告和成员生命周期
 */
public record BackendSnapshot(String sessionId, String title, long generation, String workspace, String gitBranch,
        String model, String modelSource, Integer contextWindowTokens, Integer maxInputTokens, Integer maxOutputTokens,
        String thinking, String thinkingLabel, List<String> thinkingChoices,
        Usage usage, Context context, Permission permission, List<Actor> actors, boolean teamActive,
        List<Mcp> mcp, Map<String, String> background, String bufferSummary, String bufferState,
        Tui tui, long configRevision, Capabilities capabilities, Map<String, DisplayClock.Tick> clocks,
        Map<String, List<String>> resources, List<Map<String, Object>> team) {

    /**
     * 保存累计用量、请求报告数、缺报数和分组统计，null 表示尚无报告值。
     *
     * @param inputTokens 已报告的普通输入 token 总数
     * @param outputTokens 已报告的输出 token 总数
     * @param cacheReadTokens 缓存命中输入 token 总数
     * @param cacheWriteTokens 缓存创建输入 token 总数
     * @param reportedRequests 带完整用量报告的请求数量
     * @param missingRequests 结束时缺少用量报告的请求数量
     * @param byActor 按 Agent 身份分组的用量统计
     * @param byPurpose 按正式、摘要、审批、记忆等用途分组的统计
     * @param byModel 按模型名分组的用量统计
     * @param recentCacheHitPercent 最近一次 Leader 请求的缓存命中百分比；缺报时为 null
     */
    public record Usage(Long inputTokens, Long outputTokens, Long cacheReadTokens, Long cacheWriteTokens,
            Long reportedRequests, Long missingRequests, Map<String, SessionUsage.Totals> byActor,
            Map<String, SessionUsage.Totals> byPurpose, Map<String, SessionUsage.Totals> byModel,
            Integer recentCacheHitPercent) {
    }

    /**
     * 当前投影的占用及估算来源；window 未知时整体为 null。
     *
     * @param occupiedTokens 当前上下文占用 token 数；未知时为 null
     * @param windowTokens 模型上下文窗口 token 数；未知时为 null
     * @param remainingPercent 当前上下文剩余百分比；未知时为 null
     * @param estimated 当前占用值是否来自估算
     */
    public record Context(Long occupiedTokens, Integer windowTokens, Integer remainingPercent, Boolean estimated) {
        /**
         * 旧离线快照仍表示服务端来源。
         *
         * @param occupiedTokens 当前上下文占用 token 数；未知时为 null
         * @param windowTokens 模型上下文窗口 token 数；未知时为 null
         * @param remainingPercent 当前上下文剩余百分比；未知时为 null
         */
        public Context(Long occupiedTokens, Integer windowTokens, Integer remainingPercent) {
            this(occupiedTokens, windowTokens, remainingPercent, false);
        }
    }

    /**
     * 权限资料：档位、revision、默认档位与规则概要（不传完整规则/凭据）。
     *
     * @param profile 当前选择的权限档位
     * @param label 供终端显示的人类可读名称
     * @param revision 本次操作依据的版本号，用于读取后修改时核对状态
     * @param defaultProfile 下次启动会话时使用的默认权限档位
     * @param ruleDescriptions 用于界面展示的当前规则说明列表
     * @param disabledDefaultCount 当前停用的默认规则数量
     */
    public record Permission(String profile, String label, long revision, String defaultProfile,
            List<String> ruleDescriptions, int disabledDefaultCount) {
    }

    /**
     * 一个真实 Agent/队友的状态；单人会话的 leader 不会被渲染成 Team 身份。
     *
     * @param id 当前 Agent 或会话的稳定身份标识
     * @param state 该 Agent 当前的运行状态
     */
    public record Actor(String id, String state) {
    }

    /**
     * MCP 只读服务资料；不连接、不认证、不改配置。
     *
     * @param name 本次要查询或展示的工具、服务、资源或会话名称
     * @param enabled 是否启用此记忆、沙箱或 MCP 能力
     * @param connected 该 MCP 服务当前是否已连接
     * @param tools 本次可执行工具目录，模型定义与执行查找使用同一目录
     */
    public record Mcp(String name, boolean enabled, boolean connected, List<String> tools) {
    }

    /**
     * 列出后端实际装配的能力开关，供 Node 决定显示哪些操作入口。
     *
     * @param thinking 本 Agent 的思考设置或当前值来源
     * @param permission 会话当前权限快照；会话未打开时可为 null
     * @param resume 是否提供持久历史恢复能力
     * @param mcp MCP 服务开关与服务定义表
     * @param externalEditor 外部编辑器启动命令，空串表示未配置
     * @param browserImages 当前客户端是否支持图片展示
     */
    public record Capabilities(boolean thinking, boolean permission, boolean resume, boolean mcp,
            boolean externalEditor, boolean browserImages) {
    }

    /**
     * 已批准的 TUI 设置快照（18 个有效菜单字段 + 非菜单项）。
     *
     * @param theme 终端主题选择，空值使用 system
     * @param lightTheme 浅色环境下使用的主题名
     * @param darkTheme 深色环境下使用的主题名
     * @param mode 终端展示模式 fullscreen 或 regular
     * @param fullscreenExitOutput 全屏退出后的显示方式：transcript 或 resume-hint
     * @param fullscreenScrollbar 全屏滚动条策略：auto、always 或 hidden
     * @param fullscreenCopyOnSelect 全屏选择文字后是否自动复制
     * @param fullscreenWheelScrollLines 滚轮每次滚动行数，0 表示自动
     * @param showHardwareCursor 是否显示终端硬件光标
     * @param editorPaddingX 输入编辑区左右内边距，单位字符格
     * @param outputPad 输出区域上下留白行数
     * @param autocompleteMaxVisible 补全菜单同时显示的最大候选数
     * @param thinkingVisible 是否在终端展示模型思考内容
     * @param toolsExpanded 是否默认展开工具卡片
     * @param commandSuggestions 是否显示命令建议
     * @param clearOnShrink 终端缩小时是否清屏重绘
     * @param showTerminalProgress 是否发送终端进度状态
     * @param quietStartup 是否简化启动提示
     * @param codeBlockIndent 代码块显示时使用的缩进文本
     * @param externalEditor 外部编辑器启动命令，空串表示未配置
     * @param escapeTimeoutMillis Escape 序列解析等待毫秒数，0 表示自动
     * @param keybindings 用户自定义的动作到按键绑定
     * @param showImages 是否启用图片展示
     * @param imageWidthCells 图片显示宽度，单位终端字符格
     * @param mermaidRendering Mermaid 展示模式：streaming、off 或 final
     */
    public record Tui(String theme, String lightTheme, String darkTheme, String mode, String fullscreenExitOutput,
            String fullscreenScrollbar, boolean fullscreenCopyOnSelect, int fullscreenWheelScrollLines,
            boolean showHardwareCursor, int editorPaddingX, int outputPad, int autocompleteMaxVisible,
            boolean thinkingVisible, boolean toolsExpanded, boolean commandSuggestions, boolean clearOnShrink,
            boolean showTerminalProgress, boolean quietStartup, String codeBlockIndent, String externalEditor,
            int escapeTimeoutMillis, Map<String, List<String>> keybindings,
            boolean showImages, int imageWidthCells, String mermaidRendering) {
    }

    /**
     * 组合一份快照。
     *
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     * @param display 会话公开资料；未打开会话时为 null
     * @param permission 权限快照；未打开会话时为 null
     * @param settings 本次启动采用的设置
     * @param configRevision 已持久化的配置版本，Node 用它拒绝陈旧 patch
     * @param mcp MCP 服务开关与服务定义表
     * @param actors Agent 身份到当前运行状态的映射
     * @param bufferSummary 当前用户缓冲的展示摘要
     * @param bufferState 当前缓冲处于 QUEUED 还是 INSERTION
     * @param background 会话共用的后台命令管理器，负责编号、交付和停止
     * @param clocks 各 Agent 的轮次显示时钟快照
     * @return 组合当前会话、权限、设置与运行事实的展示快照
     */
    public static BackendSnapshot capture(long generation, SessionRuntime.Display display,
            PermissionState.Snapshot permission,
            JadeSettings settings, long configRevision, List<McpManager.ServerSnapshot> mcp,
            Map<String, String> actors, String bufferSummary, String bufferState,
            Map<String, String> background, Map<String, DisplayClock.Tick> clocks) {
        JadeSettings.Tui tui = settings.tui();
        Usage usage = display == null ? usage(null) : usage(display.usage());
        Context context = display == null ? null : context(display.context());
        Permission permissionView = permission == null ? null
                : new Permission(permission.profile().value(), permission.profile().label(), permission.revision(),
                        settings.permissions().defaultProfile().value(),
                        permission.rules().stream().map(rule -> rule.description()).toList(),
                        permission.disabledDefaults().size());
        List<Actor> actorList = actors.entrySet().stream().map(e -> new Actor(e.getKey(), e.getValue())).toList();
        boolean teamActive = actors.keySet().stream().anyMatch(id -> id.startsWith("agent_")
                && !id.contains("/") && !"CLOSED".equals(actors.get(id)));
        List<Mcp> servers = mcp == null ? List.of()
                : mcp.stream()
                        .map(server -> new Mcp(server.name(), server.enabled(), server.connected(), server.tools()))
                        .toList();
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
                Map.copyOf(clocks == null ? Map.of() : clocks), display == null ? Map.of() : display.resources(),
                display == null ? List.of() : display.team());
    }

    /**
     * 把会话用量快照转换为界面统计，保留总量及按 Agent、用途、模型的分组。
     * 最近 Leader 请求有完整输入量时计算缓存命中百分比，否则命中率为空；无快照时返回各项为空的统计。
     *
     * @param snapshot 包含累计值、分组统计和最近 Leader 请求报告的用量快照
     * @return 含累计值、分组和可选缓存命中率的用量展示资料
     */
    private static Usage usage(SessionUsage.Snapshot snapshot) {
        if (snapshot == null)
            return new Usage(null, null, null, null, null, null, Map.of(), Map.of(), Map.of(), null);
        SessionUsage.Totals total = snapshot.total();
        var recent = snapshot.recentLeaderUsage();
        // Anthropic 普通 input 不含缓存读写，分母必须是同一次请求的完整输入；0/缺报都隐藏。
        Integer hit = recent == null || recent.totalInputTokens() == 0 ? null
                : (int) Math.round(100.0 * recent.cacheReadInputTokens() / recent.totalInputTokens());
        return new Usage(total.inputTokens(), total.outputTokens(), total.cacheReadTokens(),
                total.cacheWriteTokens(), total.reportedRequests(), total.missingRequests(),
                snapshot.byActor(), snapshot.byPurpose(), snapshot.byModel(), hit);
    }

    /**
     * 将上下文占用转换为界面所需的已用 token、窗口、剩余百分比和估算标记；输入为空时返回 null。
     *
     * @param value 要写入的可选统计数值或当前上下文用量；缺报保留 null
     * @return 上下文占用展示资料；原用量为 null 时为 null
     */
    private static Context context(ContextUsage value) {
        return value == null ? null
                : new Context(value.occupiedTokens(), value.windowTokens(), value.remainingPercent(),
                        value.estimated());
    }

    /**
     * 组装 wire 载荷；字段名是版本化契约，重命名必须同步 schema 与 Node 校验。
     *
     * @return 字段名与协议 schema 对应的完整 JSON 状态载荷
     */
    public ObjectNode toJson() {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("sessionId", sessionId);
        node.put("title", title);
        node.put("generation", Long.toString(generation));
        node.put("workspace", workspace);
        node.put("gitBranch", gitBranch);
        node.put("model", model);
        node.put("modelSource", modelSource);
        node.set("resources", Json.mapper().valueToTree(resources));
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
        putNullable(usageNode, "recentCacheHitPercent", usage.recentCacheHitPercent());
        // 分类来自 Java 的同一账本；Node 只投影，不能把分类再加回总数。
        usageNode.set("byActor", Json.mapper().valueToTree(usage.byActor()));
        usageNode.set("byPurpose", Json.mapper().valueToTree(usage.byPurpose()));
        usageNode.set("byModel", Json.mapper().valueToTree(usage.byModel()));
        if (context == null)
            node.putNull("context");
        else {
            ObjectNode contextNode = node.putObject("context");
            putNullable(contextNode, "occupiedTokens", context.occupiedTokens());
            putNullable(contextNode, "windowTokens", context.windowTokens());
            putNullable(contextNode, "remainingPercent", context.remainingPercent());
            contextNode.put("estimated", Boolean.TRUE.equals(context.estimated()));
        }
        if (permission == null)
            node.putNull("permission");
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
        node.set("team", Json.mapper().valueToTree(team));
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

    /**
     * 将可选长整数写入 JSON；值为空时保留显式 null，供界面区分未报告与真实零值。
     *
     * @param node 当前读取或写入的 JSON 节点
     * @param field 本次读取或写入的 JSON 字段名
     * @param value 要写入的可选统计数值或当前上下文用量；缺报保留 null
     */
    private static void putNullable(ObjectNode node, String field, Long value) {
        if (value == null)
            node.putNull(field);
        else
            node.put(field, value);
    }

    /**
     * 将可选整数写入 JSON；值为空时保留显式 null，供界面区分未报告与真实零值。
     *
     * @param node 当前读取或写入的 JSON 节点
     * @param field 本次读取或写入的 JSON 字段名
     * @param value 要写入的可选统计数值或当前上下文用量；缺报保留 null
     */
    private static void putNullable(ObjectNode node, String field, Integer value) {
        if (value == null)
            node.putNull(field);
        else
            node.put(field, value);
    }

    /**
     * 工作目录与 git 分支的只读读取；不执行 git 命令、不修改仓库。
     */
    static final class Paths {
        /**
         * 汇集状态快照使用的当前工作目录定位方法。
         */
        private Paths() {
        }

        /**
         * 返回当前进程工作目录的规范化绝对路径字符串，供缺少会话显示信息时填入快照。
         *
         * @return 当前进程工作目录的规范化绝对路径文本
         */
        static String workspace() {
            return java.nio.file.Path.of("").toAbsolutePath().normalize().toString();
        }

        /**
         * 读 .git/HEAD：分支名不需要调用外部进程，失败时返回空串。
         *
         * @return Git 分支名；无法读取时为空串
         */
        static String gitBranch() {
            try {
                java.nio.file.Path head = java.nio.file.Path.of(".git", "HEAD");
                if (!java.nio.file.Files.isRegularFile(head))
                    return "";
                String text = java.nio.file.Files.readString(head).strip();
                return text.startsWith("ref: refs/heads/") ? text.substring("ref: refs/heads/".length()) : "";
            } catch (java.io.IOException | RuntimeException ignored) {
                return "";
            }
        }
    }

    /**
     * 供 §显示层使用：权限档位的英文值。
     *
     * @param profile 当前选择的权限档位
     * @return 当前权限档位的稳定英文值
     */
    public static String profileValue(PermissionProfile profile) {
        return profile == null ? "" : profile.value();
    }
}
