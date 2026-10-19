package com.jadecode.session;

import com.jadecode.llm.ModelMetadata;
import com.jadecode.llm.ModelResponse;

/**
 * 最近一次正式响应时的上下文占用估计，与整个会话累计花费分开保存。
 * 只用服务端输入（包括缓存）加本次输出计算，不拿字符数冒充 tokenizer。
 * 工具结果、用户消息或压缩改变历史后，调用方丢弃此快照，等下一份匹配报告。
 */
public record ContextUsage(long occupiedTokens, int windowTokens) {
    public ContextUsage {
        if (occupiedTokens < 0 || windowTokens < 1) throw new IllegalArgumentException("上下文占用无效");
    }

    /** 只有总窗口和本次用量均已知才生成；最大输入限额不能冒充总窗口。 */
    public static ContextUsage measured(ModelResponse.Usage usage, ModelMetadata metadata) {
        Integer window = metadata.limits().contextWindowTokens();
        return usage == null || window == null ? null : new ContextUsage(
                Math.addExact(usage.totalInputTokens(), usage.outputTokens()), window);
    }

    /** 这是最近响应后的估计，不是下一请求还能追加多少输入的精确保证。 */
    public int remainingPercent() {
        // 用 long 做整数百分比，避免浮点舍入把恰好剩余 80% 显示为 79%。
        long remaining = windowTokens - Math.min(occupiedTokens, windowTokens);
        return (int) (remaining * 100 / windowTokens);
    }
}
