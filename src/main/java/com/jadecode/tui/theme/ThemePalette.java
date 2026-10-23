package com.jadecode.tui.theme;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.jadecode.tui.text.Color;

/**
 * 主题兼容门面与颜色数学：内置资源定位、旧版解析入口，以及 OKHSL/256 色/对比度工具。
 *
 * <p>第九轮起产品色值的唯一来源是 {@code themes/jadecode.json}（v2，深/浅两个变体），
 * 结构校验在 {@link ThemeDocument}，展开与派生在 {@link ThemeResolver}。本类不再持有
 * 另一套手写色板，也不再生成本地化的 system 强调色：{@link #builtin(String)} 与
 * {@link #system} 都委托同一解析链，只是选择不同变体与终端绑定策略。</p>
 *
 * <p>颜色字面量解析（hex/rgb/okhsl/oklch/oklab）、OKHSL 转换与 256 色近似是协议/数学
 * 工具，不是产品配色；终端查询和编码器继续使用它们。渲染层只接收 {@link Color}，不接触
 * JSON、文件或终端回复。</p>
 *
 * <p>OKHSL 转换移植自 Björn Ottosson 的参考实现（MIT），保证派生与 Pi 使用同一色彩空间。</p>
 */
public final class ThemePalette {
    /** 解析后的主题定义；颜色表不可变；panelBgExplicit 区分用户显式值与派生值。 */
    public record Definition(String name, String appearance, Map<String, Color> colors, boolean panelBgExplicit) {
        public Definition {
            colors = Map.copyOf(colors);
        }
        /** 兼容旧构造：是否显式由颜色表是否自带 panelBg 推断。 */
        public Definition(String name, String appearance, Map<String, Color> colors) {
            this(name, appearance, colors, colors != null && colors.containsKey("panelBg"));
        }
    }

    /** 唯一内置主题资源；迁移后不再有生产 dark/light 两份色表。 */
    private static final String BUILTIN_RESOURCE = "/com/jadecode/tui/themes/jadecode.json";
    /** 内置文档缓存：资源随 JAR 固定，解析一次足够；不会缓存到 View。 */
    private static volatile ThemeDocument builtinDocument;

    private ThemePalette() { }

    /**
     * 读取并缓存唯一内置主题文档（v2，含 dark/light 变体）。
     *
     * @return 不可变文档
     * @throws IllegalStateException 资源缺失/不可读时明确失败，不静默退回任意配色
     */
    public static ThemeDocument builtinDocument() {
        ThemeDocument cached = builtinDocument;
        if (cached != null) return cached;
        try (InputStream input = ThemePalette.class.getResourceAsStream(BUILTIN_RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing builtin theme resource: " + BUILTIN_RESOURCE);
            ThemeDocument parsed = ThemeDocument.parse(
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            builtinDocument = parsed;
            return parsed;
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read builtin theme: " + BUILTIN_RESOURCE, failure);
        }
    }

    /**
     * 兼容入口：内置 dark/light 现在来自同一个双变体文件。
     *
     * @param name dark 或 light
     * @return 对应变体的完整定义，展示名为 dark/light
     */
    public static Definition builtin(String name) {
        String normalized = name == null || name.isBlank() ? ThemeDocument.DARK : name;
        if (!normalized.equals(ThemeDocument.DARK) && !normalized.equals(ThemeDocument.LIGHT))
            throw new IllegalArgumentException("Unknown builtin theme: " + name);
        boolean light = normalized.equals(ThemeDocument.LIGHT);
        return ThemeResolver.resolve(builtinDocument(), normalized, false,
                new ThemeResolver.TerminalFacts(!light, null, null), normalized);
    }

    /**
     * 兼容入口：解析主题 JSON。
     *
     * <p>生产路径（{@link ThemeManager}）按用户选择和终端事实显式选变体；这里只服务旧测试
     * 与诊断调用：v1 单外观直接解析，v2 取 dark 变体当作完整定义。v2 不会被当成 v1 后
     * 静默丢掉 variants。</p>
     *
     * @param json 主题文件内容
     * @return 完整定义
     */
    public static Definition parse(String json) {
        ThemeDocument document = ThemeDocument.parse(json);
        String variant = document.isVersion2() ? ThemeDocument.DARK : ThemeDocument.SINGLE;
        return ThemeResolver.resolve(document, variant, false,
                new ThemeResolver.TerminalFacts(true, null, null), document.name());
    }

    /**
     * 兼容入口：system 主题。
     *
     * <p>S92 裁决后 system 只保留终端背景与普通文字，其余颜色来自统一内置主题；
     * {@code ansi} 参数保留是为了终端查询调用方兼容，产品强调色不再读取它。</p>
     *
     * @param dark 终端背景是否为深色
     * @param foreground 终端默认前景；null 表示未报告
     * @param background 终端默认背景；null 表示未报告
     * @param ansi 0–15 ANSI 色；仅为兼容保留，不参与取色
     * @return 名称为 system 的完整定义
     */
    public static Definition system(boolean dark, Color foreground, Color background, List<Color> ansi) {
        ThemeResolver.TerminalFacts facts = new ThemeResolver.TerminalFacts(dark, foreground, background);
        return ThemeResolver.resolve(builtinDocument(), dark ? ThemeDocument.DARK : ThemeDocument.LIGHT,
                true, facts, "system");
    }

    /** 解析单个颜色字面量；非法值抛出带原文字的异常，由配置读取层显示给用户。 */
    public static Color parseColor(String value) {
        String text = value.trim();
        if (text.startsWith("#")) {
            String hex = text.substring(1);
            if (hex.length() == 3) hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1)
                    + hex.charAt(2) + hex.charAt(2);
            if (hex.length() != 6) throw new IllegalArgumentException("Unsupported color: " + value);
            try {
                return Color.rgb(Integer.parseInt(hex.substring(0, 2), 16),
                        Integer.parseInt(hex.substring(2, 4), 16), Integer.parseInt(hex.substring(4, 6), 16));
            } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Unsupported color: " + value); }
        }
        if (text.regionMatches(true, 0, "rgb(", 0, 4) && text.endsWith(")")) {
            String[] parts = text.substring(4, text.length() - 1).split("[,/ ]+");
            if (parts.length < 3) throw new IllegalArgumentException("Unsupported rgb color: " + value);
            try {
                int[] rgb = new int[3];
                for (int i = 0; i < 3; i++) rgb[i] = Integer.parseInt(parts[i].trim());
                return Color.rgb(rgb[0], rgb[1], rgb[2]);
            } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Unsupported rgb color: " + value); }
        }
        if (text.regionMatches(true, 0, "okhsl(", 0, 6) && text.endsWith(")")) {
            double[] channels = channels(text.substring(6, text.length() - 1));
            if (channels.length < 3) throw new IllegalArgumentException("Unsupported okhsl color: " + value);
            return okhsl(channels[0], channels[1], channels[2]);
        }
        if (text.regionMatches(true, 0, "oklch(", 0, 6) && text.endsWith(")")) {
            double[] channels = channels(text.substring(6, text.length() - 1));
            if (channels.length < 3) throw new IllegalArgumentException("Unsupported oklch color: " + value);
            return oklch(channels[0], channels[1], channels[2]);
        }
        if (text.regionMatches(true, 0, "oklab(", 0, 6) && text.endsWith(")")) {
            double[] channels = channels(text.substring(6, text.length() - 1));
            if (channels.length < 3) throw new IllegalArgumentException("Unsupported oklab color: " + value);
            return linearSrgbToRgb(oklabToLinearSrgb(channels[0], channels[1], channels[2]));
        }
        throw new IllegalArgumentException("Unsupported color: " + value);
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
                throw new IllegalArgumentException("Color component is not a number: " + body);
            }
        }
        double[] result = new double[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }



