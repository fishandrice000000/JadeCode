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
 */
public sealed interface SessionEvent {
    /** 新目标，只由终端或调度适配器提交；cronId 为 null 表示不是定时交付。 */
    record Request(String prompt, ExecutionContext context, String cronId) implements SessionEvent { }

    /** 压缩成功后的完整候选历史；复制外层，防止随后 clear 原列表同时清空候选。 */
    record HistoryPrepared(List<Message> messages) implements SessionEvent {
        public HistoryPrepared { messages = List.copyOf(messages); }
    }

    /** 已校验的正式模型回复；运行器先采用和确认交付，再决定是否执行其中的工具。 */
    record ModelReply(Message message, List<ToolUseBlock> calls) implements SessionEvent {
        public ModelReply { calls = List.copyOf(calls); }
    }

    /**
     * 一批完整工具结果；failure 非 null 时仍先闭合交换，再报告执行链失败。
     * compact 只表示工具确实发出了压缩请求，不在工具执行中间压缩。
     */
    record ToolBatch(List<ToolUseBlock> calls, List<ContentBlock> results, int roundsSinceTodo,
            boolean compact, RuntimeException failure) implements SessionEvent {
        public ToolBatch {
            calls = List.copyOf(calls);
            results = List.copyOf(results);
        }
    }

    /** Stop hook 的继续输入，沿用当前目标，不进行一次新任务的入口召回。 */
    record Continue(String prompt) implements SessionEvent { }

    /** 后台结果转换后的上下文文本；元数据归属已经由运行器检查，不再复用原 tool_use_id。 */
    record Notifications(List<String> texts) implements SessionEvent {
        public Notifications { texts = List.copyOf(texts); }
    }
}
