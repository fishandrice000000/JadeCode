package com.jadecode.context;

/**
 * 压缩各层用的全部阈值。把阈值集中到一个类型里，测试时才能注入很小的值，
 * 否则要构造几十万字符的输入才能触发某一层。生产用 {@link #defaults()}，测试自己构造小值。
 *
 * <p>所有“长度”都指 Java 字符数（{@link String#length()}），不是字节数，也不是精确 token 数。
 *
 * @param contextCharLimit         超过这个长度才执行启用的 micro／fit／自动摘要
 * @param toolResultBatchCharLimit 最新一批工具结果的总量上限，只看最后一条 user 消息
 * @param largeResultCharLimit     单个结果超过这个长度就要存盘
 * @param summaryInputCharLimit    交给模型摘要的输入上限，超出时保留头尾并省略中段
 * @param keepRecentResults        micro 层保留的“已读过”结果条数
 * @param keepRecentMessages       响应式补救保留的最近消息条数
 * @param microResultMinChars      micro 层不处理的短结果门槛
 * @param budgetPreviewChars       第一层存盘后保留的预览字符数
 * @param fitPreviewChars          第三层兜底存盘后保留的预览字符数
 * @param summaryMaxOutputTokens   摘要请求的 max_tokens（摘要请求不带工具）
 * @param snipMaxMessages          snip 层的消息条数门槛
 * @param snipHeadMessages         snip 层固定保留的头部消息条数
 * @param targetRatio              micro / fit 的目标长度 = contextCharLimit * ratio
 */
public record CompactionLimits(
        int contextCharLimit,
        int toolResultBatchCharLimit,
        int largeResultCharLimit,
        int summaryInputCharLimit,
        int keepRecentResults,
        int keepRecentMessages,
        int microResultMinChars,
        int budgetPreviewChars,
        int fitPreviewChars,
        int summaryMaxOutputTokens,
        int snipMaxMessages,
        int snipHeadMessages,
        double targetRatio) {

    /**
     * 日常长会话的默认字符预算，避免沿用原来为教学快速触发压缩的 50K 门槛。
     *
     * <p>300K 字符是本项目的可调工程起点，不是 Claude Code 的 167K token，也不是后端的
     * 硬窗口保证。中文、英文与工具 JSON 的 token 比例不同，仍须按实际模型调节。
     * 单结果 50K 与约 2K 预览参考该快照；摘要输入上限高于触发线，避免一触发就先截掉大段内容。
     * snip／micro／fit 的阈值仍提供，但默认开关关闭，不会因这些数字而偷偷运行。</p>
     */
    public static CompactionLimits defaults() {
        return new CompactionLimits(
                300_000, // 序列化历史超过此字符数，才进入启用的缩短／摘要步骤
                200_000, // 可选的最新批次总正文预算，默认不开启
                50_000, // 默认启用的单个结果转存门槛
                400_000, // 摘要输入的字符上限，不是 token 上限
                5, // 若开启 micro，保留最近五条已消费结果
                5, // 若开启响应式补救，保护最近五条消息（切点还会保护完整工具交换）
                4_000, // 可选 micro 不处理更短的结果
                2_000, // 单条转存后保留的正文预览
                2_000, // 可选 fit 的正文预览
                8_000, // 摘要请求允许输出的最大 token 数
                200, // 可选 snip 的消息数门槛，不是生产默认的另一条触发线
                3, // 可选 snip 固定保留的头部消息数
                0.8); // 可选 micro／fit 尝试缩到总字符门槛的 80%
    }

    /** 原课程阈值，仅供教学与已有小阈值验证复用；生产装配不再调用本方法。 */
    public static CompactionLimits courseDefaults() {
        return new CompactionLimits(
                50_000, // CONTEXT_CHAR_LIMIT
                200_000, // TOOL_RESULT_BATCH_CHAR_LIMIT
                30_000, // LARGE_RESULT_CHAR_LIMIT
                80_000, // SUMMARY_INPUT_CHAR_LIMIT
                3, // KEEP_RECENT_RESULTS
                5, // KEEP_RECENT_MESSAGES
                120, // micro 层最短处理长度
                2_000, // 第一层预览
                1_000, // fit 层预览
                2_000, // 摘要输出上限
                50, // snip 消息数门槛
                3, // snip 头部保留
                0.8); // 目标比例
    }

    /**
     * micro 和 fit 两层要压到的目标长度，由总上限乘目标比例得到。
     *
     * @return 目标字符数
     */
    public int targetChars() {
        return (int) (contextCharLimit * targetRatio);
    }
}
