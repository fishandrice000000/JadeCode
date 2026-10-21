package com.jadecode.tui.overlay;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.geometry.Insets;
import com.jadecode.tui.geometry.Rect;

/**
 * 浮层位置、尺寸、可见性和焦点恢复；不负责具体搜索/菜单绘制。
 *
 * <p>支持 Pi 九宫格锚点、百分比（相对可移动范围）、margin、固定宽高/maxHeight。非捕获焦点
 * 的浮层仍覆盖其矩形（点击不能穿透到下层），但键盘可继续由父层处理；审批 dock 由宿主
 * 直接放在更高优先级，不通过本类抢占。resize 后按新的 bounds 重算并夹取，父层不可见时
 * 由焦点栈选择有效目标。</p>
 */
public final class OverlayManager {
    /** 九宫格锚点。 */
    public enum Anchor { TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER_LEFT, CENTER, CENTER_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

    /**
     * 一个浮层定义。
     * @param id 稳定 ID
     * @param component 内容组件
     * @param anchor 锚点
     * @param percentX 水平百分比 0-100（相对可移动范围）；负表示只用锚点
     * @param percentY 垂直百分比 0-100；负表示只用锚点
     * @param margin 与边缘的最小距离
     * @param width 固定宽；null 表示按内容/父宽
     * @param height 固定高；null 表示按内容
     * @param maxHeight 最大高；null 不限制
     * @param captureFocus 是否捕获键盘焦点
     */
    public record Overlay(String id, Component component, Anchor anchor, double percentX, double percentY,
            Insets margin, Integer width, Integer height, Integer maxHeight, boolean captureFocus) {
        public Overlay {
            if (id == null || component == null) throw new IllegalArgumentException("浮层 ID/组件不能为空");
            if (margin == null) margin = Insets.ZERO;
        }
    }

    private final List<Overlay> overlays = new ArrayList<>();

    /** 添加/替换同名浮层；后加入者在上层。 */
    public void add(Overlay overlay) {
        overlays.removeIf(existing -> existing.id().equals(overlay.id()));
        overlays.add(overlay);
    }

    /** 移除浮层。 */
    public void remove(String id) { overlays.removeIf(overlay -> overlay.id().equals(id)); }

    /** @return 是否包含指定浮层。 */
    public boolean contains(String id) { return overlays.stream().anyMatch(overlay -> overlay.id().equals(id)); }

    /** @return 顶层浮层；无则 null。 */
    public Overlay top() { return overlays.isEmpty() ? null : overlays.getLast(); }

    /** @return 自底向上的快照；绘制时按顺序合成。 */
    public List<Overlay> all() { return List.copyOf(overlays); }

    /** 清空所有浮层。 */
    public void clear() { overlays.clear(); }

    /**
     * 计算浮层矩形。
     * @param bounds 可用屏幕/容器区域
     * @param contentWidth 内容理想宽
     * @param contentHeight 内容理想高
     * @return 夹在 bounds 内的矩形
     */
    public static Rect place(Overlay overlay, Rect bounds, int contentWidth, int contentHeight) {
        Insets margin = overlay.margin();
        Rect inner = bounds.inset(margin);
        int width = Math.min(inner.width(), overlay.width() != null ? overlay.width() : contentWidth);
        int height = Math.min(inner.height(), overlay.height() != null ? overlay.height() : contentHeight);
        if (overlay.maxHeight() != null) height = Math.min(height, overlay.maxHeight());
        int movableX = Math.max(0, inner.width() - width);
        int movableY = Math.max(0, inner.height() - height);
        int x = switch (overlay.anchor()) {
            case TOP_LEFT, CENTER_LEFT, BOTTOM_LEFT -> 0;
            case TOP_CENTER, CENTER, BOTTOM_CENTER -> movableX / 2;
            case TOP_RIGHT, CENTER_RIGHT, BOTTOM_RIGHT -> movableX;
        };
        int y = switch (overlay.anchor()) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> 0;
            case CENTER_LEFT, CENTER, CENTER_RIGHT -> movableY / 2;
            case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> movableY;
        };
        if (overlay.percentX() >= 0) x = (int) Math.round(movableX * Math.min(100, overlay.percentX()) / 100.0);
        if (overlay.percentY() >= 0) y = (int) Math.round(movableY * Math.min(100, overlay.percentY()) / 100.0);
        return new Rect(inner.x() + Math.max(0, Math.min(movableX, x)),
                inner.y() + Math.max(0, Math.min(movableY, y)), width, height);
    }
}
