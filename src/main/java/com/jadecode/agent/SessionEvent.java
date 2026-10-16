package com.jadecode.agent;

import java.util.List;

import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.ToolUseBlock;

/**
 * 执行器交给会话运行器的结构不可变事件：描述要采用什么，而不是自己修改历史。
 *
 * <p>这些事件可在消费者线程内同步处理，不意味着每一步都启动线程。工具批次使用一个事件
 * 交付全部结果，避免半个交换期间插入别的消息。模型协议 Message 是采用后的表示，不能用
 * role=user 判断一次事件究竟是新请求、工具结果还是续跑。列表只复制外层，不是对 Message
 * 或 JSON 做深拷贝；生产者交出事件后不能再原地修改其中的消息与参数。</p>
 *
 * <p>只有 Request 作为新目标进入 SessionInbox；其余事件是当前任务的推进结果，由
 * SessionRuntime.apply 在唯一消费者中采用。这里不定义事件持久化、自动重投或恰好一次
 * 业务执行保证；列表复制只固定交付结构，不是消息事务。</p>
 */
public sealed interface SessionEvent {
    /**
     * 一项新的执行目标；只由可信终端/定时适配器创建，不由模型选择自己的权限身份。
     * @param prompt 请求原文；正常提交入口保证非空白，兼容已有历史的内部入口使用不追加的空占位
     * @param context Harness 固定的执行身份及审批通道，不进入模型可填写的工具 schema
     * @param cronId 定时定义 ID，供确认/归还投递资格使用；人工任务为 null，不等同于执行 ID
     */
    record Request(String prompt, ExecutionContext context, String cronId) implements SessionEvent { }

    /**
     * 压缩成功后可采用的完整历史，而不是追加到末尾的一条摘要。
     * @param messages 有序候选列表；构造时复制外层结构，采用时替换当前历史内容
     */
    record HistoryPrepared(List<Message> messages) implements SessionEvent {
        /**
         * 固定候选列表，避免运行器 clear 原历史时连同关联视图中的候选也被清空。
         * Message/内容块不做深拷贝，生产者仍须遵守交出后不原地改写的约定。
         * @param messages 非 null、无 null 元素的完整候选历史
         * @throws NullPointerException 列表或元素为 null
         */
        public HistoryPrepared { messages = List.copyOf(messages); }
    }

    /**
     * 一次正式模型步骤的合法回复，不包含召回/摘要辅助模型的回复。
     * @param message ASSISTANT 消息，由 AgentLoop 校验角色后产生
     * @param calls 从该消息提取且已校验 ID 唯一的工具调用；空列表仅表示模型想结束，
     *              运行器还须检查必等后台结果与 Stop，不能直接等同于任务完成
     */
    record ModelReply(Message message, List<ToolUseBlock> calls) implements SessionEvent {
        /**
         * 固定调用顺序；不重新校验角色或复制消息中的 JSON，校验责任仍在执行器。
         * @param message 正式回复消息
         * @param calls 非 null、无 null 元素的已校验调用列表
         * @throws NullPointerException calls 或其中元素为 null
         */
        public ModelReply { calls = List.copyOf(calls); }
    }

    /**
     * 一批完整工具结果；failure 非 null 时仍先闭合交换，再报告执行链失败。
     * compact 只表示工具确实发出了压缩请求，不在工具执行中间压缩。
     *
     * @param calls 原始有序调用列表，供批次策略识别纯 compact 或混合批次
     * @param results 每个调用恰有一个同 ID 的结果，末尾还可能附加 todo 提醒 TextBlock
     * @param roundsSinceTodo 本批后未更新 todo 的累计批次数；已发提醒时归零
     * @param compact 是否收到成功的 CompactTool 信号，不表示压缩已经执行
     * @param failure 意外工具/hook 运行异常；为 null 时可能仍有普通 Error 文本，非 null 时
     *                先采用完整批次再结束任务，不能重跑已执行的工具
     */
    record ToolBatch(List<ToolUseBlock> calls, List<ContentBlock> results, int roundsSinceTodo,
            boolean compact, RuntimeException failure) implements SessionEvent {
        /**
         * 固定调用及结果列表的外层，保证事件等待采用期间不会增删半批结果。
         * @param calls 非 null 的原调用列表
         * @param results 非 null 的完整结果列表
         * @param roundsSinceTodo 下一工具批次使用的提醒计数
         * @param compact 待处理的主动压缩信号
         * @param failure 可为 null 的原始执行链异常
         * @throws NullPointerException 任一列表或其元素为 null
         */
        public ToolBatch {
            calls = List.copyOf(calls);
            results = List.copyOf(results);
        }
    }

    /**
     * Stop hook 要求继续当前目标的输入，不是排队的新目标，不重新做入口召回。
     * @param prompt hook 返回的继续提示，作为 USER 文本加入历史；不替换固定 activeRequest
     */
    record Continue(String prompt) implements SessionEvent { }

    /**
     * 已筛选归属的后台结果参考文本，随当前任务的下一次模型请求发送。
     * @param texts 有序通知；必等结果属于当前执行，脱离结果带来源 ID 但不改变当前目标。
     *              原 bash 调用已返回启动 tool_result，因此不能复用其 tool_use_id 再返回第二个结果
     */
    record Notifications(List<String> texts) implements SessionEvent {
        /**
         * 固定通知文本列表，不在构造时修改历史或释放后台记录容量。
         * @param texts 非 null、无 null 元素的通知列表
         * @throws NullPointerException 列表或其元素为 null
         */
        public Notifications { texts = List.copyOf(texts); }
    }
}
