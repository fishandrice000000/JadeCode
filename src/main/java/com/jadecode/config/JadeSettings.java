package com.jadecode.config;

import com.jadecode.context.CompactionLimits;
import com.jadecode.context.CompactionPolicy;
import com.jadecode.llm.ModelMetadata.Limits;
import java.util.Map;

/**
 * 一次启动采用的非秘密配置快照。前端与运行装配共享它，不在每个请求中反复读配置文件。
 * 模型、认证和 endpoint 继续由 AppConfig 管理，不因统一 UI／压缩设置而搬动凭据。
 *
 * @param tui 当前终端展示设置快照
 * @param compaction 当前压缩实现或限制配置
 * @param limits 当前压缩流程使用的预算与保留限制
 * @param memory 当前会话的长期记忆入口，负责参考、候选笔记和提取作业
 * @param modelMetadata 模型限额发现和手动覆盖配置
 * @param thinking 本 Agent 的思考设置或当前值来源
 * @param permissions 会话与工具检查器共用的权限状态
 * @param mcp MCP 服务开关与服务定义表
 * @param contextCompaction 当前上下文压缩阈值、预留和重试配置
 * @param sandbox Docker 沙箱的镜像、资源和闲置回收配置
 */
public record JadeSettings(Tui tui, CompactionPolicy compaction, CompactionLimits limits, Memory memory,
        Models modelMetadata,
        com.jadecode.llm.ThinkingSettings thinking, Permissions permissions, Mcp mcp,
        com.jadecode.context.ProductionCompactionSettings contextCompaction,
        com.jadecode.sandbox.SandboxSettings sandbox) {

    /**
     * 检查思考档位、权限、MCP、压缩和沙箱配置均已提供，将它们组成一次应用设置快照；
     * 必需项缺失时抛出 NullPointerException。
     *
     * @param tui 当前终端展示设置快照
     * @param compaction 当前压缩实现或限制配置
     * @param limits 当前压缩流程使用的预算与保留限制
     * @param memory 当前会话的长期记忆入口，负责参考、候选笔记和提取作业
     * @param modelMetadata 模型限额发现和手动覆盖配置
     * @param thinking 本 Agent 的思考设置或当前值来源
     * @param permissions 会话与工具检查器共用的权限状态
     * @param mcp MCP 服务开关与服务定义表
     * @param contextCompaction 当前上下文压缩阈值、预留和重试配置
     * @param sandbox Docker 沙箱的镜像、资源和闲置回收配置
     * @throws NullPointerException thinking、permissions、mcp、contextCompaction、sandbox 为 null
     */
    public JadeSettings {
        java.util.Objects.requireNonNull(thinking, "thinking");
        java.util.Objects.requireNonNull(permissions, "permissions");
        java.util.Objects.requireNonNull(mcp, "mcp");
        java.util.Objects.requireNonNull(contextCompaction, "contextCompaction");
        java.util.Objects.requireNonNull(sandbox, "sandbox");
    }

    /**
     * 非秘密服务配置；显式空表关闭所有 MCP 入口，不影响普通工具或 Java 构建。
     *
     * @param servers MCP 服务名到可信启动定义的配置表
     */
    public record Mcp(Map<String, com.jadecode.mcp.McpServerConfig> servers) {
        /**
         * 固定本次启动采用的配置；后续连接不能读取模型提供的替代 command。
         *
         * @param servers MCP 服务名到可信启动定义的配置表
         * @throws IllegalArgumentException 配置中的 MCP 服务名不满足命名要求或与定义身份不一致
         */
        public Mcp {
            servers = Map.copyOf(servers);
            if (servers.size() > 16 || servers.keySet().stream().anyMatch(n -> n.isBlank() || n.length() > 128))
                throw new IllegalArgumentException("Invalid MCP server count or name");
        }

        /**
         * 默认开放 Fetch 的连接入口，安装路径来自全局用户根，不依赖当前项目。
         * 此处只创建配置数据；环境未安装时由连接工具报告安装指引，不能在启动时自动下载。
         * 用户显式提供 servers 表时整表替换，因此空表或 enabled=false 仍能关闭服务。
         *
         * @return 启用 Fetch 发现入口的默认服务配置
         */
        public static Mcp defaults() {
            return new Mcp(Map.of("fetch", new com.jadecode.mcp.McpServerConfig(true,
                    JadePaths.defaults().fetchEnvironment().resolve("bin/python").toString(),
                    java.util.List.of("-m", "mcp_server_fetch"), "",
                    15, 30, 20_000, java.util.Set.of("fetch"), true)));
        }
    }

    /**
     * 启动默认与长期规则；不能在模型参数中伪造这份配置。
     *
     * @param defaultProfile 下次启动会话时使用的默认权限档位
     * @param rules 用户配置的有序权限规则列表
     * @param disabledDefaults 停用的内置默认规则 ID 集合
     */
    public record Permissions(com.jadecode.safety.PermissionProfile defaultProfile,
            java.util.List<com.jadecode.safety.rules.PermissionRule> rules, java.util.Set<String> disabledDefaults) {
        /**
         * 将权限规则和停用的默认规则 ID 复制为不可变集合，校验默认档位、规则 ID 唯一性及停用 ID 的有效性。
         * 非法规则配置抛出 IllegalArgumentException。
         *
         * @param defaultProfile 下次启动会话时使用的默认权限档位
         * @param rules 用户配置的有序权限规则列表
         * @param disabledDefaults 停用的内置默认规则 ID 集合
         * @throws IllegalArgumentException 停用默认规则 ID 未知，或用户规则 ID 重复
         * @throws NullPointerException defaultProfile 为 null
         */
        public Permissions {
            java.util.Objects.requireNonNull(defaultProfile);
            rules = java.util.List.copyOf(rules);
            disabledDefaults = java.util.Set.copyOf(disabledDefaults);
            if (!com.jadecode.safety.rules.DefaultPermissionRules.IDS.containsAll(disabledDefaults))
                throw new IllegalArgumentException("Unknown default permission rule id");
            if (rules.stream().map(com.jadecode.safety.rules.PermissionRule::id).distinct().count() != rules.size())
                throw new IllegalArgumentException("Duplicate permission rule id");
        }

        /**
         * 用默认档位、已保存规则和停用项创建会话权限状态，并接入 store 保存后续规则修改。返回独立的运行时状态对象。
         *
         * @param store 当前操作使用的持久存储入口
         * @return 沿用当前配置并绑定规则存储的独立权限状态
         */
        public com.jadecode.safety.PermissionState state(com.jadecode.safety.rules.PermissionRuleStore store) {
            return new com.jadecode.safety.PermissionState(defaultProfile, rules, disabledDefaults, store);
        }
    }

    /**
     * 非秘密 TUI 设置快照。所有字段在装配时明确提供；读取配置时保留正常默认值。主题、模式、
     * 图片/候选等只影响显示，不改变 Agent/压缩/认证策略。{@code keybindings} 为动作 ID→
     * 键列表的局部覆盖；{@code externalEditor} 为未配置空串。
     *
     * @param theme 终端主题选择，空值使用 system
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
     * @param mermaidRendering Mermaid 展示模式：streaming、off 或 final
     * @param showImages 是否启用图片展示
     * @param imageWidthCells 图片显示宽度，单位终端字符格
     * @param clearOnShrink 终端缩小时是否清屏重绘
     * @param showTerminalProgress 是否发送终端进度状态
     * @param quietStartup 是否简化启动提示
     * @param codeBlockIndent 代码块显示时使用的缩进文本
     * @param externalEditor 外部编辑器启动命令，空串表示未配置
     * @param keybindings 用户自定义的动作到按键绑定
     * @param escapeTimeoutMillis Escape 序列解析等待毫秒数，0 表示自动
     * @param lightTheme 浅色环境下使用的主题名
     * @param darkTheme 深色环境下使用的主题名
     */
    public record Tui(String theme, String mode, String fullscreenExitOutput, String fullscreenScrollbar,
            boolean fullscreenCopyOnSelect, int fullscreenWheelScrollLines, boolean showHardwareCursor,
            int editorPaddingX, int outputPad, int autocompleteMaxVisible, boolean thinkingVisible,
            boolean toolsExpanded, boolean commandSuggestions, String mermaidRendering, boolean showImages,
            int imageWidthCells, boolean clearOnShrink, boolean showTerminalProgress, boolean quietStartup,
            String codeBlockIndent, String externalEditor, java.util.Map<String, java.util.List<String>> keybindings,
            int escapeTimeoutMillis, String lightTheme, String darkTheme) {
        /**
         * 补齐主题、终端模式、退出显示、滚动条和 Mermaid 渲染的缺省值，统一空编辑器配置并复制键位表。
         * 随后校验模式枚举及滚动行数、边距、补全数量、图片宽度和按键超时范围；
         * 配置值超出范围时抛出 IllegalArgumentException。
         *
         * @param theme 终端主题选择，空值使用 system
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
         * @param mermaidRendering Mermaid 展示模式：streaming、off 或 final
         * @param showImages 是否启用图片展示
         * @param imageWidthCells 图片显示宽度，单位终端字符格
         * @param clearOnShrink 终端缩小时是否清屏重绘
         * @param showTerminalProgress 是否发送终端进度状态
         * @param quietStartup 是否简化启动提示
         * @param codeBlockIndent 代码块显示时使用的缩进文本
         * @param externalEditor 外部编辑器启动命令，空串表示未配置
         * @param keybindings 用户自定义的动作到按键绑定
         * @param escapeTimeoutMillis Escape 序列解析等待毫秒数，0 表示自动
         * @param lightTheme 浅色环境下使用的主题名
         * @param darkTheme 深色环境下使用的主题名
         * @throws IllegalArgumentException 终端模式、退出方式、滚动条、渲染模式或尺寸数量超出支持范围
         */
        public Tui {
            theme = theme == null || theme.isBlank() ? "system" : theme;
            mode = mode == null || mode.isBlank() ? "fullscreen" : mode;
            if (!mode.equals("fullscreen") && !mode.equals("regular"))
                throw new IllegalArgumentException("tui.mode must be fullscreen or regular");
            fullscreenExitOutput = fullscreenExitOutput == null || fullscreenExitOutput.isBlank() ? "transcript"
                    : fullscreenExitOutput;
            if (!fullscreenExitOutput.equals("transcript") && !fullscreenExitOutput.equals("resume-hint"))
                throw new IllegalArgumentException("tui.fullscreenExitOutput must be transcript or resume-hint");
            fullscreenScrollbar = fullscreenScrollbar == null || fullscreenScrollbar.isBlank() ? "auto"
                    : fullscreenScrollbar;
            if (!java.util.Set.of("auto", "always", "hidden").contains(fullscreenScrollbar))
                throw new IllegalArgumentException("tui.fullscreenScrollbar must be auto, always or hidden");
            if (fullscreenWheelScrollLines < 0 || fullscreenWheelScrollLines > 100)
                throw new IllegalArgumentException("tui.fullscreenWheelScrollLines must be auto (0) or 1-100");
            if (editorPaddingX < 0 || editorPaddingX > 3)
                throw new IllegalArgumentException("tui.editorPaddingX must be 0-3");
            if (outputPad < 0 || outputPad > 1)
                throw new IllegalArgumentException("tui.outputPad must be 0 or 1");
            if (autocompleteMaxVisible < 3 || autocompleteMaxVisible > 20)
                throw new IllegalArgumentException("tui.autocompleteMaxVisible must be 3-20");
            mermaidRendering = mermaidRendering == null || mermaidRendering.isBlank() ? "streaming" : mermaidRendering;
            if (!java.util.Set.of("streaming", "off", "final").contains(mermaidRendering))
                throw new IllegalArgumentException("tui.mermaidRendering must be streaming, off or final");
            if (imageWidthCells < 1)
                throw new IllegalArgumentException("tui.imageWidthCells must be positive");
            codeBlockIndent = codeBlockIndent == null ? "  " : codeBlockIndent;
            externalEditor = externalEditor == null ? "" : externalEditor;
            keybindings = keybindings == null ? java.util.Map.of() : java.util.Map.copyOf(keybindings);
            if (escapeTimeoutMillis < 0)
                throw new IllegalArgumentException("tui.escapeTimeoutMillis must be positive or 0 for auto");
            lightTheme = lightTheme == null || lightTheme.isBlank() ? "light" : lightTheme;
            darkTheme = darkTheme == null || darkTheme.isBlank() ? "dark" : darkTheme;
        }

        /**
         * 读取启动提示是否使用精简模式，供终端启动时选择展示内容。
         *
         * @return 是否安静启动。
         */
        public boolean quietStartup() {
            return quietStartup;
        }
    }

    /**
     * 记忆辅助请求的独立额度；token 是输出上限，char 是 UTF-16 输入预算，不改变正式请求。
     * enabled=false 只停用自动召回/提取/整理，不删除已有知识；缺省保持启用。
     *
     * @param enabled 是否启用长期记忆维护
     * @param extractionMaxOutputTokens 单次经验提取允许的最大输出 token 数
     * @param consolidationMaxOutputTokens 单次知识整理允许的最大输出 token 数
     * @param consolidationInputChars 知识整理输入和证据读取的字符预算
     */
    public record Memory(boolean enabled, int extractionMaxOutputTokens, int consolidationMaxOutputTokens,
            int consolidationInputChars) {

        /**
         * 校验提取输出 token、整理输出 token 和整理输入字符预算均为正数，供后台维护分配请求额度；
         * 预算非法时抛出 IllegalArgumentException。
         *
         * @param enabled 是否启用长期记忆维护
         * @param extractionMaxOutputTokens 单次经验提取允许的最大输出 token 数
         * @param consolidationMaxOutputTokens 单次知识整理允许的最大输出 token 数
         * @param consolidationInputChars 知识整理输入和证据读取的字符预算
         * @throws IllegalArgumentException 提取输出、整理输出或输入字符预算不是正数
         */
        public Memory {
            if (extractionMaxOutputTokens < 1 || consolidationMaxOutputTokens < 1 || consolidationInputChars < 1)
                throw new IllegalArgumentException("memory output and input budgets must be positive integers");
        }
    }

    /**
     * 参数发现只在生产启动执行；overrides 按精确模型 ID 区分，换模型后不套用旧覆盖。
     * 手动值只影响限额，不选择 provider、模型或认证。映射复制后不允许运行中修改。
     *
     * @param autoDiscover 是否自动发现当前模型的元数据
     * @param cacheTtlHours 模型元数据缓存有效时长，单位小时
     * @param timeoutMillis 查询模型元数据的等待上限，单位毫秒
     * @param overrides 按模型名保存的手动限额覆盖值
     */
    public record Models(boolean autoDiscover, int cacheTtlHours, int timeoutMillis, Map<String, Limits> overrides) {
        /**
         * 校验模型元数据缓存时长和查询超时为正数，将手动模型覆盖表复制为不可变映射。
         * 非法时长抛出 IllegalArgumentException。
         *
         * @param autoDiscover 是否自动发现当前模型的元数据
         * @param cacheTtlHours 模型元数据缓存有效时长，单位小时
         * @param timeoutMillis 查询模型元数据的等待上限，单位毫秒
         * @param overrides 按模型名保存的手动限额覆盖值
         * @throws IllegalArgumentException 元数据缓存时长或请求超时不是正数
         */
        public Models {
            if (cacheTtlHours < 1 || timeoutMillis < 1)
                throw new IllegalArgumentException("Model cache TTL and timeout must be positive integers");
            overrides = Map.copyOf(overrides);
        }
    }

    /**
     * 程序化离线装配的默认值；正式配置解析为 sandbox 单独采用启用的生产默认。
     *
     * @return 用于程序化装配的完整默认设置
     */
    public static JadeSettings defaults() {
        return new JadeSettings(
                new Tui("system", "fullscreen", "transcript", "auto", true, 0, false, 0, 1, 5, true, false, true,
                        "streaming", true, 60, false, false, false, "  ", "", java.util.Map.of(), 0, "light", "dark"),
                CompactionPolicy.defaults(), CompactionLimits.defaults(), new Memory(true, 8000, 16384, 128000),
                new Models(true, 24, 2000, Map.of()), com.jadecode.llm.ThinkingSettings.DEFAULT, new Permissions(
                        com.jadecode.safety.PermissionProfile.WORKSPACE, java.util.List.of(), java.util.Set.of()),
                Mcp.defaults(), com.jadecode.context.ProductionCompactionSettings.defaults(),
                com.jadecode.sandbox.SandboxSettings.offline());
    }
}
