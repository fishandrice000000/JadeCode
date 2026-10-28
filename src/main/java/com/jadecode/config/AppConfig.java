package com.jadecode.config;

/**
 * 运行期配置：由 {@link ModelConfigLoader} 合并 JSON/环境，或由离线调用方明确提供模型资料。
 *
 * <p>
 * 未指定 provider 时缺省值面向 DeepSeek:baseUrl 默认 {@code https://api.deepseek.com/anthropic},
 * model 默认 {@code deepseek-v4-flash},maxTokens 默认自动跟随已知模型输出上限。只有 API key 必填,
 * 缺失时打印指引并抛异常——fail-fast,不带着半套配置往下跑。
 * </p>
 *
 * @param apiKey    必填凭据，旧环境名仍为 ANTHROPIC_API_KEY；Go 使用 Bearer 发送此值
 * @param baseUrl   端点基址(来自 ANTHROPIC_BASE_URL;SDK 自动在其后拼 /v1/messages)
 * @param model     模型 id(来自 MODEL_ID)
 * @param maxTokens 正式请求输出设置；0 是内部 auto 标记，正整数是用户显式额度。
 *                  装配时必须先解析为真实正数，不能直接将 0 发送给模型
 * @param provider 启动时固定的接入约定；Go 仅适配 deepseek-v4.1-flash，仍使用 Messages
 */
public record AppConfig(String apiKey, String baseUrl, String model, int maxTokens, ModelProvider provider) {
    /** 所有配置来源共享原来的后端/模型缺省值，新增 JSON 不改变默认服务。 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic";
    public static final String DEFAULT_MODEL = "deepseek-v4-flash";
    /** 仅配置层使用的自动标记；模型未知时的回退值由 ModelMetadata 统一决定。 */
    public static final int AUTO_MAX_TOKENS = 0;

    /** 旧调用者仍使用原 Anthropic 兼容入口，不根据 URL 或 Key 内容偷偷切换身份。 */
    public AppConfig(String apiKey, String baseUrl, String model, int maxTokens) {
        this(apiKey, baseUrl, model, maxTokens, ModelProvider.ANTHROPIC_COMPATIBLE);
    }

    /**
     * 手工构造也验证接入范围；0 只表示 auto，不代表零 token 请求。
     * Go 的目标模型明确限定，不把改一个字符串误认为其它套餐模型也已适配。
     */
    public AppConfig {
        java.util.Objects.requireNonNull(provider, "provider");
        if (maxTokens < AUTO_MAX_TOKENS)
            throw new IllegalArgumentException("Output budget must be automatic or a positive integer");
        // 官方 Go URL 已明确表达服务商意图；缺少选择器时不能沿用旧认证并漏掉会话头。
        // 这里只报出修正方法，不根据 URL 静默切换 provider，也不读取/发送任何凭据。
        if (provider != ModelProvider.OPENCODE_GO && isOpenCodeGoEndpoint(baseUrl))
            throw new IllegalArgumentException("OpenCode Go endpoint requires provider: opencode-go in model.json (or MODEL_PROVIDER=opencode-go)");
        if (provider == ModelProvider.OPENCODE_GO) {
            if (!provider.defaultModel().equals(model))
                throw new IllegalArgumentException("OpenCode Go is adapted only for deepseek-v4.1-flash");
            // 显式代理/本机离线服务可以使用其它主机；官方主机必须走 Go Messages，不能误入余额计费路径。
            java.net.URI endpoint = java.net.URI.create(baseUrl);
            if ("opencode.ai".equalsIgnoreCase(endpoint.getHost())
                    && (!"https".equalsIgnoreCase(endpoint.getScheme())
                        || endpoint.getPort() != -1 && endpoint.getPort() != 443
                        || !"/inference/go/anthropic".equals(endpoint.getRawPath().replaceAll("/+$", ""))
                        || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null))
                throw new IllegalArgumentException("OpenCode Go Messages baseUrl must be https://opencode.ai/inference/go/anthropic");
        }
    }

    /**
     * 识别官方 Go 路由，只用于阻止错配，不给其它主机/路径猜测 Go 能力。
     * 路径边界要求 go 后结束或带斜杠，因此 /inference/gopher 不会误判。
     * 非法 URL 仍由原配置校验负责，本方法不回显 URL，也不改变旧手工构造的其它行为。
     */
    private static boolean isOpenCodeGoEndpoint(String baseUrl) {
        if (baseUrl == null) return false;
        try {
            java.net.URI endpoint = java.net.URI.create(baseUrl);
            String path = endpoint.getRawPath();
            return "opencode.ai".equalsIgnoreCase(endpoint.getHost()) && path != null
                    && (path.equals("/inference/go") || path.startsWith("/inference/go/"));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    /**
     * 从 Env 读出全部配置项,缺失项按各自缺省值补齐。
     *
     * @param env 加载器已合并、校验的内存快照；不读文件或再次查询进程环境
     * @return 完整配置
     * @throws IllegalStateException ANTHROPIC_API_KEY 缺失,或 MAX_TOKENS 既非 auto 也非正整数
     */
    public static AppConfig fromEnv(Env env) {
        ModelProvider provider = ModelProvider.parse(env.get("MODEL_PROVIDER"));
        return new AppConfig(getApiKeyFrom(env), getBaseUrlFrom(env, provider), getModelFrom(env, provider), getMaxTokensFrom(env), provider);
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

    /** 端点未显式提供时采用所选档案的 Messages 基址，不拼完整请求路径。 */
    private static String getBaseUrlFrom(Env env, ModelProvider provider) {
        String s = env.get("ANTHROPIC_BASE_URL");
        return s == null ? provider.defaultBaseUrl() : s;
    }

    /** 模型未显式提供时采用所选档案的目标；显式值仍须通过 Go 范围校验。 */
    private static String getModelFrom(Env env, ModelProvider provider) {
        String s = env.get("MODEL_ID");
        return s == null ? provider.defaultModel() : s;
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

    /**
     * 非秘密的恢复身份：供应商/协议可见，URL 和模型组合只存摘要，不保存 Key 或配置原文。
     * Go 历史中的 thinking 只能交回同一接入约定；末尾斜杠不构成另一个后端。
     */
    public String backendIdentity() {
        try {
            // 去掉不影响 SDK 路径拼接的尾斜杠，再区分实际端点与模型；Key 不参与摘要。
            String basis = baseUrl.replaceAll("/+$", "") + "\n" + model;
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(basis.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // 摘要只用于判断恢复资料是否同源，不是认证，也不把原 URL 写入会话清单。
            return provider.value() + ":messages:" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The JDK does not provide SHA-256", impossible);
        }
    }

    /** 配置可能被用于诊断；覆盖 record 的默认打印，避免把 API Key 带进日志。 */
    @Override
    public String toString() {
        return "AppConfig[apiKey=<redacted>, baseUrl=" + baseUrl + ", model=" + model + ", maxTokens=" + maxTokens
                + ", provider=" + provider.value() + "]";
    }
}
