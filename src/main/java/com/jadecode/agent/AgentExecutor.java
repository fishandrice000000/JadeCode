package com.jadecode.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.context.ContextCompactor;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.execution.ExecutionObserver;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.LlmClient;
import com.jadecode.llm.LlmException;
import com.jadecode.llm.ModelResponse;
import com.jadecode.llm.ResponseStream;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.ToolExchangeProtocol;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.tools.CompactTool;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;

/**
 * 执行会话循环中的一次模型请求和一批工具调用。
 *
 * <p>request 固定工具目录、准备上下文并取得完整回复；executeTools 依次经过 Hook 和权限审批执行工具，
 * 返回与调用 ID 对应的结果。会话采用整批结果后，通过 compactAfterBatch 处理手动压缩，
 * 通过 stop 取得继续工作的提示。会话运行时负责排队、历史落盘和整轮完成判断。</p>
 *
 * @see com.jadecode.session.SessionRuntime
 * @see com.jadecode.tools.ToolCatalog
 */
public class AgentExecutor implements AutoCloseable {
    /** 向模型请求下一步操作的客户端；查记忆和生成摘要的客户端由相应对象保存。 */
    private final LlmClient client;
    /** 内置与动态工具来源；每次请求固定一个快照，本批执行不重新取目录。 */
    private final com.jadecode.tools.ToolCatalog tools;
    /** 基础 system 提示词；会话只加固定参考边界，召回正文作为独立消息追加。 */
    private final String system;
    /** 模型一次回答最多生成多少 token，不是历史消息允许的字符数。 */
    private final int maxTokens;
    /** 工具执行前后和任务结束时调用的函数表，用来查权限、显示日志或要求继续。 */
    private final Hooks hooks;
    /** 压缩器，可以没有；生成较短的消息列表，再由会话运行器写回 history。 */
    private final ContextCompactor compactor;
    /** 正式请求独占的展示工厂；队友默认不展示，不存到共享客户端。 */
    private final Supplier<ResponseStream> streams;
    /** 用户退出后禁止晚到回复继续执行；不拥有外部模型客户端或进程服务的关闭责任。 */
    private volatile boolean closed;
    /** 只读观察器；无前端的离线执行显式使用 NONE。 */
    private ExecutionObserver observer = ExecutionObserver.NONE;
    /** 主/队友各有自己的执行器；子代理另建局部锚点，不能把它放进共享压缩器。 */
    private final com.jadecode.context.ContextTokenTracker contextTokens = new com.jadecode.context.ContextTokenTracker();
    private final Supplier<com.jadecode.llm.ThinkingSettings> contextThinking;
    private final Supplier<String> rebuildState;
    private String lastTaskSystem;

    /**
     * 使用最近的任务 system、当前历史、工具目录和思考设置估算上下文占用，返回与压缩器共用统计锚点的估算结果。
     *
     * @param messages 本次读取或采用的模型消息列表
     * @return 当前完整请求的上下文占用估算，与压缩器使用同一统计锚点
     */
    public com.jadecode.context.ContextTokenTracker.Estimate contextEstimate(List<Message> messages) {
        return contextTokens.estimate(new CompleteRequest(lastTaskSystem == null ? system : lastTaskSystem,
                messages, maxTokens, tools.snapshot().definitions(), contextThinking.get(), ""));
    }

    /**
     * 清除上下文用量锚点，供历史恢复或回滚后重新估算当前请求。
     */
    public void invalidateContext() {
        contextTokens.invalidate();
    }

    /**
     * 在会话启动前绑定执行事件观察器，供模型、工具和轮次状态发送界面事件。
     *
     * @param value 本执行器启动前安装的执行事件接收者
     * @throws NullPointerException value 为 null
     */
    public void observe(ExecutionObserver value) {
        observer = java.util.Objects.requireNonNull(value);
    }

    /**
     * 将执行事实交给本 Agent 的观察器，供前端更新状态和计时。
     *
     * @param notice 当前执行事实，包含 Agent、事件种类、状态、工具身份与用量
     */
    public void notice(ExecutionNotice notice) {
        observer.publish(notice);
    }

