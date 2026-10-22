package com.jadecode.cli.tui.view;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * JadeCode 欢迎区品牌：用户原始 mascot + 六行 JADE CODE 字标、真实版本与必要说明。
 *
 * <p>宽屏把六行 mascot 放在左、六行字标放在右（固定三列间隔），版本/说明在下方；
 * 宽度只够 mascot 时保留原画并配单行蓝色 {@code JADE CODE}；再窄或终端高度不足时降级为
 * 一行名字加必要说明，绝不输出半张字符画。档位只由 {@link DisplayWidth} 的终端列和真实
 * 可用行预算决定，不使用浏览器像素、CSS breakpoint 或 Java 字符长度。</p>
 *
 * <p>颜色按字符区域表达：字标全部 {@code brandPrimary}；mascot 边框 {@code brandFrame}，
 * 三个圆点/{@code $}/{@code ▸}/末尾 {@code _} 为 {@code brandAccent}，mascot 内
 * {@code JADE} 用品牌蓝，{@code jade}/{@code ready} 用正文色。这里只生成 Span/Style，
 * 不把 ANSI 塞进资源文本；NO_COLOR 时主题返回 PLAIN，字符、边框、版本和布局仍然保留。</p>
 *
 * <p>资源只读缓存一次，缺失时抛出明确错误（英文）而不是悄悄画空白。本类不读 Host、
 * 配置或网络，也不写终端；真实版本由调用方传入，避免写死版本号。</p>
 */
public final class BrandingView {
    /** mascot 与字标之间的固定列数。 */
    private static final int GAP = 3;
    /** 完整字符画所需的最低终端行数；低于它先保障聊天/输入区。 */
    private static final int MIN_ART_ROWS = 22;
    /** 单行普通字标文本。 */
    private static final String WORDMARK = "JADE CODE";

    /** 档位只用于测试/诊断；真实选择在 {@link #render} 内按宽高计算。 */
    public enum Mode { FULL, COMPACT, LINE }

    /** 只读字符资产；第一次使用时从 classpath 读取，之后复用。 */
    private static final class Assets {
        static final List<String> MASCOT = load("mascot.txt");
        static final List<String> BANNER = load("banner.txt");
        static final int MASCOT_WIDTH = maxWidth(MASCOT);
        static final int BANNER_WIDTH = maxWidth(BANNER);

        private static List<String> load(String name) {
            String resource = "/com/jadecode/tui/branding/" + name;
            try (InputStream input = BrandingView.class.getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("Missing branding resource: " + resource);
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                var lines = new ArrayList<String>();
                for (String line : text.split("\n", -1)) lines.add(line.stripTrailing());
                // 文件末尾的换行不产生额外空行；中间空行仍保留。
                while (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
                if (lines.isEmpty()) throw new IllegalStateException("Empty branding resource: " + resource);
                return List.copyOf(lines);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot read branding resource: " + resource, failure);
            }
        }

        private static int maxWidth(List<String> lines) {
            int width = 0;
            for (String line : lines) width = Math.max(width, DisplayWidth.width(line));
            return width;
        }
    }

    /** @return 原始 mascot 行（只读）；测试和布局诊断使用。 */
    public static List<String> mascotLines() { return Assets.MASCOT; }

    /** @return 原始六行字标（只读）；测试和布局诊断使用。 */
    public static List<String> bannerLines() { return Assets.BANNER; }

    /** @return mascot 实测终端列宽。 */
    public static int mascotWidth() { return Assets.MASCOT_WIDTH; }

    /** @return 字标实测终端列宽。 */
    public static int bannerWidth() { return Assets.BANNER_WIDTH; }

    /** @return 宽屏并排所需的实测列宽（mascot + 间隔 + 字标）。 */
    public static int fullWidth() { return Assets.MASCOT_WIDTH + GAP + Assets.BANNER_WIDTH; }

    /** @return 紧凑档位所需列宽（mascot + 间隔 + 单行 JADE CODE）。 */
    public static int compactWidth() { return Assets.MASCOT_WIDTH + GAP + WORDMARK.length(); }

    /**
     * 按真实宽度/行预算选择档位；只做几何判断，不产生副作用。
     *
     * @param width 欢迎区可用列数
     * @param budgetRows 欢迎区可用行数（真实视口/终端预算）
     * @return FULL、COMPACT 或 LINE
     */
    public static Mode mode(int width, int budgetRows) {
        if (budgetRows >= MIN_ART_ROWS && width >= fullWidth()) return Mode.FULL;
        if (budgetRows >= MIN_ART_ROWS && width >= compactWidth()) return Mode.COMPACT;
        return Mode.LINE;
    }

