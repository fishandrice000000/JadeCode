package com.jadecode.tui.core;

import java.util.List;

/**
 * 已成功 flush 的 Frame 证据。只有 renderer 在写入、flush 和错误检查全部成功后才会创建。
 *
 * <p>审批/点击必须同时满足：动作属于当前 PresentedFrame、frameId/epoch 与当前一致、对象
 * revision 仍有效。resize、模式切换、主题重排或请求移除时立即 {@link #invalidate()}，
 * 使旧画面的按钮在新画面写出前无法被键盘连按或鼠标点击批准。</p>
 *
 * @param frameId 展示帧号
 * @param terminalEpoch 终端代次
 * @param width 展示时的列数
 * @param height 展示时的行数
 * @param revision 布局 revision
 * @param actions 本帧实际包含的动作
 */
public record PresentedFrame(long frameId, long terminalEpoch, int width, int height, long revision,
        List<ActionToken> actions) {
    public PresentedFrame { actions = List.copyOf(actions); }

    /** @return 动作是否确实在本帧中且 revision/epoch 仍匹配。 */
    public boolean contains(ActionToken token, long currentEpoch, long currentRevision) {
        return terminalEpoch == currentEpoch && revision == currentRevision
                && actions.stream().anyMatch(action -> action.equals(token));
    }

    /** @return 本帧是否仍适用于给定画面尺寸。 */
    public boolean matchesSize(int columns, int rows) { return width == columns && height == rows; }
}
