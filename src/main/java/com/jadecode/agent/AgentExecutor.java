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
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolExchangeProtocol;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.tools.CompactTool;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;

/**
 * 落实一次模型请求、整批工具执行及压缩步骤，不管理会话队列或任务完成状态。
 *
 * <p>例如模型要求先读文件再运行命令，本类负责请求回复、查权限并执行工具；
 * 会话负责采用这些结果、决定等不等后台及何时维护长期记忆。
 * 这条边界让执行步骤不需要知道会话事件协议，也不拥有 cron 或进程服务。</p>
 *
 * <p>阅读 request → executeTools → compactAfterBatch → stop。压缩需要替换历史时，
 * 通过只传消息列表的窄回调立即采用，再正式请求模型；本类不直接清空调用方列表。
 * 普通工具失败文本仍交给模型处理，意外异常则补齐整批结果后由会话结束任务。
 * 已执行的文件副作用不会因请求失败而回滚。</p>
 */
public class AgentExecutor implements AutoCloseable {
    /** 向模型请求下一步操作的客户端；查记忆和生成摘要的客户端由相应对象保存。 */
    private final LlmClient client;
    /** 内置与动态工具来源；每次请求固定一个快照，本批执行不重新取目录。 */
    private final com.jadecode.tools.ToolCatalog tools;
    /** 基础 system 提示词，会话可在任务入口加上查到的记忆。 */
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
    /** 只在装配时安装；无前端的旧调用方保持空观察器。 */
    private ExecutionObserver observer = ExecutionObserver.NONE;

    /** 安装快速、只读的显示接收者，不替换权限 hook。须在会话 start 前调用。 */
    public void observe(ExecutionObserver value) { observer = java.util.Objects.requireNonNull(value); }
    /** 会话共用本 Agent 的身份通道；调用只发快照，不授予显示端修改历史的权力。 */
    public void notice(ExecutionNotice notice) { observer.publish(notice); }

    /** 用来识别本批是否调用 todo_write，调用后重新计数，不据此判断任务已完成。 */
    private static final String TODO_TOOL = "todo_write";
    /** 只处理真正执行过且返回指定文字的 compact，不靠扫描任意工具输出猜是否要压缩。 */
    private static final String COMPACT_TOOL = "compact";
    /** 连续三批工具都没更新 todo 后，给模型的提醒；不是用户的新任务。 */
    private static final String REMINDER_MESSAGE = "<reminder>Update your todos.</reminder>";

    /**
     * 创建不带压缩的执行器；构造只保存依赖，不启动线程、工具或模型请求。
     * @param client 正式请求使用的客户端
     * @param tools 工具名到实例的表，构造后调用者不得并发修改
     * @param system 基础 system 提示词，任务级记忆由会话另外准备
     * @param maxTokens 单次回答的输出 token 上限
     * @param hooks 权限、展示与 Stop 所用的有序 hook 表
     * @throws IllegalArgumentException 注册 compact 却没有压缩器
     */
    public AgentExecutor(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks) {
        this(client, tools, system, maxTokens, hooks, null);
    }

    /**
     * 保存执行依赖，不取得记忆、cron 或进程服务的所有权。
     * @param client 正式请求使用的客户端
     * @param tools 运行期只读的工具表，实际执行仍使用这些实例
     * @param system 基础 system 提示词
     * @param maxTokens 单次回答的输出 token 上限
     * @param hooks 按顺序执行的权限、展示和 Stop hook
     * @param compactor 父子可共用的压缩器，可为 null；注册 compact 时必须提供
     * @throws IllegalArgumentException compact 请求无人处理
     */
    public AgentExecutor(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor) {
        this(client, tools, system, maxTokens, hooks, compactor, () -> ResponseStream.NONE);
    }

