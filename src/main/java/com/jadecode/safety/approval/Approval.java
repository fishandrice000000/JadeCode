package com.jadecode.safety.approval;

/**
 * 询问用户“是否允许这次操作”的函数，不负责决定哪些命令危险。
 *
 * <p>运行时通过接口等待会话审批账本或离线适配器；TUI 桥验证呈现证据，headless 无人工通道。
 * 离线检查可以直接返回明确答复。这个函数由程序传给任务，不是模型可填写的参数。</p>
 */
@FunctionalInterface
public interface Approval {
    /** 为同一审批通道附上程序已知的 Agent 身份；旧离线实现无需关心显示身份。 */
    default Approval forActor(String actor) { return this; }
    /**
     * 请用户明确同意或拒绝这次操作，可以等用户回答，但不能绕过前面的绝对禁止规则。
     * 没有答复、会话退出或线程中断时应按拒绝处理，不把任意下一行输入当作批准。
     * @param request 原操作、身份、原因和可保存建议的不可变快照
     * @return 仅本次、保存建议规则、拒绝或内部重新判断；没有答复不能当作允许
     */
    ApprovalAnswer ask(ApprovalRequest request);

    /** 配置变化时结束旧等待；默认适配器无等待状态，TUI/文本实现覆盖此方法。 */
    default void reconsider(long revision) { }
}
