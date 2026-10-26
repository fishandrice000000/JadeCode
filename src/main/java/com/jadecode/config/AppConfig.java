package com.jadecode.config;

/**
 * 运行期配置：由 {@link ModelConfigLoader} 合并 JSON/环境，或由离线调用方明确提供模型资料。
 *
 * <p>
 * 缺省值面向 DeepSeek:baseUrl 默认 {@code https://api.deepseek.com/anthropic},
 * model 默认 {@code deepseek-v4-flash},maxTokens 默认自动跟随已知模型输出上限。只有 API key 必填,
 * 缺失时打印指引并抛异常——fail-fast,不带着半套配置往下跑。
 * </p>
 *
 * @param apiKey    Anthropic 协议 API key(必填,来自 ANTHROPIC_API_KEY)
 * @param baseUrl   端点基址(来自 ANTHROPIC_BASE_URL;SDK 自动在其后拼 /v1/messages)
 * @param model     模型 id(来自 MODEL_ID)
 * @param maxTokens 正式请求输出设置；0 是内部 auto 标记，正整数是用户显式额度。
 *                  装配时必须先解析为真实正数，不能直接将 0 发送给模型
 */
public record AppConfig(String apiKey, String baseUrl, String model, int maxTokens) {
    /** 所有配置来源共享原来的后端/模型缺省值，新增 JSON 不改变默认服务。 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic";
    public static final String DEFAULT_MODEL = "deepseek-v4-flash";
    /** 仅配置层使用的自动标记；模型未知时的回退值由 ModelMetadata 统一决定。 */
    public static final int AUTO_MAX_TOKENS = 0;

    /** 手工构造同样拒绝负数；0 保留给程序明确构造 auto 配置，不代表零 token 请求。 */
    public AppConfig {
        if (maxTokens < AUTO_MAX_TOKENS)
            throw new IllegalArgumentException("Output budget must be automatic or a positive integer");
    }

    /**
     * 从 Env 读出全部配置项,缺失项按各自缺省值补齐。
     *
     * @param env 加载器已合并、校验的内存快照；不读文件或再次查询进程环境
     * @return 完整配置
     * @throws IllegalStateException ANTHROPIC_API_KEY 缺失,或 MAX_TOKENS 既非 auto 也非正整数
     */
    public static AppConfig fromEnv(Env env) {
        return new AppConfig(getApiKeyFrom(env), getBaseUrlFrom(env), getModelFrom(env), getMaxTokensFrom(env));
    }

    /** 必填项缺失时补一句指引(用户最常见的初次运行失败),再原样抛出 */
    private static String getApiKeyFrom(Env env) {
        try {
            return env.required("ANTHROPIC_API_KEY");
        } catch (IllegalStateException e) {
            System.err.println("Configure ~/.jadecode/config/model.json, --model-config, or ANTHROPIC_API_KEY");
            throw e;
        }
    }

    /** 端点基址;缺省 DeepSeek 的 Anthropic 兼容端点 */
    private static String getBaseUrlFrom(Env env) {
        String s = env.get("ANTHROPIC_BASE_URL");
        return s == null ? DEFAULT_BASE_URL : s;
    }

    /** 模型 id;缺省 deepseek-v4-flash */
    private static String getModelFrom(Env env) {
        String s = env.get("MODEL_ID");
        return s == null ? DEFAULT_MODEL : s;
    }

    /**
     * 缺失、空白或 auto 选择自动额度；显式数字必须为正数。
     * 不把旧配置 8000 猜成“没有设置”，避免抹掉用户主动选择的限制。
     */
    private static int getMaxTokensFrom(Env env) {
        String s = env.get("MAX_TOKENS");
        if (s == null || s.isBlank() || s.strip().equalsIgnoreCase("auto")) {
            return AUTO_MAX_TOKENS;
        }
        try {
            int configured = Integer.parseInt(s.strip());
            if (configured > 0) return configured;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("MAX_TOKENS must be auto or a positive integer; current value:" + s, e);
        }
        throw new IllegalStateException("MAX_TOKENS must be auto or a positive integer; current value:" + s);
    }

    /** 配置可能被用于诊断；覆盖 record 的默认打印，避免把 API Key 带进日志。 */
    @Override
    public String toString() {
        return "AppConfig[apiKey=<redacted>, baseUrl=" + baseUrl + ", model=" + model + ", maxTokens=" + maxTokens + "]";
    }
}
