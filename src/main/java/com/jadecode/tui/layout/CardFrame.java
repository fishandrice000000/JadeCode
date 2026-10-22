package com.jadecode.tui.layout;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * 输入区卡片的共享几何：闭合外框、嵌入标题、左右 padding、面板底色与正文矩形。
 *
 * <p>第八轮把信息页、选择页、搜索和审批统一成“替换输入栏的卡片”，它们此前各自复制了
 * 一套边框/留白/铺底算法，容易出现某页缺右边框、标题不嵌边或命中坐标仍按旧布局计算的
 * 问题。本类只回答纯布局问题：给定卡片总宽、总行预算和可选固定区/底部提示，算出真实
 * 正文宽、正文可用行数以及每行的行列结构。它不保存页面状态、不生成审批动作、不读写
 * 终端，也不依赖 {@code com.jadecode.cli}；页面 View 负责给正文，宿主负责把局部行号
 * 平移到屏幕/文档坐标。</p>
 *
 * <p>坐标约定：所有返回的行都是“卡片内相对行”，0 是上边框；正文列从
 * {@link Metrics#contentX()} 开始，正文宽为 {@link Metrics#contentWidth()}。卡片总宽
 * 按 {@link DisplayWidth} 的终端列计算，不使用 Java {@code String.length()}。</p>
 *
 * <p>降级顺序：宽度小于 10 列或行预算小于 3 行时返回 {@linkplain Metrics#minimal()
 * 最小模式}，由调用方输出一行“标题 · 关闭/拒绝提示”；一般宽度至少保留闭合四角，先减
 * 左右 padding（2→1→0），正文仍可通过页面自己的滚动到达。短页不补到预算上限，卡片
 * 高度随实际正文收缩。</p>
 */
public final class CardFrame {
    private CardFrame() { }

    /**
     * 一次卡片测量的结果。
     *
     * @param width 卡片总列数（已夹到至少 1）
     * @param maxRows 卡片最多可占的行数（含上下边框/固定区/底部提示）
     * @param padding 正文左右各留的空白列（0/1/2）
     * @param contentWidth 正文可用列数，等于 width - 2 边框 - 2*padding
     * @param contentX 正文起始列（左括号、padding 之后），用于命中/光标平移
     * @param headerRows 固定头部行数（如 Hotkeys 的分类导航），不随正文滚动
     * @param bodyRows 正文最多可显示的行数；实际高度由 {@link #compose} 按内容收缩
     * @param footer 是否为底部提示预留一行
     * @param minimal 是否进入单行安全降级
     */
    public record Metrics(int width, int maxRows, int padding, int contentWidth, int contentX,
            int headerRows, int bodyRows, boolean footer, boolean minimal) {
    }

    /**
     * 计算卡片几何。调用方先给出固定头部行数与是否保留底部提示，再按返回的
     * {@code contentWidth} 生成正文换行，最后交给 {@link #compose} 组装。
     *
     * @param width 卡片可用总宽（通常是当帧输入区宽度）
     * @param maxRows 卡片可用总行数；调用方应先扣除状态栏/footer/widget
     * @param headerRows 不滚动的头部行数，负数按 0
     * @param footer 是否有独立的底部提示行
     * @return 不可变几何；极窄/极矮时为 minimal
     */
    public static Metrics measure(int width, int maxRows, int headerRows, boolean footer) {
        int columns = Math.max(1, width);
        int budget = Math.max(1, maxRows);
        int head = Math.max(0, headerRows);
        if (columns < 10 || budget < 3) {
            return new Metrics(columns, budget, 0, columns, 0, 0, 0, false, true);
        }
        // 一般左右各 2 列；11–13 列只够 1 列；更窄时不加 padding，保证至少 1 列正文。
        int padding = columns >= 14 ? 2 : (columns >= 11 ? 1 : 0);
        // 高度很紧时先牺牲留白，保留标题、正文和底边；这比截断边框更可读。
        if (budget < 6 && padding == 2) padding = 1;
        int contentWidth = Math.max(1, columns - 2 - padding * 2);
        int reserved = 2 + head + (footer ? 1 : 0);
        int bodyRows = Math.max(0, budget - reserved);
        // 头部本身已超过预算时仍让 bodyRows 为 0，由 compose 截到 maxRows，不抛异常。
        return new Metrics(columns, budget, padding, contentWidth, 1 + padding, head, bodyRows, footer, false);
    }

    /**
     * 组装完整卡片行。
     *
     * <p>正文按 {@code metrics.bodyRows()} 截取，不主动补空行，所以内容少的页面保持紧凑；
     * 每一行（含 padding、补齐空白和边框）都铺 {@code panelBg}。按钮等显式背景的 Span
     * 保留自己的背景，最后铺底不会把绿/黄/红选中色抹掉。</p>
     *
     * @param title 嵌入上边框的标题；超宽时省略
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

    /**
     * 把一段内容包成一行完整卡片正文：左边界、padding、内容、补齐、padding、右边界。
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
     * 这是卡片“矩形每格都有底色”的唯一实现，页面不再各自复制。
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

    /**
     * 上边框：标题嵌入边框；边框普通字重，标题 accent+bold；总宽恰为 width。
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
