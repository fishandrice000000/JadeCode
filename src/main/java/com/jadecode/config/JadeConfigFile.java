package com.jadecode.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.context.CompactionLimits;
import com.jadecode.context.CompactionPolicy;
import com.jadecode.util.Json;

/**
 * 读写统一 config.json，负责 TUI、压缩、记忆额度与模型参数发现设置，不读取环境凭据。
 *
 * <p>启动读取一次并验证已知字段；缺失项使用默认值，写错类型或范围则报字段名，不能静默
 * 回退后继续使用意外的压缩方式。保存主题时重新读当前文件，只改 tui.theme，保留其它项。
 * 写入先完成同目录临时文件再替换；这里的进程内锁不提供多进程同时改配置的保证。</p>
 */
public final class JadeConfigFile implements com.jadecode.safety.rules.PermissionRuleStore {
    private static final Object WRITE_LOCK = new Object();
    private final Path path;

    public JadeConfigFile(Path path) { this.path = path.toAbsolutePath().normalize(); }

    /** 新的唯一用户配置位置；不会把配置写进当前项目或 CODEX_HOME。 */
    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".jadecode", "config", "config.json");
    }

    /**
     * 读取快照；首次发现旧 tui.json 时只迁移其主题，不删除旧文件。
     * 新文件或新主题已经存在时优先使用新值。迁移失败抛 IOException，不假称已迁移。
     */
    public JadeSettings load() throws IOException {
        synchronized (WRITE_LOCK) {
            ObjectNode root = read(path);
            // 先校验整份旧文件，再备份/迁移压缩段，避免坏配置也被覆盖成“看似正常”。
            ObjectNode migrated = migrateCompaction(root);
            if (migrated != root) {
                parse(root); parse(migrated);
                if (Files.exists(path)) backup(path);
                write(migrated); root = migrated;
            }
            Path legacy = path.resolveSibling("tui.json");
            if (!section(root, "tui").has("theme") && Files.exists(legacy)) {
                String oldTheme = theme(read(legacy).get("theme"), "tui.json.theme");
                // 先验证现有配置，避免迁移时把非法配置写成一份看似正常的新文件。
                parse(root);
                if (Files.exists(path)) backup(path);
                backup(legacy);
                ObjectNode ui = section(root, "tui").deepCopy(); ui.put("theme", oldTheme); root.set("tui", ui);
                if (!Files.exists(path)) {
                    ObjectNode full = template(); full.set("tui", merge((ObjectNode) full.get("tui"), ui)); root = full;
                }
                write(root);
            }
            return parse(root);
        }
    }

    /**
     * 用户明确选中主题后保存。其它已知／未知字段保留，不能用一份仅含 theme 的 JSON 覆盖压缩配置。
     * 这是磁盘 IO，调用方应在界面线程以外执行；失败时原文件仍可读取。
     */
    public void saveTheme(String value) throws IOException {
        theme(Json.mapper().getNodeFactory().textNode(value), "tui.theme");
        synchronized (WRITE_LOCK) {
            ObjectNode root = Files.exists(path) ? read(path) : template();
            ObjectNode ui = section(root, "tui").deepCopy(); ui.put("theme", value); root.set("tui", ui);
            parse(root);
            write(root);
        }
    }

    /**
     * 白名单保存 TUI 子树：只接受已批准字段，重新读取当前文件合并，保留未知字段/Agent 配置。
     * 保存前用同一严格 parse 验证，坏值不落盘。调用方应在工作线程执行。
     */
    public void saveTui(java.util.Map<String, Object> patch) throws IOException {
        if (patch == null || patch.isEmpty()) return;
        java.util.Set<String> allowed = java.util.Set.of(
                "theme", "mode", "fullscreenExitOutput", "fullscreenScrollbar", "fullscreenCopyOnSelect",
                "fullscreenWheelScrollLines", "showHardwareCursor", "editorPaddingX", "outputPad",
                "autocompleteMaxVisible", "thinkingVisible", "toolsExpanded", "commandSuggestions",
                "mermaidRendering", "showImages", "imageWidthCells", "clearOnShrink", "showTerminalProgress",
                "quietStartup", "codeBlockIndent", "externalEditor", "keybindings", "escapeTimeoutMillis",
                "lightTheme", "darkTheme");
        for (String key : patch.keySet())
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unsupported tui setting: " + key);
        synchronized (WRITE_LOCK) {
            ObjectNode root = Files.exists(path) ? read(path) : template();
            ObjectNode ui = section(root, "tui").deepCopy();
            for (var entry : patch.entrySet()) ui.set(entry.getKey(), Json.mapper().valueToTree(entry.getValue()));
            root.set("tui", ui);
            parse(root);
            write(root);
        }
    }

    /** 严格读取 TUI 键位覆盖：动作 ID→非空字符串数组，空数组允许清除默认键。 */
    private static java.util.Map<String, java.util.List<String>> keybindings(ObjectNode ui) throws IOException {
        if (!ui.has("keybindings")) return java.util.Map.of();
        if (!ui.path("keybindings").isObject()) throw new IOException("config.tui.keybindings must be an object");
        var result = new java.util.LinkedHashMap<String, java.util.List<String>>();
        for (var entry : ui.path("keybindings").properties()) {
            if (!entry.getValue().isArray()) throw new IOException("config.tui.keybindings." + entry.getKey() + " must be an array");
            var keys = new java.util.ArrayList<String>();
            for (var item : entry.getValue()) {
                if (!item.isTextual()) throw new IOException("config.tui.keybindings may only contain strings");
                keys.add(item.textValue());
            }
            result.put(entry.getKey(), java.util.List.copyOf(keys));
        }
        return java.util.Map.copyOf(result);
    }

    /** 必填字符串字段；空串允许。 */
    private static String string(ObjectNode node, String key, String path) throws IOException {
        if (!node.path(key).isTextual()) throw new IOException("config." + path.substring(path.indexOf('.') + 1) + " must be a string");
        return node.path(key).textValue();
    }

    /** 可选字符串：字段存在时必须是字符串，null 不写入。 */
    private static String optionalString(ObjectNode node, String key, String path) throws IOException {
        if (!node.has(key)) return "";
        if (!node.path(key).isTextual()) throw new IOException("config." + path + " must be a string");
        return node.path(key).textValue();
    }

    /** 明确保存启动默认，只改 permissions.defaultProfile；不会自动改变另一份运行时的当前档位。 */
    public void savePermissionProfile(com.jadecode.safety.PermissionProfile profile) throws IOException {
        synchronized (WRITE_LOCK) {
            ObjectNode root = Files.exists(path) ? read(path) : template();
            ObjectNode permissions = section(root, "permissions").deepCopy(); permissions.put("defaultProfile", profile.value());
            root.set("permissions", permissions); parse(root); write(root);
        }
    }

    /** 保存一条明确授权，保留所有其它配置。内存状态由调用方在本次 IO 成功后发布。 */
    @Override public void save(com.jadecode.safety.rules.PermissionRule rule) throws IOException {
        synchronized (WRITE_LOCK) {
            ObjectNode root = Files.exists(path) ? read(path) : template();
            var current = parse(root).permissions();
            var rules = new java.util.ArrayList<>(current.rules()); rules.add(rule);
            ObjectNode permissions = section(root, "permissions").deepCopy(); permissions.set("rules", Json.mapper().valueToTree(rules));
            root.set("permissions", permissions); parse(root); write(root);
        }
    }

    /** 导出可复制的完整默认配置，不含任何凭据；也用于第一次保存时给用户可编辑的配置项。 */
    public static ObjectNode template() {
        JadeSettings defaults = JadeSettings.defaults();
        ObjectNode root = Json.mapper().createObjectNode().put("version", 1);
        root.set("tui", Json.mapper().valueToTree(defaults.tui()));
        root.set("memory", Json.mapper().valueToTree(defaults.memory()));
        root.set("modelMetadata", Json.mapper().valueToTree(defaults.modelMetadata()));
        root.set("mcp", Json.mapper().valueToTree(defaults.mcp()));
        ObjectNode permissions = root.putObject("permissions");
        permissions.put("defaultProfile", defaults.permissions().defaultProfile().value());
        permissions.putArray("rules"); permissions.putArray("disabledDefaults");
        ObjectNode compact = Json.mapper().valueToTree(defaults.contextCompaction());
        compact.put("summaryMaxInputTokens", "auto"); root.set("compaction", compact);
        return root;
    }

    /** 将可选覆盖值合成完整快照，不修改读取的树，也不把配置正文写入异常。 */
    private static JadeSettings parse(ObjectNode root) throws IOException {
        if (root.has("version") && (!root.get("version").isInt() || root.get("version").intValue() != 1))
            throw new IOException("config.version must be 1");
        JadeSettings d = JadeSettings.defaults();
        ObjectNode ui = section(root, "tui"), c = section(root, "compaction"), l = section(c, "limits");
        // 1. 未填写的 UI 字段用默认值；显式写错则报错，不能把 "false" 字符串当作布尔值。
        JadeSettings.Tui dt = d.tui();
        JadeSettings.Tui tui;
        try {
            tui = new JadeSettings.Tui(
                    ui.has("theme") ? theme(ui.get("theme"), "tui.theme") : dt.theme(),
                    ui.has("mode") ? string(ui, "mode", "tui.mode") : dt.mode(),
                    ui.has("fullscreenExitOutput") ? string(ui, "fullscreenExitOutput", "tui.fullscreenExitOutput") : dt.fullscreenExitOutput(),
                    ui.has("fullscreenScrollbar") ? string(ui, "fullscreenScrollbar", "tui.fullscreenScrollbar") : dt.fullscreenScrollbar(),
                    bool(ui, "fullscreenCopyOnSelect", dt.fullscreenCopyOnSelect()),
                    number(ui, "fullscreenWheelScrollLines", dt.fullscreenWheelScrollLines(), 0, "config.tui"),
                    bool(ui, "showHardwareCursor", dt.showHardwareCursor()),
                    number(ui, "editorPaddingX", dt.editorPaddingX(), 0, "config.tui"),
                    number(ui, "outputPad", dt.outputPad(), 0, "config.tui"),
                    number(ui, "autocompleteMaxVisible", dt.autocompleteMaxVisible(), 3, "config.tui"),
                    bool(ui, "thinkingVisible", dt.thinkingVisible()), bool(ui, "toolsExpanded", dt.toolsExpanded()),
                    bool(ui, "commandSuggestions", dt.commandSuggestions()),
                    ui.has("mermaidRendering") ? string(ui, "mermaidRendering", "tui.mermaidRendering") : dt.mermaidRendering(),
                    bool(ui, "showImages", dt.showImages()),
                    number(ui, "imageWidthCells", dt.imageWidthCells(), 1, "config.tui"),
                    bool(ui, "clearOnShrink", dt.clearOnShrink()),
                    bool(ui, "showTerminalProgress", dt.showTerminalProgress()),
                    bool(ui, "quietStartup", dt.quietStartup()),
                    ui.has("codeBlockIndent") ? string(ui, "codeBlockIndent", "tui.codeBlockIndent") : dt.codeBlockIndent(),
                    ui.has("externalEditor") ? optionalString(ui, "externalEditor", "tui.externalEditor") : dt.externalEditor(),
                    keybindings(ui), number(ui, "escapeTimeoutMillis", dt.escapeTimeoutMillis(), 0, "config.tui"),
                    ui.has("lightTheme") ? string(ui, "lightTheme", "tui.lightTheme") : dt.lightTheme(),
                    ui.has("darkTheme") ? string(ui, "darkTheme", "tui.darkTheme") : dt.darkTheme());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid config.tui settings: " + invalid.getMessage(), invalid);
        }
        CompactionPolicy p = d.compaction();
        // 2. 开关与数值分开：保存某层的阈值不意味着已经打开那层策略。
        CompactionPolicy policy = new CompactionPolicy(bool(c, "largeResultEnabled", p.largeResultEnabled()),
                bool(c, "batchBudgetEnabled", p.batchBudgetEnabled()), bool(c, "snipEnabled", p.snipEnabled()),
                bool(c, "microEnabled", p.microEnabled()), bool(c, "fitEnabled", p.fitEnabled()),
                bool(c, "autoSummaryEnabled", p.autoSummaryEnabled()), bool(c, "reactiveEnabled", p.reactiveEnabled()));
        CompactionLimits a = d.limits();
        // 3. 所有阈值都允许局部覆盖。保留条数／预览可为零，预算和输出额度必须为正。
        CompactionLimits limits = new CompactionLimits(number(l, "contextCharLimit", a.contextCharLimit(), 1),
                number(l, "toolResultBatchCharLimit", a.toolResultBatchCharLimit(), 1),
                number(l, "largeResultCharLimit", a.largeResultCharLimit(), 1),
                number(l, "summaryInputCharLimit", a.summaryInputCharLimit(), 1),
                number(l, "keepRecentResults", a.keepRecentResults(), 0), number(l, "keepRecentMessages", a.keepRecentMessages(), 0),
                number(l, "microResultMinChars", a.microResultMinChars(), 1), number(l, "budgetPreviewChars", a.budgetPreviewChars(), 0),
                number(l, "fitPreviewChars", a.fitPreviewChars(), 0), number(l, "summaryMaxOutputTokens", a.summaryMaxOutputTokens(), 1),
                number(l, "snipMaxMessages", a.snipMaxMessages(), 1), number(l, "snipHeadMessages", a.snipHeadMessages(), 0), ratio(l, a.targetRatio()));
        ObjectNode memory = section(root, "memory");
        int extractionTokens = number(memory, "extractionMaxOutputTokens", d.memory().extractionMaxOutputTokens(), 1, "config.memory");
        ObjectNode models = section(root, "modelMetadata");
        var overrides = new java.util.LinkedHashMap<String, com.jadecode.llm.ModelMetadata.Limits>();
        ObjectNode configured = section(models, "overrides");
        for (var fields = configured.fields(); fields.hasNext();) {
            var field = fields.next();
            String path = "config.modelMetadata.overrides." + field.getKey();
            if (field.getKey().isBlank() || !(field.getValue() instanceof ObjectNode values))
                throw new IOException("config.modelMetadata.overrides must map non-empty model ids to objects");
            overrides.put(field.getKey(), new com.jadecode.llm.ModelMetadata.Limits(
                    optionalLimit(values, "contextWindowTokens", path), optionalLimit(values, "maxInputTokens", path),
                    optionalLimit(values, "maxOutputTokens", path)));
        }
        var aModels = d.modelMetadata();
        var discovery = new JadeSettings.Models(bool(models, "autoDiscover", aModels.autoDiscover(), "config.modelMetadata"),
                number(models, "cacheTtlHours", aModels.cacheTtlHours(), 1, "config.modelMetadata"),
                number(models, "timeoutMillis", aModels.timeoutMillis(), 1, "config.modelMetadata"), overrides);
        // 请求模式和界面显隐分开；未填写时不发送额外参数，不改服务商的默认模式。
        ObjectNode reasoning = section(root, "thinking");
        com.jadecode.llm.ThinkingSettings thinking = d.thinking();
        if (reasoning.has("effort")) {
            if (!reasoning.get("effort").isTextual()) throw new IOException("config.thinking.effort must be a string");
            try { thinking = com.jadecode.llm.ThinkingSettings.parse(reasoning.get("effort").textValue()); }
            catch (IllegalArgumentException invalid) { throw new IOException("Invalid config.thinking.effort"); }
        }
        var memorySettings = new JadeSettings.Memory(bool(memory, "enabled", d.memory().enabled()), extractionTokens,
                number(memory, "consolidationMaxOutputTokens", d.memory().consolidationMaxOutputTokens(), 1, "config.memory"),
                number(memory, "consolidationInputChars", d.memory().consolidationInputChars(), 1, "config.memory"));
        var defaults = com.jadecode.context.ProductionCompactionSettings.defaults();
        Integer summaryInput = null;
        if (c.has("summaryMaxInputTokens") && !c.get("summaryMaxInputTokens").isNull()
                && !(c.get("summaryMaxInputTokens").isTextual() && c.get("summaryMaxInputTokens").asText().equals("auto")))
            summaryInput = number(c, "summaryMaxInputTokens", 1, 1, "config.compaction");
        var production = new com.jadecode.context.ProductionCompactionSettings(
                bool(c, "autoSummaryEnabled", true, "config.compaction"),
                number(c, "reserveTokens", defaults.reserveTokens(), 0, "config.compaction"),
                number(c, "keepRecentTokens", defaults.keepRecentTokens(), 0, "config.compaction"),
                number(c, "summaryMaxOutputTokens", defaults.summaryMaxOutputTokens(), 1, "config.compaction"), summaryInput,
                number(c, "largeResultCharLimit", defaults.largeResultCharLimit(), 1, "config.compaction"),
                number(c, "largeResultPreviewChars", defaults.largeResultPreviewChars(), 0, "config.compaction"));
        return new JadeSettings(tui, policy, limits, memorySettings, discovery, thinking, permissions(root), mcp(root), production);
    }

    /**
     * 只迁移已识别的课程压缩字段，保留其它设置和未知扩展；返回原对象表示不需要写盘。
     * 旧默认 8000 按用户批准升级为 32768，其它显式摘要输出值保留；字符阈值不猜成 Token。
     * 备份和原子替换归 load，纯转换过程不修改输入。
     */
    private static ObjectNode migrateCompaction(ObjectNode root) throws IOException {
        ObjectNode old = section(root, "compaction");
        var removed = java.util.Set.of("largeResultEnabled", "batchBudgetEnabled", "snipEnabled", "microEnabled", "fitEnabled", "reactiveEnabled");
        if (!old.has("limits") && removed.stream().noneMatch(old::has)) return root;
        ObjectNode result = root.deepCopy(), compact = old.deepCopy(), legacy = section(old, "limits");
        compact.remove("limits"); removed.forEach(compact::remove);
        if (!compact.has("largeResultCharLimit") && legacy.has("largeResultCharLimit"))
            compact.set("largeResultCharLimit", legacy.get("largeResultCharLimit"));
        if (!compact.has("largeResultPreviewChars") && legacy.has("budgetPreviewChars"))
            compact.set("largeResultPreviewChars", legacy.get("budgetPreviewChars"));
        if (!compact.has("summaryMaxOutputTokens") && legacy.has("summaryMaxOutputTokens")
                && legacy.get("summaryMaxOutputTokens").asInt() != 8000)
            compact.set("summaryMaxOutputTokens", legacy.get("summaryMaxOutputTokens"));
        ObjectNode defaults = Json.mapper().valueToTree(com.jadecode.context.ProductionCompactionSettings.defaults());
        defaults.put("summaryMaxInputTokens", "auto");
        defaults.fields().forEachRemaining(entry -> { if (!compact.has(entry.getKey())) compact.set(entry.getKey(), entry.getValue()); });
        result.set("compaction", compact); return result;
    }

    /**
     * 读取可信用户配置中的 stdio 服务；缺失时启用默认 Fetch，非法配置报字段而不回显启动参数。
     * 不安装依赖、不验证程序存在，也不启动服务，因此未安装 Fetch 不影响配置读取或构建。
     */
    private static JadeSettings.Mcp mcp(ObjectNode root) throws IOException {
        ObjectNode mcp = section(root, "mcp");
        // 旧用户文件尚无 MCP 字段时也获得默认入口；显式 servers 表（包括空表）完整替换默认表。
        if (!mcp.has("servers")) return JadeSettings.Mcp.defaults();
        ObjectNode configured = section(mcp, "servers");
        var servers = new java.util.LinkedHashMap<String, com.jadecode.mcp.McpServerConfig>();
        for (var field : configured.properties()) {
            if (!(field.getValue() instanceof ObjectNode value)) throw new IOException("Each config.mcp.servers entry must be an object");
            // 拼错 transport 或 command 不能静默回退；第一版只支持 stdio，没有网络 transport 隐式降级。
            for (var property : value.properties()) {
                String key = property.getKey();
                if (!java.util.Set.of("enabled", "command", "args", "cwd", "startupTimeoutSeconds", "callTimeoutSeconds",
                        "maxResultChars", "readOnlyTools").contains(key)) throw new IOException("config.mcp.servers contains an unsupported field");
            }
            if (!value.path("command").isTextual() || value.has("cwd") && !value.path("cwd").isTextual())
                throw new IOException("config.mcp command/cwd must be strings");
            try {
                servers.put(field.getKey(), new com.jadecode.mcp.McpServerConfig(bool(value, "enabled", true, "config.mcp"),
                        value.path("command").textValue(), mcpStrings(value, "args"), value.path("cwd").asText(""),
                        number(value, "startupTimeoutSeconds", 15, 1, "config.mcp"),
                        number(value, "callTimeoutSeconds", 30, 1, "config.mcp"),
                        number(value, "maxResultChars", 20_000, 1, "config.mcp"),
                        new java.util.LinkedHashSet<>(mcpStrings(value, "readOnlyTools"))));
            } catch (IllegalArgumentException invalid) { throw new IOException("Invalid config.mcp server arguments or range", invalid); }
        }
        try { return new JadeSettings.Mcp(servers); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid config.mcp server count or name", invalid); }
    }

    /** 严格读取字符串数组；缺失为空，不把对象/数字宽松转换成可执行参数。 */
    private static java.util.List<String> mcpStrings(ObjectNode value, String key) throws IOException {
        if (!value.has(key)) return java.util.List.of();
        if (!value.path(key).isArray() || value.path(key).size() > 64) throw new IOException("config.mcp." + key + " must be a bounded string array");
        var result = new java.util.ArrayList<String>();
        for (var item : value.path(key)) {
            if (!item.isTextual() || item.textValue().length() > 8192) throw new IOException("config.mcp." + key + " contains an invalid argument");
            result.add(item.textValue());
        }
        return java.util.List.copyOf(result);
    }

    /** 严格读取权限配置；未知枚举/类型/编号不能静默降级为放行。异常不回显配置原文。 */
    private static JadeSettings.Permissions permissions(ObjectNode root) throws IOException {
        ObjectNode p = section(root, "permissions");
        try {
            var profile = p.has("defaultProfile") ? com.jadecode.safety.PermissionProfile.parse(p.path("defaultProfile").asText())
                    : com.jadecode.safety.PermissionProfile.WORKSPACE;
            var rules = new java.util.ArrayList<com.jadecode.safety.rules.PermissionRule>();
            var disabled = new java.util.LinkedHashSet<String>();
            if (p.has("rules")) {
                if (!p.path("rules").isArray() || p.path("rules").size() > 1000) throw new IllegalArgumentException();
                for (var rule : p.path("rules")) {
                    // 权限规则不能沿用聊天反序列化的宽松模式：workspce 拼错不能变成全局授权。
                    if (!rule.isObject()) throw new IllegalArgumentException();
                    for (String key : java.util.List.of("id", "effect", "kind", "tool", "workspace"))
                        if (!rule.path(key).isTextual()) throw new IllegalArgumentException();
                    for (String key : java.util.List.of("operation", "path"))
                        if (rule.has(key) && !rule.path(key).isTextual()) throw new IllegalArgumentException();
                    if (rule.has("recursive") && !rule.path("recursive").isBoolean()) throw new IllegalArgumentException();
                    if (rule.has("argvPrefix")) {
                        if (!rule.path("argvPrefix").isArray()) throw new IllegalArgumentException();
                        for (var token : rule.path("argvPrefix")) if (!token.isTextual()) throw new IllegalArgumentException();
                    }
                    rules.add(Json.mapper().readerFor(com.jadecode.safety.rules.PermissionRule.class)
                            .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(rule));
                }
            }
            if (p.has("disabledDefaults")) {
                if (!p.path("disabledDefaults").isArray()) throw new IllegalArgumentException();
                for (var id : p.path("disabledDefaults")) { if (!id.isTextual()) throw new IllegalArgumentException(); disabled.add(id.textValue()); }
            }
            return new JadeSettings.Permissions(profile, rules, disabled);
        } catch (Exception invalid) { throw new IOException("Invalid config.permissions; check the profile, rules and default protection ids"); }
    }

    /** 手动限额允许省略／null 表示未知；其它值必须是可表示的正整数，不能把字符串数字当事实。 */
    private static Integer optionalLimit(ObjectNode root, String key, String path) throws IOException {
        return !root.hasNonNull(key) ? null : number(root, key, 1, 1, path);
    }

    /** 缺省章节是空对象；显式填成 null／数组等则是错误，不把拼错的配置当作未填写。 */
    private static ObjectNode section(ObjectNode root, String key) throws IOException {
        if (!root.has(key)) return Json.mapper().createObjectNode();
        if (!(root.get(key) instanceof ObjectNode object)) throw new IOException("config." + key + " must be an object");
        return object;
    }
    /** 只接受真正的 JSON boolean；缺省才使用 fallback，null 或字符串不是“未填写”。 */
    private static boolean bool(ObjectNode root, String key, boolean fallback) throws IOException {
        return bool(root, key, fallback, "config");
    }
    private static boolean bool(ObjectNode root, String key, boolean fallback, String section) throws IOException {
        if (!root.has(key)) return fallback;
        if (!root.get(key).isBoolean()) throw new IOException(section + "." + key + " must be a boolean");
        return root.get(key).booleanValue();
    }
    /** 限制为可装入 Java int 的整数，避免小数被截断或过大数字溢出后变成负预算。 */
    private static int number(ObjectNode root, String key, int fallback, int minimum) throws IOException {
        return number(root, key, fallback, minimum, "config.compaction.limits");
    }
    /** 不同章节复用数值检查，但错误信息必须指向实际字段，不能一律写成压缩配置。 */
    private static int number(ObjectNode root, String key, int fallback, int minimum, String section) throws IOException {
        if (!root.has(key)) return fallback;
        JsonNode value = root.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum)
            throw new IOException(section + "." + key + " must be an integer greater than or equal to " + minimum + "");
        return value.intValue();
    }
    /** 目标比例必须有限且严格在 0 与 1 之间，否则不能表示“把历史缩短到多少”。 */
    private static double ratio(ObjectNode root, double fallback) throws IOException {
        if (!root.has("targetRatio")) return fallback;
        JsonNode value = root.get("targetRatio"); double ratio = value.asDouble(Double.NaN);
        if (!value.isNumber() || !Double.isFinite(ratio) || ratio <= 0 || ratio >= 1)
            throw new IOException("config.compaction.limits.targetRatio must be between 0 and 1");
        return ratio;
    }
    /** 主题名只采用程序提供的两项，不将配置文字拼成 ANSI 指令或任意文件路径。 */
    private static String theme(JsonNode value, String field) throws IOException {
        // C24 允许 system/auto/dark/light 以及用户 themes 目录中的自定义标识（也可是路径）；
        // 旧实现只允许 dark/light，导致默认 system 配置的任何 saveTui 都在校验阶段失败。
        // 这里只拒绝空值和控制字符；主题是否存在/可读由 ThemeManager 在加载时明确报错。
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().chars().anyMatch(Character::isISOControl))
            throw new IOException("config." + field + " must be system/auto/dark/light or a custom theme id");
        return value.textValue().strip();
    }
    /** 文件不存在视为缺省，已存在但空白／损坏则失败；异常不附带 JSON 正文。 */
    private static ObjectNode read(Path source) throws IOException {
        if (!Files.exists(source)) return Json.mapper().createObjectNode();
        JsonNode node;
        try { node = Json.mapper().readTree(Files.readString(source)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            // 解析器的原始异常可能附带正文片段；配置错误只报位置，不泄漏文件内容。
            throw new IOException("Invalid configuration JSON: " + source);
        }
        if (!(node instanceof ObjectNode object)) throw new IOException("Configuration must be a JSON object: " + source);
        return object;
    }
    private static ObjectNode merge(ObjectNode target, ObjectNode source) { source.fields().forEachRemaining(e -> target.set(e.getKey(), e.getValue())); return target; }

    /** 只在自动迁移前备份，不循环覆盖同名恢复文件；旧配置本身也保留。 */
    private static void backup(Path source) throws IOException {
        Files.copy(source, source.resolveSibling(source.getFileName() + ".pre-config-migration-" + UUID.randomUUID() + ".bak"));
    }

    /** 原子替换优先；文件系统不支持时回退普通替换，任何失败都清理本次临时文件。 */
    private void write(ObjectNode root) throws IOException {
        Files.createDirectories(path.getParent()); Path temporary = Files.createTempFile(path.getParent(), "jade-config-", ".tmp");
        try {
            Files.writeString(temporary, root.toPrettyString() + "\n");
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
