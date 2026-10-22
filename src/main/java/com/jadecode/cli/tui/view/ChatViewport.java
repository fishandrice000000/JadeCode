package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.WheelScrollPolicy;
import com.jadecode.tui.viewport.SelectionModel;
import com.jadecode.tui.viewport.SearchModel;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.LinkTarget;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.viewport.ScrollView;

/**
 * 产品转录视口：文档滚动、滚动条、选区/搜索高亮、链接命中和 Jump to latest。
 *
 * <p>文档来自 {@link ConversationView} 的完整排版；{@link ScrollView} 只保存偏移/跟随态。
 * 全屏捕获滚轮与选择；regular 由终端原生滚动/拖选处理。搜索覆盖整篇文档，选区/搜索坐标
 * 与当前文档同一 revision，内容/宽度变化后由宿主清空重建。滚动条 auto 在 1 秒后隐藏，
 * always 实际占一列。本类不执行链接、不写剪贴板、不请求模型。</p>
 */
public final class ChatViewport {
    /** 滚动条策略。 */
    public enum Scrollbar { AUTO, ALWAYS, HIDDEN }

    private final ConversationView conversation;
    private final ScrollView scroll = new ScrollView();
    private final SelectionModel selection = new SelectionModel();
    private final SearchModel search = new SearchModel();
    private Scrollbar scrollbar;
    private boolean copyOnSelect;
    private long scrollbarVisibleUntil;
    private final WheelScrollPolicy wheelPolicy = new WheelScrollPolicy(0);
    /** 历史阅读锚点：顶部所属条目与条目内偏移；重排/异步结果到达时保持阅读位置。 */
    private String anchorEntry = "";
    private int anchorDelta;
    private boolean lastShowBar;
    private int lastHeight = 1;
    private int lastViewportStart;
    private List<StyledLine> lastDocument = List.of();

    /**
     * @param conversation 转录组装器（由 Application 持有以复用缓存）
     * @param scrollbar 滚动条策略
     * @param copyOnSelect 全屏选区完成时是否主动复制
     */
    public ChatViewport(ConversationView conversation, Scrollbar scrollbar, boolean copyOnSelect) {
        this.conversation = conversation; this.scrollbar = scrollbar; this.copyOnSelect = copyOnSelect;
    }

    /** @return 滚动状态（供 PageUp/Ctrl+End/Jump）。 */
    public ScrollView scrollState() { return scroll; }

    /** @return 选区模型。 */
    public SelectionModel selection() { return selection; }

    /** @return 搜索模型。 */
    public SearchModel search() { return search; }

    /** @return 是否启用 copy-on-select。 */
    public boolean copyOnSelect() { return copyOnSelect; }

    /**
     * 设置滚轮策略：0 为 Pi auto，其余为固定行数。设置变化会重置手势，
     * 防止上一段滚动的间隔/余量影响新策略。
     */
    public void setWheelScrollLines(int storedLines) { wheelPolicy.setLines(storedLines); }

    /** @return 当前存储策略（0=auto），供诊断/测试。 */
    public int wheelScrollLines() { return wheelPolicy.storedLines(); }

    /** 模式切换/恢复时清除手势，但不改当前行数设置。 */
    public void resetWheelGesture() { wheelPolicy.resetGesture(); }

    /** 运行时切换滚动条策略（/settings）。 */
    public void setScrollbar(Scrollbar value) { scrollbar = value == null ? Scrollbar.AUTO : value; }

    /** 运行时切换 copy-on-select。 */
    public void setCopyOnSelect(boolean value) { copyOnSelect = value; }

    /** @return 当前滚动条策略。 */
    public Scrollbar scrollbar() { return scrollbar; }

    /** @return 最近一次渲染的完整文档。 */
    public List<StyledLine> document() { return lastDocument; }

    /** @return 视图顶部文档行。 */
    public int viewportStart() { return lastViewportStart; }

    /**
     * 排版可见视口。
     * @param state 当前显示状态（文档来源）
     * @param context 同帧上下文；height 为视口高度
     * @param resources 欢迎资源
     * @param welcomeExpanded Ctrl+O
     * @param fullscreen 是否全屏模式（决定滚动条/选区）
     */
    public List<StyledLine> render(com.jadecode.cli.tui.TuiState state, RenderContext context, int height,
            List<String> resources, boolean welcomeExpanded, boolean fullscreen) {
        lastHeight = Math.max(1, height);
        List<StyledLine> document = conversation.render(state, context, resources, welcomeExpanded);
        boolean showBar = fullscreen && shouldShowScrollbar(document.size());
        int contentWidth = showBar ? Math.max(0, context.width() - 1) : Math.max(0, context.width());
        if (showBar && contentWidth > 0) {
            // 滚动条占用最后一列时，正文必须在 contentWidth 下重新排版，不能先按全宽
            // 排版再裁一列；否则满行内容会静默丢失。
            document = conversation.render(state, context.withWidth(contentWidth), resources, welcomeExpanded);
        }
        if (!scroll.following()) restoreAnchor(document);
        lastDocument = document;
        lastShowBar = showBar;
        List<StyledLine> visible = scroll.view(document, lastHeight);
        int start = scroll.offset();
        lastViewportStart = start;
        // 滚动条 thumb 与轨道比例只计算一次，所有行复用同一几何。
        int track = Math.max(1, visible.size());
        int thumbHeight = Math.max(1, (int) Math.round(track * (double) track / Math.max(track, document.size())));
        int maxStart = Math.max(1, document.size() - track);
        int maxThumbStart = Math.max(0, track - thumbHeight);
        int thumbStart = Math.round(maxThumbStart * (start / (float) maxStart));
        var result = new ArrayList<StyledLine>(visible.size());
        for (int row = 0; row < visible.size(); row++) {
            int documentRow = start + row;
            StyledLine line = highlight(visible.get(row), documentRow, context);
            boolean thumb = row >= thumbStart && row < thumbStart + thumbHeight;
            result.add(composeRow(line, contentWidth, showBar, thumb, context));
        }
        return result;
    }

