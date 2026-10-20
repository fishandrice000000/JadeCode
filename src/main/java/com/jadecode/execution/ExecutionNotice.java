package com.jadecode.execution;

/**
 * 运行过程的不可变显示快照。它不携带可执行工具、模型历史或审批权。
 * id 用于找回要更新的显示记录；整轮计时沿用 ExecutionContext 的运行编号，但不能凭它授予权限。
 * @param actor 工厂绑定的 Agent／子代理显示身份，不取自模型参数
 * @param kind 通知类型，决定其余显示字段的含义
 * @param id 工具/缓冲的显示编号，或整轮计时的运行编号；无须关联的状态通知使用空串
 * @param name 工具名或初始角色名，不参与工具查找
 * @param detail 参数 JSON、结果正文或状态说明，只用于展示
 * @param state 运行事实，例如 RUNNING、RETURNED、BLOCKED；RETURNED 不是业务成功
 * @param inputTokens USAGE 通知中的真实输入用量，其它类型为零
 * @param outputTokens USAGE 通知中的真实输出用量，其它类型为零
 * @param elapsedNanos 执行线程用单调时钟测得的耗时；零表示未提供，不是历史任务耗时为零
 * @param startedNanos TURN_START 的本轮起点，取自执行线程的 System.nanoTime；零表示未提供。
 *                     只在同一 JVM 内给实时计时使用，不保存为墙上时钟或跨进程恢复
 */
public record ExecutionNotice(String actor, Kind kind, String id, String name, String detail,
        String state, long inputTokens, long outputTokens, long elapsedNanos, long startedNanos) {
    /** 仅提供完成耗时的旧调用仍有效；不会用界面收到事件的时刻伪造执行起点。 */
    public ExecutionNotice(String actor, Kind kind, String id, String name, String detail,
            String state, long inputTokens, long outputTokens, long elapsedNanos) {
        this(actor, kind, id, name, detail, state, inputTokens, outputTokens, elapsedNanos, 0);
    }
    /** 旧调用未提供计时，保持原通知构造方式，不伪造耗时。 */
    public ExecutionNotice(String actor, Kind kind, String id, String name, String detail,
            String state, long inputTokens, long outputTokens) {
        this(actor, kind, id, name, detail, state, inputTokens, outputTokens, 0);
    }
    public enum Kind {
        /** 启动取得的模型限额及来源，不包含认证、思考或完整 HTTP 响应。 */ MODEL,
        /** 会话开始、等待后台、回空闲或关闭；不会请求模型。 */ SESSION,
        /** 一项大轮次开始处理，id 和 startedNanos 来自真实执行，不包括此前排队时间。 */ TURN_START,
        /** 大轮次结束，含总耗时；name=Reply 才给最终文字卡加总耗时，失败不伪装 DONE。 */ TURN_END,
        /** 同一卡片从排队到真正执行，id 保持不变。 */ TOOL_START,
        /** 工具返回、被阻止或中断；每种结束都需要收尾卡片。 */ TOOL_END,
        /** 完整正式响应报告的输入／输出 token。 */ USAGE,
        /** 压缩改变模型历史，不删除界面记录。 */ COMPACT,
        /** 现有用户缓冲的快照，不是另一份业务队列。 */ BUFFER,
        /** 一次性子代理的额外显示说明。 */ SUBAGENT,
        /** 后台启动、结束或停止，不能偷取其结果队列。 */ BACKGROUND
    }
    /** 创建没有 token 统计的普通通知；正文只用于显示。 */
    public static ExecutionNotice event(Kind kind, String id, String name, String detail, String state) {
        return new ExecutionNotice("", kind, id, name, detail, state, 0, 0);
    }
    /** 工厂绑定真实 Agent 身份，不由模型填写。 */
    public ExecutionNotice actor(String value) {
        return new ExecutionNotice(value, kind, id, name, detail, state, inputTokens, outputTokens, elapsedNanos, startedNanos);
    }
    /** 补充已测得的处理耗时，不复用 Token 字段存时间，也不改变协议消息。 */
    public ExecutionNotice timed(long nanos) {
        return new ExecutionNotice(actor, kind, id, name, detail, state, inputTokens, outputTokens, Math.max(0, nanos), startedNanos);
    }
    /**
     * 在轮次开始时附上执行线程已有的计时起点，界面由此显示“现在减起点”。
     * nanoTime 的绝对值允许为负，不能像耗时一样截成零；这里只传递，不另开计时器。
     */
    public ExecutionNotice started(long nanos) {
        return new ExecutionNotice(actor, kind, id, name, detail, state, inputTokens, outputTokens, elapsedNanos, nanos);
    }
}
