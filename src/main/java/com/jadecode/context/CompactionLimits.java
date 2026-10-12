package com.jadecode.context;

/**
 * CompactionLimits -- 压缩管线的全部阈值,集中一处便于测试注入。
 *
 * <p>
 * <b>单位</b>:所有"大小"都是 Java {@link String#length()} 的字符数(等价于课程
 * {@code len(json.dumps(messages, ensure_ascii=False))}),不是字节数,也不是精确 token 数。
 * </p>
 *
 * <p>
 * <b>为什么要单独抽一个类型</b>:四层管线里每一层都有自己的触发阈值,散在
 * {@link ContextCompactor} 里就没法在单元测试中把阈值调小——不注入小阈值,就只能构造
 * 几十万字符的输入才能触发某一层。生产装配用 {@link #courseDefaults()};测试构造小阈值。
 * </p>
 *
 * <p>
 * 各分量对应的课程常量名写在下面各 {@code @param} 里,具体数值见作业单
 * {@code docs/jobs/s08/context-compact.md} 的管线表。<b>不要用反射改常量来测阈值</b>。
 * </p>
 *
 * @param contextCharLimit         课程 {@code CONTEXT_CHAR_LIMIT};超过才进第三、四层
 * @param toolResultBatchCharLimit 课程
 *                                 {@code TOOL_RESULT_BATCH_CHAR_LIMIT};只看最后一条
 *                                 user 消息
 * @param largeResultCharLimit     课程 {@code LARGE_RESULT_CHAR_LIMIT};单项超过它就要落盘
 * @param summaryInputCharLimit    课程
 *                                 {@code SUMMARY_INPUT_CHAR_LIMIT};超出时掐头去尾留中缝
 * @param keepRecentResults        课程 {@code KEEP_RECENT_RESULTS};micro
 *                                 层保留的已消费结果条数
 * @param keepRecentMessages       课程 {@code KEEP_RECENT_MESSAGES};reactive
 *                                 层保留的最近消息条数
 * @param microResultMinChars      micro 层不处理的短结果门槛
 * @param budgetPreviewChars       第一层落盘后保留的预览字符数
 * @param fitPreviewChars          第三层 fit 落盘后保留的预览字符数
 * @param summaryMaxOutputTokens   摘要请求的 max_tokens(摘要请求不带任何 tools)
 * @param snipMaxMessages          snip 层的消息数门槛
 * @param snipHeadMessages         snip 层固定保留的头部消息数
 * @param targetRatio              micro / fit 的目标 = contextCharLimit * ratio
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
     * 生产默认阈值:按课程原值逐项组装。
     *
     * <p>
     * TODO(s08-user): 组装课程默认值。课程各常量的数值见作业单
     * {@code docs/jobs/s08/context-compact.md} §二 的管线表;本 record 的分量顺序与
     * {@code @param} 顺序一一对应。
     * </p>
     *
     * @return 课程原始阈值
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
     * micro / fit 两层的目标字符数。
     *
     * <p>
     * TODO(s08-user): 由 {@link #contextCharLimit} 与 {@link #targetRatio} 推出。
     * </p>
     *
     * @return 目标字符数
     */
    public int targetChars() {
        return (int) (contextCharLimit * targetRatio);
    }
}
