package com.jadecode.cli.tui.approval;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.layout.CardFrame;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Color;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.text.TextSanitizer;

/**
 * S52 审批输入区：直接替换底部 Editor 的完整有边界面板。
 *
 * <p>标题嵌入顶边（accent+bold），正文每行闭合侧边、左右各 2 列 padding、整块矩形铺
 * {@code panelBg}；TUI 不再绘制请求 reason（请求对象、Gate、plain/wire 原数据不受影响）。
 * 未选中按钮中性；三个选项始终竖排，选中分别使用绿/黄/红底与对比前景。初始 choice=-1；
 * 真实高度不足时不生成批准动作，但保留可见的安全拒绝入口。
 * 返回的 Button/choice 坐标都是完成外框后的面板坐标，宿主只平移。</p>
 *
 * <p>本类只读 {@link ApprovalViewState} 并更新其页大小/偏移；不完成 future、不判断权限，
 * 动作证据仍由状态类与 PresentedFrame/水位校验。</p>
 */
public final class ApprovalView {
    /** 一个按钮的帧内行/列范围及动作种类。 */
    public record Button(int row, int from, int to, com.jadecode.tui.core.ActionToken.Kind kind, boolean enabled) { }

    /** 排版结果；行号相对审批区起点，宿主平移后写入 Frame hit。 */
    public record Rendered(List<StyledLine> lines, int choiceRow, int choiceColumn, List<Button> buttons) {
        public Rendered { lines = List.copyOf(lines); buttons = List.copyOf(buttons); }
    }

    private static final String[] LABELS = {"Allow once", "Allow similar", "Deny"};

    /**
     * 兼容测试/旧调用的四参数入口：默认按 Team 模式显示 actor 名称。
     * 生产路径由宿主传入真实 Team 快照，单人会话不显示任何身份。
     */
    public Rendered render(ApprovalViewState state, RenderContext context, int width, int maxRows) {
        return render(state, context, width, maxRows, true);
    }

    /**
     * @param state 当前审批状态（只读；会更新页大小/偏移夹取）
     * @param context 同帧上下文
     * @param width 可用列
     * @param maxRows 审批区真实可用行数（Dock 已扣除 status/footer/widget）
     * @param showActor 是否有真实常驻 Team 上下文；false 时标题不拼任何 actor
     */
    public Rendered render(ApprovalViewState state, RenderContext context, int width, int maxRows,
            boolean showActor) {
        var out = new ArrayList<StyledLine>();
        if (!state.active()) return new Rendered(out, 0, 0, List.of());
        int columns = Math.max(1, width);
        int budget = Math.max(1, maxRows);
        ApprovalViewState.Request request = state.request();
        // S83：单人（包括 leader 派出的子代理）审批标题只有状态与工具名；
        // 只有真实常驻 Team 才显示受信 actor。审批对象与权限身份保持原样。
        String actor = !showActor || request.actor() == null || request.actor().isBlank() ? ""
                : com.jadecode.cli.tui.TuiState.actorName(request.actor()) + " · ";
        String title = UiMessages.APPROVAL + " · " + actor + request.tool();
        if (budget < 3 || columns < 10) {
            return denyOnly(request, context, columns);
        }
        // 审批显式声明闭合卡片：Pi 上下横线只用于信息页/选择器/搜索，不改变安全布局。
        CardFrame.Metrics frame = CardFrame.measure(columns, budget, 0, true, CardFrame.Surface.APPROVAL_BOX);
        if (frame.minimal()) return denyOnly(request, context, columns);
        int contentWidth = frame.contentWidth();

        // 详情：只显示完整参数和必要长期范围；reason 不进入 TUI 显示；每个续行同样缩进。
        var details = new ArrayList<StyledLine>();
        details.add(StyledLine.of("Parameters:", context.theme().style("muted")));
        details.addAll(indentWrap(TextSanitizer.visible(request.arguments()), contentWidth, context));
        if (!request.suggestion().isBlank()) {
            details.add(StyledLine.empty());
            details.add(StyledLine.of("Long-term scope:", context.theme().style("muted")));
            details.addAll(indentWrap(TextSanitizer.visible(request.suggestion()), contentWidth, context));
        }

        boolean hasSuggestion = !request.suggestion().isBlank();
        String[] labels = new String[LABELS.length];
        for (int i = 0; i < LABELS.length; i++) labels[i] = buttonLabel(i, state.choice(), i != 1 || hasSuggestion);
        int hintRows = 1;
        // 卡片正文 = 详情 + 三个固定按钮 + 提示；上下边框由 CardFrame 负责。
        int detailRows = Math.max(0, frame.bodyRows() - LABELS.length - hintRows);
        if (detailRows < 1 || DisplayWidth.width(labels[1]) > contentWidth) {
            return denyOnly(request, context, columns);
        }
        state.page(detailRows, details.size());
        int from = Math.min(state.offset(), Math.max(0, details.size() - 1));
        int to = Math.min(details.size(), from + detailRows);

        var body = new ArrayList<StyledLine>();
        for (int i = from; i < to; i++) body.add(details.get(i));

        int choice = state.choice();
        var buttons = new ArrayList<Button>();
        int choiceRow = 0, choiceColumn = 0;
        int startColumn = frame.contentX();
        // 三行固定预算；命中列取自完成边框和 padding 后的真实标签起点。
        for (int i = 0; i < LABELS.length; i++) {
            boolean enabled = i != 1 || hasSuggestion;
            int row = 1 + body.size();
            if (choice == i) { choiceRow = row; choiceColumn = startColumn; }
            body.add(StyledLine.of(labels[i], buttonStyle(context, i, choice, enabled)));
            if (enabled) buttons.add(new Button(row, startColumn,
                    startColumn + DisplayWidth.width(labels[i]), token(i), true));
        }
        String status = "Details " + (from + 1) + "–" + to + " / " + details.size()
                + " · ↑↓/Tab select · Enter confirm · Esc deny · PgUp/PgDn read";
        // 窄屏优先交代确认和退出；不要让长详情计数把 Esc 拒绝提示裁掉。
        if (contentWidth < 65) status = "↑↓ Select · Enter OK · Esc Deny";
        body.add(StyledLine.of(DisplayWidth.ellipsize(status, contentWidth), context.theme().style("muted")));
        out.addAll(CardFrame.compose(title, "Esc deny", List.of(), body, null, frame, context));
        return new Rendered(out, choiceRow, choiceColumn, buttons);
    }

