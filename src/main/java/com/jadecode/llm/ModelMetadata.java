package com.jadecode.llm;

/**
 * 当前模型已取得的限额及其来源，不包含认证或对话资料。
 * 正式请求可自动使用最大允许输出；辅助请求仍使用自己的额度。额度是上限，不要求模型写满。
 * 缺失值是未知，不能伪装成测得的零。
 * @param limits 已知限额，可能只有部分字段
 * @param source 用户可读的固定来源说明，不包含原始 HTTP 响应
 */
public record ModelMetadata(Limits limits, String source) {
    /** 模型未报告最大输出时，auto 正式请求沿用原来的 8000；不是猜测的模型能力值。 */
    public static final int UNKNOWN_FORMAL_OUTPUT_TOKENS = 8000;
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

    /**
     * 装配正式 Agent（包括队友和一次性子代理）时解析配置，不修改元数据或发送请求。
     * @param configured 0 表示 auto，正整数表示用户指定额度；负数拒绝
     * @return 真实正数：auto 取已知最大输出，未知回退 8000；显式额度仍受模型上限封顶
     * @throws IllegalArgumentException 配置为负数
     */
    public int formalOutputBudget(int configured) {
        if (configured < 0) throw new IllegalArgumentException("Configured output budget cannot be negative");
        // 只在正式请求装配入口解释 auto；摘要、Memory、审批不经过这里，不能被一起放大。
        if (configured == 0)
            return limits.maxOutputTokens() == null ? UNKNOWN_FORMAL_OUTPUT_TOKENS : limits.maxOutputTokens();
        return capOutput(configured);
    }

    /** 所有实际请求共用的输出封顶，只降低正数额度；配置层 auto 必须先在装配时解析。 */
    public int capOutput(int requested) {
        if (requested < 1) throw new IllegalArgumentException("Request output budget must be positive; resolve auto before sending");
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
