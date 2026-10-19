package com.jadecode.execution;

/**
 * 运行过程的不可变显示快照。它不携带可执行工具、模型历史或审批权。
 * id 是显示关联编号，不能用它替换 ExecutionContext 的真实任务编号。
 * @param actor 工厂绑定的 Agent／子代理显示身份，不取自模型参数
 * @param kind 通知类型，决定其余显示字段的含义
 * @param id 工具或缓冲的显示关联编号；无须关联的状态通知使用空串
 * @param name 工具名或初始角色名，不参与工具查找
 * @param detail 参数 JSON、结果正文或状态说明，只用于展示
 * @param state 运行事实，例如 RUNNING、RETURNED、BLOCKED；RETURNED 不是业务成功
 * @param inputTokens USAGE 通知中的真实输入用量，其它类型为零
 * @param outputTokens USAGE 通知中的真实输出用量，其它类型为零
 */
public record ExecutionNotice(String actor, Kind kind, String id, String name, String detail,
        String state, long inputTokens, long outputTokens) {
    public enum Kind {
        /** 启动取得的模型限额及来源，不包含认证、思考或完整 HTTP 响应。 */ MODEL,
        /** 会话开始、等待后台、回空闲或关闭；不会请求模型。 */ SESSION,
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
        return new ExecutionNotice(value, kind, id, name, detail, state, inputTokens, outputTokens);
    }
}