    /**
     * 渲染品牌区。
     *
     * @param version 真实版本号；空/未知时省略 `v` 前缀，不伪造版本
     * @param context 同帧主题（品牌色/NO_COLOR）
     * @param width 可用列数
     * @param budgetRows 可用行数
     * @return 品牌行；LINE 档位为两行，其余为字符画加版本/说明
     */
    public List<StyledLine> render(String version, RenderContext context, int width, int budgetRows) {
        int columns = Math.max(1, width);
        int rows = Math.max(1, budgetRows);
        Mode selected = mode(columns, rows);
        var out = new ArrayList<StyledLine>();
        String versionLabel = version == null || version.isBlank() ? "" : " v" + version;
        // 必要说明使用真实运行时 Java feature；终端实际是 21 时与设计值一致，升级后不会撒谎。
        String info = "coding agent · Java " + Runtime.version().feature();
        if (selected == Mode.LINE) {
            var title = new ArrayList<Span>();
            title.add(new Span("JadeCode", context.theme().style("brandPrimary"), null));
            if (!versionLabel.isBlank()) title.add(new Span(versionLabel, context.theme().style("muted"), null));
            out.add(new StyledLine(title).crop(columns));
            out.add(StyledLine.of(DisplayWidth.ellipsize(info, columns), context.theme().style("muted")));
            return out;
        }
        int artRows = Math.min(Assets.MASCOT.size(), Assets.BANNER.size());
        for (int i = 0; i < artRows; i++) {
            var spans = new ArrayList<Span>();
            spans.addAll(mascotLine(Assets.MASCOT.get(i), context));
            spans.add(new Span(" ".repeat(GAP), Style.PLAIN, null));
            if (selected == Mode.FULL) {
                // 原字标短行在布局时右补齐到实测最大宽，保持右边界整齐；不改变字形本身。
                String banner = Assets.BANNER.get(i);
                int padding = Math.max(0, Assets.BANNER_WIDTH - DisplayWidth.width(banner));
                spans.add(new Span(banner + " ".repeat(padding), context.theme().style("brandPrimary"), null));
            } else if (i == 0) {
                // 紧凑档位只把普通字标放在第一行，与 mascot 顶对齐，不纵向拉伸。
                spans.add(new Span(WORDMARK, context.theme().style("brandPrimary"), null));
            }
            out.add(new StyledLine(spans));
        }
        var title = new ArrayList<Span>();
        title.add(new Span("JadeCode", context.theme().style("brandPrimary"), null));
        if (!versionLabel.isBlank()) title.add(new Span(versionLabel, context.theme().style("muted"), null));
        out.add(new StyledLine(title));
        out.add(StyledLine.of(DisplayWidth.ellipsize(info, columns), context.theme().style("muted")));
        return out;
    }

    /**
     * 把 mascot 一行拆成品牌色 Span：边框/空白、圆点与提示符、JADE、普通小写文字。
     * 资源是用户原始字符画，按字符类别着色而不是在最终 ANSI 字符串上正则替换。
     */
    private static List<Span> mascotLine(String line, RenderContext context) {
        var spans = new ArrayList<Span>();
        Style frame = context.theme().style("brandFrame");
        Style primary = context.theme().style("brandPrimary");
        Style accent = context.theme().style("brandAccent");
        Style text = context.theme().style("text");
        var buffer = new StringBuilder();
        Style current = null;
        for (String cluster : DisplayWidth.clusters(line)) {
            Style style = mascotStyle(cluster, frame, primary, accent, text);
            if (current != null && current.equals(style)) { buffer.append(cluster); continue; }
            if (!buffer.isEmpty()) spans.add(new Span(buffer.toString(), current, null));
            buffer.setLength(0); buffer.append(cluster); current = style;
        }
        if (!buffer.isEmpty()) spans.add(new Span(buffer.toString(), current, null));
        return spans;
    }

    /** 单个字素的语义颜色；未知字符按正文色，避免吞掉用户字符。 */
    private static Style mascotStyle(String cluster, Style frame, Style primary, Style accent, Style text) {
        if (cluster.equals("●") || cluster.equals("$") || cluster.equals("▸") || cluster.equals("_")) return accent;
        if (cluster.equals("┏") || cluster.equals("━") || cluster.equals("┓") || cluster.equals("┃")
                || cluster.equals("┣") || cluster.equals("┫") || cluster.equals("┗") || cluster.equals("┛")
                || cluster.equals(" ")) return frame;
        if (cluster.chars().allMatch(Character::isUpperCase) && cluster.chars().anyMatch(Character::isLetter))
            return primary;
        return text;
    }
}
