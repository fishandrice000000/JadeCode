package com.jadecode.context;

import java.util.List;
import java.util.Locale;
import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.LlmException;
import com.jadecode.messages.*;

/**
 * AgentExecutor 使用的压缩契约；生产 Token 管线与课程字符算法各自实现。
 * 调用只返回候选，不修改输入历史；无改动返回原引用，失败时不采用候选。
 * 存盘和摘要 IO 由实现负责，usage 锚点由每个执行器独立提供。
 */
public interface ContextCompactor {
    int MAX_REACTIVE_RETRIES = 1;

    /** 正式请求前整理历史；摘要失败原样抛出，调用方只在成功后采用。 */
    List<Message> prepare(CompleteRequest request, String goal, ContextTokenTracker tracker, String state)
            throws LlmException;

    /** 一批 tool_result 全部回灌后处理主动 compact，不能拆开工具交换。 */
    List<Message> compactAfterBatch(List<ToolUseBlock> calls, CompleteRequest request, String goal, String state)
            throws LlmException;

    /** 按本次投影限制输出额度；已知无法容纳输入时明确失败。 */
    int outputBudget(CompleteRequest request, ContextTokenTracker tracker) throws LlmException;

    /** 生产策略关闭响应式有损补救；课程策略可以显式启用。 */
    boolean reactiveEnabled();

    /** 服务端明确超限后的限次补救；仍不修改输入历史。 */
    List<Message> reactiveCompact(List<Message> messages, String goal) throws LlmException;

    /**
     * 从会话结尾取出本次任务的用户请求原文。
     *
     * <p>Anthropic 的消息格式里，工具结果也用 role=user 回灌，所以历史最后一条 user 消息
     * 常常是工具结果，不是人说的话。摘要消息里的 Current user request 必须写对，
     * 所以要在任务开始时、任何工具结果或通知出现之前先把这个请求原文取出来，
     * 后面工具续跑、Stop 续跑和后台恢复都沿用同一份，不重新提取。
     *
     * <p>只看最后一条消息，不往前找更早的 user 消息。满足以下条件才接受，否则抛
     * {@link IllegalArgumentException}：
     * <ul>
     * <li>列表非 null、非空，最后一条不是 null；</li>
     * <li>最后一条是 {@link Role#USER}；</li>
     * <li>最后一条没有宿主召回参考来源标记；</li>
     * <li>最后一条不含 {@link ToolResultBlock}（即使它同时还带有文字，那也是工具回灌，不算请求）；</li>
     * <li>最后一条至少有一个非空白 {@link TextBlock}（只含空格、制表符或换行的不算）。</li>
     * </ul>
     *
     * <p>通过校验后，用 {@link Message#extractText()} 把该消息里所有 TextBlock 按顺序用换行
     * 拼接返回，包括空串和纯空白的块，不做 trim。“至少一个非空白块”是能不能接受的门槛，
     * “原样拼接全部文本”是返回的内容，两件事。
     *
     * <p>本方法只读，不修改消息、不读写文件、不调模型。
     *
     * @param messages 会话消息列表
     * @return 本次任务的用户请求原文
     * @throws IllegalArgumentException 列表为 null、为空，或最后一条不满足上述条件
     */
    public static String extractActiveRequest(List<Message> messages) {
        // 1. 列表本身:入口至少要有一条"人类请求"可言。
        if (messages == null) {
            throw new IllegalArgumentException(
                    "messages cannot be null: the entry point requires at least one human request");
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException(
                    "messages cannot be empty: the entry point requires at least one human request");
        }

        // 2. 末条消息:必须存在、来自 USER,并且 content 不为 null。
        //    Message 是 record,构造器不校验这些字段,所以这里逐个 null 检查,避免后面抛偶发 NPE
        //    (例如 extractText 直接在 null content 上迭代)。
        Message last = messages.getLast();
        if (last == null) {
            throw new IllegalArgumentException("The last message cannot be null");
        }
        if (last.role() != Role.USER) {
            throw new IllegalArgumentException("The last request must be a USER message, but was " + last.role());
        }
        // USER 是协议角色；宿主召回资料不是用户新指令，不能成为压缩时的当前请求。
        if (last.source() == Message.Source.MEMORY_REFERENCE) {
            throw new IllegalArgumentException("A recalled memory reference is not a user request");
        }
        if (last.content() == null) {
            throw new IllegalArgumentException("The last request content cannot be null");
        }

        // 3. 逐块检查末条 content:拒绝工具结果与 null 结构,同时记录"是否存在非空白文本"。
        //    发现 tool_result 就立刻拒绝——哪怕同一消息里还有人类文本:混合形态属于工具回灌,
        //    不能因为"顺带有字"就当成请求。
        boolean hasNonBlankText = false;
        for (ContentBlock block : last.content()) {
            if (block == null) {
                throw new IllegalArgumentException("The last request content cannot contain null blocks");
            }
            if (block instanceof ToolResultBlock) {
                throw new IllegalArgumentException(
                        "The last request must not contain tool_result: tool results also use the USER role and cannot be treated as a human request");
            }
            if (block instanceof TextBlock text) {
                String value = text.text();
                if (value == null) {
                    throw new IllegalArgumentException("The last request text block text cannot be null");
                }
                if (!value.isBlank()) {
                    hasNonBlankText = true;
                }
            }
        }

        // 4. 入口校验的第二半:至少要有一个非空白文本块。
        //    空 content、只含非文本块、或只含空串 / 纯空白,都不是人类请求。
        if (!hasNonBlankText) {
            throw new IllegalArgumentException(
                    "The last request needs at least one non-blank text block (empty, whitespace-only or null text does not count as a request)");
        }

        // 5. 通过校验:交给 Message.extractText() 按出现顺序用 "\n" 连接全部 TextBlock,
        //    原样返回(不 trim / strip)。复用这个方法可以保证与项目其它地方"提取文本"的口径一致。
        return last.extractText();
    }

