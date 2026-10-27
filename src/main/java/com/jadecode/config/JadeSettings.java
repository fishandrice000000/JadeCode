package com.jadecode.config;

import com.jadecode.context.CompactionLimits;
import com.jadecode.context.CompactionPolicy;
import com.jadecode.llm.ModelMetadata.Limits;
import java.util.Map;

/**
 * 一次启动采用的非秘密配置快照。前端与运行装配共享它，不在每个请求中反复读配置文件。
 * 模型、认证和 endpoint 继续由 AppConfig 管理，不因统一 UI／压缩设置而搬动凭据。
 */
public record JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits, Memory memory, Models modelMetadata,
        com.jadecode.llm.ThinkingSettings thinking, Permissions permissions, Mcp mcp,
        com.jadecode.context.ProductionCompactionSettings contextCompaction) {
    /** 兼容显式离线装配，旧字符阈值不被解释成生产 Token 预算。 */
    public JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits, Memory memory, Models models,
            com.jadecode.llm.ThinkingSettings thinking, Permissions permissions, Mcp mcp) {
        this(tui, compaction, limits, memory, models, thinking, permissions, mcp,
                com.jadecode.context.ProductionCompactionSettings.defaults().withAutomatic(compaction.autoSummaryEnabled()));
    }
    /** 旧配置和离线装配同样提供默认 Fetch；只登记入口，不启动外部程序。 */
    public JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits, Memory memory, Models models,
            com.jadecode.llm.ThinkingSettings thinking, Permissions permissions) {
        this(tui, compaction, limits, memory, models, thinking, permissions, Mcp.defaults());
    }
    /** 旧装配保持 Workspace 默认，不因新增配置而读取用户文件。 */
    public JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits, Memory memory, Models models,
            com.jadecode.llm.ThinkingSettings thinking) {
        this(tui, compaction, limits, memory, models, thinking, new Permissions(com.jadecode.safety.PermissionProfile.WORKSPACE, java.util.List.of(), java.util.Set.of()));
    }
    /** 老配置/离线装配缺少思考设置时不改变服务商默认行为。 */
    public JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits, Memory memory, Models modelMetadata) {
        this(tui, compaction, limits, memory, modelMetadata, com.jadecode.llm.ThinkingSettings.DEFAULT);
    }
    public JadeSettings {
        java.util.Objects.requireNonNull(thinking, "thinking"); java.util.Objects.requireNonNull(permissions, "permissions");
        java.util.Objects.requireNonNull(mcp, "mcp");
        java.util.Objects.requireNonNull(contextCompaction, "contextCompaction");
    }

    /** 非秘密服务配置；显式空表关闭所有 MCP 入口，不影响普通工具或 Java 构建。 */
    public record Mcp(Map<String, com.jadecode.mcp.McpServerConfig> servers) {
        /** 固定本次启动采用的配置；后续连接不能读取模型提供的替代 command。 */
        public Mcp {
            servers = Map.copyOf(servers);
            if (servers.size() > 16 || servers.keySet().stream().anyMatch(n -> n.isBlank() || n.length() > 128))
                throw new IllegalArgumentException("Invalid MCP server count or name");
        }

        /**
         * 默认开放 Fetch 的连接入口，安装路径来自全局用户根，不依赖当前项目。
         * 此处只创建配置数据；环境未安装时由连接工具报告安装指引，不能在启动时自动下载。
         * 用户显式提供 servers 表时整表替换，因此空表或 enabled=false 仍能关闭服务。
         */
        public static Mcp defaults() {
            return new Mcp(Map.of("fetch", new com.jadecode.mcp.McpServerConfig(true,
                    JadePaths.defaults().fetchEnvironment().resolve("bin/python").toString(), java.util.List.of("-m", "mcp_server_fetch"), "",
                    15, 30, 20_000, java.util.Set.of("fetch"))));
        }
    }
    /** 启动默认与长期规则；不能在模型参数中伪造这份配置。 */
    public record Permissions(com.jadecode.safety.PermissionProfile defaultProfile,
            java.util.List<com.jadecode.safety.rules.PermissionRule> rules, java.util.Set<String> disabledDefaults) {
        public Permissions {
            java.util.Objects.requireNonNull(defaultProfile); rules = java.util.List.copyOf(rules); disabledDefaults = java.util.Set.copyOf(disabledDefaults);
            if (!com.jadecode.safety.rules.DefaultPermissionRules.IDS.containsAll(disabledDefaults)) throw new IllegalArgumentException("Unknown default permission rule id");
            if (rules.stream().map(com.jadecode.safety.rules.PermissionRule::id).distinct().count() != rules.size()) throw new IllegalArgumentException("Duplicate permission rule id");
        }
        public com.jadecode.safety.PermissionState state(com.jadecode.safety.rules.PermissionRuleStore store) {
            return new com.jadecode.safety.PermissionState(defaultProfile, rules, disabledDefaults, store);
        }
    }
    /** 兼容已有离线装配；未指定记忆设置时使用相同默认值。 */
    public JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits) {
        this(tui, compaction, limits, new Memory(8000));
    }
    /** 不指定参数发现设置的旧调用仍使用默认值；离线工厂不会因此自行联网。 */
    public JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits, Memory memory) {
        this(tui, compaction, limits, memory, new Models(true, 24, 2000, Map.of()));
    }
    /**
     * 非秘密 TUI 设置快照。旧四字段构造继续有效；新增字段都有兼容默认值。主题、模式、
     * 图片/候选等只影响显示，不改变 Agent/压缩/认证策略。{@code keybindings} 为动作 ID→
     * 键列表的局部覆盖；{@code externalEditor} 为未配置空串。
     */
    public record Tui(String theme, String mode, String fullscreenExitOutput, String fullscreenScrollbar,
            boolean fullscreenCopyOnSelect, int fullscreenWheelScrollLines, boolean showHardwareCursor,
            int editorPaddingX, int outputPad, int autocompleteMaxVisible, boolean thinkingVisible,
            boolean toolsExpanded, boolean commandSuggestions, String mermaidRendering, boolean showImages,
            int imageWidthCells, boolean clearOnShrink, boolean showTerminalProgress, boolean quietStartup,
            String codeBlockIndent, String externalEditor, java.util.Map<String, java.util.List<String>> keybindings,
            int escapeTimeoutMillis, String lightTheme, String darkTheme) {
        public Tui {
            theme = theme == null || theme.isBlank() ? "system" : theme;
            mode = mode == null || mode.isBlank() ? "fullscreen" : mode;
            if (!mode.equals("fullscreen") && !mode.equals("regular"))
                throw new IllegalArgumentException("tui.mode must be fullscreen or regular");
            fullscreenExitOutput = fullscreenExitOutput == null || fullscreenExitOutput.isBlank() ? "transcript" : fullscreenExitOutput;
            if (!fullscreenExitOutput.equals("transcript") && !fullscreenExitOutput.equals("resume-hint"))
                throw new IllegalArgumentException("tui.fullscreenExitOutput must be transcript or resume-hint");
            fullscreenScrollbar = fullscreenScrollbar == null || fullscreenScrollbar.isBlank() ? "auto" : fullscreenScrollbar;
            if (!java.util.Set.of("auto", "always", "hidden").contains(fullscreenScrollbar))
                throw new IllegalArgumentException("tui.fullscreenScrollbar must be auto, always or hidden");
            if (fullscreenWheelScrollLines < 0 || fullscreenWheelScrollLines > 100)
                throw new IllegalArgumentException("tui.fullscreenWheelScrollLines must be auto (0) or 1-100");
            if (editorPaddingX < 0 || editorPaddingX > 3) throw new IllegalArgumentException("tui.editorPaddingX must be 0-3");
            if (outputPad < 0 || outputPad > 1) throw new IllegalArgumentException("tui.outputPad must be 0 or 1");
            if (autocompleteMaxVisible < 3 || autocompleteMaxVisible > 20)
                throw new IllegalArgumentException("tui.autocompleteMaxVisible must be 3-20");
            mermaidRendering = mermaidRendering == null || mermaidRendering.isBlank() ? "streaming" : mermaidRendering;
            if (!java.util.Set.of("streaming", "off", "final").contains(mermaidRendering))
                throw new IllegalArgumentException("tui.mermaidRendering must be streaming, off or final");
            if (imageWidthCells < 1) throw new IllegalArgumentException("tui.imageWidthCells must be positive");
            codeBlockIndent = codeBlockIndent == null ? "  " : codeBlockIndent;
            externalEditor = externalEditor == null ? "" : externalEditor;
            keybindings = keybindings == null ? java.util.Map.of() : java.util.Map.copyOf(keybindings);
            if (escapeTimeoutMillis < 0) throw new IllegalArgumentException("tui.escapeTimeoutMillis must be positive or 0 for auto");
            lightTheme = lightTheme == null || lightTheme.isBlank() ? "light" : lightTheme;
            darkTheme = darkTheme == null || darkTheme.isBlank() ? "dark" : darkTheme;
        }

        /** 旧四字段配置的兼容构造；其余字段取 Pi/Jade 批准默认值。 */
        public Tui(String theme, boolean thinkingVisible, boolean toolsExpanded, boolean commandSuggestions) {
            this(theme, "fullscreen", "transcript", "auto", true, 0, false, 0, 1,
                    5, thinkingVisible, toolsExpanded, commandSuggestions, "streaming", true, 60,
                    false, false, false, "  ", "", java.util.Map.of(), 0, "light", "dark");
        }

        /** @return 是否安静启动。 */
        public boolean quietStartup() { return quietStartup; }
    }

    /**
     * 记忆辅助请求的独立额度；token 是输出上限，char 是 UTF-16 输入预算，不改变正式请求。
     * enabled=false 只停用自动召回/提取/整理，不删除已有知识；缺省保持启用。
     */
    public record Memory(boolean enabled, int extractionMaxOutputTokens, int consolidationMaxOutputTokens, int consolidationInputChars) {
        /** 只指定提取额度时，为整理输出和输入预算采用默认值。 */
        public Memory(int extractionMaxOutputTokens) { this(true, extractionMaxOutputTokens, 16384, 128000); }
        public Memory {
            if (extractionMaxOutputTokens < 1 || consolidationMaxOutputTokens < 1 || consolidationInputChars < 1)
                throw new IllegalArgumentException("memory output and input budgets must be positive integers");
        }
    }

    /**
     * 参数发现只在生产启动执行；overrides 按精确模型 ID 区分，换模型后不套用旧覆盖。
     * 手动值只影响限额，不选择 provider、模型或认证。映射复制后不允许运行中修改。
     */
    public record Models(boolean autoDiscover, int cacheTtlHours, int timeoutMillis, Map<String, Limits> overrides) {
        public Models {
            if (cacheTtlHours < 1 || timeoutMillis < 1) throw new IllegalArgumentException("Model cache TTL and timeout must be positive integers");
            overrides = Map.copyOf(overrides);
        }
    }

    /** 无配置文件或某项未填写时使用；离线装配也从此处取得与机器无关的默认值。 */
    public static JadeSettings defaults() {
        return new JadeSettings(new Tui("system", true, false, true), CompactionPolicy.defaults(), CompactionLimits.defaults());
    }
}
