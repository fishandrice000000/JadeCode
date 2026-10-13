package com.jadecode.context;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.jadecode.llm.LlmClient;
import com.jadecode.llm.LlmException;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;

/**
 * ContextCompactor -- 上下文压缩的<b>总入口</b>:固定四层管线与对外 API。
 *
 * <p>
 * 真正的算法不在本类,而在五个职责明确的策略类里;本类只负责按顺序编排它们,
 * 并为调用方保留一层<b>薄委托</b>(不是第二份实现)。对外(loop 集成)可见的入口只有
 * {@link #prepare}、{@link #toolResultBudget}、{@link #compactAll}、{@link #compactHistory}、
 * {@link #compactAfterBatch}、{@link #reactiveCompact}、{@link #extractActiveRequest} 与
 * {@link #isContextOverflow};
 * 其余层与工程委托收敛为包内可见,供同包测试直接调用。
 * </p>
 *
 * <table border="1">
 * <caption>层与 owner</caption>
 * <tr>
 * <th>层</th>
 * <th>owner</th>
 * </tr>
 * <tr>
 * <td>第一层 最新结果批次预算</td>
 * <td>{@link ToolResultBudgetCompactor}</td>
 * </tr>
 * <tr>
 * <td>第二层 归档中段</td>
 * <td>{@link SnipCompactor}</td>
 * </tr>
 * <tr>
 * <td>第三层 A 缩短已消费结果</td>
 * <td>{@link MicroCompactor}</td>
 * </tr>
 * <tr>
 * <td>第三层 B 兜底落盘超大结果</td>
 * <td>{@link ToolResultFitter}</td>
 * </tr>
 * <tr>
 * <td>第四层 事实摘要</td>
 * <td>{@link HistoryCompactor}</td>
 * </tr>
 * </table>
 *
 * <p>
 * 共享的工程能力各有唯一实现,不复制到多个类:{@link ToolOutputStore}(工具结果落盘与预览)、
 * {@link TranscriptStore}(归档与归档标记)、{@link ToolExchangeBoundary}(配对判定与安全切点)、
 * {@link ContextSerialization}(序列化与字符估算)。
 * </p>
 *
 * <h2>三条不可动摇的约束</h2>
 * <ul>
 * <li><b>不可变</b>:<b>不修改输入</b>——任何方法都不得修改传入的列表、{@link Message} 或其中的 block 列表。
 * <b>无变化:返回输入列表本身</b>;<b>有变化:重建列表</b>,并且只重建发生变化的 message / block。
 * 调用方用 clear + addAll 把结果灌回自己持有的外层列表,以保留那个列表的身份——
 * <b>替换前必须先判"是不是同一个列表"</b>,否则无变化时会先把自己清空再灌回来。</li>
 * <li><b>工具交换是原子边界</b>:任何切点都不得落在 assistant(tool_use) 与其后的
 * user(tool_result) 之间,也不得制造孤立 result。判定集中在 {@link ToolExchangeBoundary},
 * 三处切点共用,不各写一套。</li>
 * <li><b>失败语义</b>:落盘必须成功才替换上下文——建目录或写文件失败要带 cause 明确抛出,
 * 绝不返回不存在的恢复路径,也绝不悄悄丢弃原结果。反过来,识别"可信 marker"时是 fail-closed:
 * 无法判定就当作不可信、重新落盘,最坏多一个文件,不丢数据。</li>
 * </ul>
 *
 * <p>
 * 本类<b>没有可变会话状态</b>,父子循环可以安全共享同一个实例。
 * </p>
 */
public final class ContextCompactor {

    /** 响应式补救的全局上限:同一次正式模型调用最多补救这么多次 */
    public static final int MAX_REACTIVE_RETRIES = 1;

    /**
     * compact 工具名:手动批次分类只认这个字符串值(与 {@code tools.CompactTool#name()} 一致)。
     *
     * <p>
     * 这里刻意<b>不</b>从 CompactTool 共享常量:本类只做字符串值比较,不为共享一个名字而给工具类
     * 增加对外依赖或引入枚举;值一旦与工具名不符,手动压缩测试会立刻失败。
     * </p>
     */
    private static final String COMPACT_TOOL = "compact";

    /** 阈值集合 */
    private final CompactionLimits limits;

    /** 工具结果落盘与预览 */
    private final ToolOutputStore toolOutputStore;

    /** transcript 归档与归档标记识别 */
    private final TranscriptStore transcriptStore;

    /** 第一层:最新结果批次预算 */
    private final ToolResultBudgetCompactor toolResultBudgetCompactor;

    /** 第二层:归档中段 */
    private final SnipCompactor snipCompactor;

    /** 第三层 A:缩短已消费的旧结果 */
    private final MicroCompactor microCompactor;

    /** 第三层 B:兜底落盘超大结果 */
    private final ToolResultFitter toolResultFitter;

    /** 第四层:事实摘要 */
    private final HistoryCompactor historyCompactor;

