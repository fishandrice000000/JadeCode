package com.jadecode.agent;

import java.util.List;

import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.ToolUseBlock;

/**
 * 告诉 SessionRuntime 发生了什么，以及应该往 history 放什么内容。
 *
 * <p>例如模型回答后交来 ModelReply，执行整批工具后交来 ToolBatch。
 * 它们不会自己修改 history，而由 SessionRuntime.apply 统一写入。
 * 这样不会在一批工具只执行到一半时，突然插入另一个用户任务。</p>
 *
 * <p>只有 Request 要作为新任务排队，其它事件直接用于继续当前任务，不需要每步都开线程。
 * 最终很多内容发给模型时都是 USER 消息，但工具结果、后台通知和用户新请求不能混为一谈。</p>
 *
 * <p>带列表的事件会复制列表，防止交出去后再增删元素；不会复制每个 Message 或其中的 JSON。
 * 发送方仍不能在交出去后改消息内容。事件不保存到磁盘，也不保证失败时可以自动重做。</p>
 */
public sealed interface SessionEvent {
    /**
     * 一项待执行的新任务，由用户输入处理代码或定时检查代码创建。
     * @param prompt 要做的事；正常提交须非空白，旧 run(messages) 已有请求，用空串但不追加它
     * @param context 程序记录的任务来源、ID 和询问用户的函数，模型不能填写
     * @param cronId 定时定义 ID，稍后用它标记已送达；非定时任务为 null
     */
    record Request(String prompt, ExecutionContext context, String cronId) implements SessionEvent { }

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
     * 模型对当前任务的一次回答，不包括查记忆或生成摘要的辅助回答。
     * @param message AgentLoop 已检查角色为 ASSISTANT 的消息
     * @param calls 从这条消息取出的工具调用，ID 已检查不重复；没有调用不一定代表任务完成，
     *              还要看是否在等后台结果，以及 Stop 是否要求继续
     */
    record ModelReply(Message message, List<ToolUseBlock> calls) implements SessionEvent {
        /**
         * 复制工具调用列表，保持顺序；角色和 ID 由 AgentLoop 检查，这里不再查。
         * @param message 模型回答
         * @param calls 不能为 null、不能含 null 的调用列表；不复制里面的 JSON
         * @throws NullPointerException calls 或其中元素为 null
         */
        public ModelReply { calls = List.copyOf(calls); }
    }

    /**
     * 模型同一次回复要求的工具已经处理完，把结果一次性交给运行器。
     *
     * <p>发生意外异常时，也先给每个调用配上结果说明，再报告失败，
     * 免得下一次请求的历史中留下没有 tool_result 的 tool_use。</p>
     * @param calls 原始调用顺序，压缩器用它判断这批是否只有 compact
     * @param results 每个调用有一个同 ID 的结果，最后还可能带 todo 提醒文字
     * @param roundsSinceTodo 接下来使用的 todo 提醒计数，按批次而不是工具个数计数
     * @param compact compact 工具是否真的返回了指定文字；true 只说明要压缩，还没开始压缩
     * @param failure 意外运行异常，可为 null；普通工具返回 Error 文本不放这里。
     *                非 null 时先把结果写入历史再结束任务，不能重跑已经执行过的工具
     */
    record ToolBatch(List<ToolUseBlock> calls, List<ContentBlock> results, int roundsSinceTodo,
            boolean compact, RuntimeException failure) implements SessionEvent {
        /**
         * 复制调用和结果列表，防止交给运行器后又少掉或多出一项。
         * @param calls 原调用列表
         * @param results 已配齐的整批结果
         * @param roundsSinceTodo 下一批使用的提醒计数
         * @param compact 是否需要在结果写入 history 后压缩
         * @param failure 实际意外异常，没有则为 null
         * @throws NullPointerException 列表或其中元素为 null
         */
        public ToolBatch {
            calls = List.copyOf(calls);
            results = List.copyOf(results);
        }
    }

    /**
     * Stop hook 说还有工作要做，让模型继续当前任务，不排成另一项任务。
     * @param prompt 加入 history 的提示文字，不替换程序记住的用户原始请求
     */
    record Continue(String prompt) implements SessionEvent { }

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
