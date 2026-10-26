package com.jadecode.context;

/**
 * 生产压缩的少量参数，与课程字符管线分开。Token 预留只决定何时压缩，近期原文预算只决定切点。
 * summaryMaxInputTokens=null 表示按当前模型窗口自动求值，不代表无限输入；本对象无 IO 或会话状态。
 */
public record ProductionCompactionSettings(boolean autoSummaryEnabled, int reserveTokens, int keepRecentTokens,
        int summaryMaxOutputTokens, Integer summaryMaxInputTokens, int largeResultCharLimit, int largeResultPreviewChars) {
    /** 拒绝无法解释的负预算，零预留/零近期预算仍由协议边界保护约束。 */
    public ProductionCompactionSettings {
        if (reserveTokens < 0 || keepRecentTokens < 0 || summaryMaxOutputTokens < 1
                || summaryMaxInputTokens != null && summaryMaxInputTokens < 1
                || largeResultCharLimit < 1 || largeResultPreviewChars < 0)
            throw new IllegalArgumentException("Invalid production compaction budget");
    }

    /** 用户确认的生产起点；输出是上限，不要求摘要写满。 */
    public static ProductionCompactionSettings defaults() {
        return new ProductionCompactionSettings(true, 16384, 20000, 32768, null, 50000, 2000);
    }

    /** 离线旧装配只覆盖自动开关，不把旧课程字符阈值偷偷变成生产预算。 */
    public ProductionCompactionSettings withAutomatic(boolean enabled) {
        return new ProductionCompactionSettings(enabled, reserveTokens, keepRecentTokens, summaryMaxOutputTokens,
                summaryMaxInputTokens, largeResultCharLimit, largeResultPreviewChars);
    }
}