    /**
     * 生产装配用课程默认阈值。
     *
     * @param client         LLM 客户端,仅摘要层使用
     * @param transcriptDir  transcript 归档目录
     * @param toolResultsDir 工具结果落盘目录
     */
    public ContextCompactor(LlmClient client, Path transcriptDir, Path toolResultsDir) {
        this(client, transcriptDir, toolResultsDir, CompactionLimits.courseDefaults());
    }

    /**
     * 按注入的依赖组装共享工程能力与五个策略;不引入隐藏全局实例或可变会话状态。
     *
     * @param client         LLM 客户端,仅摘要层使用
     * @param transcriptDir  transcript 归档目录
     * @param toolResultsDir 工具结果落盘目录
     * @param limits         阈值集合(测试注入小阈值)
     */
    public ContextCompactor(LlmClient client, Path transcriptDir, Path toolResultsDir, CompactionLimits limits) {
        this.limits = limits;
        this.toolOutputStore = new ToolOutputStore(toolResultsDir);
        this.transcriptStore = new TranscriptStore(transcriptDir);
        this.toolResultBudgetCompactor = new ToolResultBudgetCompactor(limits, toolOutputStore);
        this.snipCompactor = new SnipCompactor(limits, transcriptStore);
        this.microCompactor = new MicroCompactor(limits, toolOutputStore);
        this.toolResultFitter = new ToolResultFitter(limits, toolOutputStore);
        this.historyCompactor = new HistoryCompactor(limits, client, transcriptStore);
    }

    /** @return 本实例的阈值集合(装配与测试断言用) */
    public CompactionLimits limits() {
        return limits;
    }

    /* ===================== 总入口 ===================== */

    /**
     * 每次正式模型请求前执行的固定四层管线。
     *
     * <p>
     * 第一、二层每轮都跑(确定性、可恢复、不调模型);只有仍超上限才进第三层;
     * 只有第三层也没压下去才进第四层(唯一会发起额外 API 调用的一层)。
     * </p>
     *
     * <p>
     * TODO(s08-user): 按上述顺序串起四个方法,并在每层之后重新估算。
     * </p>
     *
     * @param messages      当前会话消息
     * @param activeRequest 入口捕获的本次请求,写进摘要消息的 Current user request 分区
     * @return 压缩后的消息列表
     * @throws LlmException 摘要调用失败;原样向上抛,不伪装成压缩成功
     */
    public List<Message> prepare(List<Message> messages, String activeRequest) throws LlmException {
        /**
         * 第一层, 工具调用如果因为有超大结果而导致这批结果长度过长, 那么转存这些大结果, 留下路径和预览
         * 如果转存完某个大结果后, 长度不超限了, 那么就不再转存后续的大结果
         */
        List<Message> budgetedMessages = toolResultBudget(messages);

        // 第二层, 将早期消息和近期消息传回, 中间消息归档
        List<Message> snippedMessages = snipCompact(budgetedMessages);

        // 经过一二层处理, 如果消息仍然过大, 继续压缩
        if (smallEnough(snippedMessages)) {
            return snippedMessages;
        }

        // 第三层 A, 转存已看过的旧结果
        List<Message> microCompactedMessages = microCompact(snippedMessages, limits.targetChars());

        // 经过第三层 A 处理, 如果消息仍然过大, 继续压缩
        if (smallEnough(microCompactedMessages)) {
            return microCompactedMessages;
        }

        // 第三层 B, 兜底处理, 尝试转存所有之前在第一层未转存的大结果, 直到消息长度不超限
        List<Message> fittedMessages = fitToolResults(microCompactedMessages, limits.targetChars());

        // 经过第三层 B 处理, 如果消息仍让过大, 交给 LLM 生成摘要
        if (smallEnough(fittedMessages)) {
            return fittedMessages;
        }

        // 第四层, 交给 LLM 生成摘要
        return compactHistory(fittedMessages, activeRequest);
    }

    private boolean smallEnough(List<Message> messages) {
        return estimateChars(messages) <= limits.contextCharLimit();
    }

    /* ===================== 手动批次压缩入口 ===================== */