    /** 选区/搜索高亮：按列范围拆分 span，宽字符边界由 DisplayWidth 保证。 */
    private StyledLine highlight(StyledLine line, int documentRow, RenderContext context) {
        var ranges = new ArrayList<int[]>();
        String plain = line.text();
        var searchRanges = search.highlightsFor(documentRow);
        ranges.addAll(searchRanges);
        var selectionRange = selection.rangeFor(documentRow, plain);
        if (!selectionRange.empty()) ranges.add(new int[]{selectionRange.fromColumn(), selectionRange.toColumn()});
        if (ranges.isEmpty()) return line;
        var spans = new ArrayList<Span>();
        int column = 0;
        for (Span span : line.spans()) {
            String text = span.text();
            for (String cluster : DisplayWidth.clusters(text)) {
                int cells = DisplayWidth.clusterWidth(cluster);
                Style style = span.style();
                boolean selected = false, matched = false;
                for (int[] range : ranges) {
                    if (column >= range[0] && column < range[1]) {
                        if (searchRanges.contains(range)) matched = true; else selected = true;
                    }
                }
                if (selected) style = style.withBg(context.theme().color("selectedBg"));
                else if (matched) style = style.withBg(context.theme().color("searchMatchBg"));
                append(spans, cluster, style, span.link());
                column += cells;
            }
        }
        return new StyledLine(spans, line.annotation());
    }

    private static void append(List<Span> spans, String text, Style style, LinkTarget link) {
        if (!spans.isEmpty()) {
            Span last = spans.getLast();
            if (last.style().equals(style) && java.util.Objects.equals(last.link(), link)) {
                spans.removeLast(); spans.add(last.withText(last.text() + text)); return;
            }
        }
        spans.add(new Span(text, style, link));
    }

    private boolean shouldShowScrollbar(int documentLines) {
        if (scrollbar == Scrollbar.HIDDEN) return false;
        if (scrollbar == Scrollbar.ALWAYS) return true;
        return documentLines > lastHeight && System.nanoTime() < scrollbarVisibleUntil;
    }

    /** @return auto 滚动条到期时刻（纳秒）；无期限返回 Long.MAX_VALUE。 */
    public long scrollbarDeadline() {
        return scrollbar == Scrollbar.AUTO && lastShowBar ? scrollbarVisibleUntil : Long.MAX_VALUE;
    }

    /** @return auto 滚动条当前是否可见；宿主据此安排 1 秒后的清除重绘。 */
    public boolean scrollbarVisible() {
        if (scrollbar == Scrollbar.HIDDEN) return false;
        if (scrollbar == Scrollbar.ALWAYS) return lastShowBar;
        return lastShowBar && System.nanoTime() < scrollbarVisibleUntil;
    }

    /**
     * 把一行合成为固定总宽：正文裁/补到 contentWidth，滚动条只画在最后一列。
     * 旧实现把 `█/│` 直接追加到文字末尾，短行落在参差列、满行溢出后被 Frame 裁掉，
     * 表现为正文行尾白块/竖线。
     */
    private StyledLine composeRow(StyledLine line, int contentWidth, boolean showBar, boolean thumb,
            RenderContext context) {
        StyledLine body = line.crop(Math.max(0, contentWidth));
        if (contentWidth > body.width()) {
            var spans = new ArrayList<>(body.spans());
            spans.add(new Span(" ".repeat(contentWidth - body.width()), Style.PLAIN, null));
            body = new StyledLine(spans, body.annotation());
        }
        if (!showBar) return body;
        var spans = new ArrayList<>(body.spans());
        spans.add(new Span(thumb ? "█" : "│",
                context.theme().style(thumb ? "scrollbarThumb" : "scrollbarTrack"), null));
        return new StyledLine(spans, body.annotation());
    }

    /** 记录当前顶部所属条目与条目内偏移，供异步重排时恢复阅读位置。 */
    private void captureAnchor() {
        anchorEntry = conversation.entryIdAtRow(scroll.offset());
        Integer startRow = conversation.entryStartRows().get(anchorEntry);
        anchorDelta = startRow == null ? 0 : scroll.offset() - startRow;
    }

