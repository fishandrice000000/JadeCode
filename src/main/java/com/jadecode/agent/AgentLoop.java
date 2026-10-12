package com.jadecode.agent;

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
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolExchangeProtocol;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.safety.Hooks;
import com.jadecode.tools.CompactTool;
import com.jadecode.tools.Tool;
import com.jadecode.util.Json;

/**
 * Agent 主循环:与 LLM 对话直到模型不再调工具为止。
 *
 * <p>
 * 每轮流程:组装 CompleteRequest 并调用 → 收集该回复里的 tool_use 并拒绝重复 ID →
 * assistant 消息入栈 → 逐条串行执行(PreToolUse hook 可拦截)→ 结果合成一条 user 消息
 * 回灌 → <b>整批闭合之后</b>才处理本批的压缩请求,进入下一轮。停止条件:模型回复里没有
 * tool_use(停之前先问 Stop hook,它可能强制追加一轮)。
 * </p>
 *
 * <p>
 * run 入口先做一次既有历史校验(工具交换必须完整合法,见
 * {@link ToolExchangeProtocol#validateHistory(List)}):非法历史在第一次模型调用前就被拒绝。
 * 如果注入了压缩器,入口还会用 {@link ContextCompactor#extractActiveRequest(List)} 捕获一次
 * "本次 run 的请求",整轮沿用(原因见 {@link #run})。
 * </p>
 *
 * <p>
 * <b>手动压缩(compact 工具)的分工</b>:模型负责<b>提出请求</b>——只有它知道自己是否做完了一个阶段;
 * harness 负责<b>决定何时执行</b>——要等这一批工具交换<b>全部闭合</b>(每个 tool_use 都有对应
 * tool_result 回灌)之后才动手。顺序不能颠倒:在工具执行中间裁剪历史,同批其它工具的结果还没回灌,
 * 会留下"有调用没结果"的半个交换,甚至删掉已经发生过的文件写入记录,让模型重复副作用。
 * </p>
 *
 * <p>
 * <b>压缩是一次独立的摘要调用</b>:它用专用 system prompt、不带任何工具,不是"下一轮正常对话让模型
 * 自己总结";压缩成功后,替换后的历史<b>原地写回</b>调用方持有的那个列表,于是下一次正常请求看到的
 * 就是压缩结果。todo 提醒的轮次计数不受影响:摘要调用不会被算成一个额外的工具轮。
 * </p>
 */
public class AgentLoop {
    /** LLM 连接;Main 装配时注入 */
    private final LlmClient client;

    /** 工具分发表:模型 tool_use 块的 name → Tool;执行按此查表 */
    private final Map<String, Tool> tools;

    /** system prompt 原文,随每次请求原样发给模型 */
    private final String system;

    /** 单次请求的 max_tokens 上限 */
    private final int maxTokens;

    /** hook 事件表:PreToolUse(拦截)/ PostToolUse(展示)/ Stop(强制续跑) */
    private final Hooks hooks;

    /**
     * 手动压缩依赖;{@code null} 表示<b>未启用</b>:此时 run 的行为与 s01–s07 完全一致,
     * 既不捕获入口请求,也不处理 compact 请求。
     */
    private final ContextCompactor compactor;

    /** todo_write 的工具名:只有成功执行它才算"用了 todo"(见 {@link #run}) */
    private static final String TODO_TOOL = "todo_write";

    /** compact 的工具名:只有成功执行且输出精确匹配时才置位"本批请求过压缩" */
    private static final String COMPACT_TOOL = "compact";

    /** 连续 3 轮未用 todo_write 时,追加进回灌消息的提醒文本 */
    private static final String REMINDER_MESSAGE = "<reminder>Update your todos.</reminder>";