    /**
     * 把"模型主动请求压缩"的一次批次动作,落到具体的压缩策略上。
     *
     * <p>
     * <b>它应对的需求与场景</b>:自动机制关注的是预算与历史规模——第一层的结果预算与第二层的 snip
     * 每次进入都会检查,只有后续层才受整体大小判断约束;它<b>不</b>判断业务阶段。而模型可以在长任务里
     * 依据进度判断"某些探索记录不必再常驻",于是显式调用无副作用的 {@code compact} 工具请求收束历史。
     * 父循环等这一批工具交换<b>全部闭合</b>后,把本批的调用集合交给本方法,由本方法挑一条压缩路径。
     * </p>
     *
     * <p>
     * <b>这只是可选调节,不是事实或安全保证</b>:模型并不准确知道 token 预算、上下文是否已经满了、
     * 某个阶段是否结束或用户任务是否完成;主动摘要本身也有一次模型调用成本与信息损失,不承诺一定改善
     * 推理或降低总成本,需要按场景取舍。harness 仍负责预算约束、自动整理的接入与安全执行时机,
     * 主动请求不取代这些保障(请求前的 {@link #prepare} 已经在父循环显式注入压缩器时自动执行,
     * 但 Main 与子循环尚未接入,所以它不是生产 CLI 默认就已具备的保障)。
     * </p>
     *
     * <p>
     * <b>为什么在总入口(compactor)而不是在循环里</b>:纯 / 混合的分法是一种<b>信息保护取舍</b>,
     * 属于压缩策略——它回答"哪些历史可以牺牲、哪些刚产生的证据必须留住"。循环只负责
     * <b>时机与生命周期</b>(工具真的执行成功、整批结果回灌闭合、采用新历史继续跑)。职责分开后,
     * 循环不必知道归档、落盘与摘要范围;本类也不必知道 hook、权限闸门、工具表或会话对象。
     * </p>
     *
     * <p>
     * <b>为什么必须等批次闭合才执行</b>:模型同一条回复里可能既有 compact 也有普通工具调用。
     * 在工具执行中间裁剪历史,同批其它工具的结果还没回灌,会留下"有调用没结果"的半个交换,
     * 甚至删掉已经发生过的文件写入记录,让模型重复副作用。所以本方法的前置条件是
     * <b>调用方已经回灌完整批结果</b>;本方法不执行工具、不触发 hook、不确认权限,也不回灌结果。
     * </p>
     *
     * <p>
     * <b>分类与两条策略</b>:按本批<b>全部</b> {@code tool_use} 的名字判断,不看执行成功的子集、
     * 不看结果正文,也不看结果消息里是否混入 reminder 文本块——分类依据是模型实际提出的调用集合。
     * </p>
     * <ul>
     * <li><b>纯 compact 批次</b>(每个调用都是 {@code compact}):这个分类唯一的事实是本批调用名<b>全为</b>
     * compact,所以没有别的业务工具新产生的、需要优先保护的结果,于是 {@link #compactAll} 把整段历史
     * 换成一条摘要。<b>不</b>从这个信号推断阶段已完成或任务已被验收;混合策略不受影响。</li>
     * <li><b>混合批次</b>(还含别的工具):本批可能有主任务模型<b>还没读过</b>的新证据,所以先
     * {@link #toolResultBudget} 把超大的新鲜结果换成可恢复的"路径 + 预览"(候选),再让
     * {@link #compactHistory} 只摘要<b>旧前缀</b>,最新那条闭合交换按原引用留在摘要后面。</li>
     * </ul>
     *
     * <p>
     * <b>替代设计的得失</b>:也可以让循环自己判断纯 / 混合,或让本方法接收一个"模式"参数;但那样会把
     * 压缩取舍的细节复制到调用方,还多一个可被误用的开关。当前只暴露这一个入口,策略变化集中在
     * compactor 内部;代价是本类需要知道 {@code compact} 这个工具名。
     * </p>
     *
     * <p>
     * <b>不可变与失败语义</b>:与其它入口一致——只返回<b>候选</b>,不修改传入的列表、{@link Message}
     * 或其中的 block;<b>无变化时返回入参本身</b>。"采用候选"(clear + addAll 写回调用方列表)是
     * <b>调用方的职责</b>,且只在本方法正常返回之后才做。落盘 / 归档失败(带 cause 的
     * {@code IllegalStateException})与摘要失败({@link LlmException})都<b>原样传播</b>:不吞错、
     * 不伪造摘要、不重跑已经完成的工具。混合路径里的预算结果只是候选——即使落盘文件已经写出,
     * 摘要失败也不会把历史半替换成预览。
     * </p>
     *
     * <p>
     * <b>前置条件由调用方保证</b>(本方法不做全历史扫描或参数校验):本批确有"真实执行成功"的
     * compact 请求、整批结果已回灌闭合、{@code toolCalls} 非空且就是本批全部调用,
     * 且 {@code activeRequest} 是入口捕获的本次人类请求。
     * </p>
     *
     * @param toolCalls     本批全部 tool_use(用于纯 / 混合分类)
     * @param messages      已经闭合的当前历史
     * @param activeRequest 入口捕获的本次请求;两条策略都会把它写进摘要消息
     * @return 压缩候选;可能与 {@code messages} 是同一个列表对象,表示"没有可安全压缩的部分"
     * @throws LlmException          摘要调用失败,原样向上传播
     * @throws IllegalStateException 落盘 / 归档失败(带 cause)
     */
    public List<Message> compactAfterBatch(List<ToolUseBlock> toolCalls, List<Message> messages,
            String activeRequest) throws LlmException {
        // 1. 纯 / 混合分类:只要出现一个不是 compact 的调用,本批就算混合批次。
        //    这里只看调用名,不看它有没有被拦截、有没有执行成功,也不扫描结果正文。
        boolean pureCompactBatch = true;
        for (ToolUseBlock u : toolCalls) {
            if (!COMPACT_TOOL.equals(u.name())) {
                pureCompactBatch = false;
                break;
            }
        }

        // 2. 纯 compact:本批没有新鲜业务数据,整段历史换成一条摘要(内部会先归档再摘要)。
        if (pureCompactBatch) {
            return compactAll(messages, activeRequest);
        }

        // 3. 混合批次:先把最新那条闭合交换里的超大新鲜结果变成可恢复的"路径 + 预览"(候选),
        //    再让摘要只覆盖它之前的前缀——刚生成、模型还没读过的结果不能被先转述进摘要而失真。
        //    两步都复用既有层;不调用整套 prepare,也不在这里重复实现归档或摘要逻辑。
        List<Message> budgeted = toolResultBudget(messages);
        return compactHistory(budgeted, activeRequest);
    }

