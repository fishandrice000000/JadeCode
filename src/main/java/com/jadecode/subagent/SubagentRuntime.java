package com.jadecode.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.jadecode.context.ContextCompactor;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.LlmException;
import com.jadecode.messages.Message;

/**
 * 一次同步委派的生命周期：父 Agent 等待最终文字，子 Agent 使用独立历史和执行器。
 *
 * <p>每次入口固定思考快照、显示身份和沙箱作用域，再由工厂创建 AgentExecutor。
 * 模型传输与权限 Hook 可以共用，但工具目录、正式 usage 锚点与摘要归属独立。
 * request/executeTools/compactAfterBatch 统一处理角色校验、超限补救和闭合工具交换；
 * 本类只决定是否继续、30 轮上限和 SUBAGENT_START/END，不复制第二套执行算法。</p>
 *
 * <p>只有成功取得完整正式回复才消耗一轮；没有工具且 Stop 不要求继续时返回最终文字。
 * 子任务不读取父历史、不加入父队列，也不启动后台 bash。所有退出均撤销本次沙箱授权；
 * 已执行的文件副作用不会因模型失败或用户退出而回滚。</p>
 */
public class SubagentRuntime {
    /** 每次委派单独的显示身份，绝不替换父任务的 ExecutionContext。 */
    private final ThreadLocal<String> displayId = new ThreadLocal<>();
    private final com.jadecode.execution.ExecutionObserver observer;
    private final java.util.function.Function<Invocation, com.jadecode.agent.AgentExecutor> executors;
    private final java.util.function.Supplier<com.jadecode.llm.ThinkingSettings> thinking;
    private final java.util.function.Consumer<ExecutionContext> sandboxCleanup;

    /** 一次委派的可信装配信息；思考取入口快照，观察器只负责该子身份。 */
    public record Invocation(String scope, com.jadecode.llm.ThinkingSettings thinking,
            com.jadecode.execution.ExecutionObserver observer) {
    }

    /**
     * 保存每次委派的执行器工厂、显示、思考来源与沙箱清理动作，构造不执行任务。
     * executors 每次必须返回新实例，用完整 scope 绑定客户端/摘要的用量归属；不共享历史锚点。
     * 不需要沙箱时显式给空清理动作，调用方无需在构造后补装必需依赖。
     */
    public SubagentRuntime(java.util.function.Function<Invocation, com.jadecode.agent.AgentExecutor> executors,
            com.jadecode.execution.ExecutionObserver observer,
            java.util.function.Supplier<com.jadecode.llm.ThinkingSettings> thinking,
            java.util.function.Consumer<ExecutionContext> sandboxCleanup) {
        this.executors = java.util.Objects.requireNonNull(executors);
        this.observer = java.util.Objects.requireNonNull(observer);
        this.thinking = java.util.Objects.requireNonNull(thinking);
        this.sandboxCleanup = java.util.Objects.requireNonNull(sandboxCleanup);
    }

    private void display(com.jadecode.execution.ExecutionNotice notice) {
        observer.publish(notice.actor("Subagent " + displayId.get()));
    }

    /** 子代理最多消耗几轮"成功回复";循环正常走完即超限,回 stopped 文案 */
    private static final int MAX_TURNS = 30;

    /** stopped 文案 */
    private static final String STOPPED = "Subagent stopped after " + MAX_TURNS
            + " turns without a final answer.";