    /**
     * 未启用压缩的构造器:s01–s07 的原有装配方式,等价于把第六个参数传 {@code null}。
     *
     * <p>
     * 注意:如果 {@code tools} 里注册了 compact,用这个构造器会直接抛配置错误
     * (见六参构造器)——不允许出现"工具返回成功、但没人处理这个请求"的静默失效。
     * </p>
     *
     * @param client    LLM 客户端(真实或测试替身)
     * @param tools     工具分发表
     * @param system    system prompt 原文
     * @param maxTokens 单请求 token 上限
     * @param hooks     hook 事件表
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks) {
        this(client, tools, system, maxTokens, hooks, null);
    }

    /**
     * 完整构造器:可注入手动压缩依赖。
     *
     * <p>
     * <b>为什么用"可空依赖"而不是模式开关</b>:本阶段只有"启用 / 未启用"两种装配,一个可空参数
     * 已经够用,不需要模式枚举、工厂或泛化接口;{@code null} 明确表示"这条路径还没有压缩能力"。
     * </p>
     *
     * <p>
     * <b>配置错误要早暴露</b>:工具表里注册了 compact 却没有提供压缩器,说明装配漏了一半——
     * 调用会"成功"返回 {@link CompactTool#OUTPUT},而没有任何人处理这个请求,历史永远不会被压缩。
     * 这种组合在<b>构造时</b>就抛 {@link IllegalArgumentException},不留到运行期静默失效。
     * </p>
     *
     * <p>
     * 本轮不修改 Main,所以生产 CLI 仍走五参构造器(未启用);测试通过显式注入压缩器验证真实父循环链路。
     * 压缩器不持有会话状态,父子循环可以共享同一个实例。
     * </p>
     *
     * @param client    LLM 客户端(真实或测试替身)
     * @param tools     工具分发表;注册了 compact 时必须同时提供 compactor
     * @param system    system prompt 原文
     * @param maxTokens 单请求 token 上限
     * @param hooks     hook 事件表
     * @param compactor 手动压缩依赖;{@code null} = 未启用(旧路径)
     * @throws IllegalArgumentException tools 注册了 compact 但 compactor 为 null
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
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
     * @return hook 事件表(测试与 Main 需要拿它注册/断言 hook)
     */
    public Hooks hooks() {
        return hooks;
    }

