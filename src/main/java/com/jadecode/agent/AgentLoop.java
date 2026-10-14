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
import com.jadecode.memory.MemoryManager;
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
 * 如果注入了压缩器或记忆管理器,入口还会用 {@link ContextCompactor#extractActiveRequest(List)} 捕获一次
 * "本次 run 的请求",整轮沿用(原因见 {@link #run})。
 * </p>
 *
 * <p>
 * <b>手动压缩(compact 工具)的分工</b>:模型负责<b>提出请求</b>——它可以依据任务进度判断"某些探索记录
 * 不必再常驻",主动请求收束历史;这是一种可选调节,并不表示它准确知道 token 预算、上下文是否已满、
 * 某个阶段是否结束或用户任务是否完成。harness 负责<b>决定何时执行</b>——要等这一批工具交换
 * <b>全部闭合</b>(每个 tool_use 都有对应 tool_result 回灌)之后才动手,并由自身的预算约束与自动整理
 * 继续兜底,不让模型的主动请求取代这些保障。顺序不能颠倒:在工具执行中间裁剪历史,同批其它工具的结果
 * 还没回灌,会留下"有调用没结果"的半个交换,甚至删掉已经发生过的文件写入记录,让模型重复副作用。
 * </p>
 *
 * <p>
 * <b>策略与时机分属两处</b>:本类只做两件事——<b>时机</b>(成功信号、闭合之后)与
 * <b>会话更新</b>(把压缩候选原地写回调用方持有的列表)。"纯 compact 整段摘要 / 混合批次只摘要旧前缀"
 * 这一信息保护取舍属于压缩策略,放在 {@link ContextCompactor#compactAfterBatch(List, List, String)};
 * 本类不判断归档范围、落盘或摘要细节。注意"纯 compact"只表示模型请求压缩且本批没有别的新鲜数据,
 * <b>不</b>代表某个用户任务已经完成。
 * </p>
 *
 * <p>
 * <b>压缩是一次独立的摘要调用</b>:它用专用 system prompt、不带任何工具,不是"下一轮正常对话让模型
 * 自己总结";压缩成功后,替换后的历史<b>原地写回</b>调用方持有的那个列表,于是下一次正常请求看到的
 * 就是压缩结果。todo 提醒的轮次计数不受影响:摘要调用不会被算成一个额外的工具轮。
 * </p>
 *
 * <p>
 * <b>正式请求前的自动整理</b>:模型可能连续读文件、跑测试却始终没请求 compact,历史照样会涨,
 * 所以不能把"记得请求压缩"当作上下文受保护的唯一依靠。启用压缩器时,<b>每一次</b>正式请求之前都会先跑
 * 一遍 {@link ContextCompactor#prepare}:先按预算把超大的最新工具结果转成可恢复的"路径 + 预览",
 * 再在消息条数过多时归档中段,只有整体仍然过大才逐层进入 micro / fit,最后才用一次摘要调用兜底。
 * 它整理的是<b>预算与历史规模</b>,不判断业务阶段,也<b>不等于每轮都会摘要</b>;
 * 它与模型主动调 compact 是两条独立入口——自动路径不要求模型先请求,主动路径也不依赖自动路径。
 * 五参构造器(未启用压缩器)保持 s01–s07 的旧行为:既不自动整理,也不捕获入口请求、不处理 compact 请求。
 * </p>
 *
 * <p>
 * <b>被拒绝后的有限补救</b>:自动整理靠的是<b>本地字符估算</b>,估得准不等于服务端也肯收,所以正式请求
 * 仍可能被以"上下文过长"拒绝。这条兜底只针对<b>正式请求</b>抛出的异常:先用
 * {@link ContextCompactor#isContextOverflow} 做一次近似判定(只认现有两个关键词),命中且本次请求还有额度时,
 * 请 {@link ContextCompactor#reactiveCompact} 造一份"旧前缀摘要 + 原样保留近期尾部"的候选并采用,
 * 再回到循环顶部重新整理、重新发<b>同一次</b>正式请求(每次正式请求最多
 * {@link ContextCompactor#MAX_REACTIVE_RETRIES} 次)。认证 / 限流 / 网络这类命中不了分类的失败不靠压缩处理
 * ——压缩改变不了它们的根因,只会白白损伤上下文。额度是 run 局部的:正式请求成功即归零,所以每个新请求
 * 各有一次机会;重试不会重跑任何已经执行过的工具,也不算新业务轮。补救自身失败(归档 / 摘要)按原样传播;
 * 候选一旦采用,之后的重试失败也不会回滚。五参 / null 压缩器路径不做补救。
 * </p>
 */
public class AgentLoop {
    /** LLM 连接;Main 装配时注入 */
    private final LlmClient client;

    /** 工具分发表:模型 tool_use 块的 name → Tool;执行按此查表 */
    private final Map<String, Tool> tools;

    /** 基础 system；启用记忆时每次 run 派生固定背景，不污染下一次用户任务。 */
    private final String system;

    /** 单次请求的 max_tokens 上限 */
    private final int maxTokens;

    /** hook 事件表:PreToolUse(拦截)/ PostToolUse(展示)/ Stop(强制续跑) */
    private final Hooks hooks;

    /**
     * 压缩依赖;{@code null} 表示<b>未启用</b>:此时 run 的行为与 s01–s07 完全一致——
     * 不在每次正式请求前自动整理、不处理 compact 请求；若单独启用记忆，仍会捕获入口请求。
     */
    private final ContextCompactor compactor;

    /** 可选长期记忆生命周期；旧五 / 六参构造器不启用，父 CLI 七参装配启用。 */
    private final MemoryManager memory;

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
     * 完整构造器:可注入压缩依赖(自动整理与主动压缩共用)。
     *
     * <p>
     * <b>为什么用"可空依赖"而不是模式开关</b>:本阶段只有"启用 / 未启用"两种装配,一个可空参数
     * 已经够用,不需要模式枚举、工厂或泛化接口;{@code null} 明确表示"这条路径还没有压缩能力"。
     * </p>
     *
     * <p>
     * <b>注入它启用的能力</b>:每次正式请求前的自动整理({@link ContextCompactor#prepare})
     * 与正式请求被判定超限时的有限补救({@link ContextCompactor#reactiveCompact})都来自这一个依赖,
     * 只要注入了压缩器就一直有效;此后再把 compact 注册进工具表,才会多出"模型主动请求压缩"
     * 这条入口({@link ContextCompactor#compactAfterBatch})。也就是说:工具表里<b>没有</b>注册 compact
     * 也可以注入压缩器,那时自动整理与超限补救照常工作,只是模型没有主动请求压缩的工具可用。
     * </p>
     *
     * <p>
     * <b>配置错误要早暴露</b>:工具表里注册了 compact 却没有提供压缩器,说明装配漏了一半——
     * 调用会"成功"返回 {@link CompactTool#OUTPUT},而没有任何人处理这个请求,历史永远不会被压缩。
     * 这种组合在<b>构造时</b>就抛 {@link IllegalArgumentException},不留到运行期静默失效。
     * </p>
     *
     * <p>
     * <b>生产装配使用七参构造器同时启用记忆</b>:{@code Main.buildLoop} 会建一个生产默认压缩器传给父循环,
     * 所以 CLI 的 run / repl 默认就具备自动整理、主动压缩与超限补救。五参构造器保留给"不启用压缩"的
     * 装配路径(例如只需要旧行为的调用方或测试)。压缩器不持有会话状态,父子循环可以共享同一个实例。
     * </p>
     *
     * @param client    LLM 客户端(真实或测试替身)
     * @param tools     工具分发表;注册了 compact 时必须同时提供 compactor
     * @param system    system prompt 原文
     * @param maxTokens 单请求 token 上限
     * @param hooks     hook 事件表
     * @param compactor 自动整理、超限补救与主动压缩共用的依赖;{@code null} = 未启用(旧路径:既不自动整理、
     *                  不补救超限,也不捕获入口请求、不处理 compact 请求)。不注册 compact 也可以注入它,
     *                  此时自动整理与超限补救仍然生效,只是没有模型主动请求压缩的入口
     * @throws IllegalArgumentException tools 注册了 compact 但 compactor 为 null
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor) {
        this(client, tools, system, maxTokens, hooks, compactor, null);
    }

    /**
     * 生产完整装配：在六参压缩行为之外，可启用每次用户任务的长期记忆生命周期。
     *
     * <p>memory 为 null 保持旧行为；非 null 时入口捕获当前请求、召回一次，正常结束后提取 / 整理。
     * 记忆不改变工具批次、Stop、压缩或正式请求重试的次序，辅助 IO 失败由管理器报告。
     * 构造本身无 IO / 模型调用；messages 仍由 run 的调用方持有。</p>
     *
     * @param client 正式请求客户端
     * @param tools 工具分发表；compact 注册要求 compactor 非空
     * @param system 基础 system，不含本次召回背景
     * @param maxTokens 正式请求输出上限
     * @param hooks hook 事件表
     * @param compactor 可选上下文保护
     * @param memory 可选工作区长期记忆管理
     * @throws IllegalArgumentException 注册 compact 却未提供压缩器
     */
    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens, Hooks hooks,
            ContextCompactor compactor, MemoryManager memory) {
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
     * 轮次语义:一轮 = while 循环一圈(正式请求前的自动整理 + 模型一次回复 + 该回复里全部工具的
     * 串行执行 + 该批闭合后的压缩请求处理)。工具执行串行,结果按 tool_use 出现顺序回灌;
     * todo 提醒计数在 for 结束后统一推进一次,所以同轮的多条调用不会让计数多跳,
     * 压缩器内部的摘要调用也不会被算成额外的一轮。
     * </p>
     *
     * <p>
     * <b>两条整理路径的历史前提是一致的</b>:自动整理面对的是"上一次迭代结束时留下的完整历史"
     * (入口历史已校验、每批交换都已闭合);批次闭合后的手动压缩触发时,手里同样是这份完整历史。
     * 两者都不在工具执行中途动手,所以都可以安全地归档中段或摘要旧前缀。
     * </p>
     *
     * <p>
     * <b>activeRequest 为什么只在入口捕获一次</b>:工具结果也用 {@code role=user} 回灌,
     * 所以"循环里最后一条 user 消息"会随执行不断变化。压缩消息要写的是<b>人</b>这次提出的请求,
     * 因此启用压缩器时,在第一次模型调用之前用
     * {@link ContextCompactor#extractActiveRequest(List)} 捕获一次,存成 {@code final} 局部变量:
     * 之后的工具结果、Stop hook 注入的 synthetic user 消息、乃至压缩后的新历史,都不会改变这个来源。
     * 自动整理用的是同一个 {@code activeRequest}:它只替换历史、不重新提取请求,
     * 所以摘要消息里的 Current user request 始终是入口那一句。
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
     *                                  启用压缩器或记忆时,入口请求不满足契约同样在此抛出
     * @throws LlmException 网络 / API 错误。被当前分类判为"上下文过长"的正式请求异常,会在本次请求的额度内
     *                      先尝试一次补救;额度耗尽、命中不了分类,以及自动整理 / 主动压缩 / 补救里的摘要失败,
     *                      都<b>原样向上抛</b>——不包装成成功。抛异常之后不再发起新的正式请求;
     *                      已经成功写出的归档 / 落盘保留,已发生的工具副作用不撤销,也不重跑
     * @throws IllegalStateException 自动整理、主动压缩或补救里的归档 / 落盘失败(带 cause);
     *                               此时同样不会采用部分候选
     */
    public void run(List<Message> messages) throws LlmException {
        // 0. 入口校验:既有历史的工具交换必须完整合法(相邻、角色正确、数量/顺序/ID 一一对应)。
        //    在第一次模型调用前拒绝,避免把非法历史发给模型。
        ToolExchangeProtocol.validateHistory(messages);

        // 0.1 启用压缩器或记忆时,捕获本次 run 的"人类请求"(入口校验也在这里完成:非法入口在
        //     任何模型调用与工具副作用之前抛出)。未启用路径保持旧行为,不施加这个形状限制。
        final String activeRequest = compactor == null && memory == null
                ? null : ContextCompactor.extractActiveRequest(messages);

        // 0.2 原课程每次用户任务召回一次。工具结果 / Stop 续跑 / 超限重试仍属于同一个任务，
        // 不重复选择，也不拿它们的 USER 角色误当成用户新需求。背景只在本次 run 的 system 中。
        final String taskSystem = memory == null ? system : memory.prepareSystem(system, activeRequest);

        int roundsSinceTodo = 0; // 连续几轮没用 todo_write;每条用户消息(每次 run)从 0 重计

        // 本次正式请求已经用掉的补救次数。它是 run 的局部变量(不是实例字段),所以:
        //   - 不跨 run 继承;每个新 run、每个新的正式请求都从 0 开始;
        //   - 正式请求一旦成功就归零,下一次正式请求又是一次新的机会。
        int reactiveRetries = 0;

        while (true) {
            // 1. 正式请求之前的自动整理(只在这条循环启用了压缩器时执行)。位置的三条理由:
            //    ① 必须在 client.complete 之前,这次正式请求才能看到整理后的历史;
            //    ② 不能放进下面逐条执行工具的 for 里——那时同批结果还没回灌,历史是"有调用没结果"
            //       的半个交换,就地裁剪会丢掉"已经执行过什么"的记录,模型可能重复副作用;
            //    ③ 每次 while 迭代都经过这里,所以首次请求、普通工具结果之后的请求、Stop hook 强制
            //       续跑之后的请求都同样受保护,而不是只在 run 入口或成功 compact 之后整理一次。
            //    与手动压缩的分工一致:循环决定"何时"(请求边界),compactor 决定"如何整理"。
            //    自动整理面向的是预算与历史规模,不判断业务阶段,也不等于每轮都会摘要——
            //    前两层是确定性检查,摘要仍是压不下去时的最后手段。
            if (compactor != null) {
                List<Message> prepared = compactor.prepare(messages, activeRequest);
                // 采用候选的规则与手动压缩一致:先拿到候选,只有它正常返回之后才动会话。
                // 两个失败边界要分清,别混成"整理失败会回滚一切":
                //   ① prepare 自己没正常返回(归档 / 摘要失败)时根本走不到这里——那次整理的部分候选
                //      一律不采用,内存会话保持进入 prepare 时的状态;
                //   ② 一旦在这里采用成功,随后正式请求失败也<b>不会</b>撤销已经采用的历史,
                //      更不会抹掉已成功写出的归档 / 落盘结果或已经发生的工具副作用。
                // 本类不做回滚,也不声称能回滚整个 run。
                // 判引用是必需的:自动整理允许 no-op,返回入参本身;这时先 clear() 会把要保留的
                // 内容一起清掉,再 addAll(自己)也救不回来。
                // 也不能写成 messages = prepared:那只是给局部变量换个引用,调用方持有的那个列表
                // 不会被同步,跨轮会话、持久化与失败恢复会和整理后的历史分叉。
                if (prepared != messages) {
                    messages.clear();
                    messages.addAll(prepared);
                }
            }

            // 2. 组装 CompleteRequest 并调用获取 LLM 回复。
            //    这是整条循环里唯一被 try/catch 包住的地方,而且只 catch 这一个正式调用抛出的
            //    LlmException:prepare 的整理、工具执行、hooks,以及各种摘要调用都不在捕获范围内——
            //    它们各自的失败按原来的语义直接向上抛,不会被误当成"这次正式请求超限"而去补救。
            ModelResponse response;
            try {
                response = client
                        .complete(new CompleteRequest(taskSystem, messages, maxTokens, List.copyOf(tools.values())));
            } catch (LlmException error) {
                // 2.1 失败分流:只有"看起来是上下文过长"、且这次正式请求还有额度,才值得付一次有损压缩。
                //     compactor == null(五参旧路径)不补救;命中不了分类的失败(认证 401 / 限流 429 /
                //     网络中断 / 普通 400)也不补救——压缩改变不了它们的根因,只会白白损伤上下文。
                //     关键词判定本身只是近似:服务端换个措辞会漏判、说明里恰好引用这两个词会误判,
                //     所以这里只把它当"值不值得试一次"的开关,不声称穷尽了所有真实超限。
                //     额度用尽同样直接抛出,而且抛的是<b>当前这个</b>异常对象:不包装、不无限重试。
                if (compactor == null || !ContextCompactor.isContextOverflow(error)
                        || reactiveRetries >= ContextCompactor.MAX_REACTIVE_RETRIES) {
                    throw error;
                }

                // 2.2 先消耗额度。即使下面拿到的是 no-op 也算"已经试过一次"——事实如此,不能因为
                //     没有变化就不计数,否则同一层 while 会无限 continue 下去。
                reactiveRetries++;

                // 2.3 只造候选:压缩器负责"旧前缀摘要 + 原样保留近期尾部",它不捕获错误、不发请求、
                //     不管重试计数;归档或摘要失败会在这里原样抛出(不会被上面那个 catch 接住)。
                List<Message> recovered = compactor.reactiveCompact(messages, activeRequest);

                // 2.4 采用规则与自动整理、手动压缩一致:返回入参本身 = 没变化(比如空前缀 no-op),
                //     这时先 clear() 会把要保留的内容一起清掉;只有换了列表才原地 clear + addAll,
                //     调用方持有的会话列表身份始终不变。
                if (recovered != messages) {
                    messages.clear();
                    messages.addAll(recovered);
                }

                // 2.5 回到循环顶部重新走一遍:自动整理 → 组装正式请求。
                //     这里刻意<b>不</b>把失败当成一轮:失败的调用没有拿到主任务回复,所以不追加 assistant、
                //     不执行工具、不推进 reminder 计数、不触发 Stop——重试的是"同一次正式请求"。
                continue;
            }
            // 2.6 正式请求成功,本次额度归零:下一次正式请求又是新的一次机会(不是整个 run 只许补救一次)。
            reactiveRetries = 0;

            // 3. 从 response.message().content() 收集 ToolUseBlock,并在追加 assistant 消息
            //    与执行任何工具之前拒绝重复调用 ID:失败不留半截 assistant 消息,也无工具副作用
            List<ToolUseBlock> toolCalls = new ArrayList<>();

            for (ContentBlock c : response.message().content()) {
                if (c instanceof ToolUseBlock u) {
                    toolCalls.add(u);
                }
            }
            ToolExchangeProtocol.requireDistinctCallIds(toolCalls);

            // 4. 追加 assistant 消息
            messages.add(response.message());

            // 5. 没有 tool_use, 此轮结束
            if (toolCalls.isEmpty()) {
                String force = hooks.trigger(Hooks.STOP, Map.of(Hooks.PARAM_MESSAGES, messages));
                if (force != null) {
                    messages.add(Message.userText(force));
                    continue;
                }
                // Stop 不要求续跑才算真正完成。辅助提取回复不能加入主历史、更不能交给工具分发。
                if (memory != null) {
                    memory.finishTask(messages);
                }
                return;
            }

            // 6. 调用 tool。本批的"是否请求压缩"标志是 run 循环里的局部变量,每批重新初始化:
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

            // 7. 全部结果合成一条 user 消息:一个 ToolResultBlock(u.id(), output) 对应一个 ToolUseBlock。
            //    到这一行为止,本批的工具交换才"闭合":每个 tool_use 都有结果,执行记录完整。
            messages.add(Message.userBlocks(results));

            // 8. 批次闭合之后,才处理本批的压缩请求。为什么必须等在这里:如果在上面的 for 里就地裁剪,
            //    同批其它工具的结果还没回灌,会拆开工具交换、丢掉已发生的副作用记录,而且 results
            //    也还没进历史。
            if (compactRequested) {
                List<Message> compacted = compactor.compactAfterBatch(toolCalls, messages, activeRequest);
                // 全部压缩步骤都成功返回之后,才把候选"采用"到调用方持有的这个列表上。
                // 判引用是必需的:压缩器在无变化时会返回入参本身;若这时先 clear() 再 addAll(自己),
                // 会先把历史清空,然后往"已经空了的那个列表"里添加它自己,历史就丢了。
                // 为什么不用 messages = compacted 重绑局部变量:本次 run 后续的 while 循环确实会用
                // 那个新引用,所以下一次正式请求也能看到压缩候选;但调用方手里仍是旧列表,它的会话继续、
                // 持久化与失败恢复视角都没有同步,会与压缩后的历史分叉。原地 clear + addAll 的目的
                // 正是同步调用方会话,并保留外层列表的对象身份。
                if (compacted != messages) {
                    messages.clear();
                    messages.addAll(compacted);
                }
            }
        }
    }
}