    /* ===================== 第一层:最新结果批次预算 ===================== */

    /**
     * 委托 {@link ToolResultBudgetCompactor#toolResultBudget};契约见该处。
     *
     * @param messages 消息列表
     * @return 处理后的列表;无需处理时原样返回入参
     */
    public List<Message> toolResultBudget(List<Message> messages) {
        return toolResultBudgetCompactor.toolResultBudget(messages);
    }

    /**
     * 委托 {@link ToolResultBudgetCompactor#persistLargeOutput};契约见该处。
     *
     * @param toolUseId 工具调用 ID
     * @param output    完整原文
     * @return 原文,或落盘预览外壳
     */
    String persistLargeOutput(String toolUseId, String output) {
        return toolResultBudgetCompactor.persistLargeOutput(toolUseId, output);
    }

    /* ===================== 第二层:归档中段 ===================== */

    /**
     * 委托 {@link SnipCompactor#snipCompact};契约见该处。
     *
     * @param messages 消息列表
     * @return 裁剪后的列表;无需或无法裁剪时原样返回入参
     */
    List<Message> snipCompact(List<Message> messages) {
        return snipCompactor.snipCompact(messages);
    }

    /* ===================== 第三层:micro 与 fit ===================== */

    /**
     * 委托 {@link MicroCompactor#microCompact};契约见该处。
     *
     * @param messages    消息列表
     * @param targetChars 目标字符数;null = 不做目标检查
     * @return 处理后的列表;无改动时原样返回入参
     */
    List<Message> microCompact(List<Message> messages, Integer targetChars) {
        return microCompactor.microCompact(messages, targetChars);
    }

    /**
     * 委托 {@link ToolResultFitter#fitToolResults};契约见该处。
     *
     * @param messages    消息列表
     * @param targetChars 目标字符数
     * @return 处理后的列表;无改动时原样返回入参
     */
    List<Message> fitToolResults(List<Message> messages, int targetChars) {
        return toolResultFitter.fitToolResults(messages, targetChars);
    }

    /* ===================== 第四层:摘要 ===================== */

    /**
     * 委托 {@link HistoryCompactor#compactAll};契约见该处。
     *
     * @param messages      消息列表
     * @param activeRequest 入口捕获的本次请求
     * @return 只含一条摘要消息的新列表
     * @throws LlmException 摘要调用失败
     */
    public List<Message> compactAll(List<Message> messages, String activeRequest) throws LlmException {
        return historyCompactor.compactAll(messages, activeRequest);
    }

    /**
     * 委托 {@link HistoryCompactor#compactHistory};契约见该处。
     *
     * @param messages      消息列表
     * @param activeRequest 入口捕获的本次请求
     * @return 压缩后的列表
     * @throws LlmException 摘要调用失败
     */
    public List<Message> compactHistory(List<Message> messages, String activeRequest) throws LlmException {
        return historyCompactor.compactHistory(messages, activeRequest);
    }

    /**
     * 委托 {@link HistoryCompactor#summarizeHistory};契约见该处。
     *
     * @param messages 需要摘要的消息(调用方负责只传该摘要的那一段)
     * @return 摘要文本;模型给出空摘要时用稳定兜底文案
     * @throws LlmException 调用失败
     */
    String summarizeHistory(List<Message> messages) throws LlmException {
        return historyCompactor.summarizeHistory(messages);
    }

    /**
     * 委托 {@link HistoryCompactor#summaryInput};契约见该处。
     *
     * @param messages 消息列表
     * @return 序列化后的摘要输入
     */
    String summaryInput(List<Message> messages) {
        return historyCompactor.summaryInput(messages);
    }

    /* ===================== 持久化 ===================== */

    /**
     * 委托 {@link TranscriptStore#writeTranscript};契约见该处。
     *
     * @param messages 消息列表
     * @return 实际写出的文件路径
     * @throws IllegalStateException 建目录、写文件或序列化失败(带 cause)
     */
    Path writeTranscript(List<Message> messages) {
        return transcriptStore.writeTranscript(messages);
    }

