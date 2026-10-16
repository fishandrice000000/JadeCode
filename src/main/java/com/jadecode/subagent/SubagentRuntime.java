package com.jadecode.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.context.ContextCompactor;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.LlmClient;
import com.jadecode.llm.LlmException;
import com.jadecode.llm.ModelResponse;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.ToolExchangeProtocol;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.tools.CompactTool;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;

/**
 * 在独立的消息列表里运行一项子任务，完成后只把最终文字交给父 Agent。
 *
 * <p>父 Agent 调用 task 时，会在当前线程等本类返回。子任务从一条 user(prompt) 开始，
 * 不读取父历史。模型客户端、hooks、工作区和压缩器可以共用，但每次 run 的消息、任务原文、
 * 轮数和重试次数都单独创建，不会混到上次委派或父任务里。</p>
 *
 * <p>子任务也可能读很多文件把上下文撑大，所以每次请求模型前同样调用 prepare，
 * 遇到上下文超限时限次压缩后重试，compact 工具则等整批结果都加进历史后再处理。
 * 这些父子能力的组合是 JadeCode 的做法，不是声称原 s08 课程已有同样实现。</p>
 *
 * <p>本次重构只让子 Agent 沿用父任务的来源、询问用户的方法和会话退出检查。
 * 它仍用自己的历史，不加入父任务队列，也没有后台 bash。
 * 因此用户退出后，子模型即使才返回，也不能继续启动新工具。</p>
 */
public class SubagentRuntime {
    /** 子代理最多消耗几轮"成功回复";循环正常走完即超限,回 stopped 文案 */
    private static final int MAX_TURNS = 30;

    /** stopped 文案 */
    private static final String STOPPED = "Subagent stopped after " + MAX_TURNS
            + " turns without a final answer.";

    /** no summary 文案 */
    private static final String NO_SUMMARY = "(no summary)";

    /** unknown tool 文案 */
    private static final String UNKNOWN_TOOL = "Error: Unknown tool: ";

    /**
     * compact 工具名:主动压缩的批次标志只认这个字符串值。
     *
     * <p>
     * 与父循环一样,这里刻意不为了共享常量而给工具类加依赖;值一旦与工具名不符,子代理压缩测试会立刻失败。
     * </p>
     */
    private static final String COMPACT_TOOL = "compact";

    /** LLM 客户端:与父循环共用同一实例 */
    private final LlmClient client;

    /** 子代理工具表(基础工具,不含 task) */
    private final Map<String, Tool> tools;

    /** 子代理的 system prompt */
    private final String system;

    /** 单次请求的 max_tokens,与父循环同值 */
    private final int maxTokens;

    /** 子代理和父代理共用同一份事件表：子工具调用一样会走权限检查和前后 hook。 */
    private final Hooks hooks;

    /**
     * 子任务压缩消息所用的对象，null 表示不压缩。
     *
     * <p>Main.buildLoop 把父 Agent 使用的同一个压缩器传进来，所以子 Agent 也能处理
     * 请求前压缩、compact 工具和上下文超限。共用的是压缩方法及保存目录，
     * 不是消息列表；压缩器里不保存本次任务的 history，子消息仍在每次 run 中新建。</p>
     */
    private final ContextCompactor compactor;

    /**
     * 创建不压缩历史的子 Agent，和六参数版本传 compactor=null 相同，不立即执行任务。
     * @param client 请求模型的客户端，与父 Agent 共用
     * @param tools 子工具表，当前不包含 task
     * @param system 子 Agent 的 system 提示词
     * @param maxTokens 单次回答生成 token 的上限
     * @param hooks 工具检查及子任务开始/结束时调用的函数表
     * @throws IllegalArgumentException 工具表有 compact，却未提供处理它的压缩器
     */
    public SubagentRuntime(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks) {
        this(client, tools, system, maxTokens, hooks, null);
    }

    /**
     * 给子 Agent 提供自动压缩、compact 工具和超限重试共用的压缩器。
     *
     * <p>工具表有 compact 却没给压缩器，就立即报错；否则工具会说“已请求压缩”，
     * 实际上却没人去压缩。也可以只给压缩器、不开放 compact 工具，
     * 那仍可自动整理历史和处理上下文超限。构造时不请求模型或压缩消息。</p>
     * @param client 请求模型的客户端，与父 Agent 共用
     * @param tools 子工具表，当前不包含 task
     * @param system 子 Agent 的 system 提示词
     * @param maxTokens 单次回答生成 token 的上限
     * @param hooks 工具检查及子任务开始/结束时调用的函数表
     * @param compactor 压缩器，可为 null；工具表含 compact 时必须提供
     * @throws IllegalArgumentException 工具表含 compact 但 compactor 为 null
     */
    public SubagentRuntime(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor) {
        if (compactor == null && tools.containsKey(COMPACT_TOOL)) {
            throw new IllegalArgumentException("工具表注册了 " + COMPACT_TOOL
                    + " 但构造时未提供 ContextCompactor:压缩请求将无人处理,请注入压缩器或移除该工具");
        }
        this.client = client;
        this.tools = tools;
        this.system = system;
        this.maxTokens = maxTokens;
        this.hooks = hooks;
        this.compactor = compactor;
    }