    /**
     * 为正式请求安装展示工厂，工具、权限与压缩仍走原执行流程。
     * @param client 可与其它 Agent 共享的模型客户端
     * @param tools 本 Agent 的只读工具表
     * @param system 基础提示词
     * @param maxTokens 单次输出 token 上限
     * @param hooks 权限和展示函数表
     * @param compactor 压缩器，可为 null；注册 compact 时不可缺少
     * @param streams 每次请求创建新的展示对象，不展示时返回 ResponseStream.NONE
     */
    public AgentExecutor(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor, Supplier<ResponseStream> streams) {
        this(client, com.jadecode.tools.ToolCatalog.fixed(tools), system, maxTokens, hooks, compactor, streams);
    }

    /**
     * 动态工具装配入口；只保存目录，不连接外部服务，不启动执行线程。
     * 每次请求的目录将随 ModelReply 交给会话，执行时再原样传回，避免发现后的目录变化影响当前批次。
     */
    public AgentExecutor(LlmClient client, com.jadecode.tools.ToolCatalog tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor, Supplier<ResponseStream> streams) {
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
    }

    /** @return 原 hook 表；只在开始运行前注册，不因读取而触发 hook。 */
    public Hooks hooks() { return hooks; }

    /** @return 原基础提示词；不召回记忆、不修改消息，关闭后拒绝继续准备任务。 */
    public String system() { ensureOpen(); return system; }

    /** @return 是否启用压缩，供会话判断是否需要在入口保存请求原文；不执行压缩。 */
    public boolean usesCompaction() { ensureOpen(); return compactor != null; }

