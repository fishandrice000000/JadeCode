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
 * 当前任务的执行器：执行一个模型步骤或一个工具批次，不再拥有整个会话循环。
 *
 * <p>SessionRuntime 决定何时执行、等待或结束；本类将结果作为 SessionEvent 交回运行器，
 * 不自行领取新请求、收集后台通知或修改会话历史。普通模型与工具仍同步执行，只有显式后台
 * bash 才由工作者执行。统一事件入口不等于所有操作都异步化。</p>
 *
 * <p>可按调用顺序阅读：activeRequest/taskSystem 固定任务目标和召回背景，request 获取
 * 正式回复，executeTools 执行其中的工具，compactAfterBatch 在完整结果回灌后处理主动压缩，
 * stop/finish 参与结束检查。是否继续、等待后台或领取新任务，均由 SessionRuntime 决定。
 * 保留类名与 run API 是为兼容既有装配和调用方，不表示本类还有另一套会话调度循环。</p>
 *
 * <p>压缩仍遵守原契约：每次正式请求前自动整理，正式请求超限才有限补救，工具批次闭合后
 * 才处理 compact。辅助摘要不计业务轮，不进入工具分发或 Stop；文件 IO 副作用不回滚。</p>
 */
public class AgentLoop implements AutoCloseable {
    /** 正式主任务请求使用的客户端；辅助服务是否使用同一客户端由装配决定。 */
    private final LlmClient client;
    /** 工具名到实现的分发表；装配后不应由外部并发改写。 */
    private final Map<String, Tool> tools;
    /** 基础 system；每项任务可在此基础上追加一次记忆召回背景。 */
    private final String system;
    /** 单次正式回复的输出 token 上限，不是上下文压缩字符阈值。 */
    private final int maxTokens;
    /** 同步扩展点，包括工具审批、展示与 Stop 控制；并非异步消息队列。 */
    private final Hooks hooks;
    /** 可选压缩策略；它产生候选历史，运行器决定采用，不拥有会话列表。 */
    private final ContextCompactor compactor;
    /** 可选任务级记忆服务，不在每次后台唤醒时重新召回。 */
    private final MemoryManager memory;
    /** 与父 bash 共享的命令资源；整个会话关闭时一并释放。 */
    private final BackgroundManager background;
    /** 定时定义与 pending 状态；本类仅把它交给运行器，不启动计时线程。 */
    private final CronScheduler cron;
    /** 退出后禁止新请求/工具；volatile 使消费者可看见 CLI 线程的关闭。 */
    private volatile boolean closed;

    /** 识别本批是否更新过计划，用于重置提醒计数，不据此判断业务完成。 */
    private static final String TODO_TOOL = "todo_write";
    /** 只认实际执行且返回稳定信号的 compact 调用，不扫描任意输出文本猜测压缩意图。 */
    private static final String COMPACT_TOOL = "compact";
    /** 连续三个未更新 todo 的工具批次后附带的普通文本提醒，不是新目标。 */
    private static final String REMINDER_MESSAGE = "<reminder>Update your todos.</reminder>";

