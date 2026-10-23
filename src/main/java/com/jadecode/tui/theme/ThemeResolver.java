package com.jadecode.tui.theme;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.jadecode.tui.text.Color;

/**
 * 纯计算的主题解析器：把不可变 {@link ThemeDocument} 展开成完整颜色快照。
 *
 * <p>第九轮的核心职责划分：文件读取与 JSON 结构在 {@link ThemeDocument}，本类不做任何
 * IO；它只接收“选中哪个变体 / 是否绑定终端 / 终端事实”并返回既有
 * {@link ThemePalette.Definition}，供 {@link ThemeManager} 在同一 UI 线程生成
 * {@link Theme} 快照。组件仍然只消费语义角色，不知道主题文件、变体或变量引用。</p>
 *
 * <p>解析顺序（与 Spec 一致）：</p>
 * <ol>
 *   <li>system 主题在展开引用前绑定 {@code foreground}/{@code background} 变量、
 *       {@code text} 根角色与已声明的 {@code surfaceBg}，因此普通文字跟随真实终端；</li>
 *   <li>展开变量与角色，检测循环/未知引用，识别显式空值与受限的 {@code auto}；</li>
 *   <li>按“显式值 → 兼容语义兜底 → 同外观内置默认 → Color.DEFAULT”补齐缺失角色；</li>
 *   <li>最后计算 {@code panelBg} 与审批前景等派生值，显式值优先。</li>
 * </ol>
 *
 * <p>品牌与语法角色不被终端 ANSI 槽覆盖；终端色板只影响真实前景/背景与派生方向，避免
 * 用户换终端配色时整体强调色悄悄漂移。</p>
 */
public final class ThemeResolver {
    /** 允许在角色中出现 auto 的键；其它角色/变量出现 auto 必须报错。 */
    private static final Set<String> AUTO_ROLES = Set.of(
            "panelBg", "approvalOnceFg", "approvalSimilarFg", "approvalDenyFg");

    /** 兼容语义兜底：旧自定义主题没有新角色时，沿用已有的相近语义色。 */
    private static final Map<String, String> FALLBACKS = Map.ofEntries(
            Map.entry("borderAccent", "border"), Map.entry("borderMuted", "border"),
            Map.entry("customMessageLabel", "accent"), Map.entry("mdLinkUrl", "muted"),
            // 旧自定义主题缺失标题/正文链接/行内代码时优先沿用其 accent，而不是内置蓝色。
            Map.entry("mdHeading", "accent"), Map.entry("mdLink", "accent"),
            Map.entry("mdCode", "accent"), Map.entry("mdCodeBlock", "text"),
            Map.entry("mdQuote", "muted"), Map.entry("userMessageText", "text"),
            Map.entry("customMessageText", "text"),
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
            Map.entry("thinkingXhigh", "thinkingText"), Map.entry("thinkingMax", "thinkingXhigh"),
            Map.entry("bashMode", "success"),
            // Jade 卡片/审批扩展：旧主题缺失时退回自身已有语义色，不凭空出现固定 RGB。
            Map.entry("requestTitle", "customMessageLabel"), Map.entry("replyTitle", "accent"),
            Map.entry("thinkingTitle", "thinkingText"), Map.entry("approval", "borderAccent"),
            Map.entry("approvalDeny", "error"), Map.entry("permission", "warning"),
            // S85 品牌扩展 + S94 蓝色层次：字标轮廓退回字身，旧主题保持原单色意图。
            Map.entry("brandPrimary", "accent"), Map.entry("brandAccent", "warning"),
            Map.entry("brandFrame", "border"), Map.entry("brandBannerBody", "brandPrimary"),
            Map.entry("brandBannerShadow", "brandBannerBody"),
            // 审批按钮底色：旧/最小主题使用自己的成功/警告/失败语义。
            Map.entry("approvalOnceBg", "success"), Map.entry("approvalSimilarBg", "warning"),
            Map.entry("approvalDenyBg", "error"));