    /** 用来识别本批是否调用 todo_write，调用后重新计数，不据此判断任务已完成。 */
    private static final String TODO_TOOL = "todo_write";
    /** 只处理真正执行过且返回指定文字的 compact，不靠扫描任意工具输出猜是否要压缩。 */
    private static final String COMPACT_TOOL = "compact";

    /**
     * 绑定模型客户端、工具目录、基础提示、输出预算、Hook、压缩器和显示出口，以及请求时读取的思考与重建状态。
     * 每份执行器维护独立的上下文计量；注册 compact 工具时要求同时提供压缩器，
     * 装配不一致时抛出 IllegalArgumentException。
     *
     * @param client 用于请求模型的客户端，由装配方确定具体后端
     * @param tools 本次可执行工具目录，模型定义与执行查找使用同一目录
     * @param system 本次请求或执行器使用的系统提示词
     * @param maxTokens 本次模型回答允许的最大输出 token 数
     * @param hooks 当前 Agent 的有序生命周期回调表
     * @param compactor 当前 Agent 的上下文压缩器，null 表示关闭压缩
     * @param streams 为每次正式模型请求创建独立流式出口的工厂
     * @param observer 接收工具、轮次、用量和后台执行事实的观察器
     * @param thinking 本 Agent 的思考设置或当前值来源
     * @param state 为压缩重建提供当前 Todo、后台和协作状态的函数
     * @throws IllegalArgumentException 目录注册了 compact，但没有装配对应压缩器
     * @throws NullPointerException streams、observer、thinking、state 为 null
     */
    public AgentExecutor(LlmClient client, com.jadecode.tools.ToolCatalog tools, String system, int maxTokens,
            Hooks hooks, ContextCompactor compactor, Supplier<ResponseStream> streams,
            ExecutionObserver observer, Supplier<com.jadecode.llm.ThinkingSettings> thinking, Supplier<String> state) {
        // 有控制工具却没有处理器会形成假成功，仍在构造时拒绝这种装配。
        if (compactor == null && tools.hasBuiltin(COMPACT_TOOL)) {
            throw new IllegalArgumentException("Tool table registered " + COMPACT_TOOL
                    + " but construction did not provide a ContextCompactor: compaction requests would be unhandled. Inject a compactor or remove the tool");
        }
        this.client = client;
        this.tools = tools;
        this.system = system;
        this.maxTokens = maxTokens;
        this.hooks = hooks;
        this.compactor = compactor;
        this.streams = java.util.Objects.requireNonNull(streams);
        this.observer = java.util.Objects.requireNonNull(observer);
        this.contextThinking = java.util.Objects.requireNonNull(thinking);
        this.rebuildState = java.util.Objects.requireNonNull(state);
    }

    /**
     * 返回当前执行器使用的 Hook 表，供会话启动前注册权限与任务生命周期回调。
     *
     * @return 本执行器持有的有序 Hook 表
     */
    public Hooks hooks() {
        return hooks;
    }

    /**
     * 检查执行器处于可用状态后返回基础 system 提示，供会话加入本轮参考资料。
     *
     * @return 当前执行器的基础 system 文本
     */
    public String system() {
        ensureOpen();
        return system;
    }

    /**
     * 检查执行器处于可用状态后返回压缩器是否已装配，供会话决定是否保留本轮原始请求。
     *
     * @return 已装配上下文压缩器时为 true
     */
    public boolean usesCompaction() {
        ensureOpen();
        return compactor != null;
    }