    /**
     * 创建最小执行器，保留旧调用方不启用后续章节能力的装配方式。
     * 不启动模型、命令或线程；工具表不能包含无人处理的 compact。
     * @param client 正式模型客户端
     * @param tools 工具分发表，不得注册 compact
     * @param system 基础系统提示
     * @param maxTokens 单次正式回复的输出 token 上限
     * @param hooks 同步 hook 注册表
     * @throws IllegalArgumentException 注册了 compact 但没有提供压缩器
     * @see #AgentLoop(LlmClient, Map, String, int, Hooks, ContextCompactor, MemoryManager, BackgroundManager, CronScheduler)
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks) {
        this(client, tools, system, maxTokens, hooks, null);
    }

    /**
     * 在最小执行器上增加上下文保护；其余可选能力不启用，构造不触发压缩。
     * @param client 正式模型客户端
     * @param tools 工具分发表
     * @param system 基础系统提示
     * @param maxTokens 单次正式回复的输出 token 上限
     * @param hooks 同步 hook 注册表
     * @param compactor 自动整理、主动压缩和超限补救共用的策略，可为 null
     * @throws IllegalArgumentException 注册 compact 却未提供 compactor
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor) {
        this(client, tools, system, maxTokens, hooks, compactor, null);
    }

    /**
     * 绑定压缩与任务级记忆，不启用后台或定时依赖；构造不召回或提取记忆。
     * @param client 正式模型客户端
     * @param tools 工具分发表
     * @param system 召回前的基础系统提示
     * @param maxTokens 单次正式回复的输出 token 上限
     * @param hooks 同步 hook 注册表
     * @param compactor 可选压缩策略
     * @param memory 可选入口召回与结束维护服务，后台续跑不重新建立任务
     * @throws IllegalArgumentException 注册 compact 却未提供 compactor
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor, MemoryManager memory) {
        this(client, tools, system, maxTokens, hooks, compactor, memory, null);
    }

    /**
     * 增加会话后台命令资源，不启用定时生产；绑定资源本身不启动命令或线程。
     * @param client 正式模型客户端
     * @param tools 工具分发表
     * @param system 基础系统提示
     * @param maxTokens 单次正式回复的输出 token 上限
     * @param hooks 同步 hook 注册表
     * @param compactor 可选压缩策略
     * @param memory 可选任务级记忆服务
     * @param background 可选后台管理器；必须与父 bash 使用同一实例，否则无法正确等待/收集结果
     * @throws IllegalArgumentException 注册 compact 却未提供 compactor
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor, MemoryManager memory, BackgroundManager background) {
        this(client, tools, system, maxTokens, hooks, compactor, memory, background, null);
    }

    /**
     * 生产装配：仅绑定依赖，实际会话和计时器由 CLI 显式启动。
     *
     * <p>参数中的资源由装配方准备；本构造器不读取配置、不做文件 IO，也不复制工具表。
     * 注册 compact 却不提供处理器会立即失败，避免工具返回“已请求”而实际没有压缩。</p>
     * @param client 正式模型调用客户端，辅助服务的客户端由各自装配决定
     * @param tools 工具分发表；包含 compact 时要求 compactor 非空
     * @param system 不含本次召回背景的基础提示
     * @param maxTokens 单次正式请求上限
     * @param hooks 工具与 Stop 扩展点
     * @param compactor 可选上下文保护，无会话历史所有权
     * @param memory 可选任务入口召回与结束维护
     * @param background 可选会话后台命令所有者
     * @param cron 可选工作区定时状态，不在本类启动计时
     * @throws IllegalArgumentException 注册 compact 却未提供 compactor
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

    /**
     * 提供同一 hook 注册表，供装配方添加入口、工具和结束回调。
     * @return 原 hook 对象；只应在受控装配阶段注册，不启动消费者或触发回调
     */
    public Hooks hooks() { return hooks; }

    /**
     * 供运行器创建定时生产适配器，确保生产者与定时工具观察同一份状态。
     * @return 绑定的调度器；旧装配未启用 cron 时为 null，获取它不代表计时器已经运行
     */
    public CronScheduler cronScheduler() { return cron; }

    /**
     * 向同包运行器提供后台命令资源，不把管理器暴露成模型工具参数。
     * @return 与父 bash 共享的管理器；未启用后台能力时为 null
     */
    BackgroundManager background() { return background; }

    /**
     * 兼容既有调用方：在调用者持有的列表上完成一个任务，仍使用唯一的会话执行实现。
     *
     * <p>此入口不启动 cron，也不在返回时关闭整个执行器；后续 run 可继续同一历史。异常原样
     * 向上传播，已经采用的压缩、完整工具交换及 IO 保留。不要与另一个运行器并发调用。</p>
     * @param messages 可变、工具交换完整的既有历史，末条为本次请求
     * @throws LlmException 既有有限超限补救后仍无法继续的模型错误
     * @throws RuntimeException 历史/回复非法、执行链意外失败或执行器关闭；已发生的副作用不撤销
     */
    public void run(List<Message> messages) throws LlmException {
        SessionRuntime.runExisting(this, messages);
    }