    /**
     * 委托 {@link ToolOutputStore#saveOutput};契约见该处。
     *
     * @param toolUseId 工具调用 ID
     * @param output    完整原文
     * @return 实际写出的文件路径
     * @throws IllegalStateException 建目录或写文件失败(带 cause)
     */
    Path saveOutput(String toolUseId, String output) {
        return toolOutputStore.saveOutput(toolUseId, output);
    }

    /**
     * 委托 {@link ToolOutputStore#persistedOutputPath};契约见该处。
     *
     * @param output 工具结果文本
     * @return 可信的绝对路径字符串;不可信或无法判定时为 null
     */
    String persistedOutputPath(String output) {
        return toolOutputStore.persistedOutputPath(output);
    }

    /**
     * 委托 {@link ToolOutputStore#persistedPreview};契约见该处。
     *
     * @param toolUseId    工具调用 ID
     * @param output       完整原文
     * @param previewChars 预览保留的字符数
     * @return 落盘预览外壳文本
     */
    String persistedPreview(String toolUseId, String output, int previewChars) {
        return toolOutputStore.persistedPreview(toolUseId, output, previewChars);
    }

    /**
     * 委托 {@link TranscriptStore#isArchiveMarker};契约见该处。
     *
     * @param message 候选消息
     * @return true = 可信的归档标记
     */
    boolean isArchiveMarker(Message message) {
        return transcriptStore.isArchiveMarker(message);
    }

    /* ===================== 估算与判定 ===================== */

    /**
     * 委托 {@link ContextSerialization#estimateChars};契约见该处。
     *
     * @param messages 消息列表
     * @return JSON 文本的字符数
     */
    static int estimateChars(List<Message> messages) {
        return ContextSerialization.estimateChars(messages);
    }

    /**
     * 委托 {@link ToolExchangeBoundary#hasToolUse};契约见该处。
     *
     * @param message 一条消息
     * @return true = 含 tool_use 的 assistant 消息
     */
    static boolean hasToolUse(Message message) {
        return ToolExchangeBoundary.hasToolUse(message);
    }

    /**
     * 委托 {@link ToolExchangeBoundary#isToolResult};契约见该处。
     *
     * @param message 一条消息
     * @return true = 含 tool_result 的 user 消息
     */
    static boolean isToolResult(Message message) {
        return ToolExchangeBoundary.isToolResult(message);
    }

    /**
     * 委托 {@link ToolExchangeBoundary#isClosedToolExchange};契约见该处。
     *
     * @param messages       消息列表
     * @param assistantIndex 候选 assistant 消息下标
     * @return true = 闭合的工具交换
     */
    static boolean isClosedToolExchange(List<Message> messages, int assistantIndex) {
        return ToolExchangeBoundary.isClosedToolExchange(messages, assistantIndex);
    }

    /**
     * 委托 {@link ToolExchangeBoundary#safeTailStart};契约见该处。
     *
     * @param messages  消息列表
     * @param tailStart 候选尾部起点
     * @return 修正后的尾部起点
     */
    static int safeTailStart(List<Message> messages, int tailStart) {
        return ToolExchangeBoundary.safeTailStart(messages, tailStart);
    }

    /**
     * 委托 {@link ToolExchangeBoundary#safeHeadEnd};契约见该处。
     *
     * @param messages 消息列表
     * @param headEnd  候选头部末端(不含)
     * @return 修正后的头部末端
     */
    static int safeHeadEnd(List<Message> messages, int headEnd) {
        return ToolExchangeBoundary.safeHeadEnd(messages, headEnd);
    }

    /* ===================== active request 与响应式判定 ===================== */