    /** no summary 文案 */
    private static final String NO_SUMMARY = "(no summary)";

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
     * <p>工具耗时从批准后实际调用 execute 才开始计算；审批等待不计入，被拒绝的工具不提供执行耗时。</p>
     *
     * <p>只有拿到正式模型回复才消耗一轮，最多 30 轮；摘要和失败重试不算。
     * 第 30 次若能结束就返回 DONE，否则先给已调用工具加上结果，再返回 STOPPED，
     * 不发第 31 次请求。正式请求的超限错误才考虑压缩重试，生成摘要失败不能再套一层重试。</p>
     * <p>明确以 max_tokens 截断的回复在采用前失败，既不返回 DONE 总结，也不执行其中工具。
     * 不自动增大输出额度或续写；外层统一发出一次 FAILED，父 Agent 得到真实失败而非半份结论。</p>
     *
     * <p>LlmException（包括父会话退出）由外层报告 FAILED 后重新抛出；
     * 归档出错和调用 ID 重复也有各自的 FAILED 处理。其它工具或 hook 的运行异常也统一报告 FAILED，保留原异常类型向父工具传播。</p>
     * @param prompt 子任务描述；启用压缩时须符合压缩器对请求的要求，不修改原字符串
     * @param context 父任务来源、询问用户的方法和会话退出检查，不可为 null
     * @return 最终文字，空白时使用已有无摘要提示；轮数用完则返回 STOPPED 提示
     * @throws LlmException 模型或压缩摘要失败、输出被 max_tokens 截断，或用户已退出父会话
     * @throws IllegalStateException 压缩归档等运行失败，不能继续
     * @throws NullPointerException context 为 null
     */
    public String run(String prompt, ExecutionContext context) throws LlmException {
        java.util.Objects.requireNonNull(context, "executionContext");
        com.jadecode.llm.ThinkingSettings taskThinking = thinking.get();
        displayId.set(java.util.UUID.randomUUID().toString().substring(0, 8));
        display(com.jadecode.execution.ExecutionNotice.event(com.jadecode.execution.ExecutionNotice.Kind.SESSION, "",
                "", "", "RUNNING"));
        // 显示短编号不承担权限唯一性；隔离编号使用完整 UUID，且不改变父任务来源。
        ExecutionContext scoped = context.withActorScope(java.util.UUID.randomUUID().toString());
        try {
            return runObserved(prompt, scoped, taskThinking);
        } finally {
            try {
                sandboxCleanup.accept(scoped);
            } finally {
                display(com.jadecode.execution.ExecutionNotice
                        .event(com.jadecode.execution.ExecutionNotice.Kind.SESSION, "", "", "", "CLOSED"));
                displayId.remove();
            }
        }
    }

    /** 原子代理主流程；外层只负责显示身份与 finally 收尾，不干涉运行异常。 */
    private String runObserved(String prompt, ExecutionContext context, com.jadecode.llm.ThinkingSettings taskThinking)
            throws LlmException {
        java.util.Objects.requireNonNull(context, "executionContext");
        // 每次委派创建执行器和历史，独立持有 usage 锚点；共享的客户端只负责请求和归属记账。
        List<Message> messages = new ArrayList<>(List.of(Message.userText(prompt)));
        var executor = executors.apply(new Invocation(context.actorScope(), taskThinking, this::display));
        var hooks = executor.hooks();
        boolean terminalAnnounced = false;
        try (executor) {
            if (executor.usesCompaction())
                ContextCompactor.extractActiveRequest(messages);
            java.util.function.Consumer<List<Message>> adopt = candidate -> {
                // 无改动时不可清空同一引用；有候选才原子替换本次委派的历史。
                if (candidate != messages) {
                    messages.clear();
                    messages.addAll(candidate);
                }
            };
            hooks.trigger(Hooks.SUBAGENT_START, Map.of());
            // 一次 request 包含它自己的压缩重试；只有成功回复才消耗一轮。
            for (int turns = 0; turns < MAX_TURNS; turns++) {
                checkSessionOpen(context);
                var reply = executor.request(messages, prompt, executor.system(), adopt);
                checkSessionOpen(context);
                messages.add(reply.message());
                if (reply.calls().isEmpty()) {
                    var continuation = executor.stop(messages);
                    if (continuation != null) {
                        messages.add(Message.userText(continuation.prompt()));
                        continue;
                    }
                    String output = reply.message().extractText();
                    output = output.isBlank() ? NO_SUMMARY : output;
                    terminalAnnounced = true;
                    end(hooks, output, SubagentOutcome.DONE);
                    return output;
                }
                var batch = executor.executeTools(reply, context);
                // 意外异常也先配齐工具结果，随后失败，不会重复执行已有文件副作用。
                messages.add(Message.userBlocks(batch.results()));
                if (batch.failure() != null)
                    throw batch.failure();
                checkSessionOpen(context);
                executor.compactAfterBatch(batch, messages, prompt, adopt);
            }
            terminalAnnounced = true;
            end(hooks, STOPPED, SubagentOutcome.STOPPED);
            return STOPPED;
        } catch (LlmException | RuntimeException failure) {
            // 模型、校验、工具和归档失败共用一个收口；终态 Hook 自身失败也不重复发送 END。
            if (!terminalAnnounced)
                endFailed(hooks, failure);
            throw failure;
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
        if (context.isCancelled())
            throw new LlmException("Parent session is closed");
    }

    /**
     * 触发一次 SUBAGENT_END(FAILED):沿用既有 "Error: " + 异常消息的输出约定。
     *
     * @param error 导致委派失败的错误;原对象仍由调用处继续向上抛,这里只负责上报事件
     */
    private void endFailed(Hooks hooks, Throwable error) {
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
    private void end(Hooks hooks, String output, SubagentOutcome outcome) {
        hooks.trigger(Hooks.SUBAGENT_END,
                Map.of(Hooks.PARAM_OUTPUT, output, Hooks.PARAM_OUTCOME, outcome));
    }
}
