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
                if (value != null && value < 1) throw new IllegalArgumentException("Model limits must be positive integers or left blank");
        }
        public static Limits unknown() { return new Limits(null, null, null); }
        public boolean known() { return contextWindowTokens != null || maxInputTokens != null || maxOutputTokens != null; }
    }

    /** 所有请求共用的输出封顶，只降低已超出限额的请求，不增加调用方自己的额度。 */
    public int capOutput(int requested) {
        return limits.maxOutputTokens() == null ? requested : Math.min(requested, limits.maxOutputTokens());
    }

    /** 只展示已知值；来源仍留在元数据供诊断，不把未知占位与预算说明反复塞进 /model。 */
    public String describe() {
        var lines = new java.util.ArrayList<String>();
        if (limits.contextWindowTokens() != null) lines.add("Total context window: " + limits.contextWindowTokens() + " token");
        if (limits.maxInputTokens() != null) lines.add("Max input: " + limits.maxInputTokens() + " token");
        if (limits.maxOutputTokens() != null) lines.add("Max output: " + limits.maxOutputTokens() + " token");
        return String.join("\n", lines);
    }
}
