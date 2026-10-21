package com.jadecode.tui.overlay;

import java.util.List;
import com.jadecode.cli.tui.view.ChatViewport;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.components.Input;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.geometry.Rect;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyChord;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.StyledLine;

/**
 * 全屏搜索 Overlay：单行查询、匹配计数、Enter/Shift+Enter 前后跳转、Esc 关闭。
 *
 * <p>搜索覆盖整个转录（由 ChatViewport/SearchModel 提供），不是只搜可见页；关闭后恢复原
 * Editor 草稿/光标，Overlay 不修改编辑器。没有 Agent 树/取消后端，不提供那些搜索模式。
 * 组件不直接写终端；宿主用 OverlayManager 计算位置并合成到帧。</p>
 */
public final class SearchOverlay implements Component {
    private final Input input = new Input("Search transcript…", value -> { });
    private String status = "";
    private boolean visible;
    private ChatViewport target;

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
     * 处理按键/文字；返回 true 表示 Overlay 已消费。
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

    /** @return 建议的浮层矩形：顶部居中，宽度最多 60。 */
    public Rect rect(Rect bounds) {
        int width = Math.min(60, Math.max(20, bounds.width() - 4));
        return new Rect(bounds.x() + Math.max(0, (bounds.width() - width) / 2), bounds.y() + 1,
                Math.min(width, bounds.width()), Math.min(5, bounds.height()));
    }

    @Override public List<StyledLine> render(RenderContext context, int width) {
        if (!visible) return List.of();
        int columns = Math.max(4, width);
        var out = new java.util.ArrayList<StyledLine>();
        out.add(StyledLine.of(DisplayWidth.ellipsize("Search · " + (status.isBlank() ? "type to search" : status), columns),
                context.theme().style("accent")));
        String text = input.text();
        out.add(new StyledLine(List.of(new Span(DisplayWidth.take(text.isEmpty() ? "Search transcript…" : text,
                Math.max(1, columns - 2)), text.isEmpty() ? context.theme().style("dim") : context.theme().style("text"), null))));
        out.add(StyledLine.of(DisplayWidth.ellipsize("Enter next · Shift+Enter previous · Esc close", columns),
                context.theme().style("muted")));
        return out;
    }

    /** @return 查询输入的光标列（相对 Overlay 内容）；宿主用于硬件光标。 */
    public int cursorColumn(int width) { return input.cursorColumn(Math.max(1, width)); }

    /** @return 当前 Input（测试/诊断）。 */
    public Input input() { return input; }
}