    /**
     * 请求一次模型回答，必要时先压缩；如果模型明确说上下文太长，可在限次内压缩后重试。
     *
     * <p>每次尝试前调用 prepare。需要更换历史时，调用 adoptHistory.accept 让会话采用函数
     * 立即修改原 messages 的内容，然后把更新后的消息交给模型。本方法自己不 clear/add，
     * 也不另开线程，返回时还没有执行回复中的工具。</p>
     *
     * <p>catch 覆盖正式请求准备与响应校验；只有 LlmException 中的上下文超限才进入补救。网络、限流等错误不能一律当作上下文超限；
     * 生成摘要失败也不能再套进超限重试。已成功换上的历史不会因后续模型失败而恢复成旧版。
     * 定时任务第一次回答前如何恢复历史，由会话的失败处理单独处理。</p>
     * <p>服务端明确以 max_tokens 截断时，这份回复不能作为完整行动采用：即使某个工具块的
     * JSON 完整，也无法据此认定整份回复已经生成完。这里按请求失败交回会话，不追加回复、
     * 不执行其中的工具，也不自动扩大用户额度或续写；已显示的流式片段仍只是未完成预览。</p>
     * @param messages 当前历史；本方法读取它，但 adoptHistory 函数会直接更新这个列表的内容
     * @param activeRequest 本次用户请求，压缩后仍须记住
     * @param taskSystem 本任务开始时准备好的 system 提示词
     * @param adoptHistory 收到新历史时立即执行的函数，由会话提供，必须在返回前完成列表内容替换
     * @return ASSISTANT 回复和其中的工具调用；已检查一批内的调用 ID 不重复，但尚未执行工具
     * @throws LlmException 不能重试的模型错误、输出被 max_tokens 截断、重试次数用完，或压缩摘要失败
     * @throws IllegalArgumentException 回复不是 ASSISTANT，或工具调用 ID 重复
     * @throws IllegalStateException 用户已退出，包括等模型回答时才退出的情况
     */
    public ModelReply request(List<Message> messages, String activeRequest, String taskSystem,
            Consumer<List<Message>> adoptHistory) throws LlmException {
        // 只数这次请求的重试次数；压缩没缩短也算试过一次，否则可能无限调用付费模型。
        int retries = 0;
        while (true) {
            ensureOpen();
            if (compactor != null) {
                // adoptHistory 会立即把压缩后的消息放回原列表，下面发给模型的就是更新后的历史。
                List<Message> prepared = compactor.prepare(messages, activeRequest);
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
                @Override public void text(String text) {
                    ensureOpen();
                    display.text(text);
                    textDisplayed[0] |= !text.isEmpty();
                }
                @Override public void thinking(String text) { ensureOpen(); display.thinking(text); }
                @Override public void toolBlockStarted(int blockIndex, String callId, String name, String initialInputJson) {
                    ensureOpen(); display.toolBlockStarted(blockIndex, callId, name, initialInputJson);
                }
                @Override public void toolInputDelta(int blockIndex, String fragment) {
                    ensureOpen(); display.toolInputDelta(blockIndex, fragment);
                }
                @Override public void toolBlockClosed(int blockIndex) { ensureOpen(); display.toolBlockClosed(blockIndex); }
            };
            try {
                ensureOpen();
                com.jadecode.tools.ToolCatalog.Snapshot snapshot = tools.snapshot();
                CompleteRequest request = new CompleteRequest(taskSystem, messages, maxTokens,
                        snapshot.definitions());
                ModelResponse response = observed == ResponseStream.NONE ? client.complete(request)
                        : client.complete(request, observed);
                stopReason = response.stopReason();
                // 完整消息通过角色和工具 ID 校验后才结束预览，返回给 Session 采用。
                ensureOpen();
                // 输出额度耗尽与输入上下文太长是两种失败，不能靠压缩或执行半份行动来掩盖。
                // 在生成 ModelReply 之前拒绝，Leader 和常驻队友便都不会把它判成正常完成。
                if ("max_tokens".equals(response.stopReason())) {
                    throw new LlmException("Model output reached max_tokens; the response is incomplete and its tool calls were not executed");
                }
                if (response.message().role() != Role.ASSISTANT) {
                    throw new IllegalArgumentException("Formal model response must be ASSISTANT");
                }
                List<ToolUseBlock> calls = response.message().blocksOfType(ToolUseBlock.class);
                ToolExchangeProtocol.requireDistinctCallIds(calls);
                display.complete(response.stopReason());
                long displayCompletedNanos = System.nanoTime();
                // 用量已由客户端包装统一保存；这里仅带回本次报告，供 Session 更新自己的占用快照。
                return new ModelReply(response.message(), calls, textDisplayed[0], response.usage(), snapshot,
                        observationRequestId, displayCompletedNanos);
            } catch (LlmException failure) {
                display.failed(stopReason);
                // 配置关闭就直接暴露本次错误，不额外重试同一历史。开启时也只补救真正的上下文超限。
                ensureOpen();
                if (compactor == null || !compactor.policy().reactiveEnabled() || !ContextCompactor.isContextOverflow(failure)
                        || retries >= ContextCompactor.MAX_REACTIVE_RETRIES) throw failure;
                retries++;
                List<Message> recovered = compactor.reactiveCompact(messages, activeRequest);
                if (recovered != messages) adoptHistory.accept(recovered);
            } catch (RuntimeException failure) {
                display.failed();
                throw failure;
            }
        }
    }

    /**
     * 按顺序执行模型这次要求的整批工具，返回每个调用的结果，不修改 history。
     *
     * <p>调用前，会话运行器已把含 tool_use 的模型回复放进历史。
     * 这里先查权限，再执行工具；被拒绝和未知工具也返回对应 ID 的 tool_result。
     * 普通 Error 文本留给模型判断怎么办，不直接当成整个任务失败。</p>
     * <p>工具用时只从批准后实际调用 execute 开始计算；审批等待不计入，未执行的工具不提供执行耗时。</p>
     *
     * <p>若某工具或 hook 意外抛 RuntimeException，就保留已有结果，给未拿到结果的调用补上
     * 明确失败说明。调用者先把整批结果加进 history，再结束任务，避免只有 tool_use 没有结果。
     * 失败说明只代表没拿到结果，不代表工具什么都没做；它可能先写了文件才抛异常，不能随便重跑。</p>
     * @param calls 这次回复里的工具调用，ID 已检查不重复，顺序不变
     * @param context 程序记录的任务来源、任务 ID、询问用户的方法和退出检查方法
     * @param previousRounds 自上次 todo 更新或提醒以来的工具批次数，不是工具个数
     * @return 完整的一批结果和新的提醒计数；意外异常放在 failure 中，普通 Error 文本不放在那里
     */
    public ToolBatch executeTools(List<ToolUseBlock> calls, ExecutionContext context, int previousRounds) {
        return executeTools(calls, context, previousRounds, tools.snapshot());
    }

