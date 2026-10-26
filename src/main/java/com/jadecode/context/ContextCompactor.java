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
 * 上下文压缩的入口：把太长的消息历史整理短，再交给模型。
 *
 * <p>生产通过 {@link #production} 装配独立的 Token 管线，预算来自真实请求与每条历史的 usage 锚点。
 * 普通构造保留下面的字符课程实现；这些层只供显式教学装配，不由生产配置启用：
 * <ol>
 * <li>{@link ToolResultBudgetCompactor}：单条结果太大时转存并保留路径与预览；
 * 最新批次的合计预算是另一个默认关闭的开关。</li>
 * <li>{@link SnipCompactor}：消息条数太多时，把完整历史归档，中间一段换成一条归档标记。</li>
 * <li>还是超限时，先用 {@link MicroCompactor} 缩短模型已经读过的旧工具结果，
 * 再用 {@link ToolResultFitter} 兜底把最长的结果存盘。</li>
 * <li>启用的确定性整理仍不能满足总预算时，用 {@link HistoryCompactor} 让模型生成摘要
 * （唯一会额外调用模型的一层）。</li>
 * </ol>
 *
 * <p>三条需要遵守的规则：
 * <ul>
 * <li>不修改传入的消息列表和消息内容。没有改动就原样返回入参；有改动就新建列表，
 * 并且只重建真正变化的消息。调用方拿到结果后用 clear + addAll 写回自己持有的列表，
 * 所以要先判断返回值是不是同一个列表对象，是的话就不要清空再重灌。</li>
 * <li>工具调用（assistant 的 tool_use）和它的结果（user 的 tool_result）必须成对保留，
 * 切分历史时不能把两者分开。相关判断集中在 {@link ToolExchangeBoundary}，三处切点共用。</li>
 * <li>工具结果存盘必须成功后才能替换历史内容。写失败要抛异常，不能返回一个不存在的路径，
 * 也不能丢掉原结果。反过来，判断一条消息是不是可信的归档标记时，拿不准就当不可信，
 * 重新归档，最多多写一个文件，不丢数据。</li>
 * </ul>
 *
 * <p>本类不保存会话状态，父 Agent 和子代理可以安全地共用同一个实例。
 */
public final class ContextCompactor {
    /** 生产服务只在发布前设置；会话锚点由各历史拥有者保存，不在父子共享对象中存状态。 */
    private ProductionCompactor production;

    /** 生产唯一装配入口；旧构造与多层算法保留作显式课程验证，构造不产生 IO。 */
    public static ContextCompactor production(LlmClient client, Path transcriptDir, Path toolResultsDir,
            ProductionCompactionSettings settings) {
        var facade = new ContextCompactor(client, transcriptDir, toolResultsDir, CompactionLimits.defaults(),
                new CompactionPolicy(true, false, false, false, false, settings.autoSummaryEnabled(), false));
        facade.production = new ProductionCompactor(client, transcriptDir, toolResultsDir, settings);
        return facade;
    }

    /** @return 是否使用 Token 生产主干，供装配与验证区分课程路径。 */
    public boolean productionMode() { return production != null; }

    /** 固定一次实际请求投影，生产只判断 Token，课程继续使用显式教学阈值。 */
    public List<Message> prepare(com.jadecode.llm.CompleteRequest request, String activeRequest,
            ContextTokenTracker tracker, String runtimeState) throws LlmException {
        return production == null ? prepare(request.messages(), activeRequest)
                : production.prepare(request, activeRequest, tracker, runtimeState);
    }

    /** 主动 compact 复用生产摘要，不把课程清理层恢复到产品入口。 */
    public List<Message> compactAfterBatch(List<ToolUseBlock> calls, com.jadecode.llm.CompleteRequest request,
            String activeRequest, String runtimeState) throws LlmException {
        return production == null ? compactAfterBatch(calls, request.messages(), activeRequest)
                : production.force(request, activeRequest, runtimeState);
    }

    /** 剩余空间只适配这一次输出额度，不改固定预留或用户配置。 */
    public int outputBudget(com.jadecode.llm.CompleteRequest request, ContextTokenTracker tracker) throws LlmException {
        return production == null ? request.maxTokens() : production.outputBudget(request, tracker);
    }

    /** 响应式补救的全局上限:同一次正式模型调用最多补救这么多次 */
    public static final int MAX_REACTIVE_RETRIES = 1;

    /**
     * compact 工具的名字，用来判断这一批调用里有没有非压缩工具。
     *
     * <p>这里没有引用 CompactTool 的常量，是为了避免压缩包反向依赖工具包。
     * 如果这个名字和工具不一致，手动压缩的测试会立刻失败。
     */
    private static final String COMPACT_TOOL = "compact";

    /** 阈值集合 */
    private final CompactionLimits limits;
    /** 与阈值独立的固定开关快照；测试可明确选择课程管线，生产默认不启用所有教学策略。 */
    private final CompactionPolicy policy;

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
     * 生产默认阈值与默认管线；本类不自己读取全局配置，实际装配可显式注入用户设置。
     *
     * @param client         LLM 客户端,仅摘要层使用
     * @param transcriptDir  transcript 归档目录
     * @param toolResultsDir 工具结果落盘目录
     */
    public ContextCompactor(LlmClient client, Path transcriptDir, Path toolResultsDir) {
        this(client, transcriptDir, toolResultsDir, CompactionLimits.defaults(), CompactionPolicy.defaults());
    }

    /**
     * 自定义课程字符阈值；生产 Token 管线必须使用 production 工厂，不能由字符值猜模型窗口。
     *
     * @param client         LLM 客户端,仅摘要层使用
     * @param transcriptDir  transcript 归档目录
     * @param toolResultsDir 工具结果落盘目录
     * @param limits         阈值集合(测试注入小阈值)
     */
    public ContextCompactor(LlmClient client, Path transcriptDir, Path toolResultsDir, CompactionLimits limits) {
        this(client, transcriptDir, toolResultsDir, limits, CompactionPolicy.defaults());
    }

    /**
     * 同时注入阈值与管线选择；所有会话使用自己的历史，设置本身不可变。
     * 本构造只装配显式课程验证；生产装配通过 production 工厂转入独立实现。
     * 构造不建目录、不摘要；真正选中某个需要 IO 的策略时才写文件或调用模型。
     */
    public ContextCompactor(LlmClient client, Path transcriptDir, Path toolResultsDir, CompactionLimits limits,
            CompactionPolicy policy) {
        this.limits = java.util.Objects.requireNonNull(limits);
        this.policy = java.util.Objects.requireNonNull(policy);
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
    /** 固定的开关快照；执行器据此决定是否允许服务端超限后的补救，关闭时不偷偷重试。 */
    public CompactionPolicy policy() { return policy; }

    /* ===================== 总入口 ===================== */

    /**
     * 正式请求模型前调用：只执行启用的策略，返回整理后的消息列表。
     *
     * <p>默认先转存单条超大结果，再检查字符总量，超限时直接摘要；不会先剪裁或清理旧结果。
     * 配置开启课程策略时顺序仍是 budget → snip → micro → fit → 摘要。
     * 自动摘要也可关闭，此时保留剩余历史；本方法不伪造“已满足后端窗口”的保证。
     *
     * @param messages      当前会话消息
     * @param activeRequest 本次用户请求的原文，会被写进摘要消息的 Current user request 部分
     * @return 压缩后的消息列表；没有改动时返回入参本身
     * @throws LlmException 生成摘要失败，原样抛出，不装作压缩成功
     */
    public List<Message> prepare(List<Message> messages, String activeRequest) throws LlmException {
        if (production != null) throw new IllegalStateException("Production compaction requires the actual request projection and history token tracker");
        // 1. 只处理新鲜批次，不遍历旧结果改写缓存前缀。两种预算开关在同一入口分开判断。
        List<Message> current = toolResultBudget(messages);
        if (policy.snipEnabled()) current = snipCompact(current);
        if (smallEnough(current)) return current;

        // 2. 这些是可选的课程增强。关掉某项时完全跳过，不写它的转存／归档文件。
        if (policy.microEnabled()) current = microCompact(current, limits.targetChars());
        if (smallEnough(current)) return current;
        if (policy.fitEnabled()) current = fitToolResults(current, limits.targetChars());
        if (smallEnough(current)) return current;

        // 3. 摘要仍保护尚未消费的最新工具交换。失败原样传播，不当作整理成功。
        return policy.autoSummaryEnabled() ? compactHistory(current, activeRequest) : current;
    }

    private boolean smallEnough(List<Message> messages) {
        return estimateChars(messages) <= limits.contextCharLimit();
    }

    /* ===================== 手动批次压缩入口 ===================== */

    /**
     * 处理模型主动请求压缩的一批工具调用。
     *
     * <p>什么时候该压缩，自动机制看的是长度和消息数，但模型可以自己判断"某段探索记录不必再留在历史里"，
     * 于是调用没有参数的 {@code compact} 工具。父循环等这一批工具全部返回结果后，才把本批调用交给本方法，
     * 由它选一种压缩方式。在工具执行中途压缩会把还没回灌的调用和结果拆开，所以必须等一批结束。
     *
     * <p>两种处理：
     * <ul>
     * <li>本批所有调用都是 compact：整段历史换成一条摘要（{@link #compactAll}）。
     * 这只是"本批没有别的新结果"，不代表阶段已完成或用户已验收。</li>
     * <li>本批还含有别的工具：先把超大的新鲜结果存盘并留预览（{@link #toolResultBudget}），
     * 再只摘要旧前缀（{@link #compactHistory}），让模型还没读过的最新结果保持原样。</li>
     * </ul>
     *
     * <p>与其它入口一样，本方法只在内存里生成候选，不修改传入的消息。调用方在它正常返回后，
     * 再用 clear + addAll 把候选写回自己的列表。预设条件是：本批确实执行过 compact、
     * 整批结果已回灌、{@code toolCalls} 就是本批全部调用、{@code activeRequest} 是入口捕获的本次人类请求。
     *
     * @param toolCalls     本批全部 tool_use（用来区分纯压缩还是混合批次）
     * @param messages      已经完整回灌的当前历史
     * @param activeRequest 本次用户请求，两条路径都会把它写进摘要消息
     * @return 压缩后的候选；没有可安全压缩的部分时返回入参本身
     * @throws LlmException          生成摘要失败
     * @throws IllegalStateException 结果落盘或归档失败
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

        // 3. 混合批次:先把最新那一对里超大的新结果换成可恢复的"路径 + 预览"(候选),
        //    再让摘要只覆盖它之前的前缀——刚生成、模型还没读过的结果不能被先转述进摘要而失真。
        //    两步都复用既有层;不调用整套 prepare,也不在这里重复实现归档或摘要逻辑。
        List<Message> budgeted = toolResultBudget(messages);
        return compactHistory(budgeted, activeRequest);
    }

    /* ===================== 第一层:最新结果批次预算 ===================== */

    /**
     * 根据两个独立开关处理单条大结果和最新批次合计预算，默认只启用前者。
     * 手动混合 compact 批次也复用这里，因此不会绕过用户的转存设置。
     *
     * @param messages 消息列表
     * @return 处理后的列表;无需处理时原样返回入参
     */
    public List<Message> toolResultBudget(List<Message> messages) {
        List<Message> current = policy.largeResultEnabled() ? toolResultBudgetCompactor.persistLargeResults(messages) : messages;
        return policy.batchBudgetEnabled() ? toolResultBudgetCompactor.toolResultBudget(current) : current;
    }

    /**
     * 转发到 {@link ToolResultBudgetCompactor#persistLargeOutput}，说明见那个类。
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
     * 转发到 {@link SnipCompactor#snipCompact}，说明见那个类。
     *
     * @param messages 消息列表
     * @return 裁剪后的列表;无需或无法裁剪时原样返回入参
     */
    List<Message> snipCompact(List<Message> messages) {
        return snipCompactor.snipCompact(messages);
    }

    /* ===================== 第三层:micro 与 fit ===================== */

    /**
     * 转发到 {@link MicroCompactor#microCompact}，说明见那个类。
     *
     * @param messages    消息列表
     * @param targetChars 目标字符数;null = 不做目标检查
     * @return 处理后的列表;无改动时原样返回入参
     */
    List<Message> microCompact(List<Message> messages, Integer targetChars) {
        return microCompactor.microCompact(messages, targetChars);
    }

    /**
     * 转发到 {@link ToolResultFitter#fitToolResults}，说明见那个类。
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
     * 转发到 {@link HistoryCompactor#compactAll}，说明见那个类。
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
     * 转发到 {@link HistoryCompactor#compactHistory}，说明见那个类。
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
     * 转发到 {@link HistoryCompactor#summarizeHistory}，说明见那个类。
     *
     * @param messages 需要摘要的消息(调用方负责只传该摘要的那一段)
     * @return 摘要文本;模型给出空摘要时用稳定兜底文案
     * @throws LlmException 调用失败
     */
    String summarizeHistory(List<Message> messages) throws LlmException {
        return historyCompactor.summarizeHistory(messages);
    }

    /**
     * 转发到 {@link HistoryCompactor#summaryInput}，说明见那个类。
     *
     * @param messages 消息列表
     * @return 序列化后的摘要输入
     */
    String summaryInput(List<Message> messages) {
        return historyCompactor.summaryInput(messages);
    }

    /* ===================== 持久化 ===================== */

    /**
     * 转发到 {@link TranscriptStore#writeTranscript}，说明见那个类。
     *
     * @param messages 消息列表
     * @return 实际写出的文件路径
     * @throws IllegalStateException 建目录、写文件或序列化失败(带 cause)
     */
    Path writeTranscript(List<Message> messages) {
        return transcriptStore.writeTranscript(messages);
    }

    /**
     * 转发到 {@link ToolOutputStore#saveOutput}，说明见那个类。
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
     * 转发到 {@link ToolOutputStore#persistedOutputPath}，说明见那个类。
     *
     * @param output 工具结果文本
     * @return 可信的绝对路径字符串;不可信或无法判定时为 null
     */
    String persistedOutputPath(String output) {
        return toolOutputStore.persistedOutputPath(output);
    }

    /**
     * 转发到 {@link ToolOutputStore#persistedPreview}，说明见那个类。
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
     * 转发到 {@link TranscriptStore#isArchiveMarker}，说明见那个类。
     *
     * @param message 候选消息
     * @return true = 可信的归档标记
     */
    boolean isArchiveMarker(Message message) {
        return transcriptStore.isArchiveMarker(message);
    }

    /* ===================== 估算与判定 ===================== */

    /**
     * 转发到 {@link ContextSerialization#estimateChars}，说明见那个类。
     *
     * @param messages 消息列表
     * @return JSON 文本的字符数
     */
    static int estimateChars(List<Message> messages) {
        return ContextSerialization.estimateChars(messages);
    }

    /**
     * 转发到 {@link ToolExchangeBoundary#hasToolUse}，说明见那个类。
     *
     * @param message 一条消息
     * @return true = 含 tool_use 的 assistant 消息
     */
    static boolean hasToolUse(Message message) {
        return ToolExchangeBoundary.hasToolUse(message);
    }

    /**
     * 转发到 {@link ToolExchangeBoundary#isToolResult}，说明见那个类。
     *
     * @param message 一条消息
     * @return true = 含 tool_result 的 user 消息
     */
    static boolean isToolResult(Message message) {
        return ToolExchangeBoundary.isToolResult(message);
    }

    /**
     * 转发到 {@link ToolExchangeBoundary#isClosedToolExchange}，说明见那个类。
     *
     * @param messages       消息列表
     * @param assistantIndex 候选 assistant 消息下标
     * @return true = 完整的工具调用对
     */
    static boolean isClosedToolExchange(List<Message> messages, int assistantIndex) {
        return ToolExchangeBoundary.isClosedToolExchange(messages, assistantIndex);
    }

    /**
     * 转发到 {@link ToolExchangeBoundary#safeTailStart}，说明见那个类。
     *
     * @param messages  消息列表
     * @param tailStart 候选尾部起点
     * @return 修正后的尾部起点
     */
    static int safeTailStart(List<Message> messages, int tailStart) {
        return ToolExchangeBoundary.safeTailStart(messages, tailStart);
    }

    /**
     * 转发到 {@link ToolExchangeBoundary#safeHeadEnd}，说明见那个类。
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
     * 服务端因为上下文太长拒绝请求后，构造下一次重试要用的新历史：
     * 旧前缀的摘要 + 原样保留的最近几条消息。
     *
     * <p>调用方顺序是：正式请求抛 {@link LlmException} → {@link #isContextOverflow} 判断值不值得补救 →
     * 本方法造出新历史 → 用新历史重试同一次请求（最多一次）。本方法只负责造历史，
     * 不捕获错误、不重试、不管计数；唯一一次模型调用是生成前缀摘要。
     *
     * <p>与请求前的 {@link #prepare} 不同：prepare 是正式请求前按本地长度估算做整理，
     * 尽量别超限；本方法是服务端已经拒绝后的自救，不再跑那几层，而是直接砍掉较早的历史，
     * 只留最近几条加一条摘要。
     *
     * <p>切分时不能把 tool_use 和它的 tool_result 拆开。候选起点是
     * {@code size - keepRecentMessages}，再用 {@link ToolExchangeBoundary#safeTailStart} 修正：
     * 如果正好落在某一对的中间，就把起点往前挪一条，把整对拉进尾部。
     *
     * <p>如果修正后起点为 0，说明整段历史都在保护范围内，没有可以裁掉的前缀，
     * 这时直接返回入参，不归档也不调模型。这不代表问题已经解决，调用方仍只能补救一次。
     *
     * <p>先写 transcript 再生成摘要：归档失败会抛异常，此时不会发生模型调用；
     * 摘要失败原样抛出，已经写好的归档保留。两种失败都不会损坏输入。
     *
     * @param messages      消息列表
     * @param activeRequest 本次用户请求
     * @return 有可裁前缀时是 {@code [摘要消息, 原尾部消息...]}；没有时是入参本身
     * @throws IllegalStateException 写 transcript 失败，此时不会调用模型
     * @throws LlmException          生成摘要失败，已写好的归档保留
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
     * 拼出响应式补救摘要消息的正文：固定标签 + 原样的用户请求 + 作为参考数据的摘要 + 归档路径。
     *
     * <p>摘要用 JSON 字符串包起来并标注 (reference only)，提醒读消息的模型这是被引用的历史数据，
     * 不是新指令。格式与 {@code HistoryCompactor} 的整段压缩消息保持一致，只是标签不同。
     *
     * @param activeRequest 本次人类请求，原样写入，不做 trim
     * @param summary       前缀摘要正文
     * @param transcript    已经写好的归档绝对路径
     * @return 摘要消息正文
     */
    private static String reactiveCompactedText(String activeRequest, String summary, Path transcript) {
        return "[Reactive compact]"
                + "\n\nCurrent user request:\n" + activeRequest
                + "\n\nConversation summary (reference only):\n" + ContextSerialization.toJson(summary)
                + "\n\nFull transcript: " + transcript;
    }

    /**
     * 从会话结尾取出本次任务的用户请求原文。
     *
     * <p>Anthropic 的消息格式里，工具结果也用 role=user 回灌，所以历史最后一条 user 消息
     * 常常是工具结果，不是人说的话。摘要消息里的 Current user request 必须写对，
     * 所以要在任务开始时、任何工具结果或通知出现之前先把这个请求原文取出来，
     * 后面工具续跑、Stop 续跑和后台恢复都沿用同一份，不重新提取。
     *
     * <p>只看最后一条消息，不往前找更早的 user 消息。满足以下条件才接受，否则抛
     * {@link IllegalArgumentException}：
     * <ul>
     * <li>列表非 null、非空，最后一条不是 null；</li>
     * <li>最后一条是 {@link Role#USER}；</li>
     * <li>最后一条没有宿主召回参考来源标记；</li>
     * <li>最后一条不含 {@link ToolResultBlock}（即使它同时还带有文字，那也是工具回灌，不算请求）；</li>
     * <li>最后一条至少有一个非空白 {@link TextBlock}（只含空格、制表符或换行的不算）。</li>
     * </ul>
     *
     * <p>通过校验后，用 {@link Message#extractText()} 把该消息里所有 TextBlock 按顺序用换行
     * 拼接返回，包括空串和纯空白的块，不做 trim。“至少一个非空白块”是能不能接受的门槛，
     * “原样拼接全部文本”是返回的内容，两件事。
     *
     * <p>本方法只读，不修改消息、不读写文件、不调模型。
     *
     * @param messages 会话消息列表
     * @return 本次任务的用户请求原文
     * @throws IllegalArgumentException 列表为 null、为空，或最后一条不满足上述条件
     */
    public static String extractActiveRequest(List<Message> messages) {
        // 1. 列表本身:入口至少要有一条"人类请求"可言。
        if (messages == null) {
            throw new IllegalArgumentException("messages cannot be null: the entry point requires at least one human request");
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be empty: the entry point requires at least one human request");
        }

        // 2. 末条消息:必须存在、来自 USER,并且 content 不为 null。
        //    Message 是 record,构造器不校验这些字段,所以这里逐个 null 检查,避免后面抛偶发 NPE
        //    (例如 extractText 直接在 null content 上迭代)。
        Message last = messages.getLast();
        if (last == null) {
            throw new IllegalArgumentException("The last message cannot be null");
        }
        if (last.role() != Role.USER) {
            throw new IllegalArgumentException("The last request must be a USER message, but was " + last.role());
        }
        // USER 是协议角色；宿主召回资料不是用户新指令，不能成为压缩时的当前请求。
        if (last.source() == Message.Source.MEMORY_REFERENCE) {
            throw new IllegalArgumentException("A recalled memory reference is not a user request");
        }
        if (last.content() == null) {
            throw new IllegalArgumentException("The last request content cannot be null");
        }

        // 3. 逐块检查末条 content:拒绝工具结果与 null 结构,同时记录"是否存在非空白文本"。
        //    发现 tool_result 就立刻拒绝——哪怕同一消息里还有人类文本:混合形态属于工具回灌,
        //    不能因为"顺带有字"就当成请求。
        boolean hasNonBlankText = false;
        for (ContentBlock block : last.content()) {
            if (block == null) {
                throw new IllegalArgumentException("The last request content cannot contain null blocks");
            }
            if (block instanceof ToolResultBlock) {
                throw new IllegalArgumentException(
                        "The last request must not contain tool_result: tool results also use the USER role and cannot be treated as a human request");
            }
            if (block instanceof TextBlock text) {
                String value = text.text();
                if (value == null) {
                    throw new IllegalArgumentException("The last request text block text cannot be null");
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
                    "The last request needs at least one non-blank text block (empty, whitespace-only or null text does not count as a request)");
        }

        // 5. 通过校验:交给 Message.extractText() 按出现顺序用 "\n" 连接全部 TextBlock,
        //    原样返回(不 trim / strip)。复用这个方法可以保证与项目其它地方"提取文本"的口径一致。
        return last.extractText();
    }

    /**
     * 正式请求失败后，判断这个错误是不是“上下文过长”，值不值得压缩一次再重试。
     *
     * <p>调用方拿到 {@link LlmException} 时先问本方法：true 才去压缩并重试同一次请求；false 就按普通
     * 失败抛出。本方法只做分类，不压缩、不重试、不碰消息、不写文件。
     *
     * <p>只看异常自己的说明文字，忽略大小写做子串匹配，命中 {@code prompt_too_long} 或
     * {@code too many tokens} 就算上下文过长。不看 HTTP 状态码、cause 或栈，因为相同状态码的
     * 错误原因可能完全不同。本地估算的长度只是估算，服务端仍然可能拒绝，真正“太长”只有服务端说了算；
     * 反过来关键词匹配也只是近似，服务端换个说法就会漏判，说明里恰好引用这两个词也可能误判，
     * 所以它只是一个“值不值得试一次”的开关。
     *
     * <p>认证失败、限流、网络中断这类错误即使压缩重试也不会成功，反而白白损失上下文，
     * 所以不能进这条压缩路径。即使本方法返回 true，补救也可能失败，调用方要把同一次请求最多补救一次。
     *
     * @param error 模型调用异常
     * @return true 表示说明里出现约定关键词，可以尝试一次压缩补救
     */
    public static boolean isContextOverflow(LlmException error) {
        // 1. 没有异常、或异常没带说明,就没有可判定的文本 → false(而不是抛异常:
        //    这个判断是给调用方处理失败用的,自己不能再报新的错)。
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
