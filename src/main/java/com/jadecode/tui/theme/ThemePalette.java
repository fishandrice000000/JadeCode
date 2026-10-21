package com.jadecode.tui.theme;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jadecode.tui.text.Color;

/**
 * 一套主题的语义颜色表，以及 system 主题的 OKLCH/OKHSL 派生算法。
 *
 * <p>内置 dark/light 直接读取 Pi 固定主题 JSON 的 vars/colors；system 根据终端上报的
 * 前景/背景/16 色生成，不能拿一组近似色冒充。颜色解析支持 hex、`rgb()`、`okhsl()`、
 * `oklch()`、变量引用和 256 色索引。渲染层只接收 {@link Color}，不接触 JSON 或终端回复。</p>
 *
 * <p>这里的 OKHSL 转换移植自 Björn Ottosson 的参考实现（MIT），使 system 主题在相同输入
 * 色板下与 Pi 使用同一色彩空间。终端没有上报颜色时使用 Pi 的假定值；晚到的上报由
 * {@link ThemeManager} 用同一 epoch 替换，不阻塞输入。</p>
 */
public final class ThemePalette {
    /** 解析后的主题定义；颜色表不可变。 */
    public record Definition(String name, String appearance, Map<String, Color> colors) {
        public Definition { colors = Map.copyOf(colors); }
    }

    /** 主题 token 的兜底关系：缺失时使用另一个已存在 token。 */
    private static final Map<String, String> FALLBACKS = Map.ofEntries(
            Map.entry("borderAccent", "border"), Map.entry("borderMuted", "border"),
            Map.entry("customMessageLabel", "accent"), Map.entry("mdLinkUrl", "muted"),
            Map.entry("mdQuoteBorder", "mdQuote"), Map.entry("mdHr", "borderMuted"),
            Map.entry("mdCodeBlockBorder", "borderMuted"), Map.entry("mdListBullet", "accent"),
            Map.entry("toolTitle", "text"), Map.entry("toolOutput", "muted"),
            Map.entry("toolDiffContext", "muted"), Map.entry("scrollbarTrack", "borderMuted"),
            Map.entry("scrollbarThumb", "border"), Map.entry("searchMatchText", "text"),
            Map.entry("syntaxComment", "muted"), Map.entry("syntaxKeyword", "accent"),
            Map.entry("syntaxFunction", "mdHeading"), Map.entry("syntaxVariable", "text"),
            Map.entry("syntaxString", "mdCodeBlock"), Map.entry("syntaxNumber", "success"),
            Map.entry("syntaxType", "accent"), Map.entry("syntaxOperator", "text"),
            Map.entry("syntaxPunctuation", "muted"), Map.entry("thinkingOff", "thinkingText"),
            Map.entry("thinkingMinimal", "thinkingText"), Map.entry("thinkingLow", "thinkingText"),
            Map.entry("thinkingMedium", "thinkingText"), Map.entry("thinkingHigh", "thinkingText"),
            Map.entry("thinkingXhigh", "thinkingText"), Map.entry("bashMode", "success"),
            // Jade 批准扩展：卡片标题使用主题已有颜色，不改变 Pi 基础 token 值。
            Map.entry("requestTitle", "customMessageLabel"), Map.entry("replyTitle", "accent"),
            Map.entry("thinkingTitle", "thinkingText"), Map.entry("approval", "borderAccent"),
            Map.entry("approvalDeny", "error"), Map.entry("permission", "warning"));

    private ThemePalette() { }

    /** 读取内置主题；找不到资源时明确失败，不静默退回任意配色。 */
    public static Definition builtin(String name) {
        String normalized = name == null || name.isBlank() ? "dark" : name;
        if (!normalized.equals("dark") && !normalized.equals("light"))
            throw new IllegalArgumentException("未知内置主题: " + name);
        String resource = "/com/jadecode/tui/themes/" + normalized + ".json";
        try (InputStream input = ThemePalette.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("缺少内置主题资源: " + resource);
            return parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("读取内置主题失败: " + resource, failure);
        }
    }

