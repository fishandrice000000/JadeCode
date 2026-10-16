package com.jadecode.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.context.ContextCompactor;
import com.jadecode.cron.CronScheduler;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.hooks.Hooks;
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
import com.jadecode.process.BackgroundManager;
import com.jadecode.tools.CompactTool;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;

/**
 * 负责请求模型、执行工具，以及需要时压缩消息。
 *
 * <p>什么时候开始一项任务、等不等后台命令、能不能结束，由 SessionRuntime 决定。
 * 本类只做好它交来的这一步：request 取得模型回复，executeTools 执行一批工具，
 * compactAfterBatch 在结果写进历史后压缩。除明确放到后台的 bash 外，其它操作仍在调用线程
 * 执行，不是每收到一个事件就另开线程。</p>
 *
 * <p>先读 activeRequest、taskSystem，了解如何记住用户请求和查到的记忆；
 * 再读 request → executeTools → compactAfterBatch；最后看 stop 和 finish。
 * run(messages) 留给旧调用者，但也使用 SessionRuntime，不再单独保留一套循环。</p>
 *
 * <p>生成摘要只是为了缩短消息，摘要回复不能当成模型的工作回答去分发工具或触发 Stop。
 * 请求失败不会自动还原已经写入的文件，也不会重跑执行过的工具。</p>
 */
public class AgentLoop implements AutoCloseable {
    /** 向模型请求下一步操作的客户端；查记忆和生成摘要的客户端由相应对象保存。 */
    private final LlmClient client;
    /** 工具名称与实现对象的对应表；开始运行后，外部不要再修改。 */
    private final Map<String, Tool> tools;
    /** 基础 system 提示词，每项任务开始时可能再加上查到的记忆。 */
    private final String system;
    /** 模型一次回答最多生成多少 token，不是历史消息允许的字符数。 */
    private final int maxTokens;
    /** 工具执行前后和任务结束时调用的函数表，用来查权限、显示日志或要求继续。 */
    private final Hooks hooks;
    /** 压缩器，可以没有；生成较短的消息列表，再由 SessionRuntime 写回 history。 */
    private final ContextCompactor compactor;
    /** 长期记忆功能，可以没有；一项任务开始时查一次，不因后台命令完成就重查。 */
    private final MemoryManager memory;
    /** 父 bash 使用的命令管理器；用户退出时停止其中的命令。 */
    private final BackgroundManager background;
    /** 保存定时任务和待发送状态的对象；本类不负责定时检查。 */
    private final CronScheduler cron;
    /** 用户退出后设为 true；volatile 让处理线程也能看到这个变化。 */
    private volatile boolean closed;

    /** 用来识别本批是否调用 todo_write，调用后重新计数，不据此判断任务已完成。 */
    private static final String TODO_TOOL = "todo_write";
    /** 只处理真正执行过且返回指定文字的 compact，不靠扫描任意工具输出猜是否要压缩。 */
    private static final String COMPACT_TOOL = "compact";
    /** 连续三批工具都没更新 todo 后，给模型的提醒；不是用户的新任务。 */
    private static final String REMINDER_MESSAGE = "<reminder>Update your todos.</reminder>";