    /**
     * 正式请求失败后，判断这个错误是不是“上下文过长”，值不值得压缩一次再重试。
     *
     * <p>调用方拿到 {@link LlmException} 时先问本方法：true 才去压缩并重试同一次请求；false 就按普通
     * 失败抛出。本方法只做分类，不压缩、不重试、不碰消息、不写文件。
     *
     * <p>只看异常自己的说明文字，忽略大小写做子串匹配，命中 {@code prompt_too_long} 或
     * {@code too many tokens} 就算上下文过长。不看 HTTP 状态码、cause 或栈，因为相同状态码的
     * 错误原因可能完全不同。本地估算的长度只是估算，服务端仍然可能拒绝，真正“太长”只有服务端说了算；
     * 反过来关键词匹配也只是近似，服务端换个说法就会漏判，说明里恰好引用这两个词也可能误判，
     * 所以它只是一个“值不值得试一次”的开关。
     *
     * <p>认证失败、限流、网络中断这类错误即使压缩重试也不会成功，反而白白损失上下文，
     * 所以不能进这条压缩路径。即使本方法返回 true，补救也可能失败，调用方要把同一次请求最多补救一次。
     *
     * @param error 模型调用异常
     * @return true 表示说明里出现约定关键词，可以尝试一次压缩补救
     */
    public static boolean isContextOverflow(LlmException error) {
        // 1. 没有异常、或异常没带说明,就没有可判定的文本 → false(而不是抛异常:
        //    这个判断是给调用方处理失败用的,自己不能再报新的错)。
        if (error == null) {
            return false;
        }
        String message = error.getMessage();
        if (message == null) {
            return false;
        }

        // 2. 统一转小写后比较,实现"忽略大小写";显式指定 Locale.ROOT,
        //    避免结果随机器默认语言环境变化(某些区域设置下字母的大小写映射并不相同)。
        String normalized = message.toLowerCase(Locale.ROOT);

        // 3. 两个约定关键词任一命中即 true。这里只认这两个子串:
        //    不扩词(如 context_length_exceeded)、不做归一化、不读状态码或 cause。
        return normalized.contains("prompt_too_long") || normalized.contains("too many tokens");
    }
}
