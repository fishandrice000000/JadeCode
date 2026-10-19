package com.jadecode.context;

/**
 * 决定自动整理会调用哪些已有策略，不保存消息、计数或文件路径。
 *
 * <p>单条结果转存与整批合计预算是两回事：一个 60K 结果即使整批不到 200K，也应转存；
 * 合计预算则按整批大小决定是否启动，但课程实现仍只选择超过单项门槛的结果，不是硬总量保证。
 * 默认参考指定 Claude Code 快照的外部路径，
 * 但不宣称实现了它的缓存共享、时间微压缩或精确 token 预算。</p>
 *
 * @param largeResultEnabled 单条结果超限时转存磁盘
 * @param batchBudgetEnabled 最新工具批次超过总预算时选择大结果转存
 * @param snipEnabled 按消息条数归档中段
 * @param microEnabled 超总长度时缩短已消费的旧结果
 * @param fitEnabled 超总长度时尝试兜底转存大结果
 * @param autoSummaryEnabled 超总长度时自动请求摘要；不控制显式 compact
 * @param reactiveEnabled 服务端拒绝上下文过长后允许一次补救
 */
public record CompactionPolicy(boolean largeResultEnabled, boolean batchBudgetEnabled, boolean snipEnabled,
        boolean microEnabled, boolean fitEnabled, boolean autoSummaryEnabled, boolean reactiveEnabled) {

    /** 生产默认只做单条结果转存与自动摘要，其余已有策略需明确开启。 */
    public static CompactionPolicy defaults() {
        return new CompactionPolicy(true, false, false, false, false, true, false);
    }

    /**
     * 原课程整套管线，供已有教学验证显式选用，不是生产默认值。
     * 课程第一层只在批次超预算时处理大结果，没有独立的单条预处理。
     */
    public static CompactionPolicy coursePipeline() {
        return new CompactionPolicy(false, true, true, true, true, true, true);
    }
}