    /**
     * 同外观内置默认不作为可继承语义兜底的角色。
     *
     * <p>panelBg/审批前景/审批底色必须由当前主题派生；品牌角色始终先跟随当前主题的
     * accent/warning/border 语义，否则旧自定义主题会突然冒出内置蓝色字标。</p>
     */
    private static final Set<String> DERIVED_ROLES = Set.of(
            "panelBg", "approvalOnceBg", "approvalSimilarBg", "approvalDenyBg",
            "approvalOnceFg", "approvalSimilarFg", "approvalDenyFg",
            "brandPrimary", "brandAccent", "brandFrame", "brandBannerBody", "brandBannerShadow");

    /** 内置统一文档的深/浅默认色缓存；由解析器自身生成，不构成第二份手写色表。 */
    private static volatile Map<String, Color> stockDark;
    private static volatile Map<String, Color> stockLight;

    private ThemeResolver() { }

    /**
     * 终端事实：只包含主题需要知道的明暗与已上报的真实前景/背景。
     *
     * @param dark 终端背景是否为深色；没有可靠报告时由调用方沿用已知外观
     * @param foreground 终端上报的默认前景；null 表示尚未报告
     * @param background 终端上报的默认背景；null 表示尚未报告
     */
    public record TerminalFacts(boolean dark, Color foreground, Color background) {
        /** @return 上报的前景是否为可直接使用的 RGB 颜色。 */
        public boolean hasForeground() {
            return foreground != null && foreground.kind() == Color.Kind.RGB;
        }

        /** @return 上报的背景是否为可直接使用的 RGB 颜色。 */
        public boolean hasBackground() {
            return background != null && background.kind() == Color.Kind.RGB;
        }
    }

    /**
     * 解析一个变体。
     *
     * @param document 已解析的主题文档
     * @param variantName 目标变体；v2 只接受 dark/light，v1 忽略
     * @param bindTerminal 是否启用 S92 的前景/背景绑定（仅 system）
     * @param facts 当前终端事实；不得为 null
     * @param displayName 生成快照的展示名（system/dark/light/自定义）
     * @return 完整定义；panelBgExplicit 表示 panelBg 来自文件显式值
     */
    public static ThemePalette.Definition resolve(ThemeDocument document, String variantName,
            boolean bindTerminal, TerminalFacts facts, String displayName) {
        return resolve(document, variantName, bindTerminal, facts, displayName, true);
    }

    /**
     * 内部解析入口；{@code useStock=false} 用于生成内置默认色缓存，避免自我递归。
     */
    private static ThemePalette.Definition resolve(ThemeDocument document, String variantName,
            boolean bindTerminal, TerminalFacts facts, String displayName, boolean useStock) {
        ThemeDocument.Variant variant = document.variant(variantName);
        String appearance = appearanceOf(document, variant);
        boolean darkAppearance = !appearance.equalsIgnoreCase("light");
        Map<String, Color> bound = new LinkedHashMap<>();
        if (bindTerminal) {
            if (facts.hasForeground()) bound.put("foreground", facts.foreground());
            if (facts.hasBackground()) bound.put("background", facts.background());
        }
        Map<String, ThemeDocument.Raw> declaredVars = variant.vars();
        Map<String, Color> vars = resolveVariables(declaredVars, bound);
        Map<String, Color> colors = resolveColors(variant, bound, declaredVars, facts, bindTerminal);
        if (!colors.containsKey("text"))
            throw new IllegalArgumentException("Theme requires at least text and accent colors");
        if (!colors.containsKey("accent"))
            throw new IllegalArgumentException("Theme requires at least text and accent colors");
        semanticFallbacks(colors);
        if (useStock) stockFallbacks(colors, appearance);
        // 同外观默认补齐后再跑一次兼容兜底：旧主题缺失 warning 等语义色时，品牌色应跟随
        // 最终解析出的语义色，而不是因为执行顺序拿到内置品牌值。
        semanticFallbacks(colors);
        boolean panelExplicit = colors.containsKey("panelBg");
        Color backgroundContext = facts.hasBackground() ? facts.background() : vars.get("background");
        if (!panelExplicit)
            colors.put("panelBg", ThemePalette.derivePanelBg(backgroundContext, darkAppearance,
                    variant.panelBgMix()));
        fillApprovalForegrounds(colors);
        // 仍缺失的极端最小主题用继承/普通文字兜底，保证不可变表里没有 null。
        colors.putIfAbsent("text", Color.DEFAULT);
        colors.putIfAbsent("muted", colors.get("text"));
        colors.putIfAbsent("dim", colors.get("muted"));
        colors.putIfAbsent("accent", colors.get("text"));
        colors.putIfAbsent("border", colors.get("muted"));
        colors.putIfAbsent("selectedBg", Color.DEFAULT);
        return new ThemePalette.Definition(displayName, appearance, colors, panelExplicit);
    }