    /**
     * 独立调用子 Agent 时使用，不带父会话的信息。
     * 默认创建 USER 任务，但没有询问用户的方法，也不知道父会话是否退出。
     * 真正的 task 工具应调用双参数版本。
     * @param prompt 子任务要做的事
     * @return 最终文字或 30 轮用完的提示
     * @throws LlmException 请求模型或生成压缩摘要失败
     * @see #run(String, ExecutionContext)
     */
    public String run(String prompt) throws LlmException {
        return run(prompt, ExecutionContext.create(ExecutionContext.Source.USER, null));
    }

    /**
     * 用全新消息列表完成一项子任务，父 Agent 在调用这里的线程等最终结果。
     *
     * <p>先把 prompt 放成 USER 消息，每轮压缩需要缩短的历史后请求模型。
     * 模型要用工具就依次执行，把整批结果加进历史，必要时处理 compact；
     * 不再调用工具且 Stop 也不要求继续时，返回最终文字。父 Agent 不拿这份中间历史。</p>
     *
     * <p>prompt 原文一直记在 activeRequest，不能被工具输出或 Stop 提示换掉。
     * context 由父程序传来：定时子任务仍不能询问用户，也沿用父会话的退出检查。
     * 请求模型前后、执行工具前、等用户批准后都查是否退出，不只依赖线程中断。
     * 已经写入的文件不会因退出还原。</p>
     *
     * <p>只有拿到正式模型回复才消耗一轮，最多 30 轮；摘要和失败重试不算。
     * 第 30 次若能结束就返回 DONE，否则先给已调用工具加上结果，再返回 STOPPED，
     * 不发第 31 次请求。正式请求的超限错误才考虑压缩重试，生成摘要失败不能再套一层重试。</p>
     *
     * <p>LlmException（包括父会话退出）由外层报告 FAILED 后重新抛出；
     * 归档出错和调用 ID 重复也有各自的 FAILED 处理。其它自定义工具或 hook 的意外运行异常
     * 不一定被转换成 LlmException，不能据此以为所有异常都已经在这里统一处理。</p>
     * @param prompt 子任务描述；启用压缩时须符合压缩器对请求的要求，不修改原字符串
     * @param context 父任务来源、询问用户的方法和会话退出检查，不可为 null
     * @return 最终文字，空白时使用已有无摘要提示；轮数用完则返回 STOPPED 提示
     * @throws LlmException 模型或压缩摘要失败，或用户已退出父会话
     * @throws IllegalStateException 压缩归档等运行失败，不能继续
     * @throws NullPointerException context 为 null
     */
    public String run(String prompt, ExecutionContext context) throws LlmException {
        java.util.Objects.requireNonNull(context, "executionContext");
        // 每次 run 都新建子消息列表，不用父 history；父对话中只保留这次 task 的最终结果。
        List<Message> messages = new ArrayList<>();

        messages.add(Message.userText(prompt));

        // 这次子任务要做的事就是 prompt；不用从父历史找，也不从之后的工具结果或 Stop 提示重取。
        // 只存在本次 run 的局部变量里，不放在对象字段上，避免下一次委派沿用旧任务。
        final String activeRequest = prompt;

        // 触发事件 SUBAGENT_START(一次委派恰好一次)
        hooks.trigger(Hooks.SUBAGENT_START, Map.of());

        int turns = 0;           // 已消耗的轮次:只有正式请求真正拿到回复才 +1
        int reactiveRetries = 0; // 本次委派的补救额度:正式请求成功即归零,不跨 run

        try {
            while (turns < MAX_TURNS) {
                // 网络客户端可能不理会中断；继续下一步前仍要看用户是否已退出。
                checkSessionOpen(context);
                // 1. 正式请求之前的自动整理(未注入压缩器时整段跳过,保持旧行为)。
                //    第一次请求、加入工具结果后、Stop 要求继续后、超限重试前，都会经过这里。
                if (compactor != null) {
                    List<Message> prepared;
                    try {
                        prepared = compactor.prepare(messages, activeRequest);
                    } catch (IllegalStateException io) {
                        // 归档 / 落盘失败:子代理没法继续了,按失败收尾一次再原样抛(不是可恢复的超限)。
                        endFailed(io);
                        throw io;
                    }
                    // 只有正常返回才采用候选;返回入参本身表示没有可整理的部分(no-op),此时不能 clear。
                    if (prepared != messages) {
                        messages.clear();
                        messages.addAll(prepared);
                    }
                }

                // 2. 正式请求。这是本类唯一被"恢复 catch"包住的调用:prepare、主动压缩摘要、
                //    补救摘要、工具与 hooks 的异常都不在此列,不会被当成正式请求超限而补救。
                ModelResponse response;
                try {
                    checkSessionOpen(context);
                    response = client
                            .complete(new CompleteRequest(system, messages, maxTokens,
                                    tools.values().stream().map(Tool::definition).toList()));
                } catch (LlmException error) {
                    // 如果用户在等模型时退出，即使现在才返回超限错误，也不要再调用摘要模型。
                    checkSessionOpen(context);
                    // 2.1 恢复分流:命中"上下文过长"的分类、且本次委派还有额度,才值得付一次有损压缩。
                    //     不匹配(认证 / 限流 / 网络)或额度耗尽的异常原样抛出,交给外层统一 END(FAILED)。
                    if (compactor == null || !ContextCompactor.isContextOverflow(error)
                            || reactiveRetries >= ContextCompactor.MAX_REACTIVE_RETRIES) {
                        throw error;
                    }

                    // 2.2 先消耗额度:即使下面拿到的是 no-op,也算"已经试过一次",否则会一直重试。
                    reactiveRetries++;

                    List<Message> recovered;
                    try {
                        recovered = compactor.reactiveCompact(messages, activeRequest);
                    } catch (IllegalStateException io) {
                        endFailed(io);
                        throw io;
                    }
                    if (recovered != messages) {
                        messages.clear();
                        messages.addAll(recovered);
                    }

                    // 2.3 回到循环顶部重新整理、重新发<b>同一次</b>正式请求。
                    //     这次尝试没有拿到子代理回复,所以不追加 assistant、不执行工具、不推进轮次、
                    //     也不触发 Stop 或 SUBAGENT_TOOL——它不是一个"行动"。
                    continue;
                }

                // 2.4 正式请求成功:额度归零(下一次正式请求又是一次新机会),
                //     而且只有这一刻才消耗一个子代理轮次。
                checkSessionOpen(context);
                reactiveRetries = 0;
                turns++;

                // 3. 从 response.message().content() 收集 ToolUseBlock,并在追加 assistant 消息
                //    与执行任何工具之前拒绝重复调用 ID;失败走 END(FAILED) 恰好一次后原样抛出
                List<ToolUseBlock> toolCalls = new ArrayList<>();

                for (ContentBlock c : response.message().content()) {
                    if (c instanceof ToolUseBlock u) {
                        toolCalls.add(u);
                    }
                }
                try {
                    ToolExchangeProtocol.requireDistinctCallIds(toolCalls);
                } catch (IllegalArgumentException e) {
                    endFailed(e);
                    throw e;
                }

                // 4. 追加 assistant 消息
                messages.add(response.message());

                // 5. 没有 tool_use:先问 Stop hook(它可能强制再跑一轮),否则这就是最终答复
                if (toolCalls.isEmpty()) {
                    String force = hooks.trigger(Hooks.STOP, Map.of(Hooks.PARAM_MESSAGES, messages));
                    if (force != null) {
                        messages.add(Message.userText(force));
                        // 若这一轮已经是第 30 轮,while 条件会让循环结束 → 以 STOPPED 收尾,不发第 31 次请求
                        continue;
                    }

                    String output = response.message().extractText();
                    output = output.isBlank() ? NO_SUMMARY : output;

                    // 触发事件 SUBAGENT_END(正常收尾)
                    end(output, SubagentOutcome.DONE);

                    return output;
                }

                // 6. 调用 tool。本批的"是否请求压缩"标志每批重建:它只表示"这一批里有真实成功的
                //    compact 请求",不能跨批、跨 run 残留,也不能由被拦 / 未知工具伪造。
                List<ContentBlock> results = new ArrayList<>();
                boolean compactRequested = false;

                for (ToolUseBlock u : toolCalls) {
                    checkSessionOpen(context);
                    // 先查本类自己的工具表:子表是父表的子集,父表查得到的 task / todo_write 在这里是未知工具。
                    // 未知工具在此短路,不进任何工具 hook——否则会打出一行"调用过"的假日志
                    Tool tool = tools.get(u.name());
                    if (tool == null) {
                        results.add(new ToolResultBlock(u.id(), UNKNOWN_TOOL + u.name()));
                        continue;
                    }

                    // 已知工具才进 PRE_TOOL_USE:权限闸门与日志都挂在这里,首个非 null 即拦截
                    String blocked = hooks.trigger(Hooks.PRE_TOOL_USE,
                            Map.of(Hooks.PARAM_BLOCK, u, Hooks.PARAM_CONTEXT, context));
                    if (blocked != null) {
                        results.add(new ToolResultBlock(u.id(), blocked));
                        continue;
                    }

                    // 调用工具获取输出
                    // 等用户批准期间也可能退出；执行工具前再检查，不能只在 while 开始时检查。
                    checkSessionOpen(context);
                    ObjectNode node = u.input() instanceof ObjectNode o ? o : Json.mapper().createObjectNode();
                    String output = tool.execute(node, context);

                    // 只有"已知工具 + 未被拦截 + 真的执行完了 + 输出精确等于稳定文案"才算请求压缩。
                    // 判定放在执行之后:被拦的 compact 停在上面 continue,根本没有执行;别的工具返回同样
                    // 文案也不会误触发(名字必须是 compact)。这里不扫描结果正文或历史。
                    if (u.name().equals(COMPACT_TOOL) && CompactTool.OUTPUT.equals(output)) {
                        compactRequested = true;
                    }

                    // 触发事件 POST_TOOL_USE
                    hooks.trigger(Hooks.POST_TOOL_USE, Map.of(Hooks.PARAM_BLOCK, u, Hooks.PARAM_OUTPUT, output));
                    // 触发事件 SUBAGENT_TOOL
                    hooks.trigger(Hooks.SUBAGENT_TOOL,
                            Map.of(Hooks.PARAM_BLOCK, u, Hooks.PARAM_OUTPUT, output));

                    // 记下这个调用 ID 对应的工具结果。
                    results.add(new ToolResultBlock(u.id(), output));
                }

                // 7. 全部结果一起加入一条 USER；现在这批每个 tool_use 都有对应的 tool_result。
                messages.add(Message.userBlocks(results));

                // 8. 全部结果都已写入历史，现在才处理 compact。只有 compact 时可摘要整段；
                //    混有其它工具时先限制结果长度、保留新结果，再摘要旧消息，具体做法交给压缩器。
                if (compactRequested) {
                    checkSessionOpen(context);
                    List<Message> compacted;
                    try {
                        compacted = compactor.compactAfterBatch(toolCalls, messages, activeRequest);
                    } catch (IllegalStateException io) {
                        endFailed(io);
                        throw io;
                    }
                    if (compacted != messages) {
                        messages.clear();
                        messages.addAll(compacted);
                    }
                }
            }

            // 触发事件 SUBAGENT_END(30 轮耗尽,不是失败)
            end(STOPPED, SubagentOutcome.STOPPED);
            return STOPPED;
        } catch (LlmException e) {
            // 外层统一收尾:不匹配的正式请求失败、额度耗尽、以及各种辅助摘要失败都在这里
            // END(FAILED) 恰好一次。恢复分支自己不 END,所以不会出现两条 END。
            endFailed(e);
            throw e;
        }
    }

