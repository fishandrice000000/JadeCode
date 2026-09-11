package com.jadecode.subagent;

/**
 * 子代理一次委派的终态:随 SUBAGENT_END 事件上报,供展示层区分三种收尾方式。
 *
 * <p>
 * 无论走哪条路径,{@link SubagentRuntime#run(String)} 都恰好触发一次 END:
 * 正常收尾为 {@link #DONE},30 轮耗尽为 {@link #STOPPED},LLM 调用失败为 {@link #FAILED}。
 * </p>
 */
public enum SubagentOutcome {
    /** 正常收尾:模型给出了最终文本 */
    DONE,

    /** 30 轮耗尽仍未收尾:返回 stopped 文案 */
    STOPPED,

    /** LLM 调用失败:委派中断,异常向调用方重新抛出 */
    FAILED
}