    /**
     * 准备并发送一次模型请求，返回会话可采用的完整回复。
     *
     * <p>每次尝试固定工具目录快照，用相同的 system、历史、思考设置估算输入并准备压缩。
     * 压缩结果通过 adoptHistory 交给会话立即采用，再按剩余窗口计算输出额度，创建独立请求 ID 并发送流式请求。
     * 收到完整响应后检查 ASSISTANT 角色、工具 ID 唯一性及停止原因，更新上下文用量锚点，结束展示并返回 ModelReply。</p>
     * <p>明确的输入上下文超限会按配置限次压缩重试；输出 max_tokens 截断、其他模型错误或重试耗尽向会话报告失败。
     * 已经采用的压缩历史保留在会话中，工具执行由 executeTools 接续。</p>
     *
     * @param messages 当前会话历史；压缩时由 adoptHistory 更新该列表内容
     * @param activeRequest 当前任务原文，供压缩保留目标
     * @param taskSystem 本轮准备好的系统提示
     * @param adoptHistory 立即采用新历史的会话回调
     * @return 完整回复、工具调用、用量和本次工具目录快照
     * @throws LlmException 模型请求、压缩失败或输出截断
     * @throws IllegalArgumentException 回复角色或工具调用 ID 不合法
     * @throws IllegalStateException 执行器已经关闭
     */
    public ModelReply request(List<Message> messages, String activeRequest, String taskSystem,
            Consumer<List<Message>> adoptHistory) throws LlmException {
        // 只数这次请求的重试次数；压缩没缩短也算试过一次，否则可能无限调用付费模型。
        int retries = 0;
        while (true) {
            ensureOpen();
            // 工具定义只固定一次，预算与真正请求使用同一投影；MCP 增删自然使旧锚点失效。
            lastTaskSystem = taskSystem;
            com.jadecode.tools.ToolCatalog.Snapshot snapshot = tools.snapshot();
            CompleteRequest projection = new CompleteRequest(taskSystem, messages, maxTokens, snapshot.definitions(),
                    contextThinking.get(), "");
            if (compactor != null) {
                // adoptHistory 会立即把压缩后的消息放回原列表，下面发给模型的就是更新后的历史。
                List<Message> prepared = compactor.prepare(projection, activeRequest, contextTokens,
                        rebuildState.get());
                if (prepared != messages) {
                    adoptHistory.accept(prepared);
                }
            }

            ResponseStream display = java.util.Objects.requireNonNull(streams.get());
            boolean[] textDisplayed = { false };
            // 响应校验失败也可能已拿到停止原因，失败出口仍须保留它，不能丢掉截断提示。
            String stopReason = "";
            // 每次尝试（含压缩重试）生成独立观察 ID；旧预览不能续写到新请求。
            String observationRequestId = java.util.UUID.randomUUID().toString();
            display.requestStarted(observationRequestId);
            // 回调只更新屏幕，不碰 history；用户退出后不再打印晚到片段。
            // 工具参数预览与最终正文走同一观察对象，但都只是只读展示，不能触发执行。
            ResponseStream observed = display == ResponseStream.NONE ? display : new ResponseStream() {
                /**
                 * 检查执行器仍处于运行状态后转发正文增量，并记录本次是否已经展示非空正文，供会话采用完整回复时决定展示方式。
                 *
                 * @param text 本次处理的完整文本，按该入口执行编码、脱敏、显示或组装
                 */
                @Override
                public void text(String text) {
                    ensureOpen();
                    display.text(text);
                    textDisplayed[0] |= !text.isEmpty();
                }

                /**
                 * 检查执行器状态后将思考增量交给本次请求的展示出口。
                 *
                 * @param text 本次处理的完整文本，按该入口执行编码、脱敏、显示或组装
                 */
                @Override
                public void thinking(String text) {
                    ensureOpen();
                    display.thinking(text);
                }

                /**
                 * 检查执行器状态后转发工具预览的块下标、调用 ID、工具名和初始参数，供前端创建预览卡片。
                 *
                 * @param blockIndex 工具调用在模型内容块中的下标，用于把参数片段追加到同一个预览块
                 * @param callId 模型返回的工具调用 ID，用于关联工具结果
                 * @param name 本次要查询或展示的工具、服务、资源或会话名称
                 * @param initialInputJson 工具块开始事件携带的初始 JSON，null 以空串处理
                 */
                @Override
                public void toolBlockStarted(int blockIndex, String callId, String name, String initialInputJson) {
                    ensureOpen();
                    display.toolBlockStarted(blockIndex, callId, name, initialInputJson);
                }

                /**
                 * 检查执行器状态后转发指定块的参数片段，供前端续接该工具的 JSON 预览。
                 *
                 * @param blockIndex 工具调用在模型内容块中的下标，用于把参数片段追加到同一个预览块
                 * @param fragment 当前工具参数 JSON 的新增文本片段
                 */
                @Override
                public void toolInputDelta(int blockIndex, String fragment) {
                    ensureOpen();
                    display.toolInputDelta(blockIndex, fragment);
                }

                /**
                 * 检查执行器状态后通知展示出口结束指定工具块的参数预览。
                 *
                 * @param blockIndex 工具调用在模型内容块中的下标，用于把参数片段追加到同一个预览块
                 */
                @Override
                public void toolBlockClosed(int blockIndex) {
                    ensureOpen();
                    display.toolBlockClosed(blockIndex);
                }
            };
            try {
                ensureOpen();
                projection = new CompleteRequest(taskSystem, messages, maxTokens, snapshot.definitions(),
                        contextThinking.get(), "");
                int outputBudget = compactor == null ? maxTokens : compactor.outputBudget(projection, contextTokens);
                CompleteRequest request = new CompleteRequest(taskSystem, messages, outputBudget,
                        snapshot.definitions(), contextThinking.get(), "");
                ModelResponse response = client.complete(request, observed);
                stopReason = response.stopReason();
                // 完整消息通过角色和工具 ID 校验后才结束预览，返回给 Session 采用。
                ensureOpen();
                // 输出额度耗尽与输入上下文太长是两种失败，不能靠压缩或执行半份行动来掩盖。
                // 在生成 ModelReply 之前拒绝，Leader 和常驻队友便都不会把它判成正常完成。
                if ("max_tokens".equals(response.stopReason())) {
                    throw new LlmException(
                            "Model output reached max_tokens; the response is incomplete and its tool calls were not executed");
                }
                if (response.message().role() != Role.ASSISTANT) {
                    throw new IllegalArgumentException("Formal model response must be ASSISTANT");
                }
                List<ToolUseBlock> calls = response.message().blocksOfType(ToolUseBlock.class);
                ToolExchangeProtocol.requireDistinctCallIds(calls);
                contextTokens.accepted(request, response);
                display.complete(response.stopReason());
                long displayCompletedNanos = System.nanoTime();
                // 用量已由客户端包装统一保存；这里仅带回本次报告，供 Session 更新自己的占用快照。
                return new ModelReply(response.message(), calls, textDisplayed[0], response.usage(), snapshot,
                        observationRequestId, displayCompletedNanos);
            } catch (LlmException failure) {
                display.failed(stopReason);
                // 配置关闭就直接暴露本次错误，不额外重试同一历史。开启时也只补救真正的上下文超限。
                ensureOpen();
                if (compactor == null || !compactor.reactiveEnabled()
                        || !ContextCompactor.isContextOverflow(failure)
                        || retries >= ContextCompactor.MAX_REACTIVE_RETRIES)
                    throw failure;
                retries++;
                List<Message> recovered = compactor.reactiveCompact(messages, activeRequest);
                if (recovered != messages)
                    adoptHistory.accept(recovered);
            } catch (RuntimeException failure) {
                display.failed(stopReason);
                throw failure;
            }
        }
    }

