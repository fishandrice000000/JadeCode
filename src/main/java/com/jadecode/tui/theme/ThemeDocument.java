package com.jadecode.tui.theme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 尚未解析的不可变主题文档：只保存文件里写了什么，不展开变量、不绑定终端、不生成颜色。
 *
 * <p>第九轮（S91）把内置主题收敛为一个包含深/浅变体的文件；本记录同时表达旧版单外观
 * 文件（schemaVersion 缺省、带 colors）和新版双变体文件（schemaVersion=2、带 variants）。
 * 解析阶段的唯一职责是校验结构与类型，把 JSON 转成不可变标量，避免可变的 {@link JsonNode}
 * 逸出到 UI 线程或主题快照。</p>
 *
 * <p>两类格式的差别只在这里体现：</p>
 * <ul>
 *   <li>v1：一个 {@code colors} 对象，可选 {@code vars}/{@code appearance}；内部用
 *       {@link #SINGLE} 作为唯一变体键。</li>
 *   <li>v2：必须同时有 {@code dark}/{@code light} 两个变体，每个变体各有 colors 和可选
 *       vars/derivations；顶层 {@code variants} 不得被静默忽略。</li>
 * </ul>
 *
 * <p>解析错误全部使用英文（面向用户/日志），并且不吞掉未知版本、错误类型、缺变体等结构问题；
 * 未知的额外元数据（例如 {@code $schema}/{@code export}）保留在文件里但不参与解析。</p>
 *
 * @param schemaVersion 文件声明的版本；v1 为 {@link #VERSION_1}，v2 为 {@link #VERSION_2}
 * @param name 展示名，不取代文件路径身份
 * @param variants 变体键到原始变体的映射；v1 只有 {@link #SINGLE} 一项
 */
public record ThemeDocument(int schemaVersion, String name, Map<String, Variant> variants) {
    /** 旧版单外观文件的内部版本号。 */
    public static final int VERSION_1 = 1;
    /** 新版单文件双变体格式的版本号。 */
    public static final int VERSION_2 = 2;
    /** 深色变体键。 */
    public static final String DARK = "dark";
    /** 浅色变体键。 */
    public static final String LIGHT = "light";
    /** v1 文件的唯一变体键；不写进 v2 文件。 */
    public static final String SINGLE = "single";
    /** 面板底色相对背景的默认混合比例；v2 可在 derivations.panelBgMix 覆盖。 */
    public static final double DEFAULT_PANEL_BG_MIX = 0.10;

    /**
     * 一个未解析的颜色/变量值。
     *
     * <p>三态语义必须区分清楚：</p>
     * <ul>
     *   <li>{@code index != null}：0–255 索引色；</li>
     *   <li>{@code text} 为空字符串：显式的 {@code Color.DEFAULT}（继承），不是“缺失”；</li>
     *   <li>{@code text} 非空：颜色字面量或对同变体 vars 的引用，由解析器决定。</li>
     * </ul>
     *
     * @param text 原始文字（已 trim）；索引色时为 null
     * @param index 索引色序号；非索引色时为 null
     */
    public record Raw(String text, Integer index) {
        /** @return 是否显式空值（Color.DEFAULT），区别于“文件没有这个键”。 */
        public boolean isEmpty() { return index == null && (text == null || text.isEmpty()); }

        /** @return 是否请求主题层派生（只允许在指定角色上出现）。 */
        public boolean isAuto() { return index == null && "auto".equals(text); }
    }

    /**
     * 一个变体的原始内容。
     *
     * @param name 变体键（v1 为 {@code single}）
     * @param appearance 设计外观（dark/light）；v2 取自变体键
     * @param vars 基础色变量
     * @param colors 语义角色
     * @param panelBgMix 面板底色派生比例；默认 {@link #DEFAULT_PANEL_BG_MIX}
     */
    public record Variant(String name, String appearance, Map<String, Raw> vars, Map<String, Raw> colors,
            double panelBgMix) {
    }

    /**
     * 解析主题 JSON。
     *
     * <p>版本判定顺序：显式 {@code schemaVersion} 优先；没有版本但出现 {@code variants} 时
     * 明确报错，避免把 v2 当 v1 后静默丢掉两个变体；其余按旧版单外观解析。</p>
     *
     * @param json 完整文件内容（来自内置资源或用户文件）
     * @return 不可变文档
     * @throws IllegalArgumentException 结构/类型/版本错误；消息为英文且不复制整份原文
     */
    public static ThemeDocument parse(String json) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot parse theme JSON: " + failure.getMessage(), failure);
        }
        if (root == null || !root.isObject())
            throw new IllegalArgumentException("Cannot parse theme JSON: root must be an object");
        String name = root.path("name").asText("custom");
        JsonNode versionNode = root.get("schemaVersion");
        if (versionNode != null && !versionNode.isNull()) {
            if (!versionNode.isIntegralNumber())
                throw new IllegalArgumentException("Theme schemaVersion must be a number");
            int version = versionNode.asInt();
            if (version != VERSION_2)
                throw new IllegalArgumentException("Unsupported theme schemaVersion: " + version);
            return parseVersion2(name, root);
        }
        // 没有版本号却声明了 variants：几乎一定是漏写 schemaVersion，不能降级成 v1 后只画一半。
        if (root.has("variants"))
            throw new IllegalArgumentException("Theme has variants but no schemaVersion: 2");
        return parseVersion1(name, root);
    }

    /** 从磁盘读取并解析主题文件；文件不存在/编码错误明确失败。 */
    public static ThemeDocument read(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read " + file + ": " + failure.getMessage(), failure);
        }
    }

    /** @return 是否为双变体格式。 */
    public boolean isVersion2() { return schemaVersion == VERSION_2; }

    /**
     * 取一个变体。
     *
     * <p>v1 忽略请求名（只有一套外观）；v2 严格匹配 dark/light，未知变体报错而不是回退到
     * 任意一个变体，否则固定 dark/light 的选择可能悄悄画成另一种外观。</p>
     *
     * @param name 变体键；v1 可为 null
     * @return 原始变体
     */
    public Variant variant(String name) {
        if (!isVersion2()) {
            Variant single = variants.get(SINGLE);
            if (single == null) throw new IllegalArgumentException("Theme is missing its single variant");
            return single;
        }
        String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
        Variant found = variants.get(key);
        if (found == null) throw new IllegalArgumentException("Unknown theme variant: " + name);
        return found;
    }

    /** @return v2 的变体键集合；v1 为空（只有一个隐含单外观）。 */
    public List<String> variantNames() {
        return variants.keySet().stream().filter(key -> !key.equals(SINGLE)).sorted().toList();
    }

    /** 解析新版双变体文件；dark/light 缺一不可。 */
    private static ThemeDocument parseVersion2(String name, JsonNode root) {
        JsonNode variantsNode = root.get("variants");
        if (variantsNode == null || !variantsNode.isObject())
            throw new IllegalArgumentException("Version 2 theme requires a variants object");
        var variants = new LinkedHashMap<String, Variant>();
        for (String key : List.of(DARK, LIGHT)) {
            JsonNode node = variantsNode.get(key);
            if (node == null)
                throw new IllegalArgumentException("Version 2 theme is missing the " + key + " variant");
            if (!node.isObject())
                throw new IllegalArgumentException("Theme variant " + key + " must be an object");
            variants.put(key, parseVariant(key, key, node));
        }
        return new ThemeDocument(VERSION_2, name, Map.copyOf(variants));
    }

    /** 解析旧版单外观文件；colors 是唯一必填对象。 */
    private static ThemeDocument parseVersion1(String name, JsonNode root) {
        JsonNode colorsNode = root.get("colors");
        if (colorsNode == null || !colorsNode.isObject())
            throw new IllegalArgumentException("Theme is missing a colors object");
        String appearance = root.path("appearance").asText("");
        Variant variant = parseVariant(SINGLE, appearance, root);
        return new ThemeDocument(VERSION_1, name, Map.of(SINGLE, variant));
    }

    /**
     * 解析一个变体对象。
     *
     * @param label 错误信息中的变体名（v1 使用 single）
     * @param appearance 设计外观
     * @param node 含 colors（必需）与 vars/derivations（可选）的对象
     */
    private static Variant parseVariant(String label, String appearance, JsonNode node) {
        Map<String, Raw> vars = readValues(node.get("vars"), label, "variable");
        JsonNode colorsNode = node.get("colors");
        if (colorsNode == null || !colorsNode.isObject())
            throw new IllegalArgumentException("Theme " + label + " is missing a colors object");
        Map<String, Raw> colors = readValues(colorsNode, label, "color");
        double mix = DEFAULT_PANEL_BG_MIX;
        JsonNode derivations = node.get("derivations");
        if (derivations != null && !derivations.isNull()) {
            if (!derivations.isObject())
                throw new IllegalArgumentException("Theme " + label + " derivations must be an object");
            JsonNode mixNode = derivations.get("panelBgMix");
            if (mixNode != null && !mixNode.isNull()) {
                if (!mixNode.isNumber())
                    throw new IllegalArgumentException("Theme derivation panelBgMix must be a number");
                mix = mixNode.asDouble();
                if (!(mix >= 0 && mix <= 1))
                    throw new IllegalArgumentException("Theme derivation panelBgMix must be between 0 and 1");
            }
        }
        return new Variant(label, appearance, Map.copyOf(vars), Map.copyOf(colors), mix);
    }

    /** 读取一组变量/角色；键顺序保留，便于诊断时看到文件顺序。 */
    private static Map<String, Raw> readValues(JsonNode node, String label, String kind) {
        var result = new LinkedHashMap<String, Raw>();
        if (node == null || node.isNull()) return result;
        if (!node.isObject())
            throw new IllegalArgumentException("Theme " + label + " " + kind + "s must be an object");
        node.fields().forEachRemaining(entry ->
                result.put(entry.getKey(), readRaw(entry.getValue(), label, kind, entry.getKey())));
        return result;
    }

    /** 校验单个值只能是字符串或 0–255 整数；对象/数组/布尔/null 都明确失败。 */
    private static Raw readRaw(JsonNode value, String label, String kind, String key) {
        if (value == null || value.isNull() || value.isBoolean() || value.isObject() || value.isArray())
            throw new IllegalArgumentException("Theme " + label + " " + kind + " " + key + " must be a color value");
        if (value.isIntegralNumber()) {
            int index = value.asInt();
            if (index < 0 || index > 255)
                throw new IllegalArgumentException("Theme indexed color must be between 0 and 255: " + key);
            return new Raw(null, index);
        }
        return new Raw(value.asText("").trim(), null);
    }
}
