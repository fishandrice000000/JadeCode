package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
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
    private int wheelLines = 3;
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

    /** 设置滚轮每格行数（auto 由宿主映射）。 */
    public void wheelLines(int value) { wheelLines = Math.max(1, value); }

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
        lastDocument = document;
        boolean showBar = fullscreen && shouldShowScrollbar(document.size());
        int contentWidth = Math.max(1, context.width() - (showBar ? 1 : 0));
        // 文档已按完整宽度排版；滚动条出现时逐行裁一列，不重新排版正文（列选择/命中沿用同一映射）。
        List<StyledLine> source = showBar ? document.stream().map(line -> line.crop(contentWidth)).toList() : document;
        List<StyledLine> visible = scroll.view(source, lastHeight);
        int start = scroll.offset();
        lastViewportStart = start;
        var result = new ArrayList<StyledLine>(visible.size());
        for (int row = 0; row < visible.size(); row++) {
            int documentRow = start + row;
            StyledLine line = highlight(visible.get(row), documentRow, context);
            if (showBar) {
                var spans = new ArrayList<Span>(line.spans());
                spans.add(new Span(" ", context.theme().style("dim"), null));
                line = new StyledLine(spans, line.annotation());
            }
            result.add(line);
        }
        if (showBar) addScrollbar(result, document.size(), start, fullscreen);
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
            int offset = 0;
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
                column += cells; offset += cluster.length();
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

    private void addScrollbar(List<StyledLine> lines, int documentLines, int start, boolean fullscreen) {
        if (lines.isEmpty()) return;
        int track = lines.size();
        int thumbHeight = Math.max(1, (int) Math.round(track * (double) track / Math.max(track, documentLines)));
        int maxStart = Math.max(1, documentLines - track);
        int maxThumbStart = Math.max(0, track - thumbHeight);
        int thumbStart = Math.round(maxThumbStart * (start / (float) maxStart));
        for (int row = 0; row < track; row++) {
            boolean thumb = row >= thumbStart && row < thumbStart + thumbHeight;
            var spans = new ArrayList<Span>(lines.get(row).spans());
            spans.add(new Span(thumb ? "█" : "│", Style.PLAIN.fg(
                    com.jadecode.tui.text.Color.DEFAULT), null));
            lines.set(row, new StyledLine(spans, lines.get(row).annotation()));
        }
    }

    /** 滚动行数；手动滚动暂停跟随并显示滚动条。 */
    public void scrollLines(int delta) {
        scroll.scroll(delta, lastDocument.size(), lastHeight);
        scrollbarVisibleUntil = System.nanoTime() + 1_000_000_000L;
    }

    /** 翻页。 */
    public void page(int direction) {
        scroll.page(direction, lastDocument.size(), lastHeight);
        scrollbarVisibleUntil = System.nanoTime() + 1_000_000_000L;
    }

    /** 滚到顶部/底部；底部恢复跟随。 */
    public void top() { scroll.top(); scrollbarVisibleUntil = System.nanoTime() + 1_000_000_000L; }
    public void bottom() { scroll.bottom(lastDocument.size(), lastHeight); }
    public void follow() { scroll.follow(true); }

    /** 鼠标滚轮；Alt 倍速由 controller 把倍率传给 delta。 */
    public void wheel(int direction, int multiplier) { scrollLines(direction * wheelLines * Math.max(1, multiplier)); }

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
