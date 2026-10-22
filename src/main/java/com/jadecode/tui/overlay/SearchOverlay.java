package com.jadecode.tui.overlay;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.view.ChatViewport;
import com.jadecode.tui.components.Input;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyChord;
import com.jadecode.tui.layout.CardFrame;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.StyledLine;

/**
 * 全文搜索卡片：单行查询、匹配计数、Enter/Shift+Enter 前后跳转、Esc 关闭。
 *
 * <p>搜索覆盖整个转录（由 ChatViewport/SearchModel 提供），不是只搜可见页；关闭后恢复原
 * Editor 草稿/光标，搜索不修改编辑器。第八轮起它不再在聊天顶部居中覆盖，而是作为输入区
 * 卡片渲染：外框/留白由 {@link CardFrame} 统一生成，宿主 DockView 把它替换 Editor 并负责
 * 局部行号平移。本类保留查询/匹配状态，不直接写终端，也不拥有聊天锚点。</p>
 */
public final class SearchOverlay implements Component {
    private final Input input = new Input("Search transcript…", value -> { });
    private String status = "";
    private boolean visible;
    private ChatViewport target;

    /**
     * 搜索卡的一帧排版。
     *
     * @param lines 卡片完整行
     * @param cursorRow 查询行在卡片内的行；不可见 0
     * @param cursorColumn 查询光标列（卡片坐标）
     * @param cursorVisible 是否显示硬件光标
     */
    public record Layout(List<StyledLine> lines, int cursorRow, int cursorColumn, boolean cursorVisible) {
        public Layout {
            lines = List.copyOf(lines);
        }
    }

    /** @return 是否打开。 */
    public boolean visible() { return visible; }

    /** 打开搜索；target 用于执行查询与定位。 */
    public void open(ChatViewport target) {
        this.target = target; visible = true; input.clear(); status = "";
        input.setText("");
    }

    /** 关闭并清空查询；不触碰原编辑器草稿。 */
    public void close() {
        visible = false; input.clear(); status = ""; target = null;
    }

    /** @return 当前查询文字。 */
    public String query() { return input.text(); }

    /**
     * 处理按键/文字；返回 true 表示搜索卡已消费。
     * Enter 下一个、Shift+Enter/Ctrl+Shift+G 上一个、Esc 关闭；查询变化后重新搜索全文。
     */
    public boolean handle(InputEvent event) {
        if (!visible) return false;
        if (event.kind() == InputEvent.Kind.KEY && event.actionableKey()) {
            KeyChord key = event.key();
            if (key.key().equals("escape")) { close(); return true; }
            if (key.key().equals("enter") && key.has(KeyChord.Modifier.SHIFT)) { previous(); return true; }
            if (key.key().equals("enter")) { next(); return true; }
            if (key.has(KeyChord.Modifier.CTRL) && key.key().equals("g")) {
                if (key.has(KeyChord.Modifier.SHIFT)) previous(); else next();
                return true;
            }
        }
        boolean consumed = input.handle(event);
        if (consumed) search();
        return consumed;
    }

    private void search() {
        if (target == null) return;
        if (input.text().isEmpty()) { target.search().clear(); status = ""; return; }
        var match = target.search(input.text());
        status = match == null ? "No matches" : (target.search().currentIndex() + 1) + "/" + target.search().size();
    }

    private void next() {
        if (target == null || input.text().isEmpty()) return;
        target.searchNext();
        status = (target.search().currentIndex() + 1) + "/" + target.search().size();
    }

    private void previous() {
        if (target == null || input.text().isEmpty()) return;
        target.searchPrevious();
        status = (target.search().currentIndex() + 1) + "/" + target.search().size();
    }

    /**
     * 生成输入区搜索卡。
     *
     * @param context 同帧主题/宽高
     * @param width 卡片总宽
     * @param maxRows 卡片最多行数（Dock 已扣除 status/footer/widget）
     * @return 卡片行、查询光标与可见性；未打开时为空布局
     */
    public Layout render(RenderContext context, int width, int maxRows) {
        if (!visible) return new Layout(List.of(), 0, 0, false);
        int columns = Math.max(1, width);
        CardFrame.Metrics frame = CardFrame.measure(columns, Math.max(1, maxRows), 0, false);
        if (frame.minimal()) {
            return new Layout(CardFrame.compose("Search", "Esc close", List.of(), List.of(), null, frame, context),
                    0, 0, false);
        }
        int contentWidth = frame.contentWidth();
        var body = new ArrayList<StyledLine>();
        // 状态行固定在查询上方：空查询时给提示，有匹配时显示当前位置；不静默隐藏失败。
        body.add(StyledLine.of(DisplayWidth.ellipsize(
                "Search · " + (status.isBlank() ? "type to search" : status), contentWidth),
                context.theme().style("accent")));
        int content = Math.max(1, contentWidth - 2);
        var querySpans = new ArrayList<Span>();
        querySpans.add(new Span("> ", context.theme().style("accent"), null));
        querySpans.addAll(input.render(context, content).getFirst().spans());
        body.add(new StyledLine(querySpans).crop(contentWidth));
        body.add(StyledLine.of(DisplayWidth.ellipsize(
                "Enter next · Shift+Enter previous · Esc close", contentWidth),
                context.theme().style("muted")));
        int cursorColumn = frame.contentX() + 2 + input.cursorColumn(content);
        return new Layout(
                CardFrame.compose("Search", "Esc close", List.of(), body, null, frame, context),
                2, // 上边框 + 状态行之后就是查询行
                Math.max(0, Math.min(columns - 1, cursorColumn)), true);
    }

    @Override public List<StyledLine> render(RenderContext context, int width) {
        return render(context, width, Math.max(1, context.height())).lines();
    }

    /** @return 查询输入的光标列（相对内容）；保留给诊断/兼容测试。 */
    public int cursorColumn(int width) { return input.cursorColumn(Math.max(1, width)); }

    /** @return 当前 Input（测试/诊断）。 */
    public Input input() { return input; }
}
