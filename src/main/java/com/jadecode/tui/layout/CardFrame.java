package com.jadecode.tui.layout;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * 输入区卡片的共享几何：两种外观（Pi 上下横线 / 审批闭合卡片）、内框、面板底色与正文矩形。
 *
 * <p>第九轮（S93）把完整交互页从“四角闭合卡片”改成 Pi 的上下横线样式：标题放在上横线右端，
 * 没有左右竖边，也不整块铺 {@code panelBg}；审批（{@code APPROVAL_BOX}）继续保持原闭合卡片、
 * 左右 padding、绿/黄/红按钮底色和全部安全证据。外观必须由 {@link #measure} 决定并随
 * {@link Metrics} 传给 {@link #compose}，不能测量一种框却绘制另一种框。</p>
 *
 * <p>坐标约定：返回的行都是“卡片内相对行”，0 是上横线/上边框。Pi 模式
 * {@link Metrics#contentX()} 为 0、{@link Metrics#contentWidth()} 为总宽 W，查询/箭头从 0 列
 * 开始，标签/说明的两列缩进由页面生成一次；审批模式仍是 {@code contentX=1+padding}、
 * {@code contentWidth=W-2-2*padding}。卡片总宽按 {@link DisplayWidth} 的终端列计算。</p>
 *
 * <p>降级顺序：宽度/行预算不足 3 行时返回 {@linkplain Metrics#minimal() 最小模式}，由调用方
 * 输出一行“标题 · 关闭/拒绝提示”。短页按内容收缩，不补到预算上限。</p>
 */
public final class CardFrame {
    private CardFrame() { }

    /**
     * 卡片外观。
     *
     * <p>{@link #APPROVAL_BOX} 是审批的安全外观：闭合四角/侧边、padding、panelBg。
     * {@link #PI_SECTION} 是信息页/选择器/搜索的 Pi 外观：上下横线、右上标题、无侧边、
     * 默认继承周围底色。</p>
     */
    public enum Surface {
        /** 四角闭合、左右竖边、正文 padding、整块 panelBg（审批保留）。 */
        APPROVAL_BOX,
        /** Pi 上下横线 + 右上标题，无侧边、不铺面板底色。 */
        PI_SECTION
    }

    /**
     * 一次卡片测量的结果。
     *
     * @param width 卡片总列数（已夹到至少 1）
     * @param maxRows 卡片最多可占的行数（含上下横线/边框/固定区/底部提示）
     * @param padding 正文左右各留的空白列（0/1/2；Pi 外观恒为 0）
     * @param contentWidth 正文可用列数；Pi 外观等于 width
     * @param contentX 正文起始列（审批为左括号+padding；Pi 为 0）
     * @param headerRows 固定头部行数（如 Hotkeys 的分类导航），不随正文滚动
     * @param bodyRows 正文最多可显示的行数；实际高度由 {@link #compose} 按内容收缩
     * @param footer 是否为底部提示预留一行
     * @param minimal 是否进入单行安全降级
     * @param surface 外观；决定 compose 画闭合卡片还是 Pi 上下横线
     */
    public record Metrics(int width, int maxRows, int padding, int contentWidth, int contentX,
            int headerRows, int bodyRows, boolean footer, boolean minimal, Surface surface) {
        public Metrics {
            surface = surface == null ? Surface.APPROVAL_BOX : surface;
        }

        /** 兼容旧构造：历史调用没有外观信息，一律按闭合卡片解释。 */
        public Metrics(int width, int maxRows, int padding, int contentWidth, int contentX,
                int headerRows, int bodyRows, boolean footer, boolean minimal) {
            this(width, maxRows, padding, contentWidth, contentX, headerRows, bodyRows, footer, minimal,
                    Surface.APPROVAL_BOX);
        }
    }

    /**
     * 按闭合卡片测量（兼容旧调用，等价于 {@link Surface#APPROVAL_BOX}）。
     */
    public static Metrics measure(int width, int maxRows, int headerRows, boolean footer) {
        return measure(width, maxRows, headerRows, footer, Surface.APPROVAL_BOX);
    }

    /**
     * 计算卡片几何。调用方先给出固定头部行数与是否保留底部提示，再按返回的
     * {@code contentWidth} 生成正文换行，最后交给 {@link #compose} 组装。
     *
     * @param width 卡片可用总宽（通常是当帧输入区宽度）
     * @param maxRows 卡片可用总行数；调用方应先扣除状态栏/footer/widget
     * @param headerRows 不滚动的头部行数，负数按 0
     * @param footer 是否有独立的底部提示行
     * @param surface 目标外观
     * @return 不可变几何；极窄/极矮时为 minimal
     */
    public static Metrics measure(int width, int maxRows, int headerRows, boolean footer, Surface surface) {
        int columns = Math.max(1, width);
        int budget = Math.max(1, maxRows);
        int head = Math.max(0, headerRows);
        Surface resolved = surface == null ? Surface.APPROVAL_BOX : surface;
        if (resolved == Surface.PI_SECTION) {
            // Pi 外观需要一条上横线、至少一行正文和一条下横线。
            if (columns < 4 || budget < 3)
                return new Metrics(columns, budget, 0, columns, 0, 0, 0, false, true, resolved);
            int sectionBody = Math.max(0, budget - 2 - head - (footer ? 1 : 0));
            return new Metrics(columns, budget, 0, columns, 0, head, sectionBody, footer, false, resolved);
        }
        if (columns < 10 || budget < 3) {
            return new Metrics(columns, budget, 0, columns, 0, 0, 0, false, true, resolved);
        }
        // 一般左右各 2 列；11–13 列只够 1 列；更窄时不加 padding，保证至少 1 列正文。
        int padding = columns >= 14 ? 2 : (columns >= 11 ? 1 : 0);
        // 高度很紧时先牺牲留白，保留标题、正文和底边；这比截断边框更可读。
        if (budget < 6 && padding == 2) padding = 1;
        int contentWidth = Math.max(1, columns - 2 - padding * 2);
        int reserved = 2 + head + (footer ? 1 : 0);
        int bodyRows = Math.max(0, budget - reserved);
        // 头部本身已超过预算时仍让 bodyRows 为 0，由 compose 截到 maxRows，不抛异常。
        return new Metrics(columns, budget, padding, contentWidth, 1 + padding, head, bodyRows, footer, false,
                resolved);
    }

    /**
     * 组装完整卡片行。
     *
     * <p>闭合卡片：正文按 {@code metrics.bodyRows()} 截取，整块铺 {@code panelBg}，按钮等显式
     * 背景保留自己的颜色。Pi 外观：上下横线 + 右上标题，正文不铺面板底色、不加左右侧边，
     * 短页自然收缩。两种外观都不主动补空行。</p>
     *
     * @param title 嵌入上边框/上横线的标题；超宽时省略
     * @param minimalHint 最小模式的关闭/拒绝提示，例如 {@code Esc close}
     * @param header 固定头部行（内容坐标），只取前 headerRows 行
     * @param body 正文行（内容坐标），按行顺序取前 bodyRows 行
     * @param footer 底部提示（内容坐标）；为 null 或 metrics 未预留时不画
     * @param metrics 同一帧的测量结果
     * @param context 同帧主题/上下文
     * @return 卡片行；最小模式只有一行
     */
    public static List<StyledLine> compose(String title, String minimalHint, List<StyledLine> header,
            List<StyledLine> body, StyledLine footer, Metrics metrics, RenderContext context) {
        if (metrics.minimal()) {
            String hint = minimalHint == null || minimalHint.isBlank() ? "Esc close" : minimalHint;
            String text = safe(title) + " · " + hint;
            return List.of(fill(StyledLine.of(DisplayWidth.ellipsize(text, metrics.width()),
                    context.theme().style("text")), metrics.width(), context));
        }
        if (metrics.surface() == Surface.PI_SECTION)
            return composeSection(title, header, body, footer, metrics, context);
        return composeBox(title, header, body, footer, metrics, context);
    }

    /** 闭合卡片：既有审批外观，行为与第八轮一致。 */
    private static List<StyledLine> composeBox(String title, List<StyledLine> header, List<StyledLine> body,
            StyledLine footer, Metrics metrics, RenderContext context) {
        var out = new ArrayList<StyledLine>();
        out.add(topBorder(title, metrics, context));
        List<StyledLine> head = header == null ? List.of() : header;
        for (int i = 0; i < Math.min(metrics.headerRows(), head.size()); i++)
            out.add(bodyRow(head.get(i), metrics, context));
        List<StyledLine> lines = body == null ? List.of() : body;
        for (int i = 0; i < Math.min(metrics.bodyRows(), lines.size()); i++)
            out.add(bodyRow(lines.get(i), metrics, context));
        if (metrics.footer() && footer != null)
            out.add(bodyRow(footer, metrics, context));
        out.add(bottomBorder(metrics, context));
        // compose 内部已按 bodyRows 截断；这里只防御调用方传入超宽/超行数据的异常路径。
        if (out.size() > metrics.maxRows())
            return new ArrayList<>(out.subList(0, Math.max(1, metrics.maxRows())));
        return out;
    }

    /** Pi 外观：上下横线 + 右上标题，正文继承周围底色。 */
    private static List<StyledLine> composeSection(String title, List<StyledLine> header, List<StyledLine> body,
            StyledLine footer, Metrics metrics, RenderContext context) {
        var out = new ArrayList<StyledLine>();
        out.add(sectionTop(title, metrics, context));
        List<StyledLine> head = header == null ? List.of() : header;
        for (int i = 0; i < Math.min(metrics.headerRows(), head.size()); i++)
            out.add(sectionRow(head.get(i), metrics));
        List<StyledLine> lines = body == null ? List.of() : body;
        for (int i = 0; i < Math.min(metrics.bodyRows(), lines.size()); i++)
            out.add(sectionRow(lines.get(i), metrics));
        if (metrics.footer() && footer != null) out.add(sectionRow(footer, metrics));
        out.add(sectionBottom(metrics, context));
        if (out.size() > metrics.maxRows())
            return new ArrayList<>(out.subList(0, Math.max(1, metrics.maxRows())));
        return out;
    }

    /**
     * 把一段内容包成闭合卡片的一行：左边界、padding、内容、补齐、padding、右边界。
     * 内容超过正文宽时在字素边界裁切，不让终端隐式折行破坏行号。
     *
     * @param content 内容 Span；其背景若已显式设置（审批彩色按钮）则保留
     * @param metrics 同帧几何
     * @param context 同帧主题
     * @return 恰好 width 列的一行
     */
    public static StyledLine bodyRow(List<Span> content, Metrics metrics, RenderContext context) {
        return bodyRow(new StyledLine(content), metrics, context);
    }

    /** {@link #bodyRow(List, Metrics, RenderContext)} 的富文本重载，保留 annotation。 */
    public static StyledLine bodyRow(StyledLine content, Metrics metrics, RenderContext context) {
        if (metrics.minimal()) return fill(content.crop(metrics.width()), metrics.width(), context);
        var spans = new ArrayList<Span>();
        Style border = context.theme().style("border");
        spans.add(new Span("│" + " ".repeat(metrics.padding()), border, null));
        StyledLine cropped = content.crop(metrics.contentWidth());
        spans.addAll(cropped.spans());
        int used = cropped.width();
        if (used < metrics.contentWidth())
            spans.add(new Span(" ".repeat(metrics.contentWidth() - used),
                    context.theme().background("panelBg"), null));
        spans.add(new Span(" ".repeat(metrics.padding()) + "│", border, null));
        return fill(new StyledLine(spans, content.annotation()), metrics.width(), context);
    }

    /**
     * 给整行叠加面板背景并补齐/裁到目标宽；显式背景（审批按钮）保持不变。
     * 这是闭合卡片“矩形每格都有底色”的唯一实现，页面不再各自复制。
     *
     * @param line 原行
     * @param width 目标总宽
     * @param context 同帧主题
     * @return 恰好 width 列（或原行更窄时补齐）的行
     */
    public static StyledLine fill(StyledLine line, int width, RenderContext context) {
        Style panel = context.theme().background("panelBg");
        var result = new ArrayList<Span>();
        for (Span span : line.spans()) {
            boolean explicitBackground = !span.style().bg().isDefault();
            result.add(new Span(span.text(), panel.isPlain() || explicitBackground
                    ? span.style() : span.style().withBg(panel.bg()), span.link()));
        }
        StyledLine filled = new StyledLine(result, line.annotation());
        int columns = Math.max(1, width);
        return filled.width() < columns
                ? filled.padTo(columns, panel.isPlain() ? Style.PLAIN : panel)
                : filled.crop(columns);
    }

    /** Pi 正文行：不加侧边/底色，只在字素边界裁切并用纯空白补齐到卡片宽。 */
    private static StyledLine sectionRow(StyledLine content, Metrics metrics) {
        StyledLine cropped = content.crop(metrics.width());
        return cropped.width() < metrics.width()
                ? cropped.padTo(metrics.width(), Style.PLAIN) : cropped;
    }

    /**
     * Pi 上横线：左侧横线 + 空格 + 右上标题 + 空格 + 末尾一列横线，总宽恰为 W。
     * 标题来自页面数据，超宽时按显示列省略；空标题时整行横线。
     */
    private static StyledLine sectionTop(String title, Metrics metrics, RenderContext context) {
        int columns = Math.max(1, metrics.width());
        Style border = context.theme().style("border");
        String label = safe(title);
        if (label.isEmpty())
            return new StyledLine(List.of(new Span("─".repeat(columns), border, null)));
        // 左侧至少 2 列横线；标题两侧各一列空格，末尾保留 1 列横线收尾。
        String shown = DisplayWidth.ellipsize(label, Math.max(1, columns - 5));
        int titleWidth = DisplayWidth.width(shown);
        int left = Math.max(2, columns - titleWidth - 3);
        int trailing = Math.max(1, columns - left - titleWidth - 2);
        Style titleStyle = context.theme().style("accent");
        if (context.theme().enabled()) titleStyle = titleStyle.withBold(true);
        return new StyledLine(List.of(
                new Span("─".repeat(left), border, null),
                new Span(" " + shown + " ", titleStyle, null),
                new Span("─".repeat(trailing), border, null)));
    }

    /** 下横线：全宽 W，普通边框字重。 */
    private static StyledLine sectionBottom(Metrics metrics, RenderContext context) {
        return new StyledLine(List.of(new Span("─".repeat(Math.max(1, metrics.width())),
                context.theme().style("border"), null)));
    }

    /**
     * 上边框（闭合卡片）：标题嵌入边框；边框普通字重，标题 accent+bold；总宽恰为 width。
     * 标题过长时优先保留末尾闭合角，不把右边框挤出屏幕。
     */
    private static StyledLine topBorder(String title, Metrics metrics, RenderContext context) {
        int columns = metrics.width();
        String shown = DisplayWidth.ellipsize(safe(title), Math.max(1, columns - 5));
        int fillCount = Math.max(0, columns - DisplayWidth.width(shown) - 5);
        Style border = context.theme().style("border");
        Style titleStyle = context.theme().style("accent");
        if (context.theme().enabled()) titleStyle = titleStyle.withBold(true);
        return fill(new StyledLine(List.of(
                new Span("┌─ ", border, null),
                new Span(shown, titleStyle, null),
                new Span(" " + "─".repeat(fillCount) + "┐", border, null))), columns, context);
    }

    /** 下边框：与上边框同宽，保证四角完整。 */
    private static StyledLine bottomBorder(Metrics metrics, RenderContext context) {
        return fill(new StyledLine(List.of(new Span(
                "└" + "─".repeat(Math.max(0, metrics.width() - 2)) + "┘",
                context.theme().style("border"), null))), metrics.width(), context);
    }

    /** 标题/提示来自页面数据，压成单行并去掉控制字符，避免破坏外框。 */
    private static String safe(String value) {
        if (value == null) return "";
        return DisplayWidth.stripTrailingSpaces(value.replace('\n', ' ').replace('\t', ' '));
    }
}