    /**
     * 按模型回复中的顺序执行整批工具，收集每个调用对应的 tool_result。
     *
     * <p>会话先采用含 tool_use 的助手消息；本方法先发出整批排队事件，再逐项检查取消状态、查找回复固定的目录，
     * 执行 PreToolUse 审批，调用工具并记录结果和实际执行耗时，最后发出完成事件及 PostToolUse 通知。
     * 未知工具、权限阻止和普通工具失败均生成对应 ID 的错误结果；todo 和 compact 的实际执行结果另记为批次标记。</p>
     * <p>遇到意外运行异常时，保留已取得的结果，为其余调用补齐错误结果，并把异常放入 failure。
     * 会话先采用该批结果，再结束失败轮次。返回后由会话统一更新历史。</p>
     *
     * @param reply 已完成校验的模型回复和工具目录
     * @param context 本轮身份、权限通道、派工范围和取消信号
     * @return 完整工具结果，以及 Todo、压缩标记和可选意外异常
     */
    public ToolBatch executeTools(ModelReply reply, ExecutionContext context) {
        // 只执行本次请求给模型的目录；目录后来发现或断开不改变这一批的身份。
        List<ToolUseBlock> calls = reply.calls();
        var snapshot = java.util.Objects.requireNonNull(reply.tools(), "request tool snapshot");
        String observationRequestId = reply.observationRequestId();
        List<ContentBlock> results = new ArrayList<>();
        boolean usedTodo = false;
        boolean compact = false;
        RuntimeException failure = null;
        // 预览与执行共用同一个请求观察前缀；同一 actor 的重试/跨批同名工具也不会串卡。
        // 真实 tool_use ID 仍是 call.id()，观察 ID 不进入 wire/历史。
        String batchId = observationRequestId == null || observationRequestId.isBlank()
                ? java.util.UUID.randomUUID().toString()
                : observationRequestId;
        for (ToolUseBlock call : calls) {
            var queuedEntry = snapshot.entries().get(call.name());
            notice(ExecutionNotice.event(ExecutionNotice.Kind.TOOL_START,
                    batchId + "/" + call.id(), call.name(), call.input() == null ? "{}" : call.input().toString(),
                    "QUEUED")
                    .withIdentity(queuedEntry == null ? null : queuedEntry.identity()));
        }
        int executing = -1;
        boolean attempted = false;
        long toolStarted = 0;
        try {
            for (ToolUseBlock call : calls) {
                executing = results.size();
                attempted = false;
                // 还没执行时不计时；也清掉前一把工具的时间，防止本次审批异常时沿用旧计时点。
                toolStarted = 0;
                // 1. 先查是否退出，再查权限；被拒绝或未知的工具，也要返回与调用 ID 对应的结果。
                ensureOpen();
                if (context.isCancelled())
                    throw new IllegalStateException("Parent session is closed");
                var entry = snapshot.entries().get(call.name());
                if (entry == null) {
                    results.add(new ToolResultBlock(call.id(), "Error: Unknown tool: " + call.name(), true));
                    notice(ExecutionNotice.event(ExecutionNotice.Kind.TOOL_END, batchId + "/" + call.id(), call.name(),
                            "Unknown tool", "NOT_RUN"));
                    continue;
                }
                String blocked = hooks.trigger(Hooks.PRE_TOOL_USE,
                        Map.of(Hooks.PARAM_BLOCK, call, Hooks.PARAM_CONTEXT, context, Hooks.PARAM_TOOL_IDENTITY,
                                entry.identity()));
                if (blocked != null) {
                    results.add(new ToolResultBlock(call.id(), blocked, true));
                    notice(ExecutionNotice
                            .event(ExecutionNotice.Kind.TOOL_END, batchId + "/" + call.id(), call.name(), blocked,
                                    "BLOCKED")
                            .withIdentity(entry.identity()));
                    continue;
                }
                Tool tool = entry.tool();
                // 等用户批准可能很久；用户若已退出，即使现在收到批准，也不能再执行工具。
                ensureOpen();
                if (context.isCancelled())
                    throw new IllegalStateException("Parent session is closed");
                ObjectNode input = call.input() instanceof ObjectNode node ? node : Json.mapper().createObjectNode();
                notice(ExecutionNotice
                        .event(ExecutionNotice.Kind.TOOL_START, batchId + "/" + call.id(), call.name(),
                                input.toString(), "RUNNING")
                        .withIdentity(entry.identity()));
                // 权限检查和审批已经结束，现在才开始计工具执行时间。
                // 整轮用时另由 Session 的 TurnTimer 统计，并扣除人工审批等待，不能在这里累加。
                toolStarted = System.nanoTime();
                attempted = true;
                com.jadecode.tools.ToolOutcome outcome = tool.executeOutcome(input, context);
                String output = outcome.content();
                // 先记下工具确实返回的结果，再显示日志，免得日志出错后连执行结果都丢掉。
                results.add(new ToolResultBlock(call.id(), output, outcome.isError()));
                notice(ExecutionNotice
                        .event(ExecutionNotice.Kind.TOOL_END, batchId + "/" + call.id(), call.name(), output,
                                outcome.isError() ? "ERROR" : "RETURNED")
                        .timed(System.nanoTime() - toolStarted).withIdentity(entry.identity()));
                usedTodo |= call.name().equals(TODO_TOOL);
                compact |= call.name().equals(COMPACT_TOOL) && CompactTool.OUTPUT.equals(output);
                var notification = Map.of(Hooks.PARAM_BLOCK, call, Hooks.PARAM_OUTPUT, output,
                        Hooks.PARAM_CONTEXT, context);
                hooks.trigger(Hooks.POST_TOOL_USE, notification);
                // 可信的委派上下文由 Harness 创建；父任务不会产生子工具事件。
                if (!context.actorScope().isBlank())
                    hooks.trigger(Hooks.SUBAGENT_TOOL, notification);
            }
        } catch (RuntimeException unexpected) {
            // 2. results 中已有前面调用的结果；从下一个没拿到结果的 ID 开始补失败说明。
            failure = unexpected;
            for (int index = results.size(); index < calls.size(); index++) {
                ToolUseBlock unfinished = calls.get(index);
                notice(ExecutionNotice
                        .event(ExecutionNotice.Kind.TOOL_END, batchId + "/" + unfinished.id(), unfinished.name(),
                                index == executing && attempted
                                        ? "did not return normally and may already have side effects: "
                                                + unexpected.getMessage()
                                        : "batch interrupted; not executed",
                                index == executing && attempted ? "ERROR" : "NOT_RUN")
                        .timed(index == executing && attempted ? System.nanoTime() - toolStarted : 0));
                results.add(new ToolResultBlock(calls.get(index).id(),
                        "Error: Tool batch interrupted; this call did not produce a result. The task failed.", true));
            }
        }
        return new ToolBatch(calls, results, usedTodo, compact, failure);
    }