    /**
     * 面板底色（R6-03）：以已知/假定终端背景为基准，向对比方向中性色混合约 10%。
     * 深色背景提亮一档、浅色背景压暗一档，不引入新的强调色。
     * @param background 实际或假定的终端背景
     * @param dark 该背景是否属于深色外观
     * @return 不透明的面板背景色
     */
    public static Color derivePanelBg(Color background, boolean dark) {
        return derivePanelBg(background, dark, ThemeDocument.DEFAULT_PANEL_BG_MIX);
    }

    /**
     * 按主题文件声明的混合比例派生面板底色。
     *
     * <p>比例来自 {@code derivations.panelBgMix}（资产为 0.10）；解析器已校验为 0–1，
     * 这里再夹一次，避免非有限值让颜色计算产生 NaN。</p>
     *
     * @param background 实际或假定的终端背景
     * @param dark 该背景是否属于深色外观
     * @param mixRatio 向对比方向中性色混合的比例（0–1）
     * @return 不透明的面板背景色
     */
    public static Color derivePanelBg(Color background, boolean dark, double mixRatio) {
        Color base = background != null && background.kind() == Color.Kind.RGB
                ? background : (dark ? Color.rgb(0, 0, 0) : Color.rgb(255, 255, 255));
        double weight = Double.isFinite(mixRatio) ? Math.max(0, Math.min(1, mixRatio))
                : ThemeDocument.DEFAULT_PANEL_BG_MIX;
        return mix(base, dark ? Color.rgb(255, 255, 255) : Color.rgb(0, 0, 0), weight);
    }

    /**
     * 按底色亮度选择黑/白前景，供审批按钮与派生角色共用。
     *
     * <p>输入可能是 RGB 或 0–255 索引色；索引色先按终端近似值展开。sRGB 必须先转线性
     * 亮度再加权，否则黄底会被误判为深色而配白字。没有可计算颜色时不强造 RGB，返回
     * NULL_COLOR 由调用方决定兜底方式。</p>
     *
     * @param color 按钮/选区底色
     * @return 对比度更高的黑或白；不可计算时返回 {@link Color#DEFAULT}
     */
    public static Color contrastForeground(Color color) {
        if (color == null || color.isDefault()) return Color.DEFAULT;
        int red, green, blue;
        if (color.kind() == Color.Kind.INDEXED) {
            Color approx = indexedApprox(color.index());
            if (approx.isDefault()) return Color.DEFAULT;
            red = approx.red(); green = approx.green(); blue = approx.blue();
        } else if (color.kind() == Color.Kind.RGB) {
            red = color.red(); green = color.green(); blue = color.blue();
        } else {
            return Color.DEFAULT;
        }
        double luminance = 0.2126 * linearChannel(red) + 0.7152 * linearChannel(green)
                + 0.0722 * linearChannel(blue);
        return luminance >= 0.179 ? Color.rgb(0, 0, 0) : Color.rgb(255, 255, 255);
    }

    /** @return sRGB 0–255 分量转线性亮度。 */
    private static double linearChannel(int value) {
        double channel = value / 255.0;
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
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
