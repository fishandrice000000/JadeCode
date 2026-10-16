package com.jadecode.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.context.ContextCompactor;
import com.jadecode.cron.CronScheduler;
import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.LlmClient;
import com.jadecode.llm.LlmException;
import com.jadecode.llm.ModelResponse;
import com.jadecode.memory.MemoryManager;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolExchangeProtocol;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.safety.Hooks;
import com.jadecode.tools.BackgroundManager;
import com.jadecode.tools.CompactTool;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;

/**
 * 当前任务的执行器：负责模型、工具和压缩，不再承担会话级调度。
 *
 * <p>SessionRuntime 决定何时执行、等待或结束；本类将结果作为 SessionEvent 交回运行器，
 * 不自行领取新请求、收集后台通知或修改会话历史。普通模型与工具仍同步执行，只有显式后台
 * bash 才由工作者执行。统一事件入口不等于所有操作都异步化。</p>
 *
 * <p>压缩仍遵守原契约：每次正式请求前自动整理，正式请求超限才有限补救，工具批次闭合后
 * 才处理 compact。辅助摘要不计业务轮，不进入工具分发或 Stop；文件 IO 副作用不回滚。</p>
 */
public class AgentLoop implements AutoCloseable {
    private final LlmClient client;
    private final Map<String, Tool> tools;
    private final String system;
    private final int maxTokens;
    private final Hooks hooks;
    private final ContextCompactor compactor;
    private final MemoryManager memory;
    private final BackgroundManager background;
    private final CronScheduler cron;
    private volatile boolean closed;

    private static final String TODO_TOOL = "todo_write";
    private static final String COMPACT_TOOL = "compact";
    private static final String REMINDER_MESSAGE = "<reminder>Update your todos.</reminder>";