    /**
     * 只提供模型、工具和 hook 的基本功能，不启用压缩、记忆、后台或定时任务。
     * 构造时不启动线程，不调用模型或工具。
     * @param client 向模型发请求的客户端
     * @param tools 工具名称与具体实现的对应表
     * @param system 基础 system 提示词
     * @param maxTokens 模型单次回答最多生成多少 token
     * @param hooks 工具执行前后和任务结束时调用的函数表
     * @throws IllegalArgumentException tools 含 compact，但没有压缩器处理它
     * @see #AgentLoop(LlmClient, Map, String, int, Hooks, ContextCompactor, MemoryManager, BackgroundManager, CronScheduler)
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks) {
        this(client, tools, system, maxTokens, hooks, null);
    }

    /**
     * 在基本功能上增加消息压缩，仍不启用记忆、后台或定时任务。
     * @param client 向模型发请求的客户端
     * @param tools 工具名称与具体实现的对应表
     * @param system 基础 system 提示词
     * @param maxTokens 模型单次回答最多生成多少 token
     * @param hooks 工具执行前后和任务结束时调用的函数表
     * @param compactor 自动压缩、compact 工具和超限重试共用的压缩器；null 表示不压缩
     * @throws IllegalArgumentException 注册 compact 却没传 compactor
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor) {
        this(client, tools, system, maxTokens, hooks, compactor, null);
    }

    /**
     * 增加压缩和长期记忆功能。构造时只保存这些对象，不立即读记忆或请求模型。
     * @param client 向模型发请求的客户端
     * @param tools 工具名称与具体实现的对应表
     * @param system 基础 system 提示词
     * @param maxTokens 模型单次回答最多生成多少 token
     * @param hooks 工具执行前后和任务结束时调用的函数表
     * @param compactor 压缩器，可为 null
     * @param memory 每项任务开始时查记忆、做完后保存记忆的对象，可为 null
     * @throws IllegalArgumentException 注册 compact 却没传 compactor
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor, MemoryManager memory) {
        this(client, tools, system, maxTokens, hooks, compactor, memory, null);
    }

    /**
     * 再增加后台命令功能，仍不启动定时检查。构造时不会运行任何命令。
     * @param client 向模型发请求的客户端
     * @param tools 工具名称与具体实现的对应表
     * @param system 基础 system 提示词
     * @param maxTokens 模型单次回答最多生成多少 token
     * @param hooks 工具执行前后和任务结束时调用的函数表
     * @param compactor 压缩器，可为 null
     * @param memory 长期记忆功能，可为 null
     * @param background 必须与父 bash 使用同一个管理器，才能收集并等待它启动的命令；可为 null
     * @throws IllegalArgumentException 注册 compact 却没传 compactor
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor, MemoryManager memory, BackgroundManager background) {
        this(client, tools, system, maxTokens, hooks, compactor, memory, background, null);
    }

    /**
     * 保存完整 CLI 所需的各个对象，构造时不读文件、不启动线程，也不调用模型。
     *
     * <p>如果工具表有 compact 却没传压缩器，立即报错。否则模型调用 compact 后虽然拿到
     * “已请求压缩”的回答，实际上却没人执行压缩，会误导模型和用户。tools 不复制，
     * 构造后调用者不要再从其它线程修改它。</p>
     * @param client 向模型发请求的客户端
     * @param tools 工具名称与具体实现的对应表
     * @param system 基础 system 提示词
     * @param maxTokens 模型单次回答最多生成多少 token
     * @param hooks 工具执行前后和任务结束时调用的函数表
     * @param compactor 压缩器，可为 null；工具表有 compact 时必须提供
     * @param memory 长期记忆功能，可为 null
     * @param background 与父 bash 共用的命令管理器，可为 null
     * @param cron 与定时工具共用的任务记录，可为 null；这里只保存它，不开始检查时间
     * @throws IllegalArgumentException 注册 compact 却没传 compactor
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
     * 取得同一份 hook 表，供 Main 在开始运行之前添加日志、权限检查等函数。
     * @return 构造时传入的 Hooks；调用此方法不会触发里面的函数
     */
    public Hooks hooks() { return hooks; }

    /**
     * 让 SessionRuntime 使用和定时工具相同的任务记录。
     * @return 构造时传入的调度器；没有定时功能时为 null，取得对象不表示定时检查已经启动
     */
    public CronScheduler cronScheduler() { return cron; }

    /**
     * 让 SessionRuntime 取得 bash 使用的命令管理器，以便收结果和停止命令。
     * @return 共享的管理器；没有后台功能时为 null
     */
    BackgroundManager background() { return background; }