    /**
     * 展开变量：先检查终端绑定，再处理索引色/空值/字面量/引用。
     *
     * @param declared 文件声明的变量
     * @param bound 终端绑定值（仅 system）
     * @return 变量名到颜色；顺序保留
     */
    private static Map<String, Color> resolveVariables(Map<String, ThemeDocument.Raw> declared,
            Map<String, Color> bound) {
        var result = new LinkedHashMap<String, Color>();
        for (var entry : declared.entrySet()) {
            if (bound.containsKey(entry.getKey())) {
                result.put(entry.getKey(), bound.get(entry.getKey()));
                continue;
            }
            result.put(entry.getKey(), resolveVariable(entry.getKey(), declared, bound,
                    new java.util.HashSet<>()));
        }
        for (var entry : bound.entrySet()) result.putIfAbsent(entry.getKey(), entry.getValue());
        return result;
    }

    /** 递归解析单个变量；引用链只在同一个变体内查找，不跨变体串用。 */
    private static Color resolveVariable(String key, Map<String, ThemeDocument.Raw> declared,
            Map<String, Color> bound, Set<String> visiting) {
        Color boundColor = bound.get(key);
        if (boundColor != null) return boundColor;
        ThemeDocument.Raw raw = declared.get(key);
        if (raw == null) throw new IllegalArgumentException("Theme variable does not exist: " + key);
        if (raw.index() != null) return Color.indexed(raw.index());
        String text = raw.text() == null ? "" : raw.text();
        if (text.isEmpty()) return Color.DEFAULT;
        if (text.equals("auto"))
            throw new IllegalArgumentException("Theme variable " + key + " cannot use auto");
        Color literal = literal(text);
        if (literal != null) return literal;
        if (!visiting.add(key))
            throw new IllegalArgumentException("Cycle in theme variable references: " + key);
        Color resolved = resolveVariable(text, declared, bound, visiting);
        visiting.remove(key);
        return resolved;
    }

    /**
     * 展开角色：显式空值保留为 DEFAULT；受限 auto 先记录；裸名只引用本变体 vars。
     */
    private static Map<String, Color> resolveColors(ThemeDocument.Variant variant,
            Map<String, Color> bound, Map<String, ThemeDocument.Raw> declaredVars,
            TerminalFacts facts, boolean bindTerminal) {
        var colors = new LinkedHashMap<String, Color>();
        for (var entry : variant.colors().entrySet()) {
            String key = entry.getKey();
            // system 绑定必须发生在展开引用之前：text/surfaceBg 直接取已报告的真实颜色。
            if (bindTerminal && key.equals("text") && facts.hasForeground()) {
                colors.put(key, facts.foreground());
                continue;
            }
            if (bindTerminal && key.equals("surfaceBg") && facts.hasBackground()) {
                colors.put(key, facts.background());
                continue;
            }
            ThemeDocument.Raw raw = entry.getValue();
            if (raw.index() != null) {
                colors.put(key, Color.indexed(raw.index()));
                continue;
            }
            String text = raw.text() == null ? "" : raw.text();
            if (text.isEmpty()) {
                colors.put(key, Color.DEFAULT);
                continue;
            }
            if (text.equals("auto")) {
                if (!AUTO_ROLES.contains(key))
                    throw new IllegalArgumentException("Theme color " + key + " cannot use auto");
                // auto 角色由后续派生阶段补上，这里不写入占位值。
                continue;
            }
            Color literal = literal(text);
            if (literal != null) {
                colors.put(key, literal);
                continue;
            }
            if (!declaredVars.containsKey(text) && !bound.containsKey(text))
                throw new IllegalArgumentException("Theme variable does not exist: " + text);
            colors.put(key, resolveVariable(text, declaredVars, bound, new java.util.HashSet<>()));
        }
        return colors;
    }

