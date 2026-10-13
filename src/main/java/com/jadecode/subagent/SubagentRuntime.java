package com.jadecode.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.context.ContextCompactor;
import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.LlmClient;
import com.jadecode.llm.LlmException;
import com.jadecode.llm.ModelResponse;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolExchangeProtocol;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.safety.Hooks;
import com.jadecode.tools.CompactTool;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;

/**
 * 子代理运行时:一次委派 = 一段全新 messages 的嵌套循环。
 *
 * <p>
 * 与父循环({@link com.jadecode.agent.AgentLoop})的关系:共享 LlmClient / Hooks / 工作区,
 * 但消息完全隔离——子代理从只有一条 user 消息的列表起步,父代理只收它的最终文本。
 * 工具表由 Main 装配传入(不含 task,故只有一层委派)。
 * </p>
 *
 * <p>
 * <b>为什么子会话也需要上下文保护</b>:全新上下文降低了起步负担,却挡不住子任务自己读文件、跑构建
 * 而把历史越堆越长。只保护父会话会留下"主会话能持续、长子任务仍会超限中断"的缺口。
 * 所以这里复用父循环那套压缩能力:每次正式请求前自动整理({@link ContextCompactor#prepare}),
 * 批次闭合后处理主动压缩请求({@link ContextCompactor#compactAfterBatch}),正式请求被判定为
 * 上下文过长时做一次有限补救({@link ContextCompactor#reactiveCompact})。
 * </p>
 *
 * <p>
 * <b>共享什么、隔离什么</b>:共享的是<b>无会话状态</b>的压缩依赖与存储;隔离的是这一次委派自己的
 * 东西——messages、子目标(入口 prompt 原文)、已消耗轮数与补救额度,全都是 run 的局部变量,
 * 不写成实例字段。同一个运行时连续跑两次委派,历史与目标不会串:
 * 子任务目标与父用户的请求本来就不是同一个字符串,辅助摘要也不属于子代理的一次"行动"。
 * </p>
 *
 * <p>
 * <b>本类是 JadeCode 的组合设计</b>:本机 s08 课程只给了主循环的压缩策略,没有这套子循环生命周期;
 * 这里是把 s06 的子代理与 s08 的策略接起来,不冒充原课程已有实现。
 * </p>
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

    /** 共享事件表:子代理的工具调用同样过权限与生命周期 hook */
    private final Hooks hooks;

    /**
     * 压缩依赖;{@code null} 表示这条委派路径不压缩(旧路径,五参构造器)。
     *
     * <p>
     * 生产装配({@code Main.buildLoop})已经用六参构造器把<b>父循环那一个</b>压缩器传给子代理,
     * 所以真实 task 调用同样能自动整理、处理 compact 信号并对超限做有限补救。
     * </p>
     *
     * <p>
     * 它本身不持有会话状态,父子循环可以共享同一个实例;注入它并不会把父会话塞进子会话——
     * 子代理仍然从全新的 messages 起步。
     * </p>
     */
    private final ContextCompactor compactor;

    /**
     * 不压缩的构造器:保持原有装配方式,等价于把第六个参数传 {@code null}。
     *
     * @param client    LLM 客户端
     * @param tools     子代理工具表
     * @param system    子代理 system prompt
     * @param maxTokens 单请求 token 上限
     * @param hooks     共享事件表
     */
    public SubagentRuntime(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks) {
        this(client, tools, system, maxTokens, hooks, null);
    }

    /**
     * 完整构造器:可注入压缩依赖(请求前自动整理 + 主动压缩 + 超限补救共用它)。
     *
     * <p>
     * <b>配置错误早暴露</b>:工具表里注册了 compact 却没提供压缩器,说明装配漏了一半——
     * 工具会"成功"返回 {@link CompactTool#OUTPUT},却没有任何人处理这个请求。这种组合在<b>构造时</b>
     * 就抛 {@link IllegalArgumentException}(与父循环一致),不留到运行期静默失效。
     * 反过来,<b>不</b>注册 compact 也可以注入压缩器:那时只有请求前的自动整理与超限补救生效。
     * </p>
     *
     * @param client    LLM 客户端
     * @param tools     子代理工具表;注册了 compact 时必须同时提供 compactor
     * @param system    子代理 system prompt
     * @param maxTokens 单请求 token 上限
     * @param hooks     共享事件表
     * @param compactor 压缩依赖;{@code null} = 不压缩(旧路径)
     * @throws IllegalArgumentException tools 注册了 compact 但 compactor 为 null
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
     * 跑一次委派,返回子代理的最终文本。
     *
     * <p>
     * <b>整体数据流</b>:新建一份只有一条 user(prompt) 的历史 → 每一轮走"请求前自动整理 →
     * 正式请求 →(必要时)有限补救 → 执行本批工具 → 结果回灌闭合 → 可选的批次闭合压缩",
     * 直到模型给出没有 tool_use 的最终文本(先问 Stop hook,它可能强制再跑一轮),或者 30 轮成功回复用完。
     * </p>
     *
     * <p>
     * <b>轮次的单位</b>:只有"正式请求真正拿到回复"才消耗一轮;失败重试与辅助摘要都不占轮次。
     * 成功之后即使 Stop hook 强制续跑,也仍然消耗刚拿到的那一轮——所以第 30 次成功回复如果是最终文本,
     * 就正常 DONE;如果还在调工具,工具照常执行完并闭合,但不会再发第 31 次业务请求,以 STOPPED 收尾。
     * </p>
     *
     * <p>
     * <b>两个 catch 的分工</b>:内层只包住那一次正式 {@code client.complete},负责"要不要补救";
     * 外层统一 catch {@link LlmException},负责 END(FAILED) 恰好一次。辅助摘要(prepare / 主动压缩 /
     * 补救里的摘要调用)、工具执行与 hooks 的失败都不进内层,所以它们不会被误当成"正式请求超限"去补救——
     * 摘要失败更不该再触发一轮补救。
     * </p>
     *
     * <p>
     * 生命周期:入口恰好一次 SUBAGENT_START,出口恰好一次 SUBAGENT_END——正常收尾 DONE、
     * 轮数耗尽 STOPPED、不可恢复的正式请求错误或新增的压缩 IO 失败 FAILED(模型回复内重复调用 ID
     * 沿用既有 FAILED 契约)。补救成功不是"新起一个子代理",过程中不提前 END。
     * </p>
     *
     * @param prompt 父代理给的子任务描述
     * @return 子代理最终文本;轮数耗尽时为 "Subagent stopped after 30 turns without a final
     *         answer."
     * @throws LlmException 网络 / API 错误,向上抛给调用方决定重试或退出
     */
    public String run(String prompt) throws LlmException {
        List<Message> messages = new ArrayList<>();

        messages.add(Message.userText(prompt));

        // 本次委派的"当前请求"就是入口 prompt 原文:不借用父循环的 extractActiveRequest、
        // 不从随后的工具结果或 Stop 注入里重新提取,也不存成实例字段——它只活在这一个 run 里。
        final String activeRequest = prompt;

        // 触发事件 SUBAGENT_START(一次委派恰好一次)
        hooks.trigger(Hooks.SUBAGENT_START, Map.of());

        int turns = 0;           // 已消耗的轮次:只有正式请求真正拿到回复才 +1
        int reactiveRetries = 0; // 本次委派的补救额度:正式请求成功即归零,不跨 run

        try {
            while (turns < MAX_TURNS) {
                // 1. 正式请求之前的自动整理(未注入压缩器时整段跳过,保持旧行为)。
                //    首次请求、工具结果回灌之后、Stop 续跑之后、补救重试之前都会经过这里。
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
                    response = client
                            .complete(new CompleteRequest(system, messages, maxTokens, List.copyOf(tools.values())));
                } catch (LlmException error) {
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
                    // 先查本类自己的工具表:子表是父表的子集,父表查得到的 task / todo_write 在这里是未知工具。
                    // 未知工具在此短路,不进任何工具 hook——否则会打出一行"调用过"的假日志
                    Tool tool = tools.get(u.name());
                    if (tool == null) {
                        results.add(new ToolResultBlock(u.id(), UNKNOWN_TOOL + u.name()));
                        continue;
                    }

                    // 已知工具才进 PRE_TOOL_USE:权限闸门与日志都挂在这里,首个非 null 即拦截
                    String blocked = hooks.trigger(Hooks.PRE_TOOL_USE, Map.of(Hooks.PARAM_BLOCK, u));
                    if (blocked != null) {
                        results.add(new ToolResultBlock(u.id(), blocked));
                        continue;
                    }

                    // 调用工具获取输出
                    ObjectNode node = u.input() instanceof ObjectNode o ? o : Json.mapper().createObjectNode();
                    String output = tool.execute(node);

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

                    // 回灌结果
                    results.add(new ToolResultBlock(u.id(), output));
                }

                // 7. 全部结果合成一条 user 消息:到这一行为止,本批的工具交换才"闭合"
                messages.add(Message.userBlocks(results));

                // 8. 闭合之后才处理主动压缩:整批结果都已回灌,分类(纯 compact 整段摘要 / 混合批次先预算
                //    再摘要旧前缀)交给压缩器,子循环不复制这套策略。
                if (compactRequested) {
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