    /**
     * 在传入的 messages 上原地续跑,直到模型不再调工具为止。
     *
     * <p>
     * 轮次语义:一轮 = while 循环一圈(模型一次回复 + 该回复里全部工具的串行执行 +
     * 该批闭合后的压缩请求处理)。工具执行串行,结果按 tool_use 出现顺序回灌;
     * todo 提醒计数在 for 结束后统一推进一次,所以同轮的多条调用不会让计数多跳,
     * 压缩器内部的摘要调用也不会被算成额外的一轮。
     * </p>
     *
     * <p>
     * <b>activeRequest 为什么只在入口捕获一次</b>:工具结果也用 {@code role=user} 回灌,
     * 所以"循环里最后一条 user 消息"会随执行不断变化。压缩消息要写的是<b>人</b>这次提出的请求,
     * 因此启用压缩器时,在第一次模型调用之前用
     * {@link ContextCompactor#extractActiveRequest(List)} 捕获一次,存成 {@code final} 局部变量:
     * 之后的工具结果、Stop hook 注入的 synthetic user 消息、乃至压缩后的新历史,都不会改变这个来源。
     * 这里刻意不新增 {@code run(messages, activeRequest)} 重载,也不用跨 run 存活的字段——
     * 请求的生命周期就是这一次 run。
     * </p>
     *
     * <p>
     * <b>历史到底什么时候变(三个时间点,别混成"执行工具时历史还没变")</b>:① 模型这条回复
     * (含 assistant/tool_use)在工具执行<b>之前</b>就已经追加进 {@code messages};② 工具执行期间
     * 历史<b>不再</b>追加,每个结果先暂存进本轮的局部 {@code results} 列表——此时这条交换"有调用、
     * 还没有结果",<b>尚未闭合</b>;③ 整批结果合成一条 user 消息加入历史,交换才<b>闭合</b>,
     * 也只有在这一步之后,才可能替换历史。所以"压缩不能发生在工具执行里面"的真正原因是:
     * 此刻 assistant 已经在历史里,而对应的 user 结果还没回灌,就地裁剪会留下半个交换。
     * </p>
     *
     * @param messages 会话消息列表,原地追加 assistant / user 消息;调用方持有的
     *                 就是"当前会话",run 结束后可直接继续对话或持久化
     * @throws IllegalArgumentException 传入的既有历史存在畸形的工具交换(在第一次模型调用前拒绝);
     *                                  启用压缩器时,入口请求不满足契约同样在此抛出
     * @throws LlmException 网络 / API 错误(含摘要调用失败),向上抛给调用方决定重试或退出
     */
    public void run(List<Message> messages) throws LlmException {
        // 0. 入口校验:既有历史的工具交换必须完整合法(相邻、角色正确、数量/顺序/ID 一一对应)。
        //    在第一次模型调用前拒绝,避免把非法历史发给模型。
        ToolExchangeProtocol.validateHistory(messages);

        // 0.1 启用压缩器时,再捕获本次 run 的"人类请求"(入口校验也在这里完成:非法入口在
        //     任何模型调用与工具副作用之前抛出)。未启用路径保持旧行为,不施加这个形状限制。
        final String activeRequest = compactor == null ? null : ContextCompactor.extractActiveRequest(messages);

        int roundsSinceTodo = 0; // 连续几轮没用 todo_write;每条用户消息(每次 run)从 0 重计

        while (true) {
            // 1. 组装 CompleteRequest 并调用获取 LLM 回复
            ModelResponse response = client
                    .complete(new CompleteRequest(system, messages, maxTokens, List.copyOf(tools.values())));

            // 2. 从 response.message().content() 收集 ToolUseBlock,并在追加 assistant 消息
            //    与执行任何工具之前拒绝重复调用 ID:失败不留半截 assistant 消息,也无工具副作用
            List<ToolUseBlock> toolCalls = new ArrayList<>();

            for (ContentBlock c : response.message().content()) {
                if (c instanceof ToolUseBlock u) {
                    toolCalls.add(u);
                }
            }
            ToolExchangeProtocol.requireDistinctCallIds(toolCalls);

            // 3. 追加 assistant 消息
            messages.add(response.message());

            // 4. 没有 tool_use, 此轮结束
            if (toolCalls.isEmpty()) {
                String force = hooks.trigger(Hooks.STOP, Map.of(Hooks.PARAM_MESSAGES, messages));
                if (force != null) {
                    messages.add(Message.userText(force));
                    continue;
                }
                return;
            }

            // 5. 调用 tool。本批的"是否请求压缩"标志是 run 循环里的局部变量,每批重新初始化:
            //    它不能存进会话字段或工具自身,否则会跨批次、跨 run 残留,把上一批的请求算到下一批。
            List<ContentBlock> results = new ArrayList<>();
            boolean usedTodo = false; // 本轮是否成功执行过 todo_write;for 外声明,每轮一块
            boolean compactRequested = false; // 本轮是否有"真正执行成功"的 compact 请求

            for (ToolUseBlock u : toolCalls) {
                // 查表与权限闸门都在 PRE_TOOL_USE 的 hook 里,首个非 null 即拦截
                String blocked = hooks.trigger(Hooks.PRE_TOOL_USE, Map.of(Hooks.PARAM_BLOCK, u));
                if (blocked != null) {
                    results.add(new ToolResultBlock(u.id(), blocked));
                    continue; // 拦截的 todo_write 不算使用:置位只在成功路径后
                }

                // 调用工具获取输出
                Tool tool = tools.get(u.name());
                ObjectNode node = u.input() instanceof ObjectNode o ? o : Json.mapper().createObjectNode();
                String output = tool.execute(node);

                // 成功执行过 todo_write 就置位(拦截的已在上面 continue)
                if (u.name().equals(TODO_TOOL)) {
                    usedTodo = true;
                }

                // compact 请求的判定必须来自"这条工具真的被执行了"这条路径:
                // 一是名字用字符串值比较(与工具表的 key 一致),二是输出必须精确等于
                // CompactTool.OUTPUT。这样别的工具返回同样文案不会误触发;被拦截的 compact
                // 也不会触发——它停在上面 continue,根本没有执行。这里刻意不去扫描 tool_result
                // 文本或历史:那会把"看起来像信号的内容"当成信号,也会把权限拦截的结果误判成成功。
                if (u.name().equals(COMPACT_TOOL) && CompactTool.OUTPUT.equals(output)) {
                    compactRequested = true;
                }

                // 触发事件 POST_TOOL_USE
                hooks.trigger(Hooks.POST_TOOL_USE, Map.of(Hooks.PARAM_BLOCK, u, Hooks.PARAM_OUTPUT, output));

                // 回灌结果
                results.add(new ToolResultBlock(u.id(), output));
            }

            // 轮级 reminder 计数:在 for 结束后统一推进——一轮无论几条工具调用都只算一轮;
            // ≥3 塞一条提醒并把计数归零,下轮重新累计
            roundsSinceTodo = usedTodo ? 0 : roundsSinceTodo + 1;
            if (roundsSinceTodo >= 3) {
                results.add(new TextBlock(REMINDER_MESSAGE));
                roundsSinceTodo = 0;
            }

            // 6. 全部结果合成一条 user 消息:一个 ToolResultBlock(u.id(), output) 对应一个 ToolUseBlock。
            //    到这一行为止,本批的工具交换才"闭合":每个 tool_use 都有结果,执行记录完整。
            messages.add(Message.userBlocks(results));

            // 7. 批次闭合之后,才处理本批的压缩请求。为什么必须等在这里:如果在上面的 for 里就地裁剪,
            //    同批其它工具的结果还没回灌,会拆开工具交换、丢掉已发生的副作用记录,而且 results
            //    也还没进历史。压缩成功时,新历史会被原地写回 messages,下一次正常请求就用它。
            if (compactRequested) {
                List<Message> compacted = compactAfterBatch(toolCalls, messages, activeRequest);
                replaceHistoryInPlace(messages, compacted);
            }
        }
    }

