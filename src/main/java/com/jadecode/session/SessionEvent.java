package com.jadecode.session;

import java.util.List;

import com.jadecode.execution.ExecutionContext;
import com.jadecode.messages.Message;

/**
 * 告诉 SessionRuntime 发生了什么，以及应该往 history 放什么内容。
 *
 * <p>例如新请求交来 Request，压缩历史交来 HistoryPrepared。
 * 它们不会自己修改 history，而由 SessionRuntime.apply 统一写入。
 * 这样不会在一批工具只执行到一半时，突然插入另一个用户任务。</p>
 *
 * <p>外部 Incoming 由 Request 包装后排队，接收会话依据投递设置选择消费时机。
 * 历史替换、工具批次仍同步应用，不需要每步都开线程。
 * 最终很多内容发给模型时都是 USER 消息，但工具结果、后台通知和用户新请求不能混为一谈。</p>
 *
 * <p>带列表的事件会复制列表，防止交出去后再增删元素；不会复制每个 Message 或其中的 JSON。
 * 发送方仍不能在交出去后改消息内容。历史替换不写盘，邮箱 Incoming 由 TeamMailbox 保存；
 * 保存邮件不等于保存整个会话，也不保证失败后可以自动重做业务。</p>
 */
public sealed interface SessionEvent {
    /**
     * 一项待执行的新任务，由用户输入处理代码或定时检查代码创建。
     * @param prompt 要做的事；正常提交须非空白，旧 run(messages) 已有请求，用空串但不追加它
     * @param context 程序记录的任务来源、ID 和询问用户的函数，模型不能填写
     * @param cronId 定时定义 ID，稍后用它标记已送达；非定时任务为 null
     * @param incoming 外部消息元数据；普通用户/cron 请求为 null
     */
    record Request(String prompt, ExecutionContext context, String cronId, Incoming incoming) implements SessionEvent {
        /** 普通用户/cron 请求没有邮箱元数据，保留原有构造方式。 */
        public Request(String prompt, ExecutionContext context, String cronId) {
            this(prompt, context, cronId, null);
        }
    }

    /**
     * 从会话外送来的消息。身份与路径由 Harness 填写，模型只提供摘要和正文。
     * 接收线程只排队；只有 Session 的消费线程能把 render 的文字放进历史。
     * assignmentId 区分同一持久任务的不同派工，不能只凭 taskId 接受旧报告。
     * @param id 程序生成的消息 ID，升级用户缓冲时保持不变
     * @param fromAgentId 实际发送者，不能由模型在正文中冒充
     * @param toAgentId 收件者，Harness 将消息放进对应 Session 的 Inbox
     * @param taskId 关联任务；无关联的一般通信可为 null
     * @param assignmentId 本次派工编号；用户缓冲和普通后台通知可为 null
     * @param kind assignment 是新任务，其它类型是通知；user 是用户缓冲批次
     * @param summary 直接交给模型的短摘要；用户缓冲则保留合并后的原提示词
     * @param bodyPath Harness 保存的完整正文路径；没有独立正文的事件可为 null
     * @param allowInsertion 工作途中是否允许接收；不是抢占正在运行的工具
     * @param wakeWhenIdle 空闲时是否主动请求模型
     */
    record Incoming(String id, String fromAgentId, String toAgentId, String taskId, String assignmentId,
            String kind, String summary, String bodyPath, boolean allowInsertion, boolean wakeWhenIdle)
            implements SessionEvent {
        /** 必需的身份和正文不得为空；路径可为空，用户缓冲没有正文文件。 */
        public Incoming {
            if (id == null || fromAgentId == null || toAgentId == null || kind == null
                    || summary == null || summary.isBlank()) throw new IllegalArgumentException("消息字段不完整");
        }

        /** 给模型看的来源说明，不把外部内容伪造成工具结果或系统指令。 */
        public String render() {
            return "[Incoming " + kind + "] from=" + fromAgentId + " to=" + toAgentId
                    + " task=" + taskId + " assignment=" + assignmentId + " message=" + id
                    + "\n" + summary + (bodyPath == null ? "" : "\nFull body: " + bodyPath);
        }
    }

    /**
     * 压缩器准备好的整份历史消息，不是要加在末尾的一条摘要。
     * @param messages 将替换 history 内容的列表，保持原顺序
     */
    record HistoryPrepared(List<Message> messages) implements SessionEvent {
        /**
         * 复制列表，使 SessionRuntime 清空原 history 时，不把这份新历史也清空。
         * 只复制列表，不复制每条消息；交出后不要改里面的 Message 或 JSON。
         * @param messages 不可为 null，不能含 null 元素
         * @throws NullPointerException 列表或其中元素为 null
         */
        public HistoryPrepared { messages = List.copyOf(messages); }
    }

    /**
     * 后台命令跑完后，准备给模型看的结果说明。
     * @param texts 当前任务必须处理的结果，或其它“不必等它”的命令结果参考。
     *              原 bash 调用已经返回启动说明，不能再用同一 tool_use_id 返回第二份结果，
     *              所以这里使用普通文字，并保留来源任务 ID
     */
    record Notifications(List<String> texts) implements SessionEvent {
        /**
         * 复制通知列表；这里只保存文字，不修改 history，也不删除后台命令记录。
         * @param texts 非 null、无 null 元素的通知文字列表
         * @throws NullPointerException 列表或其中元素为 null
         */
        public Notifications { texts = List.copyOf(texts); }
    }
}
