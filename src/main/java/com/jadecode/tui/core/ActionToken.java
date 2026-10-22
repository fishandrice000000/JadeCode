package com.jadecode.tui.core;

/**
 * 宿主定义的可点击动作身份；不是模型正文提供的命令，也不是可执行 Runnable。
 *
 * <p>Frame 命中区域只保存 ActionToken，controller 在点击/回车后还必须核对对象当前版本、
 * 当前审批队首、权限 revision 以及 PresentedFrame 证据。`modelRevision` 使旧画面上的
 * 按钮在模型/布局更新后自然失效；动作 ID 只用于查找，不携带路径、shell 或模型参数。</p>
 *
 * @param kind 动作类型
 * @param objectId 目标对象 ID（审批请求、会话、条目等），由宿主生成
 * @param revision 生成动作时的模型/视图 revision
 */
public record ActionToken(Kind kind, String objectId, long revision) {
    /** 动作类型；只有 controller 知道如何执行，组件不得自行解释。 */
    public enum Kind { APPROVE, ALLOW_SIMILAR, DENY, TOGGLE_ENTRY, SELECT_ITEM, OPEN_LINK, COPY, SCROLL }

    public ActionToken {
        if (kind == null || objectId == null) throw new IllegalArgumentException("Action identity cannot be empty");
    }

    /** @return 该动作在给定当前 revision 下是否仍有效。 */
    public boolean validAt(long currentRevision) { return revision == currentRevision; }

    /** @return 审批类动作；用于防误批检查。 */
    public boolean isApproval() {
        return kind == Kind.APPROVE || kind == Kind.ALLOW_SIMILAR || kind == Kind.DENY;
    }
}