    /**
     * 重排后把顶部恢复到同一条目的同一偏移；只有阅读历史（未跟随）时使用。
     * 末尾追加内容不会改变旧条目的起始行，因此历史位置保持稳定。
     */
    private void restoreAnchor(List<StyledLine> document) {
        if (anchorEntry == null || anchorEntry.isEmpty()) return;
        Integer newStart = conversation.entryStartRows().get(anchorEntry);
        if (newStart == null) return;
        int target = newStart + anchorDelta;
        scroll.adjust(target - scroll.offset(), document.size(), lastHeight);
    }

    /** 滚动行数；手动滚动暂停跟随并显示滚动条，同时记录阅读锚点。 */
    public void scrollLines(int delta) {
        scroll.scroll(delta, lastDocument.size(), lastHeight);
        captureAnchor();
        scrollbarVisibleUntil = System.nanoTime() + 1_000_000_000L;
    }

    /** 翻页。 */
    public void page(int direction) {
        scroll.page(direction, lastDocument.size(), lastHeight);
        captureAnchor();
        scrollbarVisibleUntil = System.nanoTime() + 1_000_000_000L;
    }

    /** 滚到顶部/底部；底部恢复跟随。 */
    public void top() { scroll.top(); captureAnchor(); scrollbarVisibleUntil = System.nanoTime() + 1_000_000_000L; }
    public void bottom() { scroll.bottom(lastDocument.size(), lastHeight); anchorEntry = ""; }
    public void follow() { scroll.follow(true); anchorEntry = ""; }

    /**
     * 鼠标滚轮：行数由 {@link WheelScrollPolicy} 计算，Alt 倍速在策略内只应用一次。
     * 生产入口使用单调时钟；测试可用 {@link #wheelAt(int, boolean, long)} 固定时间。
     */
    public void wheel(int direction, boolean alt) {
        wheelAt(direction, alt, System.nanoTime() / 1_000_000L);
    }

    /**
     * 可注入时间版本的滚轮，便于验证 auto 加速、停顿与反向。
     * @param direction -1 上、1 下
     * @param alt 是否按住 Alt
     * @param nowMillis 单调时间毫秒
     */
    public void wheelAt(int direction, boolean alt, long nowMillis) {
        int lines = wheelPolicy.lines(direction, nowMillis, alt);
        scrollLines(direction * lines);
    }

    /** 开始选区；row 为视口内行。 */
    public void startSelection(int row, int column, int clickCount) {
        selection.start(new SelectionModel.Position(lastViewportStart + Math.max(0, row), Math.max(0, column)), clickCount);
    }

    /** 拖动选区；row 可越界表示拖出边缘（返回方向供自动滚动）。 */
    public int dragSelection(int row, int column) {
        int target = lastViewportStart + row;
        selection.drag(new SelectionModel.Position(Math.max(0, target), Math.max(0, column)));
        if (row < 0) return -1;
        if (row >= lastHeight) return 1;
        return 0;
    }

    /** 结束选区并返回复制文本；copy-on-select 由宿主决定是否主动复制。 */
    public String finishSelection() {
        selection.finish();
        return selection.text(lastDocument);
    }

    /** 清空选区/搜索；resize/模式切换使用。 */
    public void invalidate() {
        selection.clear(); search.clear(); scroll.reset(); lastDocument = List.of();
        anchorEntry = ""; anchorDelta = 0; lastShowBar = false;
    }

    /** 搜索全文并定位到首个匹配。 */
    public SearchModel.Match search(String query) {
        search.search(query, lastDocument, conversation.documentRevision());
        return locate(search.current());
    }

    /** 下一个匹配并滚动到可见位置。 */
    public SearchModel.Match searchNext() { return locate(search.next()); }

    /** 上一个匹配。 */
    public SearchModel.Match searchPrevious() { return locate(search.previous()); }

    private SearchModel.Match locate(SearchModel.Match match) {
        if (match != null) {
            // 让匹配位于视口中部；直接设置 offset 并停止跟随。
            scroll.anchor(Math.max(0, match.row() - lastHeight / 2));
            scroll.restoreAnchor(lastDocument.size());
        }
        return match;
    }

    /** @return 视口内点击位置的链接目标；没有链接返回 null。 */
    public LinkTarget linkAt(int row, int column) {
        int documentRow = lastViewportStart + Math.max(0, row);
        if (documentRow < 0 || documentRow >= lastDocument.size()) return null;
        int used = 0;
        for (Span span : lastDocument.get(documentRow).spans()) {
            int width = DisplayWidth.width(span.text());
            if (column >= used && column < used + width) return span.link();
            used += width;
        }
        return null;
    }

    /** @return 跳转提示是否需要显示（用户已离开底部）。 */
    public boolean jumpToLatestVisible() { return !scroll.following() && lastDocument.size() > lastHeight; }

    /** @return Jump to latest 提示行。 */
    public String jumpHint() { return "↓ Jump to latest (Ctrl+End)"; }
}