    /**
     * 在会话采用整批工具结果后，执行 compact 工具提出的压缩请求。
     *
     * <p>batch.compact 为 true 时，使用当前完整工具往返记录和任务原文调用压缩器，再把新历史交给 adoptHistory。
     * 其他批次直接返回。压缩失败以 LlmException 返回，已执行工具的结果仍在历史中。</p>
     *
     * @param batch 已执行并采用结果的工具批次
     * @param messages 包含该批全部调用与结果的历史
     * @param activeRequest 本轮任务原文
     * @param adoptHistory 立即采用压缩历史的会话回调
     * @throws LlmException 压缩摘要请求失败
     */
    public void compactAfterBatch(ToolBatch batch, List<Message> messages, String activeRequest,
            Consumer<List<Message>> adoptHistory) throws LlmException {
        if (!batch.compact())
            return;
        ensureOpen();
        CompleteRequest request = new CompleteRequest(lastTaskSystem == null ? system : lastTaskSystem,
                messages, maxTokens, tools.snapshot().definitions(), contextThinking.get(), "");
        List<Message> compacted = compactor.compactAfterBatch(batch.calls(), request, activeRequest,
                rebuildState.get());
        if (compacted != messages)
            adoptHistory.accept(compacted);
    }

    /**
     * 模型不再调用工具时，问 Stop hook 是否还有事情没做完。
     *
     * @param messages 当前历史，hook 应只读取，不直接修改
     * @return hook 返回 null 时本方法也返回 null；否则把其提示包装成 Continue，
     * 由运行器加入 history，让模型继续当前任务，不把它排成新任务
     * @throws IllegalStateException 执行器已退出
     */
    public Continue stop(List<Message> messages) {
        ensureOpen();
        String force = hooks.trigger(Hooks.STOP, Map.of(Hooks.PARAM_MESSAGES, messages));
        return force == null ? null : new Continue(force);
    }

