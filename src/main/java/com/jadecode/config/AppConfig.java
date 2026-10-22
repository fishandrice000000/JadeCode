package com.jadecode.config;

/**
 * 运行期配置:从环境变量(经 {@link Env} 读取)解析出的 API key、端点、模型、输出上限。
 *
 * <p>
 * 缺省值面向 DeepSeek:baseUrl 默认 {@code https://api.deepseek.com/anthropic},
 * model 默认 {@code deepseek-v4-flash},maxTokens 默认 8000。只有 API key 必填,
 * 缺失时打印指引并抛异常——fail-fast,不带着半套配置往下跑。
 * </p>
 *
 * @param apiKey    Anthropic 协议 API key(必填,来自 ANTHROPIC_API_KEY)
 * @param baseUrl   端点基址(来自 ANTHROPIC_BASE_URL;SDK 自动在其后拼 /v1/messages)
 * @param model     模型 id(来自 MODEL_ID)
 * @param maxTokens 单次请求输出上限(来自 MAX_TOKENS,须为数字)
 */
public record AppConfig(String apiKey, String baseUrl, String model, int maxTokens) {

    /**
     * 从 Env 读出全部配置项,缺失项按各自缺省值补齐。
     *
     * @param env 已加载的环境(.env 解析结果 + 系统环境变量回退)
     * @return 完整配置
     * @throws IllegalStateException ANTHROPIC_API_KEY 缺失,或 MAX_TOKENS 不是数字
     */
    public static AppConfig fromEnv(Env env) {
        return new AppConfig(getApiKeyFrom(env), getBaseUrlFrom(env), getModelFrom(env), getMaxTokensFrom(env));
    }

    /** 必填项缺失时补一句指引(用户最常见的初次运行失败),再原样抛出 */
    private static String getApiKeyFrom(Env env) {
        try {
            return env.required("ANTHROPIC_API_KEY");
        } catch (IllegalStateException e) {
            System.err.println("Copy .env.example to .env and fill it in");
            throw e;
        }
    }

    /** 端点基址;缺省 DeepSeek 的 Anthropic 兼容端点 */
    private static String getBaseUrlFrom(Env env) {
        String s = env.get("ANTHROPIC_BASE_URL");
        return s == null ? "https://api.deepseek.com/anthropic" : s;
    }

    /** 模型 id;缺省 deepseek-v4-flash */
    private static String getModelFrom(Env env) {
        String s = env.get("MODEL_ID");
        return s == null ? "deepseek-v4-flash" : s;
    }

    /** 输出上限;缺省 8000,配了但非数字则报错(静默回退会掩盖拼写错误) */
    private static int getMaxTokensFrom(Env env) {
        String s = env.get("MAX_TOKENS");
        if (s == null || s.isBlank()) {
            return 8000;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("MAX_TOKENS must be a number; current value:" + s);
        }
    }
}
