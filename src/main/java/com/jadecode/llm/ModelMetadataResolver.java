package com.jadecode.llm;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.jadecode.config.AppConfig;
import com.jadecode.config.JadeSettings;
import com.jadecode.llm.ModelMetadata.Limits;
import com.jadecode.util.Json;

/**
 * 使用模型前查询限额，查询失败也不让原先可工作的 Agent 无法启动。
 *
 * <p>只适配明确知道的官方 HTTPS 地址：Anthropic Messages 兼容不意味着也有同样的模型目录。
 * 未识别的代理不发查询，更不会把代理密钥送到官方域名试运气。缓存只保存解析后的数字，
 * 不保存认证、原始响应或对话；过期、损坏或字段缺失都按未知处理。构造无 IO／网络副作用。</p>
 */
public final class ModelMetadataResolver {
    /** 可替换的只读传输，离线检查不使用真实密钥或公网；生产仍先验证官方地址。 */
    @FunctionalInterface public interface Fetcher {
        JsonNode get(URI endpoint, Map<String, String> headers, int timeoutMillis) throws IOException, InterruptedException;
    }
    private final Path cacheDirectory;
    private final Clock clock;
    private final Fetcher fetcher;

    public ModelMetadataResolver(Path cacheDirectory) { this(cacheDirectory, Clock.systemUTC(), ModelMetadataResolver::get); }

    /** 注入时钟与传输便于离线验证时效／失败；所有生产校验仍经过 resolve，不跳过身份检查。 */
    public ModelMetadataResolver(Path cacheDirectory, Clock clock, Fetcher fetcher) {
        this.cacheDirectory = cacheDirectory; this.clock = clock; this.fetcher = fetcher;
    }

    public static Path defaultCacheDirectory() {
        return Path.of(System.getProperty("user.home"), ".jadecode", "cache", "model-metadata");
    }

    /**
     * 一次生产启动调用一次，队友／辅助请求复用结果，不各自重复查询。
     * 手动值优先填入对应模型，但不能扩大已知官方上限。任何未取得的字段保持 null，
     * 下游据此继续采用自己原有的请求额度／压缩策略，而不是使用猜测的官方数字。
     * @param config 已加载的运行配置；只在发往已配置官方主机的请求头中使用认证，不输出它
     * @param settings 参数发现开关、时效及按精确模型 ID 保存的手动覆盖
     * @return 部分或完整限额和固定来源说明；普通网络／缓存失败不抛出
     */
    public ModelMetadata resolve(AppConfig config, JadeSettings.Models settings) {
        Limits official = Limits.unknown(); String source;
        Target target = target(config);
        if (!settings.autoDiscover()) source = "自动查询已关闭，沿用现有配置";
        else if (target == null) source = "未适配此端点的官方查询，沿用现有配置";
        else {
            // 1. 缓存按实际端点和精确请求模型区分；命中有效记录才免去查询。
            Path cache = cacheFile(config, target);
            official = readCache(cache, target, config.model(), settings.cacheTtlHours());
            if (official.known()) source = "有效官方参数缓存";
            else {
                source = "官方参数未取得，沿用现有配置";
                try {
                    Map<String, String> headers = target.deepseek()
                            ? Map.of("Authorization", "Bearer " + config.apiKey())
                            : Map.of("x-api-key", config.apiKey(), "anthropic-version", "2023-06-01");
                    JsonNode body = fetcher.get(target.endpoint(), headers, settings.timeoutMillis());
                    official = parse(body, target.deepseek(), config.model());
                    if (official.known()) {
                        source = "官方 API";
                        if (!writeCache(cache, target, config.model(), official)) source += "（缓存未写入，本次仍可使用）";
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); source = "参数查询被中断，沿用现有配置";
                } catch (IOException | RuntimeException unavailable) {
                    // 不能输出异常正文：第三方响应、URL 或 HTTP 调试信息可能含敏感资料。
                    source = "官方参数查询失败，沿用现有配置";
                }
            }
        }
        // 2. 手动设置与官方字段分别合并；即使网络不可用，也允许明确配置的值生效。
        Limits manual = settings.overrides().getOrDefault(config.model(), Limits.unknown());
        Limits effective = new Limits(choose(manual.contextWindowTokens(), official.contextWindowTokens()),
                choose(manual.maxInputTokens(), official.maxInputTokens()), choose(manual.maxOutputTokens(), official.maxOutputTokens()));
        if (manual.known()) source += "；合并此模型的手动限额（不超过已知官方值）";
        return new ModelMetadata(effective, source);
    }

    /** 用户可以更保守地收小限额，但不能用覆盖值突破已取得的官方限额。 */
    private static Integer choose(Integer manual, Integer official) {
        // 不写混合 Integer／int 的嵌套三目式：Java 可能先自动拆箱，未知的 null 会变成 NPE。
        // 先单独返回未知分支，只有两者都非空时才比较数值。
        if (manual == null) return official;
        if (official == null) return manual;
        return Math.min(manual, official);
    }