    /** 每一行都带同样的缩进；参数/长期范围尾部内容不会因续行丢缩进。 */
    private static List<StyledLine> indentWrap(String text, int width, RenderContext context) {
        Style style = context.theme().style("text");
        var result = new ArrayList<StyledLine>();
        for (StyledLine line : StyledLine.wrap(List.of(new Span(text, style, null)), Math.max(1, width - 2))) {
            var spans = new ArrayList<Span>();
            spans.add(new Span("  ", style, null));
            spans.addAll(line.spans());
            result.add(new StyledLine(spans).crop(Math.max(1, width)));
        }
        return result;
    }

    private static String buttonLabel(int index, int choice, boolean enabled) {
        String marker = choice == index ? "›" : " ";
        return "[" + marker + " " + LABELS[index] + (enabled ? "" : " (n/a)") + " ]";
    }

    /**
     * 选中项底色/前景来自主题层的审批角色：显式值优先，否则由解析器按该主题自己的
     * 成功/警告/失败语义派生对比前景。本类不再持有 RGB 回退表，也不用 inverse 冒充配色；
     * 底色确实无法解析时保留 {@code ›} 选择标记，不伪造颜色。
     *
     * <p>未选中/禁用项保持中性，避免整页变成交通灯；布局与命中坐标不受颜色来源影响。</p>
     */
    private static Style buttonStyle(RenderContext context, int index, int choice, boolean enabled) {
        Style panel = context.theme().background("panelBg");
        Style base = context.theme().style("text");
        if (!panel.isPlain()) base = base.withBg(panel.bg());
        if (!enabled) {
            Style dim = context.theme().style("dim");
            return panel.isPlain() ? dim : dim.withBg(panel.bg());
        }
        if (choice != index) return base;
        if (!context.theme().enabled()) return base;
        String backgroundToken = switch (index) {
            case 0 -> "approvalOnceBg"; case 1 -> "approvalSimilarBg"; default -> "approvalDenyBg";
        };
        String foregroundToken = switch (index) {
            case 0 -> "approvalOnceFg"; case 1 -> "approvalSimilarFg"; default -> "approvalDenyFg";
        };
        Color background = context.theme().color(backgroundToken);
        if (background.isDefault()) return base.withBold(true);
        Style selected = base.withBg(background).withBold(true);
        Color foreground = context.theme().color(foregroundToken);
        return foreground.isDefault() ? selected : selected.withFg(foreground);
    }

    /**
     * 小屏只提供拒绝：必须完整画出当前 tool 与显式 Deny 标签后才发布 DENY 证据。
     * 完全不可见时不答复；扩大窗口后可重新建立呈现资格，绝不发布 allow token。
     */
    private static Rendered denyOnly(ApprovalViewState.Request request, RenderContext context, int columns) {
        String label = "[ Deny ]";
        String tool = TextSanitizer.display(request.tool()).replace('\n', ' ');
        String hint = tool + " · " + label + " · Esc deny";
        boolean visible = DisplayWidth.width(hint) <= columns;
        String text = visible ? hint : "Enlarge to review";
        var line = CardFrame.fill(new StyledLine(List.of(new Span(DisplayWidth.ellipsize(text, columns),
                context.theme().style(visible ? "text" : "warning"), null))), columns, context);
        int from = DisplayWidth.width(tool + " · ");
        var buttons = visible ? List.of(new Button(0, from, from + label.length(), token(2), true)) : List.<Button>of();
        return new Rendered(List.of(line), 0, from, buttons);
    }

    private static com.jadecode.tui.core.ActionToken.Kind token(int index) {
        return switch (index) {
            case 0 -> com.jadecode.tui.core.ActionToken.Kind.APPROVE;
            case 1 -> com.jadecode.tui.core.ActionToken.Kind.ALLOW_SIMILAR;
            default -> com.jadecode.tui.core.ActionToken.Kind.DENY;
        };
    }
}