    /** 正式会话用请求时的快照执行；旧手工构造的 ModelReply 沿用即时目录，不影响旧离线调用。 */
    public ToolBatch executeTools(ModelReply reply, ExecutionContext context, int previousRounds) {
        return executeTools(reply.calls(), context, previousRounds, reply.tools() == null ? tools.snapshot() : reply.tools(),
                reply.observationRequestId());
    }

    /** 在固定目录中先查成员再走权限，结果统一返回；不修改会话历史或外部目录。 */
    private ToolBatch executeTools(List<ToolUseBlock> calls, ExecutionContext context, int previousRounds,
            com.jadecode.tools.ToolCatalog.Snapshot snapshot) {
        return executeTools(calls, context, previousRounds, snapshot, "");
    }

    /**
     * 执行固定目录的一批工具。observationRequestId 为预览请求前缀：它只用于显示关联，
     * 不进入模型/工具 ID、日志或权限判断；旧调用传空串时仍生成独立批次前缀。
     */
    private ToolBatch executeTools(List<ToolUseBlock> calls, ExecutionContext context, int previousRounds,
            com.jadecode.tools.ToolCatalog.Snapshot snapshot, String observationRequestId) {
        List<ContentBlock> results = new ArrayList<>();
        boolean usedTodo = false;
        boolean compact = false;
        RuntimeException failure = null;
        // 预览与执行共用同一个请求观察前缀；同一 actor 的重试/跨批同名工具也不会串卡。
        // 真实 tool_use ID 仍是 call.id()，观察 ID 不进入 wire/历史。
        String batchId = observationRequestId == null || observationRequestId.isBlank()
                ? java.util.UUID.randomUUID().toString() : observationRequestId;
        for (ToolUseBlock call : calls) {
            var queuedEntry = snapshot.entries().get(call.name());
            notice(ExecutionNotice.event(ExecutionNotice.Kind.TOOL_START,
                    batchId + "/" + call.id(), call.name(), call.input() == null ? "{}" : call.input().toString(), "QUEUED")
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
                if (context.isCancelled()) throw new IllegalStateException("Parent session is closed");
                var entry = snapshot.entries().get(call.name());
                if (entry == null) {
                    results.add(new ToolResultBlock(call.id(), "Error: Unknown tool: " + call.name(), true));
                    notice(ExecutionNotice.event(ExecutionNotice.Kind.TOOL_END, batchId + "/" + call.id(), call.name(), "Unknown tool", "NOT_RUN"));
                    continue;
                }
                String blocked = hooks.trigger(Hooks.PRE_TOOL_USE,
                        Map.of(Hooks.PARAM_BLOCK, call, Hooks.PARAM_CONTEXT, context, Hooks.PARAM_TOOL_IDENTITY, entry.identity()));
                if (blocked != null) {
                    results.add(new ToolResultBlock(call.id(), blocked, true));
                    notice(ExecutionNotice.event(ExecutionNotice.Kind.TOOL_END, batchId + "/" + call.id(), call.name(), blocked, "BLOCKED")
                            .withIdentity(entry.identity()));
                    continue;
                }
                Tool tool = entry.tool();
                // 等用户批准可能很久；用户若已退出，即使现在收到批准，也不能再执行工具。
                ensureOpen();
                if (context.isCancelled()) throw new IllegalStateException("Parent session is closed");
                ObjectNode input = call.input() instanceof ObjectNode node ? node : Json.mapper().createObjectNode();
                notice(ExecutionNotice.event(ExecutionNotice.Kind.TOOL_START, batchId + "/" + call.id(), call.name(), input.toString(), "RUNNING")
                        .withIdentity(entry.identity()));
                // 权限检查和审批已经结束，现在才开始计工具执行时间。
                // 整轮用时另由 Session 的 TurnTimer 统计，并扣除人工审批等待，不能在这里累加。
                toolStarted = System.nanoTime();
                attempted = true;
                com.jadecode.tools.ToolOutcome outcome = tool.executeOutcome(input, context);
                String output = outcome.content();
                // 先记下工具确实返回的结果，再显示日志，免得日志出错后连执行结果都丢掉。
                results.add(new ToolResultBlock(call.id(), output, outcome.isError()));
                notice(ExecutionNotice.event(ExecutionNotice.Kind.TOOL_END, batchId + "/" + call.id(), call.name(), output, outcome.isError() ? "ERROR" : "RETURNED")
                        .timed(System.nanoTime() - toolStarted).withIdentity(entry.identity()));
                usedTodo |= call.name().equals(TODO_TOOL);
                compact |= call.name().equals(COMPACT_TOOL) && CompactTool.OUTPUT.equals(output);
                hooks.trigger(Hooks.POST_TOOL_USE,
                        Map.of(Hooks.PARAM_BLOCK, call, Hooks.PARAM_OUTPUT, output, Hooks.PARAM_CONTEXT, context));
            }
        } catch (RuntimeException unexpected) {
            // 2. results 中已有前面调用的结果；从下一个没拿到结果的 ID 开始补失败说明。
            failure = unexpected;
            for (int index = results.size(); index < calls.size(); index++) {
                ToolUseBlock unfinished = calls.get(index);
                notice(ExecutionNotice.event(ExecutionNotice.Kind.TOOL_END, batchId + "/" + unfinished.id(), unfinished.name(),
                        index == executing && attempted ? "did not return normally and may already have side effects: " + unexpected.getMessage() : "batch interrupted; not executed",
                        index == executing && attempted ? "ERROR" : "NOT_RUN")
                        .timed(index == executing && attempted ? System.nanoTime() - toolStarted : 0));
                results.add(new ToolResultBlock(calls.get(index).id(),
                        "Error: Tool batch interrupted; this call did not produce a result. The task failed.", true));
            }
        }
        // 3. 一批工具只加一次计数；任务已经失败，就不再加“更新 todo”的继续工作提示。
        int rounds = usedTodo ? 0 : previousRounds + 1;
        if (failure == null && rounds >= 3) {
            results.add(new TextBlock(REMINDER_MESSAGE));
            rounds = 0;
        }
        return new ToolBatch(calls, results, rounds, compact, failure);
    }

