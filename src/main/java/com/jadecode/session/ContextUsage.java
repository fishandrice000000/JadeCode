package com.jadecode.session;

import com.jadecode.llm.ModelMetadata;
import com.jadecode.llm.ModelResponse;

/**
 * 当前请求投影的上下文占用，与整个会话累计花费分开保存。
 * 正式响应建立服务端输入（含缓存）加输出的锚点，新增内容只作启发式估算并标记 estimated。
 * 历史替换后先清空显示；后续估算不复用已失效锚点，未知模型窗口也不伪造百分比。
 */
public record ContextUsage(long occupiedTokens, int windowTokens, boolean estimated) {
    /** 旧快照来自服务端响应，不改变已有构造的含义。 */
    public ContextUsage(long occupiedTokens, int windowTokens) { this(occupiedTokens, windowTokens, false); }
    public ContextUsage {
        if (occupiedTokens < 0 || windowTokens < 1) throw new IllegalArgumentException("Invalid context occupancy");
    }

    /** 只有总窗口和本次用量均已知才生成；最大输入限额不能冒充总窗口。 */
    public static ContextUsage measured(ModelResponse.Usage usage, ModelMetadata metadata) {
        Integer window = metadata.limits().contextWindowTokens();
        return usage == null || window == null ? null : new ContextUsage(
                Math.addExact(usage.totalInputTokens(), usage.outputTokens()), window);
    }

    /** 与执行器预算共用投影；窗口未知时不伪造百分比，估算来源随快照展示。 */
    public static ContextUsage projected(com.jadecode.context.ContextTokenTracker.Estimate value, ModelMetadata metadata) {
        Integer window = metadata.limits().contextWindowTokens();
        return window == null ? null : new ContextUsage(value.tokens(), window, value.estimated());
    }

    /** 这是最近响应后的估计，不是下一请求还能追加多少输入的精确保证。 */
    public int remainingPercent() {
        // 用 long 做整数百分比，避免浮点舍入把恰好剩余 80% 显示为 79%。
        long remaining = windowTokens - Math.min(occupiedTokens, windowTokens);
        return (int) (remaining * 100 / windowTokens);
    }
}