    /** 旧装配：不启用压缩、记忆、后台或定时依赖，构造没有运行副作用。 */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks) {
        this(client, tools, system, maxTokens, hooks, null);
    }

    /** 可选压缩器同时用于自动、主动和超限补救；注册 compact 时不可为空。 */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor) {
        this(client, tools, system, maxTokens, hooks, compactor, null);
    }

    /** 兼容启用记忆的装配：记忆仍按任务而不是按事件运行。 */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor, MemoryManager memory) {
        this(client, tools, system, maxTokens, hooks, compactor, memory, null);
    }

    /** 兼容后台装配；background 必须与父 bash 是同一个实例，不在构造时启动线程。 */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor, MemoryManager memory, BackgroundManager background) {
        this(client, tools, system, maxTokens, hooks, compactor, memory, background, null);
    }

    /**
     * 生产装配：仅绑定依赖，实际会话和计时器由 CLI 显式启动。
     * @param client 正式与辅助模型调用客户端
     * @param tools 工具分发表；包含 compact 时要求 compactor 非空
     * @param system 不含本次召回背景的基础提示
     * @param maxTokens 单次正式请求上限
     * @param hooks 工具与 Stop 扩展点
     * @param compactor 可选上下文保护，无会话历史所有权
     * @param memory 可选任务入口召回与结束维护
     * @param background 可选会话后台命令所有者
     * @param cron 可选工作区定时状态，不在本类启动计时
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor, MemoryManager memory, BackgroundManager background, CronScheduler cron) {
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
        this.memory = memory;
        this.background = background;
        this.cron = cron;
    }

    /** 返回装配所需的 hook 表，不启动事件消费者。 */
    public Hooks hooks() { return hooks; }

    /** 定时生产者共享工具所使用的状态对象，旧装配可为空。 */
    public CronScheduler cronScheduler() { return cron; }

    /** 仅供会话运行器绑定完成信号、领取结果及关闭所属命令。 */
    BackgroundManager background() { return background; }

    /**
     * 兼容既有调用方：在调用者持有的列表上完成一个任务，仍使用唯一的会话执行实现。
     *
     * <p>此入口不启动 cron，也不在返回时关闭整个执行器；后续 run 可继续同一历史。异常原样
     * 向上传播，已经采用的压缩、完整工具交换及 IO 保留。不要与另一个运行器并发调用。</p>
     * @param messages 可变、工具交换完整的既有历史，末条为本次请求
     * @throws LlmException 既有有限超限补救后仍无法继续的模型错误
     */
    public void run(List<Message> messages) throws LlmException {
        SessionRuntime.runExisting(this, messages);
    }

    /** 入口只检查一次并捕获目标；不能在后台结果或 hook 续跑时重新从末条 USER 提取。 */
    String activeRequest(List<Message> messages) {
        ensureOpen();
        ToolExchangeProtocol.validateHistory(messages);
        return compactor == null && memory == null ? null : ContextCompactor.extractActiveRequest(messages);
    }

    /** 每个任务仅召回一次，返回的背景跨后台等待、工具轮和 Stop 续跑保持固定。 */
    String taskSystem(String activeRequest) {
        ensureOpen();
        return memory == null ? system : memory.prepareSystem(system, activeRequest);
    }

    /**
     * 一次正式模型步骤，自动整理与有限超限补救包含在同一次步骤内。
     *
     * <p>历史候选通过事件交给运行器采用，本类不 clear/add 历史。只 catch 正式 complete
     * 的 LlmException，归档或辅助摘要失败直接传播，不误当作正式请求超限。已经采用的候选
     * 不因后续请求失败而撤销；定时首回复前的恢复是运行器的交付策略。</p>
     * @param messages 运行器拥有的历史，只读使用
     * @param activeRequest 本任务入口目标
     * @param taskSystem 本任务入口召回后的固定提示
     * @param events 当前消费者线程内的统一历史事件入口，不启动另一线程
     * @return 已校验调用 ID 的主任务回复，不执行工具
     */
    SessionEvent.ModelReply request(List<Message> messages, String activeRequest, String taskSystem,
            Consumer<SessionEvent> events) throws LlmException {
        int retries = 0;
        while (true) {
            ensureOpen();
            if (compactor != null) {
                List<Message> prepared = compactor.prepare(messages, activeRequest);
                if (prepared != messages) events.accept(new SessionEvent.HistoryPrepared(prepared));
            }

            ModelResponse response;
            try {
                ensureOpen();
                response = client.complete(new CompleteRequest(taskSystem, messages, maxTokens,
                        List.copyOf(tools.values())));
            } catch (LlmException failure) {
                // 只有主请求错误进入这条分流；关闭期间不再付费发起补救摘要。
                ensureOpen();
                if (compactor == null || !ContextCompactor.isContextOverflow(failure)
                        || retries >= ContextCompactor.MAX_REACTIVE_RETRIES) throw failure;
                retries++;
                List<Message> recovered = compactor.reactiveCompact(messages, activeRequest);
                if (recovered != messages) events.accept(new SessionEvent.HistoryPrepared(recovered));
                continue;
            }

            // 晚到回复不能在会话关闭后触发新的工具副作用。
            ensureOpen();
            if (response.message().role() != Role.ASSISTANT) {
                throw new IllegalArgumentException("Formal model response must be ASSISTANT");
            }
            List<ToolUseBlock> calls = response.message().blocksOfType(ToolUseBlock.class);
            ToolExchangeProtocol.requireDistinctCallIds(calls);
            return new SessionEvent.ModelReply(response.message(), calls);
        }
    }

    /**
     * 同步执行整批工具并返回一个完整结果事件；本方法不修改历史、不处理压缩。
     * 运行器此前已采用 assistant/tool_use，期间不能插入新请求或其它上下文。
     *
     * <p>普通工具失败仍返回 Error 文本。若自定义工具或 hook 意外抛 RuntimeException，
     * 已拿到的真实结果保留，尚未完成的调用用明确失败结果补齐，随后由运行器结束任务。
     * 这样下一任务不会继承一份孤立 tool_use 历史，也不会重跑已发生的副作用。</p>
     */
    SessionEvent.ToolBatch executeTools(List<ToolUseBlock> calls, ExecutionContext context, int previousRounds) {
        List<ContentBlock> results = new ArrayList<>();
        boolean usedTodo = false;
        boolean compact = false;
        RuntimeException failure = null;
        try {
            for (ToolUseBlock call : calls) {
                ensureOpen();
                if (context.isCancelled()) throw new IllegalStateException("Parent session is closed");
                String blocked = hooks.trigger(Hooks.PRE_TOOL_USE,
                        Map.of(Hooks.PARAM_BLOCK, call, Hooks.PARAM_CONTEXT, context));
                if (blocked != null) {
                    results.add(new ToolResultBlock(call.id(), blocked));
                    continue;
                }
                Tool tool = tools.get(call.name());
                if (tool == null) {
                    results.add(new ToolResultBlock(call.id(), "Error: Unknown tool: " + call.name()));
                    continue;
                }
                // 交互审批可能等了很久；批准返回后仍要复核关闭，不能把晚到批准用于退出后的工具。
                ensureOpen();
                if (context.isCancelled()) throw new IllegalStateException("Parent session is closed");
                ObjectNode input = call.input() instanceof ObjectNode node ? node : Json.mapper().createObjectNode();
                String output = tool.execute(input, context);
                // 先保留真实结果，再调用展示 hook；hook 失败不能抹去已经执行过什么。
                results.add(new ToolResultBlock(call.id(), output));
                usedTodo |= call.name().equals(TODO_TOOL);
                compact |= call.name().equals(COMPACT_TOOL) && CompactTool.OUTPUT.equals(output);
                hooks.trigger(Hooks.POST_TOOL_USE,
                        Map.of(Hooks.PARAM_BLOCK, call, Hooks.PARAM_OUTPUT, output, Hooks.PARAM_CONTEXT, context));
            }
        } catch (RuntimeException unexpected) {
            failure = unexpected;
            for (int index = results.size(); index < calls.size(); index++) {
                results.add(new ToolResultBlock(calls.get(index).id(),
                        "Error: Tool batch interrupted; this call did not produce a result. The task failed."));
            }
        }
        int rounds = usedTodo ? 0 : previousRounds + 1;
        if (failure == null && rounds >= 3) {
            results.add(new TextBlock(REMINDER_MESSAGE));
            rounds = 0;
        }
        return new SessionEvent.ToolBatch(calls, results, rounds, compact, failure);
    }

    /** 工具结果已经全部采用之后才压缩；候选仍交给同一个事件入口，不自行回填历史。 */
    void compactAfterBatch(SessionEvent.ToolBatch batch, List<Message> messages, String activeRequest,
            Consumer<SessionEvent> events) throws LlmException {
        if (!batch.compact()) return;
        ensureOpen();
        List<Message> compacted = compactor.compactAfterBatch(batch.calls(), messages, activeRequest);
        if (compacted != messages) events.accept(new SessionEvent.HistoryPrepared(compacted));
    }

    /** Stop 仍是同步控制判定：非 null 才成为当前任务的续跑事件。 */
    SessionEvent.Continue stop(List<Message> messages) {
        ensureOpen();
        String force = hooks.trigger(Hooks.STOP, Map.of(Hooks.PARAM_MESSAGES, messages));
        return force == null ? null : new SessionEvent.Continue(force);
    }

    /** 只有运行器已判定任务真正完成时才调用，不在每条事件后提取记忆。 */
    void finish(List<Message> messages) {
        ensureOpen();
        if (memory != null) memory.finishTask(messages);
    }

    /** 关闭后的正式请求、辅助调用和工具步骤必须明确失败，不能静默假成功。 */
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Agent loop is closed");
    }

    /** 整个会话退出才终止全部 shell，已发生文件副作用不回滚。 */
    @Override
    public void close() {
        closed = true;
        if (background != null) background.close();
    }
}