    /**
     * 响应式补救:正式请求被 API 以"上下文过长"拒绝后,构建<b>下一次重试要用</b>的新历史
     * ——"旧前缀的事实摘要 + 原样保留的近期尾部"。
     *
     * <p>
     * <b>它在 Agent 失败恢复链路里的位置</b>:调用方的顺序是"正式请求抛 {@link LlmException}
     * → {@link #isContextOverflow} 判定值得补救 → 本方法构建新历史 → 用同一段新历史重试<b>同一次</b>
     * 正式请求(最多一次)"。所以本方法<b>不</b>捕获 API 错误、<b>不</b>发正式请求、<b>不</b>管重试计数:
     * 它只负责把"补救后的历史"造出来;唯一的模型调用是生成前缀摘要。
     * <b>目前这条链路已由父循环实施</b>(显式注入压缩器时,正式请求的窄 catch 里判定、调用本方法并重新请求);
     * Main 与子循环尚未接入,所以它不是生产 CLI 默认就走的路径。
     * </p>
     *
     * <p>
     * <b>与请求前的 {@link #prepare} 有什么不同</b>:prepare 每次正式请求<b>之前</b>按本地字符估算
     * 做确定性整理(必要时才自动摘要),目标是"通常别超限";本方法是服务端<b>已经</b>拒绝之后的恢复动作,
     * 它不会再去跑那些确定性的层,而是直接砍掉较早的历史、只留最近若干条原样尾部 + 一条摘要。
     * 也可以理解为:prepare 是平时保养,reactiveCompact 是故障后的自救。
     * </p>
     *
     * <p>
     * <b>切点如何保护工具交换</b>:候选尾部起点是
     * {@code max(0, size - limits.keepRecentMessages())}(默认保留最近 5 条),再交给
     * {@link ToolExchangeBoundary#safeTailStart} 修正——如果这个切点正好落在
     * "assistant(tool_use) / user(tool_result)"这一对的中间,就把起点往前挪一条,把<b>整对</b>交换
     * 拉进尾部(于是可能保留 6 条)。这样新历史里不会出现"有调用没结果"或"有结果没调用"的半个交换。
     * </p>
     *
     * <p>
     * <b>两个范围为什么不一样</b>:归档范围是<b>整段</b>历史(含尾部),摘要范围只是<b>前缀</b>
     * {@code [0, tailStart)}。尾部是模型还没读到的最新结果,一旦被转述进摘要,细节在进入模型之前就失真了;
     * 而归档要能回查"这次补救之前到底有什么",所以必须完整。
     * </p>
     *
     * <p>
     * <b>空前缀是保真优先的 no-op</b>:如果安全起点是 0(空历史、消息数不足或恰好等于保留数、
     * 或切点前移到 0),说明整段历史都属于"要保护的尾部",没有可以安全裁掉的前缀——此时直接返回入参本身,
     * 零归档、零摘要调用,不生成空摘要、也不退化成整段摘要。这只表示"没有可安全压缩的空间",
     * <b>不</b>表示超限问题已经解决;后续循环仍然只能补救一次,不能因为本方法返回原列表就无限重试。
     * </p>
     *
     * <p>
     * <b>失败语义与不可变性</b>:先成功写出 transcript,再调用摘要——归档失败会带 cause 抛出,
     * 此时一次模型调用都不会发生;摘要抛 {@link LlmException} 时原样传播(不包装、不吞掉,
     * 也<b>不</b>用假摘要继续,否则调用方会以为补救成功),已经写好的归档保留。
     * 所有路径都不修改输入的外层列表、Message、content 或 block:无变化时返回入参本身,
     * 有变化时新建外层列表,尾部消息按原顺序、原引用接在摘要后面。
     * 本方法<b>不</b>保证结果一定更短或一定低于服务端的真实 token 限额,也不会重复压缩自己。
     * </p>
     *
     * @param messages      消息列表
     * @param activeRequest 入口捕获的本次请求
     * @return 有可裁前缀时是 {@code [响应式摘要消息, 原尾部消息...]};空前缀时是入参本身
     * @throws IllegalStateException 写 transcript 失败(带 cause);此时不会调用模型
     * @throws LlmException          摘要调用失败;已写好的归档保留
     */
    public List<Message> reactiveCompact(List<Message> messages, String activeRequest) throws LlmException {
        // 1. 先算保护尾部的起点:先按"保留最近 keepRecentMessages 条"取候选,
        //    再让安全切点助手把跨界的工具交换整对拉进尾部(必要时前移一条)。
        int candidateTailStart = Math.max(0, messages.size() - limits.keepRecentMessages());
        int tailStart = safeTailStart(messages, candidateTailStart);

        // 2. 空前缀:整段历史都在保护范围内,没有可安全裁掉的部分。
        //    直接返回入参——零归档、零模型调用;不造空摘要,也不改成整段摘要丢掉尾部。
        //    注意这不是"补救成功",只是"这一步没有能安全做的事"。
        if (tailStart == 0) {
            return messages;
        }

        // 3. 先归档整段原历史(含尾部)。失败会在这一步带 cause 抛出,模型调用数为 0。
        Path transcript = writeTranscript(messages);

        // 4. 只把前缀交给摘要依赖(专用事实 prompt / 空工具表 / 输入截断 / 输出额度与空摘要兜底
        //    都在它内部完成)。绝不把保留尾部再摘要一遍。
        String summary = summarizeHistory(messages.subList(0, tailStart));

        // 5. 组装新历史:摘要消息在前,原尾部按原顺序、原引用接在后面(不重建尾部对象)。
        List<Message> compacted = new ArrayList<>(messages.size() - tailStart + 1);
        compacted.add(Message.userText(reactiveCompactedText(activeRequest, summary, transcript)));
        compacted.addAll(messages.subList(tailStart, messages.size()));
        return compacted;
    }