    /**
     * 所有工具结果已写进历史后，再处理 compact 工具提出的压缩请求。
     *
     * <p>不能在两个工具之间压缩，否则可能只保留调用却丢掉结果。
     * batch.compact 只有在真正执行 compact 并得到指定返回文字后才为 true。
     * 构造时已保证有工具就有压缩器；没请求压缩时立即返回，不调用模型或写文件。</p>
     * @param batch 刚执行完的一批工具
     * @param messages 已有这一批全部结果的历史
     * @param activeRequest 本次用户原始请求，压缩后也要保留
     * @param adoptHistory 把压缩后的消息放回原 history 列表的函数
     * @throws LlmException 生成摘要失败；工具已经做过的文件修改不会还原
     * @throws IllegalStateException 需要压缩时，执行器已退出
     */
    public void compactAfterBatch(ToolBatch batch, List<Message> messages, String activeRequest,
            Consumer<List<Message>> adoptHistory) throws LlmException {
        if (!batch.compact()) return;
        ensureOpen();
        List<Message> compacted = compactor.compactAfterBatch(batch.calls(), messages, activeRequest);
        if (compacted != messages) adoptHistory.accept(compacted);
    }

    /**
     * 模型不再调用工具时，问 Stop hook 是否还有事情没做完。
     * @param messages 当前历史，hook 应只读取，不直接修改
     * @return hook 返回 null 时本方法也返回 null；否则把其提示包装成 Continue，
     *         由运行器加入 history，让模型继续当前任务，不把它排成新任务
     * @throws IllegalStateException 执行器已退出
     */
    public Continue stop(List<Message> messages) {
        ensureOpen();
        String force = hooks.trigger(Hooks.STOP, Map.of(Hooks.PARAM_MESSAGES, messages));
        return force == null ? null : new Continue(force);
    }