    /**
     * 按旧 API 执行一项任务，直接修改调用者给出的消息列表。
     *
     * <p>实际委托 SessionRuntime.runExisting，不启动定时检查，也不在返回时关闭 AgentLoop。
     * 调用者可以再次传入同一历史继续对话，但不能在另一个线程同时运行它。
     * 失败前已加入的消息、压缩后的历史及文件修改都会留下，不会自动还原。</p>
     * @param messages 可修改的消息列表，末条已是本次请求，工具调用必须各有对应结果
     * @throws LlmException 模型请求或压缩摘要失败，不能继续
     * @throws RuntimeException 消息不合法、执行意外出错或执行器已退出
     */
    public void run(List<Message> messages) throws LlmException {
        SessionRuntime.runExisting(this, messages);
    }

    /**
     * 开始处理任务时，检查消息中的工具调用和结果是否配对，然后记住用户请求。
     *
     * <p>用户请求只取这一次。以后 history 末条可能变成工具结果、后台通知或 Stop 提示，
     * 不能再把那些文字当成用户想做的新任务。压缩和长期记忆都没启用时，不需要请求原文，
     * 返回 null，但仍检查工具调用配对。</p>
     * @param messages 已含本次请求的历史；只读取，不修改，也不调用模型或读写文件
     * @return 本次请求原文；压缩和记忆都未启用时为 null
     * @throws IllegalArgumentException 调用和结果不配对，或需要请求原文却找不到合法请求
     * @throws IllegalStateException 执行器已退出
     */
    String activeRequest(List<Message> messages) {
        ensureOpen();
        ToolExchangeProtocol.validateHistory(messages);
        return compactor == null && memory == null ? null : ContextCompactor.extractActiveRequest(messages);
    }

    /**
     * 在开始一项任务时查一次长期记忆，把查到的内容放进基础 system 提示词。
     *
     * <p>没有记忆功能就直接返回原 system。后面即使运行多批工具、等后台或 Stop 要求继续，
     * 也复用这次返回的字符串，不每次都查记忆。查记忆可能读文件和请求辅助模型，
     * 失败时怎么处理由 MemoryManager 负责；不把辅助模型的回答加入主对话。</p>
     * @param activeRequest 用户最初的请求，启用记忆时应已由 activeRequest 方法检查
     * @return 这项任务接下来一直使用的 system 提示词，不修改消息列表
     * @throws IllegalStateException 执行器已退出
     */
    String taskSystem(String activeRequest) {
        ensureOpen();
        return memory == null ? system : memory.prepareSystem(system, activeRequest);
    }