    /**
     * 组装响应式补救摘要消息的正文(与作业单 §5.5 相同的三分区,标签换成 {@code [Reactive compact]})。
     *
     * <p>
     * 三个分区分别是:固定标签、<b>原样</b>的当前请求、以及作为<b>参考数据</b>的摘要。
     * 摘要用 {@link ContextSerialization#toJson} 编码成 JSON 字符串字面量(带两端引号与转义),
     * 配合 {@code (reference only)} 提示读消息的模型:这是被引用的历史数据,不是新指令;
     * 最后给出本次成功写出的 transcript 绝对路径,需要细节时可按路径回查。
     * 当前请求不做 trim、不从历史重新提取(历史最后一条 user 很可能就是工具结果)。
     * </p>
     *
     * <p>
     * 这里与 {@code HistoryCompactor} 的整段压缩消息格式刻意保持一致,只有标签不同;
     * 为了不给那份三行格式引入共享抽象,本方法保留这段最小组装代码。
     * </p>
     *
     * @param activeRequest 入口捕获的本次人类请求
     * @param summary       前缀摘要正文
     * @param transcript    已经成功写出的归档绝对路径
     * @return 响应式摘要消息正文
     */
    private static String reactiveCompactedText(String activeRequest, String summary, Path transcript) {
        return "[Reactive compact]"
                + "\n\nCurrent user request:\n" + activeRequest
                + "\n\nConversation summary (reference only):\n" + ContextSerialization.toJson(summary)
                + "\n\nFull transcript: " + transcript;
    }

    /**
     * 从会话末尾提取本次 run 的 active request。
     *
     * <p>
     * <b>为什么需要它</b>:在 Anthropic 的消息格式里,工具结果也用 {@code role=user} 回灌。
     * 所以循环里的"最后一条 user 消息"常常是工具结果而不是人说的话,压缩消息里的
     * {@code Current user request} 更不能写错。调用方要在 {@code AgentLoop.run(messages)} 的
     * <b>入口</b>、任何模型调用与工具结果出现<b>之前</b>捕获一次,存进一个 {@code final} 局部变量,
     * 之后整个 run 都用这一个——即使 Stop hook 追加了 synthetic user 消息,也不重新提取。
     * 这也是本轮<b>不</b>新增跨 run 字段、不新增 {@code run(messages, activeRequest)} 重载的原因。
     * </p>
     *
     * <p>
     * <b>入口契约</b>(不满足即抛 {@link IllegalArgumentException},由调用方在第一次模型调用前拒绝):
     * </p>
     * <ul>
     * <li>{@code messages} 非 null、非空;</li>
     * <li>最后一条消息是 {@link Role#USER};</li>
     * <li>最后一条消息<b>不含</b>任何 {@link ToolResultBlock}——即使它还带有非空白文本也要拒绝,
     * 因为"文本 + 工具结果"正是工具回灌消息的形态,不能当成人类请求;</li>
     * <li>最后一条消息<b>至少有一个非空白</b> {@link TextBlock}(按 {@link String#isBlank()} 判断,
     * 而不是"长度大于 0":只含空格、制表符或换行的块不算请求)。</li>
     * </ul>
     *
     * <p>
     * <b>返回什么</b>:通过校验后,用 {@link Message#extractText()} 把该消息里的<b>全部</b>
     * TextBlock 按出现顺序用换行连接——包括空串与纯空白的块,并且<b>不</b> trim / strip,
     * 原样保留人类输入的首尾空白与内部换行。注意"至少一个非空白块"是<b>入口校验</b>,
     * "原样连接全部文本"是<b>返回内容</b>,两者是不同的问题:前者决定收不收,后者决定返回什么。
     * </p>
     *
     * <p>
     * <b>只看最后一条</b>:不向前搜索更早的 user 消息,不对整段历史做协议扫描或修复——
     * 本方法要回答的是"本次 run 的请求是什么",不是"历史上哪条消息看起来像请求"。
     * 也正因为如此,它是一段<b>纯读取</b>:不修改外层列表、Message、content 或 block,
     * 不产生文件 IO、模型调用或工具副作用,可安全地处理不可修改的列表。
     * </p>
     *
     * @param messages 会话消息列表
     * @return 本次 run 的 active request 文本
     * @throws IllegalArgumentException 列表为 null / 为空、末条为 null 或结构异常(见契约)、
     *                                  或末条不满足上述入口契约
     */
    public static String extractActiveRequest(List<Message> messages) {
        // 1. 列表本身:入口至少要有一条"人类请求"可言。
        if (messages == null) {
            throw new IllegalArgumentException("messages 不能为 null:入口至少需要一条人类请求");
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空:入口至少需要一条人类请求");
        }

        // 2. 末条消息:必须存在、来自 USER,并且 content 不为 null。
        //    Message 是 record,构造器不校验这些字段,所以这里逐个 null 检查,避免后面抛偶发 NPE
        //    (例如 extractText 直接在 null content 上迭代)。
        Message last = messages.getLast();
        if (last == null) {
            throw new IllegalArgumentException("最后一条消息不能为 null");
        }
        if (last.role() != Role.USER) {
            throw new IllegalArgumentException("最后一次请求必须是 USER 消息,实际是 " + last.role());
        }
        if (last.content() == null) {
            throw new IllegalArgumentException("最后一次请求的 content 不能为 null");
        }

        // 3. 逐块检查末条 content:拒绝工具结果与 null 结构,同时记录"是否存在非空白文本"。
        //    发现 tool_result 就立刻拒绝——哪怕同一消息里还有人类文本:混合形态属于工具回灌,
        //    不能因为"顺带有字"就当成请求。
        boolean hasNonBlankText = false;
        for (ContentBlock block : last.content()) {
            if (block == null) {
                throw new IllegalArgumentException("最后一次请求的 content 不能包含 null 块");
            }
            if (block instanceof ToolResultBlock) {
                throw new IllegalArgumentException(
                        "最后一次请求不得包含 tool_result:工具结果也使用 USER 角色,不能当作人类请求");
            }
            if (block instanceof TextBlock text) {
                String value = text.text();
                if (value == null) {
                    throw new IllegalArgumentException("最后一次请求的文本块 text 不能为 null");
                }
                if (!value.isBlank()) {
                    hasNonBlankText = true;
                }
            }
        }

        // 4. 入口校验的第二半:至少要有一个非空白文本块。
        //    空 content、只含非文本块、或只含空串 / 纯空白,都不是人类请求。
        if (!hasNonBlankText) {
            throw new IllegalArgumentException(
                    "最后一次请求至少需要一个非空白文本块(空串、纯空白、null 文本都不算请求)");
        }

        // 5. 通过校验:交给 Message.extractText() 按出现顺序用 "\n" 连接全部 TextBlock,
        //    原样返回(不 trim / strip)。复用这个方法可以保证与项目其它地方"提取文本"的口径一致。
        return last.extractText();
    }

