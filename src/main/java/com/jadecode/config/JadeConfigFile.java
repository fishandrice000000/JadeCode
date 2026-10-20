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
        ObjectNode permissions = root.putObject("permissions");
        permissions.put("defaultProfile", defaults.permissions().defaultProfile().value());
        permissions.putArray("rules"); permissions.putArray("disabledDefaults");
        ObjectNode compact = Json.mapper().valueToTree(defaults.compaction());
        compact.set("limits", Json.mapper().valueToTree(defaults.limits())); root.set("compaction", compact);
        return root;
    }

    /** 将可选覆盖值合成完整快照，不修改读取的树，也不把配置正文写入异常。 */
    private static JadeSettings parse(ObjectNode root) throws IOException {
        if (root.has("version") && (!root.get("version").isInt() || root.get("version").intValue() != 1))
            throw new IOException("config.version 必须为 1");
        JadeSettings d = JadeSettings.defaults();
        ObjectNode ui = section(root, "tui"), c = section(root, "compaction"), l = section(c, "limits");
        // 1. 未填写的 UI 字段用默认值；显式写错则报错，不能把 "false" 字符串当作布尔值。
        JadeSettings.Tui tui = new JadeSettings.Tui(ui.has("theme") ? theme(ui.get("theme"), "tui.theme") : d.tui().theme(),
                bool(ui, "thinkingVisible", d.tui().thinkingVisible()), bool(ui, "toolsExpanded", d.tui().toolsExpanded()),
                bool(ui, "commandSuggestions", d.tui().commandSuggestions()));
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
                throw new IOException("config.modelMetadata.overrides 必须按非空模型 ID 填写对象");
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
            if (!reasoning.get("effort").isTextual()) throw new IOException("config.thinking.effort 必须是字符串");
            try { thinking = com.jadecode.llm.ThinkingSettings.parse(reasoning.get("effort").textValue()); }
            catch (IllegalArgumentException invalid) { throw new IOException("config.thinking.effort 无效"); }
        }
        return new JadeSettings(tui, policy, limits, new JadeSettings.Memory(extractionTokens), discovery, thinking, permissions(root));
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
        } catch (Exception invalid) { throw new IOException("config.permissions 无效，请检查档位、规则及默认保护编号"); }
    }

    /** 手动限额允许省略／null 表示未知；其它值必须是可表示的正整数，不能把字符串数字当事实。 */
    private static Integer optionalLimit(ObjectNode root, String key, String path) throws IOException {
        return !root.hasNonNull(key) ? null : number(root, key, 1, 1, path);
    }

    /** 缺省章节是空对象；显式填成 null／数组等则是错误，不把拼错的配置当作未填写。 */
    private static ObjectNode section(ObjectNode root, String key) throws IOException {
        if (!root.has(key)) return Json.mapper().createObjectNode();
        if (!(root.get(key) instanceof ObjectNode object)) throw new IOException("config." + key + " 必须是对象");
        return object;
    }
    /** 只接受真正的 JSON boolean；缺省才使用 fallback，null 或字符串不是“未填写”。 */
    private static boolean bool(ObjectNode root, String key, boolean fallback) throws IOException {
        return bool(root, key, fallback, "config");
    }
    private static boolean bool(ObjectNode root, String key, boolean fallback, String section) throws IOException {
        if (!root.has(key)) return fallback;
        if (!root.get(key).isBoolean()) throw new IOException(section + "." + key + " 必须是布尔值");
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
            throw new IOException(section + "." + key + " 必须是大于等于 " + minimum + " 的整数");
        return value.intValue();
    }
    /** 目标比例必须有限且严格在 0 与 1 之间，否则不能表示“把历史缩短到多少”。 */
    private static double ratio(ObjectNode root, double fallback) throws IOException {
        if (!root.has("targetRatio")) return fallback;
        JsonNode value = root.get("targetRatio"); double ratio = value.asDouble(Double.NaN);
        if (!value.isNumber() || !Double.isFinite(ratio) || ratio <= 0 || ratio >= 1)
            throw new IOException("config.compaction.limits.targetRatio 必须在 0 与 1 之间");
        return ratio;
    }
    /** 主题名只采用程序提供的两项，不将配置文字拼成 ANSI 指令或任意文件路径。 */
    private static String theme(JsonNode value, String field) throws IOException {
        if (value == null || !value.isTextual() || !java.util.Set.of("dark", "light").contains(value.textValue()))
            throw new IOException("config." + field + " 必须是 dark 或 light");
        return value.textValue();
    }
    /** 文件不存在视为缺省，已存在但空白／损坏则失败；异常不附带 JSON 正文。 */
    private static ObjectNode read(Path source) throws IOException {
        if (!Files.exists(source)) return Json.mapper().createObjectNode();
        JsonNode node;
        try { node = Json.mapper().readTree(Files.readString(source)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            // 解析器的原始异常可能附带正文片段；配置错误只报位置，不泄漏文件内容。
            throw new IOException("配置 JSON 无效：" + source);
        }
        if (!(node instanceof ObjectNode object)) throw new IOException("配置必须是 JSON 对象：" + source);
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
