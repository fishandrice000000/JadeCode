package com.jadecode.config;

/**
 * 启动时选择的两种接入约定，不是动态供应商注册表。
 *
 * <p>原有 Anthropic 兼容入口继续由 URL 决定已适配能力；新增 Go 入口只承诺一个模型。
 * 显式身份让认证和思考字段不再靠模型名字猜测，也避免把 Go Key 用在直连默认端点。</p>
 */
public enum ModelProvider {
    ANTHROPIC_COMPATIBLE("anthropic-compatible", "https://api.deepseek.com/anthropic", "deepseek-v4-flash"),
    OPENCODE_GO("opencode-go", "https://opencode.ai/inference/go/anthropic", "deepseek-v4.1-flash");

    private final String value, defaultBaseUrl, defaultModel;

    ModelProvider(String value, String defaultBaseUrl, String defaultModel) {
        this.value = value; this.defaultBaseUrl = defaultBaseUrl; this.defaultModel = defaultModel;
    }

    /** 配置使用的稳定名称；不将 enum 大写名称写入用户配置。 */
    public String value() { return value; }
    /** 只在用户没有明确提供 URL 时使用；不覆盖显式配置。 */
    public String defaultBaseUrl() { return defaultBaseUrl; }
    /** Go 的缺省模型就是本轮唯一适配目标，不继承另一个供应商的默认模型。 */
    public String defaultModel() { return defaultModel; }

    /** 缺少字段兼容旧配置；未知值明确失败，诊断不回显可能误填的 Key。 */
    public static ModelProvider parse(String value) {
        if (value == null) return ANTHROPIC_COMPATIBLE;
        for (ModelProvider provider : values())
            if (provider.value.equals(value.strip())) return provider;
        throw new IllegalStateException("provider / MODEL_PROVIDER must be anthropic-compatible or opencode-go");
    }
}