    /**
     * 解析自定义/内置主题 JSON。所有变量引用递归解析并检测环；缺失必需色时明确失败。
     * 未知 token 保留在颜色表中，供前向兼容的自定义主题使用；未知引用仍然报错。
     */
    public static Definition parse(String json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            String name = root.path("name").asText("custom");
            String appearance = root.path("appearance").asText("");
            JsonNode varsNode = root.path("vars");
            JsonNode colorsNode = root.path("colors");
            if (!colorsNode.isObject()) throw new IllegalArgumentException("主题缺少 colors 对象");
            Map<String, JsonNode> vars = new LinkedHashMap<>();
            varsNode.fields().forEachRemaining(entry -> vars.put(entry.getKey(), entry.getValue()));
            Map<String, Color> colors = new LinkedHashMap<>();
            colorsNode.fields().forEachRemaining(entry -> colors.put(entry.getKey(),
                    resolveColor(entry.getValue(), vars, new java.util.HashSet<>())));
            fillFallbacks(colors);
            if (!colors.containsKey("text") || !colors.containsKey("accent"))
                throw new IllegalArgumentException("主题至少需要 text 和 accent 颜色");
            return new Definition(name, appearance, colors);
        } catch (IOException failure) {
            throw new IllegalArgumentException("主题 JSON 无法解析: " + failure.getMessage(), failure);
        }
    }

    private static Color resolveColor(JsonNode value, Map<String, JsonNode> vars, java.util.Set<String> visited) {
        if (value == null || value.isNull()) throw new IllegalArgumentException("颜色值不能为空");
        if (value.isInt()) {
            int index = value.asInt();
            if (index < 0 || index > 255) throw new IllegalArgumentException("索引色越界: " + index);
            return Color.indexed(index);
        }
        String text = value.asText("").trim();
        if (text.isEmpty()) return Color.DEFAULT;
        if (text.startsWith("#") || text.regionMatches(true, 0, "rgb(", 0, 4)
                || text.regionMatches(true, 0, "okhsl(", 0, 6) || text.regionMatches(true, 0, "oklch(", 0, 6)
                || text.regionMatches(true, 0, "oklab(", 0, 6)) return parseColor(text);
        if (!visited.add(text)) throw new IllegalArgumentException("主题变量循环引用: " + text);
        JsonNode target = vars.get(text);
        if (target == null) throw new IllegalArgumentException("主题变量不存在: " + text);
        return resolveColor(target, vars, visited);
    }

    /** 填充已批准语义 token 的兜底；不覆盖主题明确给出的值。 */
    static void fillFallbacks(Map<String, Color> colors) {
        if (!colors.containsKey("muted")) colors.put("muted", colors.getOrDefault("text", Color.DEFAULT));
        if (!colors.containsKey("dim")) colors.put("dim", colors.get("muted"));
        if (!colors.containsKey("border")) colors.put("border", colors.getOrDefault("muted", Color.DEFAULT));
        if (!colors.containsKey("borderAccent")) colors.put("borderAccent", colors.get("border"));
        if (!colors.containsKey("accent")) colors.put("accent", colors.getOrDefault("text", Color.DEFAULT));
        // 兜底关系可能链式引用，反复直到没有新增；数量很小，不需要复杂图算法。
        for (int pass = 0; pass < 3; pass++) {
            for (var entry : FALLBACKS.entrySet()) {
                if (!colors.containsKey(entry.getKey()) && colors.containsKey(entry.getValue()))
                    colors.put(entry.getKey(), colors.get(entry.getValue()));
            }
        }
        colors.putIfAbsent("text", Color.DEFAULT);
        colors.putIfAbsent("muted", colors.get("text"));
        colors.putIfAbsent("dim", colors.get("muted"));
        colors.putIfAbsent("accent", colors.get("text"));
        colors.putIfAbsent("border", colors.get("muted"));
        colors.putIfAbsent("borderAccent", colors.get("border"));
        colors.putIfAbsent("borderMuted", colors.get("border"));
        colors.putIfAbsent("success", colors.get("text"));
        colors.putIfAbsent("error", colors.get("text"));
        colors.putIfAbsent("warning", colors.get("text"));
        colors.putIfAbsent("selectedBg", Color.DEFAULT);
        colors.putIfAbsent("userMessageBg", Color.DEFAULT);
        colors.putIfAbsent("userMessageText", colors.get("text"));
        colors.putIfAbsent("customMessageBg", Color.DEFAULT);
        colors.putIfAbsent("customMessageText", colors.get("text"));
        colors.putIfAbsent("toolPendingBg", Color.DEFAULT);
        colors.putIfAbsent("toolSuccessBg", Color.DEFAULT);
        colors.putIfAbsent("toolErrorBg", Color.DEFAULT);
        colors.putIfAbsent("requestTitle", colors.get("customMessageLabel"));
        colors.putIfAbsent("replyTitle", colors.get("accent"));
        colors.putIfAbsent("thinkingTitle", colors.get("thinkingText"));
        colors.putIfAbsent("approval", colors.get("borderAccent"));
        colors.putIfAbsent("approvalDeny", colors.get("error"));
        colors.putIfAbsent("permission", colors.get("warning"));
    }

    /** 解析单个颜色字面量；非法值抛出带原文字的异常，由配置读取层显示给用户。 */
    public static Color parseColor(String value) {
        String text = value.trim();
        if (text.startsWith("#")) {
            String hex = text.substring(1);
            if (hex.length() == 3) hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1)
                    + hex.charAt(2) + hex.charAt(2);
            if (hex.length() != 6) throw new IllegalArgumentException("不支持的颜色: " + value);
            try {
                return Color.rgb(Integer.parseInt(hex.substring(0, 2), 16),
                        Integer.parseInt(hex.substring(2, 4), 16), Integer.parseInt(hex.substring(4, 6), 16));
            } catch (NumberFormatException invalid) { throw new IllegalArgumentException("不支持的颜色: " + value); }
        }
        if (text.regionMatches(true, 0, "rgb(", 0, 4) && text.endsWith(")")) {
            String[] parts = text.substring(4, text.length() - 1).split("[,/ ]+");
            if (parts.length < 3) throw new IllegalArgumentException("不支持的 rgb 颜色: " + value);
            try {
                int[] rgb = new int[3];
                for (int i = 0; i < 3; i++) rgb[i] = Integer.parseInt(parts[i].trim());
                return Color.rgb(rgb[0], rgb[1], rgb[2]);
            } catch (NumberFormatException invalid) { throw new IllegalArgumentException("不支持的 rgb 颜色: " + value); }
        }
        if (text.regionMatches(true, 0, "okhsl(", 0, 6) && text.endsWith(")")) {
            double[] channels = channels(text.substring(6, text.length() - 1));
            if (channels.length < 3) throw new IllegalArgumentException("不支持的 okhsl 颜色: " + value);
            return okhsl(channels[0], channels[1], channels[2]);
        }
        if (text.regionMatches(true, 0, "oklch(", 0, 6) && text.endsWith(")")) {
            double[] channels = channels(text.substring(6, text.length() - 1));
            if (channels.length < 3) throw new IllegalArgumentException("不支持的 oklch 颜色: " + value);
            return oklch(channels[0], channels[1], channels[2]);
        }
        if (text.regionMatches(true, 0, "oklab(", 0, 6) && text.endsWith(")")) {
            double[] channels = channels(text.substring(6, text.length() - 1));
            if (channels.length < 3) throw new IllegalArgumentException("不支持的 oklab 颜色: " + value);
            return linearSrgbToRgb(oklabToLinearSrgb(channels[0], channels[1], channels[2]));
        }
        throw new IllegalArgumentException("不支持的颜色: " + value);
    }

    /**
     * 解析 okhsl/oklch/oklab 三个通道；色相保持度数，饱和/明度/色度支持百分比
     * （`60%`→0.6）或小数（`0.6`）。不接受其他单位，避免静默误读主题文件。
     */
    private static double[] channels(String body) {
        String[] parts = body.trim().replace(",", " ").replace("/", " ").split("\\s+");
        var values = new java.util.ArrayList<Double>();
        for (String part : parts) {
            if (part.isBlank()) continue;
            boolean percent = part.endsWith("%");
            String number = percent ? part.substring(0, part.length() - 1) : part;
            try {
                double parsed = Double.parseDouble(number);
                if (percent && values.size() > 0) parsed /= 100.0;
                values.add(parsed);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("颜色分量不是数字: " + body);
            }
        }
        double[] result = new double[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }

    /**
     * 由终端色板生成 system 主题。终端上报前景/背景/16 色时按 Pi 的 family 色相取色：
     * 中性色使用前景/背景的明度关系，accent/border 等从对应 ANSI 槽取色相和饱和度，
     * 再按背景明度计算安全的前景明度。没有上报时使用 Pi 的假定暗/亮默认色。
     *
     * @param dark 终端背景是否为深色
     * @param foreground 终端默认前景；null 使用假定值
     * @param background 终端默认背景；null 使用假定值
     * @param ansi 0-15 ANSI 色；为空时使用固定 family 色相
     */
    public static Definition system(boolean dark, Color foreground, Color background, List<Color> ansi) {
        Color fg = foreground != null && foreground.kind() == Color.Kind.RGB ? foreground
                : Color.rgb(dark ? 0xe5 : 0x00, dark ? 0xe5 : 0x00, dark ? 0xe7 : 0x00);
        Color bg = background != null && background.kind() == Color.Kind.RGB ? background
                : (dark ? Color.rgb(0, 0, 0) : Color.rgb(255, 255, 255));
        double bgL = rgbToOkhsl(bg.red(), bg.green(), bg.blue())[2];
        boolean actuallyDark = bgL < 0.5;
        double target = actuallyDark ? 0.82 : 0.28;
        double mutedTarget = actuallyDark ? 0.62 : 0.44;
        double dimTarget = actuallyDark ? 0.52 : 0.55;

        Map<String, Color> colors = new LinkedHashMap<>();
        colors.put("text", fg);
        colors.put("muted", familyColor(ansi, 8, 231.49, 0.05, mutedTarget, dark));
        colors.put("dim", familyColor(ansi, 8, 229, 0.05, dimTarget, dark));
        colors.put("thinkingText", familyColor(ansi, 4, 226, 0.12, mutedTarget, dark));
        colors.put("accent", familyColor(ansi, 5, 295, 0.5, target, dark));
        colors.put("border", familyColor(ansi, 4, 231.49, 0.45, target, dark));
        colors.put("borderAccent", familyColor(ansi, 5, 295, 0.5, target, dark));
        colors.put("borderMuted", familyColor(ansi, 8, 229, 0.08, dimTarget, dark));
        colors.put("success", familyColor(ansi, 2, 158.68, 0.62, target, dark));
        colors.put("error", familyColor(ansi, 1, 20, 0.72, target, dark));
        colors.put("warning", familyColor(ansi, 3, 82.36, 0.85, actuallyDark ? 0.78 : 0.45, dark));
        colors.put("selectedBg", mix(bg, familyColor(ansi, 4, 231.49, 0.4, actuallyDark ? 0.24 : 0.88, dark), 0.65));
        colors.put("searchMatchBg", mix(bg, familyColor(ansi, 3, 52, 0.6, actuallyDark ? 0.24 : 0.86, dark), 0.55));
        colors.put("searchMatchText", fg);
        colors.put("userMessageBg", mix(bg, familyColor(ansi, 4, 233, 0.41, actuallyDark ? 0.24 : 0.91, dark), 0.8));
        colors.put("userMessageText", fg);
        colors.put("customMessageBg", mix(bg, familyColor(ansi, 5, 295, 0.42, actuallyDark ? 0.24 : 0.91, dark), 0.75));
        colors.put("customMessageText", mutedTarget > 0 ? colors.get("muted") : fg);
        colors.put("customMessageLabel", colors.get("accent"));
        colors.put("toolPendingBg", mix(bg, colors.get("borderMuted"), 0.18));
        colors.put("toolSuccessBg", mix(bg, colors.get("success"), 0.20));
        colors.put("toolErrorBg", mix(bg, colors.get("error"), 0.20));
        colors.put("toolTitle", fg);
        colors.put("toolOutput", colors.get("muted"));
        colors.put("mdHeading", colors.get("warning"));
        colors.put("mdLink", colors.get("border"));
        colors.put("mdLinkUrl", colors.get("muted"));
        colors.put("mdCode", colors.get("accent"));
        colors.put("mdCodeBlock", colors.get("success"));
        colors.put("mdCodeBlockBorder", colors.get("borderMuted"));
        colors.put("mdQuote", colors.get("muted"));
        colors.put("mdQuoteBorder", colors.get("borderMuted"));
        colors.put("mdHr", colors.get("borderMuted"));
        colors.put("mdListBullet", colors.get("accent"));
        colors.put("toolDiffAdded", colors.get("success"));
        colors.put("toolDiffRemoved", colors.get("error"));
        colors.put("toolDiffContext", colors.get("muted"));
        colors.put("syntaxComment", colors.get("muted"));
        colors.put("syntaxKeyword", colors.get("border"));
        colors.put("syntaxFunction", colors.get("warning"));
        colors.put("syntaxVariable", colors.get("mdLink"));
        colors.put("syntaxString", colors.get("warning"));
        colors.put("syntaxNumber", colors.get("success"));
        colors.put("syntaxType", colors.get("accent"));
        colors.put("syntaxOperator", fg);
        colors.put("syntaxPunctuation", colors.get("muted"));
        for (String level : List.of("thinkingOff", "thinkingMinimal", "thinkingLow", "thinkingMedium",
                "thinkingHigh", "thinkingXhigh")) colors.put(level, colors.get("thinkingText"));
        colors.put("bashMode", colors.get("success"));
        colors.put("scrollbarTrack", mix(bg, colors.get("borderMuted"), 0.4));
        colors.put("scrollbarThumb", colors.get("borderMuted"));
        fillFallbacks(colors);
        return new Definition("system", actuallyDark ? "dark" : "light", colors);
    }

    /**
     * 从 ANSI 槽取色相/饱和度，按目标明度生成颜色。槽不可用或不是 RGB 时使用 family 的固定值。
     * 生成结果保持原色相的饱和度上限，避免把柔和终端色板变成刺眼荧光色。
     */
    private static Color familyColor(List<Color> ansi, int slot, double hue, double saturation,
            double lightness, boolean dark) {
        Color source = ansi != null && ansi.size() > slot ? ansi.get(slot) : null;
        if (source != null && source.kind() == Color.Kind.RGB) {
            double[] okhsl = rgbToOkhsl(source.red(), source.green(), source.blue());
            if (okhsl[1] > 0.02) return okhsl(okhsl[0], Math.min(saturation, okhsl[1]), lightness);
            return okhsl(okhsl[0], saturation, lightness);
        }
        return okhsl(hue, saturation, lightness);
    }

    /** 在 sRGB 空间线性混合两个颜色，权重 weight 表示第二个颜色的比例。 */
    static Color mix(Color first, Color second, double weight) {
        double w = Math.max(0, Math.min(1, weight));
        int r = (int) Math.round(first.red() * (1 - w) + second.red() * w);
        int g = (int) Math.round(first.green() * (1 - w) + second.green() * w);
        int b = (int) Math.round(first.blue() * (1 - w) + second.blue() * w);
        return Color.rgb(r, g, b);
    }

    // ===== OKLab/OKHSL：移植自 Björn Ottosson 参考实现（MIT） =====

    private static double linearToSrgb(double value) {
        return value > 0.0031308 ? 1.055 * Math.pow(value, 1 / 2.4) - 0.055 : 12.92 * value;
    }

    private static double srgbToLinear(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double[] multiply(double[][] matrix, double[] vector) {
        double[] result = new double[3];
        for (int row = 0; row < 3; row++)
            result[row] = matrix[row][0] * vector[0] + matrix[row][1] * vector[1] + matrix[row][2] * vector[2];
        return result;
    }

    private static final double[][] LINEAR_SRGB_TO_LMS = {
            {0.4122214694707629, 0.5363325372617349, 0.0514459932675022},
            {0.2119034958178251, 0.6806995506452344, 0.1073969535369405},
            {0.0883024591900564, 0.2817188391361215, 0.6299787016738222}};
    private static final double[][] LMS_TO_LAB = {
            {0.210454268309314, 0.793617774702305, -0.0040720430116193},
            {1.9779985324311684, -2.42859224204858, 0.450593709617411},
            {0.0259040424655478, 0.7827717124575296, -0.8086757549230774}};
    private static final double[][] LAB_TO_LMS = {
            {1, 0.3963377773761749, 0.2158037573099136},
            {1, -0.1055613458156586, -0.0638541728258133},
            {1, -0.0894841775298119, -1.2914855480194092}};
    private static final double[][] LMS_TO_LINEAR_SRGB = {
            {4.0767416360759583, -3.3077115392580629, 0.2309699031821043},
            {-1.2684379732850315, 2.6097573492876882, -0.341319376002657},
            {-0.0041960761386756, -0.7034186179359362, 1.7076146940746117}};
    private static final double[][] SATURATION_FIT_DIRECTIONS = {
            {-1.8817031, -0.80936501}, {1.8144408, -1.19445267}, {0.13110758, 1.81333971}};
    private static final double[][] SATURATION_FIT_POLYNOMIAL = {
            {1.19086277, 1.76576728, 0.59662641, 0.75515197, 0.56771245},
            {0.73956515, -0.45954404, 0.08285427, 0.12541073, -0.14503204},
            {1.35733652, -0.00915799, -1.1513021, -0.50559606, 0.00692167}};

    private static final double K1 = 0.206, K2 = 0.03, K3 = (1 + K1) / (1 + K2);

    /** @return OKHSL 明度到 Oklab 明度。 */
    private static double okhslToOklabLightness(double x) { return (x * x + K1 * x) / (K3 * (x + K2)); }

    /** @return Oklab 明度到 OKHSL 明度。 */
    private static double oklabToOkhslLightness(double x) {
        return 0.5 * (K3 * x - K1 + Math.sqrt((K3 * x - K1) * (K3 * x - K1) + 4 * K2 * K3 * x));
    }

    private static double[] oklabToLinearSrgb(double l, double a, double b) {
        double[] lms = multiply(LAB_TO_LMS, new double[]{l, a, b});
        return multiply(LMS_TO_LINEAR_SRGB, new double[]{Math.pow(lms[0], 3), Math.pow(lms[1], 3), Math.pow(lms[2], 3)});
    }

    private static double[] rgbToOklab(double red, double green, double blue) {
        double[] lms = multiply(LINEAR_SRGB_TO_LMS,
                new double[]{srgbToLinear(red / 255.0), srgbToLinear(green / 255.0), srgbToLinear(blue / 255.0)});
        return multiply(LMS_TO_LAB, new double[]{Math.cbrt(lms[0]), Math.cbrt(lms[1]), Math.cbrt(lms[2])});
    }

    private static Color linearSrgbToRgb(double[] linear) {
        int[] channels = new int[3];
        for (int i = 0; i < 3; i++)
            channels[i] = (int) Math.round(Math.min(1, Math.max(0, linearToSrgb(linear[i]))) * 255);
        return Color.rgb(channels[0], channels[1], channels[2]);
    }

    private static double[] lmsSlopes(double a, double b) {
        return new double[]{LAB_TO_LMS[0][1] * a + LAB_TO_LMS[0][2] * b,
                LAB_TO_LMS[1][1] * a + LAB_TO_LMS[1][2] * b, LAB_TO_LMS[2][1] * a + LAB_TO_LMS[2][2] * b};
    }

    private static double maxSaturation(double a, double b) {
        int channel = 2;
        for (int i = 0; i < 2; i++)
            if (SATURATION_FIT_DIRECTIONS[i][0] * a + SATURATION_FIT_DIRECTIONS[i][1] * b > 1) { channel = i; break; }
        double[] k = SATURATION_FIT_POLYNOMIAL[channel];
        double[] weights = LMS_TO_LINEAR_SRGB[channel];
        double saturation = k[0] + k[1] * a + k[2] * b + k[3] * a * a + k[4] * a * b;
        double[] slopes = lmsSlopes(a, b);
        double[] base = {1 + saturation * slopes[0], 1 + saturation * slopes[1], 1 + saturation * slopes[2]};
        double f = weights[0] * base[0] * base[0] * base[0] + weights[1] * base[1] * base[1] * base[1]
                + weights[2] * base[2] * base[2] * base[2];
        double f1 = weights[0] * 3 * slopes[0] * base[0] * base[0] + weights[1] * 3 * slopes[1] * base[1] * base[1]
                + weights[2] * 3 * slopes[2] * base[2] * base[2];
        double f2 = weights[0] * 6 * slopes[0] * slopes[0] * base[0] + weights[1] * 6 * slopes[1] * slopes[1] * base[1]
                + weights[2] * 6 * slopes[2] * slopes[2] * base[2];
        return saturation - (f * f1) / (f1 * f1 - 0.5 * f * f2);
    }

    private static double[] cusp(double a, double b) {
        double saturation = maxSaturation(a, b);
        double[] rgb = oklabToLinearSrgb(1, saturation * a, saturation * b);
        double lightness = Math.cbrt(1 / Math.max(rgb[0], Math.max(rgb[1], rgb[2])));
        return new double[]{lightness, lightness * saturation};
    }

    private static double maxChroma(double a, double b, double lightness, double[] peak) {
        if (lightness <= peak[0]) return (peak[1] * lightness) / peak[0];
        double t = (peak[1] * (lightness - 1)) / (peak[0] - 1);
        double[] slopes = lmsSlopes(a, b);
        double[] lms = {lightness + t * slopes[0], lightness + t * slopes[1], lightness + t * slopes[2]};
        double[] cubes = {Math.pow(lms[0], 3), Math.pow(lms[1], 3), Math.pow(lms[2], 3)};
        double[] first = {3 * slopes[0] * lms[0] * lms[0], 3 * slopes[1] * lms[1] * lms[1],
                3 * slopes[2] * lms[2] * lms[2]};
        double[] second = {6 * slopes[0] * slopes[0] * lms[0], 6 * slopes[1] * slopes[1] * lms[1],
                6 * slopes[2] * slopes[2] * lms[2]};
        double best = Double.MAX_VALUE;
        for (int row = 0; row < 3; row++) {
            double[] weights = LMS_TO_LINEAR_SRGB[row];
            double f = weights[0] * cubes[0] + weights[1] * cubes[1] + weights[2] * cubes[2] - 1;
            double f1 = weights[0] * first[0] + weights[1] * first[1] + weights[2] * first[2];
            double f2 = weights[0] * second[0] + weights[1] * second[1] + weights[2] * second[2];
            double u = f1 / (f1 * f1 - 0.5 * f * f2);
            if (u >= 0) best = Math.min(best, -f * u);
        }
        return t + best;
    }

    private static double[] chromaStops(double lightness, double a, double b) {
        double[] peak = cusp(a, b);
        double cMax = maxChroma(a, b, lightness, peak);
        double k = cMax / Math.min(lightness * (peak[1] / peak[0]), (1 - lightness) * (peak[1] / (1 - peak[0])));
        double midS = 0.11516993 + 1 / (7.4477897 + 4.1590124 * b + a * (-2.19557347 + 1.75198401 * b
                + a * (-2.13704948 - 10.02301043 * b + a * (-4.24894561 + 5.38770819 * b + 4.69891013 * a))));
        double midT = 0.11239642 + 1 / (1.6132032 - 0.68124379 * b + a * (0.40370612 + 0.90148123 * b
                + a * (-0.27087943 + 0.6122399 * b + a * (0.00299215 - 0.45399568 * b - 0.14661872 * a))));
        double cMid = 0.9 * k * Math.sqrt(Math.sqrt(
                1 / (Math.pow(1 / (lightness * midS), 4) + Math.pow(1 / ((1 - lightness) * midT), 4))));
        double c0 = Math.sqrt(1 / (Math.pow(1 / (lightness * 0.4), 2) + Math.pow(1 / ((1 - lightness) * 0.8), 2)));
        return new double[]{c0, cMid, cMax};
    }

    /** OKHSL→sRGB；输入 hue 角度、saturation/lightness 0-1，输出 0-255 颜色。 */
    public static Color okhsl(double hue, double saturation, double lightness) {
        double l = okhslToOklabLightness(Math.max(0, Math.min(1, lightness)));
        double a = 0, b = 0;
        double s = Math.max(0, Math.min(1, saturation));
        if (l > 0 && l < 1 && s > 0) {
            double angle = 2 * Math.PI * ((((hue % 360) + 360) % 360) / 360);
            double ca = Math.cos(angle), cb = Math.sin(angle);
            double[] stops = chromaStops(l, ca, cb);
            double chroma;
            if (s < 0.8) {
                double t = 1.25 * s, k1 = 0.8 * stops[0];
                chroma = (t * k1) / (1 - (1 - k1 / stops[1]) * t);
            } else {
                double t = 5 * (s - 0.8), k1 = (0.2 * stops[1] * stops[1] * 1.25 * 1.25) / stops[0];
                chroma = stops[1] + (t * k1) / (1 - (1 - k1 / (stops[2] - stops[1])) * t);
            }
            a = chroma * ca; b = chroma * cb;
        }
        return linearSrgbToRgb(oklabToLinearSrgb(l, a, b));
    }

    /** OKLCH→sRGB；chroma 使用绝对 0-1 比例。 */
    public static Color oklch(double hue, double chroma, double lightness) {
        double angle = Math.toRadians((((hue % 360) + 360) % 360));
        return linearSrgbToRgb(oklabToLinearSrgb(Math.max(0, Math.min(1, lightness)),
                chroma * Math.cos(angle), chroma * Math.sin(angle)));
    }

    /** @return sRGB 转 OKHSL 的 `[hue, saturation, lightness]`，用于 system 主题取色相。 */
    public static double[] rgbToOkhsl(int red, int green, int blue) {
        double[] lab = rgbToOklab(red, green, blue);
        double chroma = Math.hypot(lab[1], lab[2]);
        double lightness = oklabToOkhslLightness(lab[0]);
        if (chroma < 1e-9 || lightness <= 0 || lightness >= 1) return new double[]{0, 0, lightness};
        double hue = ((Math.toDegrees(Math.atan2(lab[2], lab[1])) + 360) % 360);
        double[] stops = chromaStops(lab[0], lab[1] / chroma, lab[2] / chroma);
        double saturation;
        if (chroma < stops[1]) {
            double k1 = 0.8 * stops[0];
            saturation = 0.8 * (chroma / (k1 + (1 - k1 / stops[1]) * chroma));
        } else {
            double k1 = (0.2 * stops[1] * stops[1] * 1.25 * 1.25) / stops[0];
            double offset = chroma - stops[1];
            saturation = 0.8 + 0.2 * (offset / (k1 + (1 - k1 / (stops[2] - stops[1])) * offset));
        }
        return new double[]{hue, Math.max(0, Math.min(1, saturation)), lightness};
    }

    /** @return 颜色亮度（OKHSL lightness），用于判断明暗外观；无法解析时返回 0.5。 */
    public static double lightness(Color color) {
        if (color == null || color.kind() != Color.Kind.RGB) return 0.5;
        return rgbToOkhsl(color.red(), color.green(), color.blue())[2];
    }

    /** @return 16 色索引的近似 RGB，用于终端只上报索引色时生成 system 主题。 */
    public static Color indexedApprox(int index) {
        int[][] base = {{0, 0, 0}, {205, 49, 49}, {13, 188, 121}, {229, 229, 16}, {36, 114, 200},
                {188, 63, 188}, {17, 168, 205}, {229, 229, 229}, {102, 102, 102}, {241, 76, 76},
                {35, 209, 139}, {245, 245, 67}, {59, 142, 234}, {214, 112, 214}, {41, 184, 219}, {255, 255, 255}};
        if (index >= 0 && index < 16) return Color.rgb(base[index][0], base[index][1], base[index][2]);
        return Color.DEFAULT;
    }

    /** @return 颜色的小写十六进制显示，用于测试/诊断。 */
    public static String hex(Color color) {
        if (color.kind() != Color.Kind.RGB) return color.kind().name().toLowerCase(Locale.ROOT);
        return String.format("#%02x%02x%02x", color.red(), color.green(), color.blue());
    }

    /** 列出内置/自定义定义中必须存在的 token；用于配置与测试检查。 */
    public static List<String> requiredTokens() {
        return List.of("text", "muted", "dim", "accent", "border", "success", "error", "warning",
                "selectedBg", "userMessageBg", "userMessageText", "toolPendingBg", "toolSuccessBg",
                "toolErrorBg", "toolTitle", "toolOutput", "mdHeading", "mdLink", "mdCode", "mdCodeBlock",
                "mdQuote", "mdHr", "mdListBullet", "toolDiffAdded", "toolDiffRemoved", "syntaxComment",
                "syntaxKeyword", "syntaxString", "syntaxNumber", "thinkingText", "bashMode");
    }
}
