package com.jadecode.tui.viewport;

import java.util.List;
import com.jadecode.tui.text.StyledLine;

/**
 * 通用文档视口：保存文档行偏移、可见高度和 follow-end 状态。
 *
 * <p>ChatViewport 用它滚动整篇转录；Overlay 也可复用。视口只保存偏移，不复制原文；文档由
 * 调用方每次排版成完整行列表传入。追加内容时 follow-end 自动保持底部；用户手动上滚后暂停
 * 跟随，新内容不抢回底部；Jump to latest/Ctrl+End 恢复跟随。resize 时按源行锚点保持阅读
 * 位置（调用方提供锚点行），而不是仅保持旧物理行号。</p>
 */
public final class ScrollView {
    private int offset;
    private boolean followEnd = true;
    private int lastHeight = 1;
    private int anchorLine = -1;

    /** @return 当前顶部文档行。 */
    public int offset() { return offset; }

    /** @return 是否跟随末尾。 */
    public boolean following() { return followEnd; }

    /** 设置跟随状态；恢复跟随时立即滚到底。 */
    public void follow(boolean value) { followEnd = value; if (value && anchorLine >= 0) offset = Integer.MAX_VALUE; }

    /**
     * 计算可见行并更新 offset。
     * @param document 完整文档行
     * @param height 可见高度（>=1）
     * @return 可见行切片，长度为 <=height
     */
    public List<StyledLine> view(List<StyledLine> document, int height) {
        int visible = Math.max(1, height);
        lastHeight = visible;
        int max = Math.max(0, document.size() - visible);
        if (followEnd) offset = max;
        offset = Math.max(0, Math.min(offset, max));
        if (document.isEmpty()) return List.of();
        return document.subList(offset, Math.min(document.size(), offset + visible));
    }

    /**
     * 滚动指定行数；正数向下。
     *
     * <p>手动上滚会暂停跟随，避免新输出抢走阅读位置；一旦滚动位置重新到达末端
     * （例如向下滚回底部、Ctrl+End），必须恢复 follow-end，否则追加内容不会自动跟随。
     * 旧实现每次都把 followEnd 置 false，滚到底也不恢复。</p>
     */
    public void scroll(int lines, int documentHeight, int height) {
        if (lines == 0) return;
        int max = Math.max(0, documentHeight - Math.max(1, height));
        int next = Math.max(0, Math.min(max, offset + lines));
        offset = next;
        followEnd = next >= max;
    }

    /** 翻页；正数向下。 */
    public void page(int direction, int documentHeight, int height) {
        int visible = Math.max(1, height);
        int step = Math.max(1, visible - 1);
        scroll(direction * step, documentHeight, height);
    }

    /** 滚到顶部并停止跟随。 */
    public void top() { followEnd = false; offset = 0; }

    /** 滚到底并恢复跟随。 */
    public void bottom(int documentHeight, int height) {
        followEnd = true;
        offset = Math.max(0, documentHeight - Math.max(1, height));
    }

    /** 记录源行锚点；resize 后调用 {@link #restoreAnchor(int)}。 */
    public void anchor(int documentLine) { anchorLine = Math.max(0, documentLine); }

    /** 按锚点恢复顶部；锚点不存在时夹取。 */
    public void restoreAnchor(int documentHeight) {
        if (anchorLine >= 0) {
            offset = Math.max(0, Math.min(anchorLine, Math.max(0, documentHeight - lastHeight)));
            followEnd = false;
        }
    }

    /** @return 顶部相对文档的比例，用于滚动条 thumb。 */
    public double ratio(int documentHeight, int height) {
        int max = Math.max(0, documentHeight - Math.max(1, height));
        return max == 0 ? 0 : (double) offset / max;
    }

    /** 重置到跟随末尾；切换会话时使用。 */
    public void reset() { offset = 0; followEnd = true; anchorLine = -1; }
}