    /**
     * 请求一次模型回答，必要时先压缩；如果模型明确说上下文太长，可在限次内压缩后重试。
     *
     * <p>每次尝试前调用 prepare。需要更换历史时，调用 events.accept 让 SessionRuntime.apply
     * 立即修改原 messages 的内容，然后把更新后的消息交给模型。本方法自己不 clear/add，
     * 也不另开线程，返回时还没有执行回复中的工具。</p>
     *
     * <p>catch 只包住正式的 client.complete。网络、限流等错误不能一律当作上下文超限；
     * 生成摘要失败也不能再套进超限重试。已成功换上的历史不会因后续模型失败而恢复成旧版。
     * 定时任务第一次回答前如何恢复历史，由 SessionRuntime.fail 单独处理。</p>
     * @param messages 当前历史；本方法读取它，但 events 函数会直接更新这个列表的内容
     * @param activeRequest 本次用户请求，压缩后仍须记住
     * @param taskSystem 本任务开始时准备好的 system 提示词
     * @param events 收到新历史时立即执行的函数，实际为 SessionRuntime.apply
     * @return ASSISTANT 回复和其中的工具调用；已检查一批内的调用 ID 不重复，但尚未执行工具
     * @throws LlmException 不能重试的模型错误、重试次数用完，或压缩摘要失败
     * @throws IllegalArgumentException 回复不是 ASSISTANT，或工具调用 ID 重复
     * @throws IllegalStateException 用户已退出，包括等模型回答时才退出的情况
     */
    SessionEvent.ModelReply request(List<Message> messages, String activeRequest, String taskSystem,
            Consumer<SessionEvent> events) throws LlmException {
        // 只数这次请求的重试次数；压缩没缩短也算试过一次，否则可能无限调用付费模型。
        int retries = 0;
        while (true) {
            ensureOpen();
            if (compactor != null) {
                // events 会立即把压缩后的消息放回原列表，下面发给模型的就是更新后的历史。
                List<Message> prepared = compactor.prepare(messages, activeRequest);
                if (prepared != messages) events.accept(new SessionEvent.HistoryPrepared(prepared));
            }

            ModelResponse response;
            try {
                ensureOpen();
                response = client.complete(new CompleteRequest(taskSystem, messages, maxTokens,
                        List.copyOf(tools.values())));
            } catch (LlmException failure) {
                // 这里只处理主任务模型请求的错误；用户退出了就不要再请求摘要来尝试重试。
                ensureOpen();
                if (compactor == null || !ContextCompactor.isContextOverflow(failure)
                        || retries >= ContextCompactor.MAX_REACTIVE_RETRIES) throw failure;
                retries++;
                List<Message> recovered = compactor.reactiveCompact(messages, activeRequest);
                if (recovered != messages) events.accept(new SessionEvent.HistoryPrepared(recovered));
                continue;
            }

            // 用户可能在等回答时退出；即使模型现在才回复，也不能接着执行工具。
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
     * 按顺序执行模型这次要求的整批工具，返回每个调用的结果，不修改 history。
     *
     * <p>调用前，SessionRuntime 已把含 tool_use 的模型回复放进历史。
     * 这里先查权限，再执行工具；被拒绝和未知工具也返回对应 ID 的 tool_result。
     * 普通 Error 文本留给模型判断怎么办，不直接当成整个任务失败。</p>
     *
     * <p>若某工具或 hook 意外抛 RuntimeException，就保留已有结果，给未拿到结果的调用补上
     * 明确失败说明。调用者先把整批结果加进 history，再结束任务，避免只有 tool_use 没有结果。
     * 失败说明只代表没拿到结果，不代表工具什么都没做；它可能先写了文件才抛异常，不能随便重跑。</p>
     * @param calls 这次回复里的工具调用，ID 已检查不重复，顺序不变
     * @param context 程序记录的任务来源、任务 ID、询问用户的方法和退出检查方法
     * @param previousRounds 自上次 todo 更新或提醒以来的工具批次数，不是工具个数
     * @return 完整的一批结果和新的提醒计数；意外异常放在 failure 中，普通 Error 文本不放在那里
     */
    SessionEvent.ToolBatch executeTools(List<ToolUseBlock> calls, ExecutionContext context, int previousRounds) {
        List<ContentBlock> results = new ArrayList<>();
        boolean usedTodo = false;
        boolean compact = false;
        RuntimeException failure = null;
        try {
            for (ToolUseBlock call : calls) {
                // 1. 先查是否退出，再查权限；被拒绝或未知的工具，也要返回与调用 ID 对应的结果。
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
                // 等用户批准可能很久；用户若已退出，即使现在收到批准，也不能再执行工具。
                ensureOpen();
                if (context.isCancelled()) throw new IllegalStateException("Parent session is closed");
                ObjectNode input = call.input() instanceof ObjectNode node ? node : Json.mapper().createObjectNode();
                String output = tool.execute(input, context);
                // 先记下工具确实返回的结果，再显示日志，免得日志出错后连执行结果都丢掉。
                results.add(new ToolResultBlock(call.id(), output));
                usedTodo |= call.name().equals(TODO_TOOL);
                compact |= call.name().equals(COMPACT_TOOL) && CompactTool.OUTPUT.equals(output);
                hooks.trigger(Hooks.POST_TOOL_USE,
                        Map.of(Hooks.PARAM_BLOCK, call, Hooks.PARAM_OUTPUT, output, Hooks.PARAM_CONTEXT, context));
            }
        } catch (RuntimeException unexpected) {
            // 2. results 中已有前面调用的结果；从下一个没拿到结果的 ID 开始补失败说明。
            failure = unexpected;
            for (int index = results.size(); index < calls.size(); index++) {
                results.add(new ToolResultBlock(calls.get(index).id(),
                        "Error: Tool batch interrupted; this call did not produce a result. The task failed."));
            }
        }
        // 3. 一批工具只加一次计数；任务已经失败，就不再加“更新 todo”的继续工作提示。
        int rounds = usedTodo ? 0 : previousRounds + 1;
        if (failure == null && rounds >= 3) {
            results.add(new TextBlock(REMINDER_MESSAGE));
            rounds = 0;
        }
        return new SessionEvent.ToolBatch(calls, results, rounds, compact, failure);
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
     * @param events 把压缩后的消息放回原 history 列表的函数
     * @throws LlmException 生成摘要失败；工具已经做过的文件修改不会还原
     * @throws IllegalStateException 需要压缩时，执行器已退出
     */
    void compactAfterBatch(SessionEvent.ToolBatch batch, List<Message> messages, String activeRequest,
            Consumer<SessionEvent> events) throws LlmException {
        if (!batch.compact()) return;
        ensureOpen();
        List<Message> compacted = compactor.compactAfterBatch(batch.calls(), messages, activeRequest);
        if (compacted != messages) events.accept(new SessionEvent.HistoryPrepared(compacted));
    }

    /**
     * 模型不再调用工具时，问 Stop hook 是否还有事情没做完。
     * @param messages 当前历史，hook 应只读取，不直接修改
     * @return hook 返回 null 时本方法也返回 null；否则把其提示包装成 Continue，
     *         由运行器加入 history，让模型继续当前任务，不把它排成新任务
     * @throws IllegalStateException 执行器已退出
     */
    SessionEvent.Continue stop(List<Message> messages) {
        ensureOpen();
        String force = hooks.trigger(Hooks.STOP, Map.of(Hooks.PARAM_MESSAGES, messages));
        return force == null ? null : new SessionEvent.Continue(force);
    }

    /**
     * 在该等的后台结果都已处理、Stop 也允许结束后，保存并整理长期记忆。
     *
     * <p>没有记忆功能就直接返回；有的话可能读写文件或调用辅助模型。
     * 具体失败处理由 MemoryManager 负责，不能把辅助回复当成主任务回答。
     * 工具运行到一半或仍在等后台时不能调用，否则可能把没完成的结论当成长期知识。</p>
     * @param messages 当前已完成任务的历史，只读，不由本方法追加消息
     * @throws IllegalStateException 执行器已退出
     */
    void finish(List<Message> messages) {
        ensureOpen();
        if (memory != null) memory.finishTask(messages);
    }

    /**
     * 在请求模型或执行工具前，检查用户是否已经退出。
     * 只能阻止后面的操作，不能还原已经写入的文件或立即停止正在等待的网络请求。
     * @throws IllegalStateException 执行器已退出
     */
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Agent loop is closed");
    }

    /**
     * 用户退出整个会话时调用：标记已退出，并停止所有 shell 命令，重复调用也可以。
     *
     * <p>包括设置“不必等它”的后台命令，因为那只是当前任务不用等，不是退出 CLI 后还要运行。
     * 普通 run 返回不会自动调用这里。任务队列由 SessionRuntime 另行处理，
     * 本方法也不会还原文件或保证马上停止模型的网络请求。</p>
     */
    @Override
    public void close() {
        closed = true;
        if (background != null) background.close();
    }
}