    /**
     * 只认精确主机／协议／已知基础路径；拒绝 user-info、查询参数、fragment 和非标准端口。
     * 不按模型名字猜服务商，不把任意兼容代理的凭据转交给官方站点。
     */
    private static Target target(AppConfig config) {
        try {
            URI base = URI.create(config.baseUrl());
            if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null || base.getRawUserInfo() != null
                    || base.getRawQuery() != null || base.getRawFragment() != null || base.getPort() != -1 && base.getPort() != 443)
                return null;
            String host = base.getHost(), path = base.getRawPath().replaceAll("/+$", "");
            if (host.equalsIgnoreCase("api.deepseek.com") && java.util.Set.of("", "/anthropic", "/v1").contains(path))
                return new Target(URI.create("https://api.deepseek.com/models"), true);
            if (host.equalsIgnoreCase("api.anthropic.com") && java.util.Set.of("", "/v1").contains(path))
                return new Target(URI.create("https://api.anthropic.com/v1/models/"
                        + URLEncoder.encode(config.model(), StandardCharsets.UTF_8).replace("+", "%20")), false);
        } catch (IllegalArgumentException invalid) { /* 非法端点不用于参数发现；原配置的消息请求仍按原机制处理。 */ }
        return null;
    }
    private record Target(URI endpoint, boolean deepseek) { }

    /**
     * 两家官方字段有不同语义。DeepSeek 必须精确匹配列表中的 ID，不能拿第一项或猜别名；
     * Anthropic retrieve 接口本身可以解析别名，所以返回的规范 ID 不必等于请求别名。
     */
    private static Limits parse(JsonNode root, boolean deepseek, String model) {
        if (root == null || !root.isObject()) return Limits.unknown();
        if (!deepseek) return root.path("id").isTextual() && !root.path("id").textValue().isBlank()
                ? new Limits(null, positive(root, "max_input_tokens"), positive(root, "max_tokens")) : Limits.unknown();
        JsonNode data = root.path("data");
        if (data.isArray()) for (JsonNode item : data) {
            if (item.isObject() && item.path("id").isTextual() && item.path("id").textValue().equals(model))
                return new Limits(positive(item, "context_window"), null, positive(item, "max_output_tokens"));
        }
        return Limits.unknown();
    }

    /** 服务端 null、零、小数、字符串或溢出的数字不算事实；其它有效字段仍可以使用。 */
    private static Integer positive(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0 ? value.intValue() : null;
    }

    /** 文件名只用摘要，模型名中的斜杠或特殊字符不能变成磁盘路径。摘要不含密钥。 */
    private Path cacheFile(AppConfig config, Target target) {
        try {
            byte[] bytes = (config.baseUrl() + "\n" + target.endpoint() + "\n" + config.model()).getBytes(StandardCharsets.UTF_8);
            return cacheDirectory.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + ".json");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("JDK 缺少 SHA-256", impossible); }
    }

    /** 缓存只是一份可丢弃的派生资料；坏文件、过期或时钟倒退不会阻止查询及原会话启动。 */
    private Limits readCache(Path path, Target target, String model, int ttlHours) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536) return Limits.unknown();
            JsonNode data = Json.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(Files.readString(path));
            if (data == null || !data.path("version").isInt() || data.path("version").intValue() != 1
                    || !data.path("endpoint").asText().equals(target.endpoint().toString()) || !data.path("model").asText().equals(model))
                return Limits.unknown();
            Instant fetched = Instant.parse(data.path("fetchedAt").asText());
            if (fetched.isAfter(clock.instant()) || !fetched.plus(Duration.ofHours(ttlHours)).isAfter(clock.instant())) return Limits.unknown();
            JsonNode limits = data.path("limits");
            return new Limits(positive(limits, "contextWindowTokens"), positive(limits, "maxInputTokens"), positive(limits, "maxOutputTokens"));
        } catch (IOException | RuntimeException invalid) { return Limits.unknown(); }
    }

    /** 同目录临时文件再替换，避免半份缓存；IO 失败只影响缓存命中，不回退本次成功查询的数据。 */
    private boolean writeCache(Path path, Target target, String model, Limits limits) {
        Path temporary = null;
        try {
            Files.createDirectories(cacheDirectory);
            var root = Json.mapper().createObjectNode().put("version", 1).put("endpoint", target.endpoint().toString())
                    .put("model", model).put("fetchedAt", clock.instant().toString());
            root.set("limits", Json.mapper().valueToTree(limits));
            temporary = Files.createTempFile(cacheDirectory, "model-", ".tmp");
            Files.writeString(temporary, root.toPrettyString() + "\n");
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException | RuntimeException unavailable) { return false; }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException | RuntimeException ignored) { } }
    }

    /**
     * 不跟随重定向，避免认证被发往另一主机；总等待含响应正文，超时主动取消。
     * 不发模型生成请求，不重试。异常只携带固定说明，调用者不能回显响应或请求头。
     */
    private static JsonNode get(URI endpoint, Map<String, String> headers, int timeoutMillis) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        java.util.concurrent.CompletableFuture<HttpResponse<String>> pending = null;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeoutMillis)).GET();
            headers.forEach(builder::header);
            pending = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var response = pending.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200 || response.body().length() > 2_000_000) throw new IOException("模型目录查询未成功");
            return Json.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IOException("模型目录查询失败或超时");
        } finally {
            // shutdownNow 不像 close 那样无限等外部服务，初始化不能因元数据连接挂住。
            if (pending != null) pending.cancel(true);
            http.shutdownNow();
        }
    }
}
