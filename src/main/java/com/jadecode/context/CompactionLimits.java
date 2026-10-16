package com.jadecode.context;

/**
 * 压缩各层用的全部阈值。把阈值集中到一个类型里，测试时才能注入很小的值，
 * 否则要构造几十万字符的输入才能触发某一层。生产用 {@link #courseDefaults()}，测试自己构造小值。
 *
 * <p>所有“长度”都指 Java 字符数（{@link String#length()}），不是字节数，也不是精确 token 数。
 *
 * @param contextCharLimit         超过这个长度才进第三、四层
 * @param toolResultBatchCharLimit 最新一批工具结果的总量上限，只看最后一条 user 消息
 * @param largeResultCharLimit     单个结果超过这个长度就要存盘
 * @param summaryInputCharLimit    交给模型摘要的输入上限，超出时截取中间一段
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
     * 生产默认阈值，数值来自课程。
     *
     * @return 默认阈值
     */
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