    /**
     * 子 Agent 继续请求模型或执行工具前，检查用户是否已退出父会话。
     *
     * <p>把退出报告成 LlmException，是为了让现有外层 catch 统一发出一次 FAILED。
     * 它不能立刻打断第三方网络请求或还原文件，只能阻止后面的操作。</p>
     * @param context 父任务传来的信息，通过其中函数读取会话是否退出
     * @throws LlmException 父会话已退出；不因此再请求压缩摘要
     */
    private static void checkSessionOpen(ExecutionContext context) throws LlmException {
        if (context.isCancelled()) throw new LlmException("Parent session is closed");
    }

    /**
     * 触发一次 SUBAGENT_END(FAILED):沿用既有 "Error: " + 异常消息的输出约定。
     *
     * @param error 导致委派失败的错误;原对象仍由调用处继续向上抛,这里只负责上报事件
     */
    private void endFailed(Throwable error) {
        hooks.trigger(Hooks.SUBAGENT_END,
                Map.of(Hooks.PARAM_OUTPUT, "Error: " + error.getMessage(),
                        Hooks.PARAM_OUTCOME, SubagentOutcome.FAILED));
    }

    /**
     * 触发一次 SUBAGENT_END(非失败终态)。
     *
     * @param output  终态文本(DONE 的最终答复,或 STOPPED 的固定文案)
     * @param outcome DONE 或 STOPPED
     */
    private void end(String output, SubagentOutcome outcome) {
        hooks.trigger(Hooks.SUBAGENT_END,
                Map.of(Hooks.PARAM_OUTPUT, output, Hooks.PARAM_OUTCOME, outcome));
    }
}