    /**
     * 模型对当前任务的一次回答，不包括查记忆或生成摘要的辅助回答。
     *
     * @param message AgentExecutor 已检查角色为 ASSISTANT 的消息
     * @param calls 从这条消息取出的工具调用，ID 已检查不重复；没有调用不一定代表任务完成，
     * 还要看是否在等后台结果，以及 Stop 是否要求继续
     * @param textDisplayed 正文已经通过流式回调展示；Session 仍采用完整消息，但不能重复打印正文
     * @param usage 本次后端报告，供会话估算当前占用；累计用量已由客户端保存，可为 null
     * @param tools 本次请求使用的执行目录；只在内存传递，不随 message 持久化
     * @param observationRequestId 前端观察本次请求使用的 ID，独立于模型工具调用 ID
     * @param displayCompletedNanos 显示 complete 返回的单调时间；非流式显示由 Session 记录真正输出时刻
     */
    public record ModelReply(Message message, List<ToolUseBlock> calls, boolean textDisplayed,
            ModelResponse.Usage usage,
            com.jadecode.tools.ToolCatalog.Snapshot tools, String observationRequestId, Long displayCompletedNanos) {
        /**
         * 将本次回复中的工具调用复制成不可变列表，保留模型返回的顺序，供会话采用回复后逐项执行。
         *
         * @param message 待编码、校验或投影的完整消息
         * @param calls 模型同一回复中的工具调用列表，保持原调用顺序
         * @param textDisplayed 本次回复正文是否已经由流式出口展示
         * @param usage 模型报告或会话累计的 token 用量
         * @param tools 本次可执行工具目录，模型定义与执行查找使用同一目录
         * @param observationRequestId 前端观察本次请求使用的 ID，独立于模型工具调用 ID
         * @param displayCompletedNanos 本次模型流完成展示时的单调时间，单位纳秒
         */
        public ModelReply {
            calls = List.copyOf(calls);
            if (observationRequestId == null)
                observationRequestId = "";
        }
    }