    /**
     * 执行一次"批次闭合后的压缩动作",返回新的历史列表(此时还没写回调用方的列表)。
     *
     * <p>
     * <b>纯 compact 还是混合批次</b>:按本批<b>全部</b> tool_use 的名字分类;不看结果消息里是否
     * 出现 reminder 或普通 TextBlock,也不因为普通工具被拦截就把批次当成纯 compact——分类依据是
     * 模型实际提出的调用集合。
     * </p>
     *
     * <p>
     * <b>纯 compact 批次</b>(每个调用都是 compact):模型已经明确表态"这一阶段结束了",
     * 没有需要抢救的新鲜数据,于是 {@link ContextCompactor#compactAll} 把整段历史换成一条摘要。
     * 一批里出现多个成功 compact 也只压缩一次。
     * </p>
     *
     * <p>
     * <b>混合批次</b>(还含别的工具):先对<b>最新结果批次</b>做可恢复的
     * {@link ContextCompactor#toolResultBudget},把超大的新鲜输出换成"路径 + 一小段预览";
     * 再让 {@link ContextCompactor#compactHistory} 只摘要旧前缀,最新那条闭合交换原样留在摘要后面。
     * 两步都复用既有算法,循环里不重复实现归档或摘要逻辑,也不调用整套 prepare。
     * </p>
     *
     * <p>
     * <b>失败语义</b>:落盘失败(带 cause 的 {@code IllegalStateException})与摘要失败
     * ({@link LlmException})都<b>原样传播</b>——不吞掉、不换成假摘要、不重跑已经完成的工具,
     * 也不继续发起下一次正常请求。此刻完整的工具交换已经回灌进 {@code messages},而压缩器不修改
     * 它的输入,所以失败后历史里保留的仍是那条完整闭合的记录,不会被清空或半替换。
     * 混合路径里的预算结果只是"候选":只有最终摘要动作成功,它才会被写回历史;即使磁盘上已经写出
     * 落盘文件,也不会提前提交部分替换。摘要失败时,已经成功写出的归档仍然保留。
     * </p>
     *
     * @param toolCalls     本批全部 tool_use(用于纯/混合分类)
     * @param messages      已经闭合的当前历史
     * @param activeRequest 入口捕获的本次请求;纯压缩与混合压缩都会把它写进摘要消息
     * @return 压缩后的新历史;可能与入参是同一个列表对象
     * @throws LlmException          摘要调用失败,原样向上传播
     * @throws IllegalStateException 落盘 / 归档失败(带 cause)
     */
    private List<Message> compactAfterBatch(List<ToolUseBlock> toolCalls, List<Message> messages,
            String activeRequest) throws LlmException {
        boolean pureCompactBatch = true;
        for (ToolUseBlock u : toolCalls) {
            if (!COMPACT_TOOL.equals(u.name())) {
                pureCompactBatch = false;
                break;
            }
        }

        if (pureCompactBatch) {
            return compactor.compactAll(messages, activeRequest);
        }

        // 混合批次:先把最新那条闭合交换里的超大新鲜结果变成可恢复的"路径 + 预览",
        // 再让摘要只覆盖它之前的前缀——刚生成、模型还没读过的结果不能先被转述进摘要而失真。
        List<Message> budgeted = compactor.toolResultBudget(messages);
        return compactor.compactHistory(budgeted, activeRequest);
    }

    /**
     * 把压缩结果<b>原地</b>写回调用方持有的列表,保留那个列表对象的身份。
     *
     * <p>
     * 为什么要判引用:压缩器在没有变化时会<b>返回入参本身</b>(例如混合批次没有可摘要的前缀,
     * 或预算与摘要都没改动任何一个 block)。若不做判断就 {@code clear()} 再
     * {@code addAll(自己)},会先把列表清空,然后往"已经空了的那个列表"里添加它自己,历史就丢了。
     * </p>
     *
     * <p>
     * <b>为什么不能只给参数换个引用</b>:本方法是个 helper,它的 {@code messages} 参数只是调用方
     * 传进来的一个引用副本。若在这里写 {@code messages = compacted},改的只是本方法的局部参数,
     * 调用方手里的列表一点没变,下一次请求仍会看到旧历史——就本方法这层上下文而言,压缩等于没发生。
     * </p>
     *
     * <p>
     * 同理,在 {@link #run} 里对局部 {@code messages} 重绑也有问题,只是表现不同:后续循环确实会用
     * 新列表,但<b>调用方持有的那个原历史列表没有被同步</b>(只有 clear + addAll 才会改到它),
     * 于是跨轮会话、调用方的持久化以及失败恢复时看到的记录会与原历史分叉。这就是压缩结果必须
     * <b>原地</b>灌回调用方列表的原因。
     * </p>
     *
     * @param messages  调用方持有的历史列表(会被原地修改)
     * @param compacted 压缩结果;若与 {@code messages} 是同一个对象则什么都不做
     */
    private static void replaceHistoryInPlace(List<Message> messages, List<Message> compacted) {
        if (compacted == messages) {
            return; // 无变化:返回的就是入参本身,先 clear 会把要保留的内容一起清掉
        }
        messages.clear();
        messages.addAll(compacted);
    }
}
