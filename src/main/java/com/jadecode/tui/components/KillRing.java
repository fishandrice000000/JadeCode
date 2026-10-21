package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.List;

/**
 * Emacs 风格 kill ring：连续 Ctrl+K/Ctrl+U 按方向拼接，Ctrl+Y 插入最近内容，Alt+Y 轮换。
 *
 * <p>与系统剪贴板分开：kill ring 只服务编辑器内部删除/插回，复制/粘贴走
 * ClipboardService。方向决定连续 kill 的拼接方式：向后删除把新片段追加到尾部，向前删除
 * 把新片段放到头部，使一次 Ctrl+K 与随后 Ctrl+U 的结果保持用户看到的顺序。
 * 最多保留 {@value #CAPACITY} 项，避免长会话无限增长。</p>
 */
public final class KillRing {
    /** kill 方向；BACKWARD 表示删除光标前内容。 */
    public enum Direction { BACKWARD, FORWARD }

    private static final int CAPACITY = 60;
    private final List<String> ring = new ArrayList<>();
    private int index = -1;
    private Direction lastDirection;
    private boolean lastWasKill;

    /** 登记一次删除；连续同方向 kill 合并为一项。 */
    public void kill(String text, Direction direction) {
        if (text == null || text.isEmpty()) return;
        if (lastWasKill && lastDirection == direction && !ring.isEmpty()) {
            String previous = ring.removeLast();
            ring.add(direction == Direction.BACKWARD ? text + previous : previous + text);
        } else {
            ring.add(text);
            if (ring.size() > CAPACITY) ring.removeFirst();
        }
        index = ring.size() - 1;
        lastDirection = direction;
        lastWasKill = true;
    }

    /** 非 kill 操作应调用，结束连续合并。 */
    public void interrupt() { lastWasKill = false; }

    /** @return 最近 kill 内容；空环返回空串。 */
    public String current() { return index >= 0 && index < ring.size() ? ring.get(index) : ""; }

    /** @return 轮换到上一项并返回；空环为空串。 */
    public String rotate() {
        if (ring.isEmpty()) return "";
        index = (index - 1 + ring.size()) % ring.size();
        return ring.get(index);
    }

    /** @return 是否为空。 */
    public boolean empty() { return ring.isEmpty(); }

    /** @return 环大小，测试/诊断。 */
    public int size() { return ring.size(); }

    /** 清空；新会话/提交使用。 */
    public void clear() { ring.clear(); index = -1; lastWasKill = false; }

    /** @return 只读快照，测试用。 */
    public List<String> entries() { return List.copyOf(ring); }
}