    /**
     * 模型同一次回复要求的工具已经处理完，把结果一次性交给运行器。
     *
     * <p>发生意外异常时，也先给每个调用配上结果说明，再报告失败，
     * 免得下一次请求的历史中留下没有 tool_result 的 tool_use。</p>
     *
     * @param calls 原始调用顺序，压缩器用它判断这批是否只有 compact
     * @param results 每个调用有一个同 ID 的结果，不包含会话级 todo 提醒文字
     * @param usedTodo 本批确实执行过 todo_write；父会话据此维护自己的提醒计数
     * @param compact compact 工具是否真的返回了指定文字；true 只说明要压缩，还没开始压缩
     * @param failure 意外运行异常，可为 null；普通工具返回 Error 文本不放这里。
     * 非 null 时先把结果写入历史再结束任务，不能重跑已经执行过的工具
     */
    public record ToolBatch(List<ToolUseBlock> calls, List<ContentBlock> results, boolean usedTodo,
            boolean compact, RuntimeException failure) {
        /**
         * 将本批调用和结果复制为不可变列表，连同 Todo、压缩和异常信息交给会话一次采用。
         *
         * @param calls 模型同一回复中的工具调用列表，保持原调用顺序
         * @param results 该批工具调用对应的结果内容块
         * @param usedTodo 本批是否实际执行过 todo_write
         * @param compact 本批是否提出了结果采用后执行的手动压缩请求
         * @param failure 已经发生的 IO、模型或执行异常，供原任务收尾与回执使用
         */
        public ToolBatch {
            calls = List.copyOf(calls);
            results = List.copyOf(results);
        }
    }

    /**
     * Stop hook 说还有工作要做，让模型继续当前任务，不排成另一项任务。
     *
     * @param prompt 加入 history 的提示文字，不替换程序记住的用户原始请求
     */
    public record Continue(String prompt) {
    }

    /**
     * 检查执行器关闭标记，已关闭时抛出 IllegalStateException，使当前请求或执行步骤进入会话失败收尾。
     *
     * @throws IllegalStateException 执行器已经关闭
     */
    private void ensureOpen() {
        if (closed)
            throw new IllegalStateException("Agent loop is closed");
    }

    /**
     * 设置关闭标记，使后续请求、流回调和工具执行检查进入停止路径；会话拥有的后台进程与共享客户端由外层生命周期关闭。
     */
    @Override
    public void close() {
        closed = true;
    }
}