    /**
     * 正式模型调用失败后的分流判定:这次异常是否属于"上下文过长",值得尝试一次压缩补救。
     *
     * <p>
     * <b>它在失败恢复链路里的位置</b>:调用方在 {@code client.complete(...)} 抛 {@link LlmException}
     * 时先问本方法一句——true 才进入"保存 transcript → 只摘要被裁掉的前缀 → 重试<b>同一次</b>模型调用"
     * 的补救流程;false 就按普通失败原样向上抛。<b>目前只有显式注入压缩器的父循环</b>真正走了这条链路
     * (Main 与子循环尚未接入),但判定本身仍是父子共用的一份实现,避免两边各写一套关键词。
     * 本方法<b>只做分类</b>:它不压缩、不重试、不碰消息、不写文件、不保存任何状态。
     * </p>
     *
     * <p>
     * <b>判定依据与限制</b>:只看异常<b>自身</b>的 {@link LlmException#getMessage()},
     * 忽略大小写地做<b>子串</b>匹配,命中 {@code prompt_too_long} 或 {@code too many tokens}
     * 任一即返回 true。子串可以出现在更长的服务端说明里;不做单词边界检查,也不做空格 / 下划线归一化,
     * 更不看 HTTP 状态码、cause 或栈信息——状态码相同的错误原因可能完全不同,不能单独作为依据。
     * 为什么需要它:本地对上下文的字符数估算只是估算,服务端仍然可能拒绝请求;真正"太长"的判定
     * 只有服务端给出。反过来,关键词匹配本身是<b>近似</b>判断:服务端换一种措辞会被漏判(false negative),
     * 说明里恰好引用了这两个词也可能误判(false positive),所以它只是"值不值得试一次"的开关。
     * </p>
     *
     * <p>
     * <b>为什么普通错误不能进压缩</b>:压缩是有损且要额外花一次模型调用的操作——历史会被换成
     * 摘要 + transcript 路径。认证失败(401)、限流(429)、网络中断、普通 400 这类错误,
     * 压缩后重试同样不会成功,却白白损伤了当前上下文;只有"上下文过长"这种"内容变了就可能成功"
     * 的失败才值得付这个代价。
     * </p>
     *
     * <p>
     * <b>true 不等于一定能恢复</b>:补救本身可能失败(压缩后仍然超限、或服务端再次拒绝),
     * 调用方必须按"同一次模型调用最多补救一次"处理,并且不得把补救失败伪装成成功。
     * </p>
     *
     * @param error LLM 调用异常
     * @return true = 说明里出现两个约定关键词之一,值得尝试一次压缩补救;
     *         false = 无法判定(null 异常 / null 说明)或不属于上下文过长
     */
    public static boolean isContextOverflow(LlmException error) {
        // 1. 没有异常、或异常没带说明,就没有可判定的文本 → false(而不是抛异常:
        //    分流判定是为调用方的失败处理服务的,自己不能再制造新的失败)。
        if (error == null) {
            return false;
        }
        String message = error.getMessage();
        if (message == null) {
            return false;
        }

        // 2. 统一转小写后比较,实现"忽略大小写";显式指定 Locale.ROOT,
        //    避免结果随机器默认语言环境变化(某些区域设置下字母的大小写映射并不相同)。
        String normalized = message.toLowerCase(Locale.ROOT);

        // 3. 两个约定关键词任一命中即 true。这里只认这两个子串:
        //    不扩词(如 context_length_exceeded)、不做归一化、不读状态码或 cause。
        return normalized.contains("prompt_too_long") || normalized.contains("too many tokens");
    }
}