    /**
     * 模型对当前任务的一次回答，不包括查记忆或生成摘要的辅助回答。
     * @param message AgentExecutor 已检查角色为 ASSISTANT 的消息
     * @param calls 从这条消息取出的工具调用，ID 已检查不重复；没有调用不一定代表任务完成，
     *              还要看是否在等后台结果，以及 Stop 是否要求继续
     * @param textDisplayed 正文已经通过流式回调展示；Session 仍采用完整消息，但不能重复打印正文
     * @param usage 本次后端报告，供会话估算当前占用；累计用量已由客户端保存，可为 null
     * @param tools 本次请求使用的执行目录；只在内存传递，不随 message 持久化
     * @param displayCompletedNanos 显示 complete 返回的单调时间；旧构造可为 null，非流式回退另取真正输出时刻
     */
    public record ModelReply(Message message, List<ToolUseBlock> calls, boolean textDisplayed, ModelResponse.Usage usage,
            com.jadecode.tools.ToolCatalog.Snapshot tools, String observationRequestId, Long displayCompletedNanos) {
        /** 兼容旧六参数调用；没有时间来源时不伪造时间戳。 */
        public ModelReply(Message message, List<ToolUseBlock> calls, boolean textDisplayed, ModelResponse.Usage usage,
                com.jadecode.tools.ToolCatalog.Snapshot tools, String observationRequestId) {
            this(message, calls, textDisplayed, usage, tools, observationRequestId, null);
        }
        /** 兼容旧调用；正式请求始终提供本次工具快照，不把它写进模型消息或历史。 */
        public ModelReply(Message message, List<ToolUseBlock> calls, boolean textDisplayed, ModelResponse.Usage usage) {
            this(message, calls, textDisplayed, usage, null, "");
        }
        /** 兼容没有观察 ID 的旧离线调用；执行仍生成独立批次前缀。 */
        public ModelReply(Message message, List<ToolUseBlock> calls, boolean textDisplayed, ModelResponse.Usage usage,
                com.jadecode.tools.ToolCatalog.Snapshot tools) {
            this(message, calls, textDisplayed, usage, tools, "");
        }

        /** 兼容没有报告用量的旧离线调用，不伪造实际数字。 */
        public ModelReply(Message message, List<ToolUseBlock> calls, boolean textDisplayed) { this(message, calls, textDisplayed, null); }
        /** 普通非流式调用保持原构造方式，完成时仍由 Session 显示正文。 */
        public ModelReply(Message message, List<ToolUseBlock> calls) { this(message, calls, false); }
        /**
         * 复制工具调用列表，保持顺序；角色和 ID 由 AgentExecutor 检查，这里不再查。
         * @param message 模型回答
         * @param calls 不能为 null、不能含 null 的调用列表；不复制里面的 JSON
         * @param textDisplayed 只表示本次响应正文已展示，不表示 thinking 已显示或整个任务已完成
         * @throws NullPointerException calls 或其中元素为 null
         */
        public ModelReply {
            calls = List.copyOf(calls);
            if (observationRequestId == null) observationRequestId = "";
        }
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
    public record ToolBatch(List<ToolUseBlock> calls, List<ContentBlock> results, int roundsSinceTodo,
            boolean compact, RuntimeException failure) {
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
    public record Continue(String prompt) { }

    /** 检查执行器是否已经退出；不能取消已发生的副作用或强行停止网络请求。 */
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Agent loop is closed");
    }

    /**
     * 禁止后续模型请求和工具执行，可重复调用；不代关会话拥有的 cron/process 或共享客户端。
     * 会话在关闭本执行器后另行停止命令、拒绝 future 并等待处理线程。
     */
    @Override
    public void close() { closed = true; }
}
