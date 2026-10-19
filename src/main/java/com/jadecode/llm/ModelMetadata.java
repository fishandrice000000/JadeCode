package com.jadecode.llm;

/**
 * 当前模型已取得的限额及其来源，不包含认证或对话资料。
 * “模型最大允许输出”不是“每次都请求这么多”；缺失值是未知，不能伪装成测得的零。
 * @param limits 已知限额，可能只有部分字段
 * @param source 用户可读的固定来源说明，不包含原始 HTTP 响应
 */
public record ModelMetadata(Limits limits, String source) {
    /**
     * 限额单位均为 token。Anthropic 的 maxInputTokens 不冒充总窗口；DeepSeek 的
     * contextWindowTokens 包括输入和输出，也不能直接当成最大输入。
     * 正整数才有意义，null 表示服务商未提供或用户未配置。
     */
    public record Limits(Integer contextWindowTokens, Integer maxInputTokens, Integer maxOutputTokens) {
        public Limits {
            for (Integer value : new Integer[] { contextWindowTokens, maxInputTokens, maxOutputTokens })
                if (value != null && value < 1) throw new IllegalArgumentException("模型限额必须为正整数或未填写");
        }
        public static Limits unknown() { return new Limits(null, null, null); }
        public boolean known() { return contextWindowTokens != null || maxInputTokens != null || maxOutputTokens != null; }
    }

    /** 所有请求共用的输出封顶，只降低已超出限额的请求，不增加调用方自己的额度。 */
    public int capOutput(int requested) {
        return limits.maxOutputTokens() == null ? requested : Math.min(requested, limits.maxOutputTokens());
    }

    /** 固定格式展示已知与未知字段；不推断 tokenizer、不把字符估算当作官方 token 数据。 */
    public String describe() {
        return "来源：" + source + "\n总上下文窗口：" + value(limits.contextWindowTokens())
                + "\n最大输入：" + value(limits.maxInputTokens()) + "\n最大输出：" + value(limits.maxOutputTokens())
                + "\n未知字段沿用已有预算；已知输出上限只用于封顶，不自动放大请求。"
                + "\n压缩仍使用 config.json 中的字符阈值，不等同于精确 token 计数。";
    }
    private static String value(Integer value) { return value == null ? "未知" : value + " token"; }
}
