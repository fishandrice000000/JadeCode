package com.jadecode.safety.approval;

/**
 * 为已确定需要询问的操作提供用户作答通道。权限门准备不可变请求，运行时适配器等待本请求的明确答案；
 * 权限变化通过 reconsider 唤醒旧等待重判。
 */
@FunctionalInterface
public interface Approval {
    /**
     * 为同一审批通道附上程序已知的 Agent 身份；旧离线实现无需关心显示身份。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @return 附有指定 Agent 身份的审批端口；默认实现沿用当前对象
     */
    default Approval forActor(String actor) {
        return this;
    }

    /**
     * 请用户明确同意或拒绝这次操作，可以等用户回答，但不能绕过前面的绝对禁止规则。
     * 没有答复、会话退出或线程中断时应按拒绝处理，不把任意下一行输入当作批准。
     *
     * @param request 原操作、身份、原因和可保存建议的不可变快照
     * @return 仅本次、保存建议规则、拒绝或内部重新判断；没有答复不能当作允许
     */
    ApprovalAnswer ask(ApprovalRequest request);

    /**
     * 接收当前权限版本，通知有等待状态的适配器结束旧版本申请并重新判断；默认适配器直接返回。
     *
     * @param revision 本次操作依据的版本号，用于读取后修改时核对状态
     */
    default void reconsider(long revision) {
    }
}
