package com.jadecode.cli.tui.approval;

import com.jadecode.cli.tui.UiEvent;
import com.jadecode.tui.core.ActionToken;
import com.jadecode.tui.core.PresentedFrame;
import com.jadecode.safety.approval.ApprovalAnswer;

/**
 * 当前审批面的显示状态与防误批证据。
 *
 * <p>只保存不可变显示字段、选中项（-1 表示无默认）、阅读偏移，以及“该申请已经在成功
 * flush 的 PresentedFrame 中显示”的输入水位。批准必须同时满足：仍是当前队首且 revision
 * 匹配、按钮确实在该帧、按键序号晚于首次显示、不是 paste/reply/release、且 Enter 有显式
 * 选择。resize/切模式立即清空证据；新申请不能继承上一份的高亮或水位。</p>
 *
 * <p>本类不完成 future、不改权限、不执行工具；答复仍由 Controller 交给 ApprovalBroker。</p>
 */
public final class ApprovalViewState {
    /**
     * 不可变显示快照。
     * @param id 申请 ID
     * @param actor 真实 Agent 身份
     * @param tool 工具名（仅显示）
     * @param arguments 完整参数的安全展示文本（原文保留，展示时可转义控制字符）
     * @param reason 申请理由
     * @param suggestion 长期授权范围；空表示没有“允许此类”选项
     * @param revision 权限 revision；RECHECK 后旧申请失效
     * @param inputWatermark 该申请首次显示时的输入生产者序号
     * @param epoch 显示时的终端代次
     * @param frameId 成功显示的帧号
     */
    public record Request(String id, String actor, String tool, String arguments, String reason,
            String suggestion, long revision, long inputWatermark, long epoch, long frameId) { }

    private Request request;
    private int choice = -1;
    private int offset;
    private int pageSize = 1;
    private int totalLines;
    private boolean presented;
    private long presentedFrameId = -1;

    /** @return 当前申请；无则 null。 */
    public Request request() { return request; }

    /** @return 是否已有申请。 */
    public boolean active() { return request != null; }

    /** @return 当前选择下标；-1 无默认。 */
    public int choice() { return choice; }

    /** @return 详情阅读偏移。 */
    public int offset() { return offset; }

    public int pageSize() { return pageSize; }
    public int totalLines() { return totalLines; }

    /** @return 当前申请是否已在成功帧中显示。 */
    public boolean presented() { return presented; }

    /** @return 已显示帧号；未显示 -1。 */
    public long presentedFrameId() { return presentedFrameId; }

    /**
     * 安装新的队首申请；清空选择/阅读/显示证据。水位在首次成功呈现时记录，
     * 只有那之后产生/处理的按键才可能批准。
     */
    public void show(UiEvent.ApprovalRequested event, long inputWatermark, long epoch) {
        request = new Request(event.id(), event.actor(), event.tool(),
                event.arguments() == null ? "" : event.arguments(),
                event.reason() == null ? "" : event.reason(),
                event.suggestion() == null ? "" : event.suggestion(),
                // 水位在首次成功呈现时记录，而不是申请到达时；此前产生的排队按键不能批准新画面。
                event.revision(), -1, epoch, -1);
        choice = -1; offset = 0; pageSize = 1; totalLines = 0;
        presented = false; presentedFrameId = -1;
    }

    /** 关闭/移除当前申请；清空全部证据。 */
    public void clear() {
        request = null; choice = -1; offset = 0; pageSize = 1; totalLines = 0;
        presented = false; presentedFrameId = -1;
    }

    /**
     * 采用成功呈现的帧作为证据。只有帧中确实包含本申请的任一审批按钮且 epoch 匹配才成立；
     * 重绘未包含（例如窗口太小）时保持未显示，禁止批准。
     */
    public void presented(PresentedFrame frame, long epoch, long inputWatermark) {
        if (request == null || frame == null || epoch != request.epoch()) return;
        boolean contains = frame.actions().stream().anyMatch(token -> token.isApproval()
                && token.objectId().equals(request.id()) && token.revision() == request.revision());
        if (contains) {
            presented = true; presentedFrameId = frame.frameId();
            // 以“首次显示”的水位为准；重复呈现不抬高门槛，避免用户每移动一次都要等一帧。
            if (request.inputWatermark() < 0) {
                request = new Request(request.id(), request.actor(), request.tool(), request.arguments(),
                        request.reason(), request.suggestion(), request.revision(), inputWatermark,
                        request.epoch(), frame.frameId());
            }
        }
    }

    /** resize/模式切换/权限变更：立即让旧证据失效。 */
    public void invalidate() { presented = false; presentedFrameId = -1; }

    /** 选择导航；无 suggestion 时跳过“允许类似”选项。 */
    public void move(int direction, boolean hasSuggestion) {
        if (request == null || direction == 0) return;
        int next = choice < 0 ? (direction > 0 ? 0 : (hasSuggestion ? 2 : 1)) : choice + direction;
        if (!hasSuggestion && next == 1) next += direction;
        choice = Math.floorMod(next, 3);
    }

    /** 翻页阅读；不改变选择、不答复。 */
    public void scroll(int delta) {
        int max = Math.max(0, totalLines - Math.max(1, pageSize));
        offset = Math.max(0, Math.min(max, offset + delta));
    }

    /** 设置本次布局的页大小/总行数；宽度变化后由视图更新并夹取偏移。 */
    public void page(int pageSize, int totalLines) {
        this.pageSize = Math.max(1, pageSize); this.totalLines = Math.max(0, totalLines);
        offset = Math.max(0, Math.min(offset, Math.max(0, this.totalLines - this.pageSize)));
    }

    /** @return 当前选择对应的答复；无显式选择时 null。 */
    public ApprovalAnswer selectedAnswer() {
        return switch (choice) { case 0 -> ApprovalAnswer.ALLOW_ONCE; case 1 -> ApprovalAnswer.ALLOW_SIMILAR;
            case 2 -> ApprovalAnswer.DENY; default -> null; };
    }

    /**
     * 判断一个 Enter/点击是否可以答复。
     *
     * @param sequence 触发输入的序号
     * @param epoch 当前终端代次
     * @param frame 最新成功呈现的帧
     * @param action 具体审批动作（Enter 使用选中项对应动作）
     * @return 是否允许交给 Broker
     */
    public boolean canAnswer(long sequence, long epoch, PresentedFrame frame, ActionToken.Kind action) {
        if (request == null || !presented || frame == null) return false;
        if (frame.frameId() != presentedFrameId || epoch != request.epoch()) return false;
        if (sequence <= request.inputWatermark()) return false;
        boolean contains = frame.actions().stream().anyMatch(token -> token.kind() == action
                && token.objectId().equals(request.id()) && token.revision() == request.revision());
        if (!contains) return false;
        if (action == ActionToken.Kind.ALLOW_SIMILAR && request.suggestion().isEmpty()) return false;
        return true;
    }
}