    /**
     * 兼容语义兜底：只补缺失键，绝不覆盖显式值（包括显式空值）。
     *
     * <p>多轮执行是因为兜底关系本身有链：{@code brandBannerShadow ← brandBannerBody ←
     * brandPrimary ← accent}，一轮只能前进一层。</p>
     */
    private static void semanticFallbacks(Map<String, Color> colors) {
        for (int pass = 0; pass < 4; pass++) {
            putIfMissing(colors, "muted", "text");
            putIfMissing(colors, "dim", "muted");
            putIfMissing(colors, "border", "muted");
            putIfMissing(colors, "borderMuted", "border");
            putIfMissing(colors, "thinkingText", "muted");
            putIfMissing(colors, "mdCodeBlock", "text");
            for (var entry : FALLBACKS.entrySet()) putIfMissing(colors, entry.getKey(), entry.getValue());
        }
    }

    /**
     * 同外观内置默认兜底：兼容语义兜底仍缺失的角色取统一内置主题对应值，最后才继承。
     * 派生角色（panelBg/审批前景底色）不复制，以免把内置背景或对比结果当成用户主题值。
     */
    private static void stockFallbacks(Map<String, Color> colors, String appearance) {
        for (var entry : stock(appearance).entrySet()) {
            if (DERIVED_ROLES.contains(entry.getKey())) continue;
            colors.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 计算审批三个选项的前景：显式值优先；缺失/auto 时按底色黑白对比，底色未知时退回普通
     * 文本色，保留可见选择标记但不伪造已知 RGB。
     */
    private static void fillApprovalForegrounds(Map<String, Color> colors) {
        for (var pair : List.of(
                Map.entry("approvalOnceBg", "approvalOnceFg"),
                Map.entry("approvalSimilarBg", "approvalSimilarFg"),
                Map.entry("approvalDenyBg", "approvalDenyFg"))) {
            if (colors.containsKey(pair.getValue())) continue;
            Color background = colors.get(pair.getKey());
            Color fallbackText = colors.get("text");
            if (background == null || background.isDefault())
                colors.put(pair.getValue(), fallbackText == null ? Color.DEFAULT : fallbackText);
            else colors.put(pair.getValue(), ThemePalette.contrastForeground(background));
        }
    }

    /** @return 变体对应的设计外观；v1 用文件声明，v2 用变体键。 */
    private static String appearanceOf(ThemeDocument document, ThemeDocument.Variant variant) {
        if (document.isVersion2()) return variant.name();
        String declared = variant.appearance();
        return declared == null || declared.isBlank() ? "dark" : declared;
    }

    /**
     * @return 内置统一主题某外观的完整默认色；首次调用时由同一解析器生成并缓存。
     */
    private static Map<String, Color> stock(String appearance) {
        boolean light = "light".equalsIgnoreCase(appearance);
        Map<String, Color> cached = light ? stockLight : stockDark;
        if (cached != null) return cached;
        ThemeDocument document = ThemePalette.builtinDocument();
        Map<String, Color> built = resolve(document, light ? ThemeDocument.LIGHT : ThemeDocument.DARK,
                false, new TerminalFacts(!light, null, null), "stock", false).colors();
        if (light) stockLight = built; else stockDark = built;
        return built;
    }

    private static void putIfMissing(Map<String, Color> colors, String key, String source) {
        if (colors.containsKey(key)) return;
        Color value = colors.get(source);
        if (value != null) colors.put(key, value);
    }

    /** @return 颜色字面量；不是字面量时返回 null（调用方按变量引用处理）。 */
    private static Color literal(String text) {
        if (text.startsWith("#") || text.regionMatches(true, 0, "rgb(", 0, 4)
                || text.regionMatches(true, 0, "okhsl(", 0, 6)
                || text.regionMatches(true, 0, "oklch(", 0, 6)
                || text.regionMatches(true, 0, "oklab(", 0, 6)) return ThemePalette.parseColor(text);
        return null;
    }
}