    /**
     * 在任务入口检查工具交换结构，并在需要时从最新请求捕获固定目标。
     *
     * <p>即使没有压缩/记忆也检查交换结构；两项能力均关闭时保留旧入口语义并返回 null。
     * 启用任一能力时，目标必须来自合法请求。后台通知、Stop 输入和摘要都不能在续跑时
     * 替换此目标。本方法只读历史，不做模型调用或文件 IO。</p>
     * @param messages 已含当前请求的完整历史
     * @return 供压缩和召回使用的目标；两项服务都未启用时为 null
     * @throws IllegalArgumentException 历史工具交换非法，或启用相关服务但无法提取合法请求
     * @throws IllegalStateException 执行器已关闭
     */
    String activeRequest(List<Message> messages) {
        ensureOpen();
        ToolExchangeProtocol.validateHistory(messages);
        return compactor == null && memory == null ? null : ContextCompactor.extractActiveRequest(messages);
    }

    /**
     * 为一项任务准备固定的 system，最多在入口进行一次记忆召回。
     *
     * <p>本方法不改写消息历史。启用记忆时可能读取记忆文件并请求辅助模型，具体召回
     * 失败降级由 MemoryManager 负责，不把辅助回复当作主任务回复。</p>
     * @param activeRequest 入口目标；启用 memory 时由入口提取保证合法
     * @return 基础提示或追加召回背景后的提示，跨工具轮、后台等待和 Stop 续跑复用
     * @throws IllegalStateException 执行器已关闭
     */
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
     * @param messages 运行器拥有的可变历史；本方法不直接写入，但同步 events 回调会更新它
     * @param activeRequest 本任务入口目标
     * @param taskSystem 本任务入口召回后的固定提示
     * @param events 当前消费者线程内的统一历史事件入口，不启动另一线程
     * @return 已校验调用 ID 的主任务回复，不执行工具
     * @throws LlmException 正式请求不适合超限补救、补救次数耗尽，或辅助压缩调用失败
     * @throws IllegalArgumentException 正式回复不是 ASSISTANT 或一批工具调用 ID 不唯一
     * @throws IllegalStateException 执行器已关闭，包括请求返回后才观察到关闭
     */
    SessionEvent.ModelReply request(List<Message> messages, String activeRequest, String taskSystem,
            Consumer<SessionEvent> events) throws LlmException {
        // 补救次数只属于本次正式步骤；即使压缩没有变短，也计一次，避免无限付费重试。
        int retries = 0;
        while (true) {
            ensureOpen();
            if (compactor != null) {
                // 候选同步采用后，下面 client.complete 读到的仍是同一 history 对象中的新内容。
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
     *
     * <p>失败占位只表示“没有拿到该调用的结果”，不保证调用完全没执行：工具可能先写文件
     * 再抛异常，因此不能把占位当作可以安全重试的证明。</p>
     *
     * @param calls 已校验 ID 唯一且已回灌 assistant 的整批调用，按模型给出的顺序执行
     * @param context 当前任务的可信身份、审批通道和关闭视图，传给 hook 及需要它的工具
     * @param previousRounds 此前连续未调用 todo_write 的批次数，不是单个工具数
     * @return 整批结果和下一计数；普通 Error 文本不设置 failure，意外运行异常则记录到
     *         failure 并补齐未产出结果的 ID。调用方必须先采用整批结果，再报告任务失败
     */
    SessionEvent.ToolBatch executeTools(List<ToolUseBlock> calls, ExecutionContext context, int previousRounds) {
        List<ContentBlock> results = new ArrayList<>();
        boolean usedTodo = false;
        boolean compact = false;
        RuntimeException failure = null;
        try {
            for (ToolUseBlock call : calls) {
                // 1. 先复核会话再走安全 hook；阻止/未知工具也要返回同一调用 ID 的结果。
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
            // 2. results 按调用顺序积累；其长度就是尚未产出结果的起点，不伪造工具已成功执行。
            failure = unexpected;
            for (int index = results.size(); index < calls.size(); index++) {
                results.add(new ToolResultBlock(calls.get(index).id(),
                        "Error: Tool batch interrupted; this call did not produce a result. The task failed."));
            }
        }
        // 3. 提醒按工具批次计数；已失败任务不再附带“继续更新 todo”的普通续跑提示。
        int rounds = usedTodo ? 0 : previousRounds + 1;
        if (failure == null && rounds >= 3) {
            results.add(new TextBlock(REMINDER_MESSAGE));
            rounds = 0;
        }
        return new SessionEvent.ToolBatch(calls, results, rounds, compact, failure);
    }

    /**
     * 在整批 tool_result 已采用后兑现主动压缩请求，避免切开正在进行的工具交换。
     *
     * <p>compact=true 只来自实际 CompactTool 成功信号，构造器已保证对应压缩器存在。</p>
     * @param batch 刚执行完的完整批次；compact=false 时立即返回，不做 IO 或摘要请求
     * @param messages 已包含该批全部结果的当前历史
     * @param activeRequest 固定任务目标，压缩后仍须保留
     * @param events 同步候选采用入口，保持外层历史列表对象身份
     * @throws LlmException 主动摘要调用失败；既有工具副作用不回滚
     * @throws IllegalStateException 需要压缩时执行器已关闭
     */
    void compactAfterBatch(SessionEvent.ToolBatch batch, List<Message> messages, String activeRequest,
            Consumer<SessionEvent> events) throws LlmException {
        if (!batch.compact()) return;
        ensureOpen();
        List<Message> compacted = compactor.compactAfterBatch(batch.calls(), messages, activeRequest);
        if (compacted != messages) events.accept(new SessionEvent.HistoryPrepared(compacted));
    }

    /**
     * 让同步 Stop hook 检查模型的结束意图，不在此处宣告整项任务完成。
     * @param messages 当前完整历史；hook 按只读约定检查，不应自行修改列表
     * @return null 表示 Stop 不再要求工作；非 null 为当前目标的继续输入，不是新的排队请求
     * @throws IllegalStateException 执行器已关闭
     */
    SessionEvent.Continue stop(List<Message> messages) {
        ensureOpen();
        String force = hooks.trigger(Hooks.STOP, Map.of(Hooks.PARAM_MESSAGES, messages));
        return force == null ? null : new SessionEvent.Continue(force);
    }

    /**
     * 运行任务结束时的记忆提取/整理；只有无待处理必等结果且 Stop 放行后才调用。
     *
     * <p>未启用 memory 时不做事；启用后可能调用辅助模型和写记忆文件，维护失败的记录/降级
     * 遵循 MemoryManager 契约，不能把辅助调用当作新主任务。</p>
     * @param messages 本任务结束时的完整当前历史，不由本方法改写
     * @throws IllegalStateException 执行器已关闭
     */
    void finish(List<Message> messages) {
        ensureOpen();
        if (memory != null) memory.finishTask(messages);
    }

    /**
     * 在关键执行边界拒绝关闭后的新步骤；不是对已开始 IO 的事务锁或撤销机制。
     * @throws IllegalStateException 执行器已经关闭
     */
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Agent loop is closed");
    }

    /**
     * 禁止后续执行并释放会话全部 shell，包括脱离当前任务的后台命令，可重复调用。
     * 普通 run 返回不会自动调用它。此处不关闭运行器队列、不回滚文件，也不保证取消在途 HTTP；
     * 运行器负责整个退出顺序，本类负责执行能力和命令资源边界。
     */
    @Override
    public void close() {
        closed = true;
        if (background != null) background.close();
    }
}
