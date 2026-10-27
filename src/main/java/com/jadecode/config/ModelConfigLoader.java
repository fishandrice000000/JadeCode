package com.jadecode.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.jadecode.util.Json;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 将用户级模型配置与当前工作区分开：CLI 选文件，本类只读加载、合并和校验，工厂负责客户端。
 *
 * <p>显式 JSON 取代全局 JSON，两者不互补；任何分支都不读取项目 .env。
 * 进程环境覆盖选中的 JSON；全局文件不存在时也可仅使用进程环境，方便 CI 临时指定模型。
 * 所有错误只包含固定说明/字段名/行列号，不附带可能含 Key 的解析异常、原文或配置对象。</p>
 *
 * <p>只读模型配置，不创建文件、不改变工作区、不联网。含凭据文件的首次安装权限由文档中的
 * umask 077 与不覆盖目标的首次创建步骤保证；加载器不会擅自修改用户已有文件的权限。</p>
 */
public final class ModelConfigLoader {
    private static final String KEY = "ANTHROPIC_API_KEY";
    private static final String URL = "ANTHROPIC_BASE_URL";
    private static final String MODEL = "MODEL_ID";
    private static final String TOKENS = "MAX_TOKENS";
    private static final int MAX_BYTES = 64 * 1024;
    private static final Map<String, String> FIELDS = Map.of(
            "apiKey", KEY, "baseUrl", URL, "model", MODEL, "maxTokens", TOKENS);

    private ModelConfigLoader() { }

    /** 用户级默认位置；与非秘密 config.json 分开，不随工作区切换。 */
    public static Path defaultPath() {
        return JadePaths.defaults().config().resolve("model.json");
    }

    /**
     * 加载一次启动的模型配置；相对显式路径相对于启动目录，空值选择全局默认。
     * 文件损坏、指定文件缺失或配置无效均明确失败，不静默换端点；返回值不会修改任何输入。
     */
    public static AppConfig load(Path selectedFile) {
        return load(Path.of("").toAbsolutePath(), defaultPath(), selectedFile, System.getenv());
    }

    /** 测试入口：目录、全局路径和进程环境均可注入，不读取机器上的真实配置或环境凭据。 */
    static AppConfig load(Path workspace, Path globalFile, Path selectedFile, Map<String, String> process) {
        Path file = selectedFile == null ? globalFile : workspace.resolve(selectedFile).normalize();
        // 仅能确认全局文件不存在时才允许纯进程环境；其余读取失败不能静默忽略。
        boolean json = selectedFile != null || !Files.notExists(file, LinkOption.NOFOLLOW_LINKS);
        Map<String, String> values = json ? readJson(file) : new HashMap<>();
        // 只改变端点却沿用文件 Key 可能发往另一个服务；需要同一次显式环境覆盖带上 Key。
        requireMatchingCredential(process.get(URL), values.getOrDefault(URL, AppConfig.DEFAULT_BASE_URL), process.get(KEY));
        for (String name : FIELDS.values()) {
            if (process.containsKey(name)) values.put(name, process.get(name));
        }
        // 先校验再交给 AppConfig 转换，避免转换异常把无效额度的原始值写进 stderr。
        validate(values);
        return AppConfig.fromEnv(Env.of(values));
    }

    /**
     * 只解析四个已知字段，拒绝重复键、尾随 JSON、未知字段、null 和错误类型。
     * 读取最多 64 KiB + 1 字节，避免超大文件；异常不携带原始 JSON 或底层异常 cause。
     */
    private static Map<String, String> readJson(Path file) {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw invalid("Model configuration exceeds 64 KiB");
            try (JsonParser parser = Json.mapper().getFactory().createParser(bytes)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode root = Json.mapper().readTree(parser);
                if (root == null || !root.isObject() || parser.nextToken() != null)
                    throw invalid("Model configuration must contain one JSON object");
                Map<String, String> values = new HashMap<>();
                var fields = root.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    String name = field.getKey();
                    if (!FIELDS.containsKey(name)) throw invalid("Unsupported model configuration field");
                    JsonNode value = field.getValue();
                    if (name.equals("maxTokens")) {
                        if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0)
                            values.put(TOKENS, value.asText());
                        else if (value.isTextual() && value.textValue().strip().equalsIgnoreCase("auto"))
                            values.put(TOKENS, "auto");
                        else throw invalid("maxTokens must be auto or a positive integer");
                    } else {
                        if (!value.isTextual() || value.textValue().isBlank())
                            throw invalid(name + " must be a non-empty string");
                        values.put(FIELDS.get(name), value.textValue().strip());
                    }
                }
                return values;
            }
        } catch (JsonProcessingException failure) {
            var location = failure.getLocation();
            throw invalid("Invalid model configuration JSON" + (location == null ? ""
                    : " at line " + location.getLineNr() + ", column " + location.getColumnNr()));
        } catch (IOException | SecurityException failure) {
            throw invalid("Cannot read model configuration JSON; check its path and permissions");
        }
    }

    /** 端点没有变化时可沿用已选 Key；跨端点覆盖必须同源明确提供非空 Key。 */
    private static void requireMatchingCredential(String overridingUrl, String previousUrl, String overridingKey) {
        if (overridingUrl != null && !overridingUrl.strip().equals(previousUrl == null ? "" : previousUrl.strip())
                && (overridingKey == null || overridingKey.isBlank()))
            throw invalid("Changing ANTHROPIC_BASE_URL requires ANTHROPIC_API_KEY in the same override source");
    }

    /** 校验合并后的有效值；默认额度、默认端点/模型仍由 AppConfig 统一提供。 */
    private static void validate(Map<String, String> values) {
        if (values.get(KEY) == null || values.get(KEY).isBlank())
            throw invalid("Missing apiKey / ANTHROPIC_API_KEY; configure ~/.jadecode/config/model.json or --model-config");
        if (values.get(KEY).chars().anyMatch(c -> c < 32 || c == 127))
            throw invalid("apiKey / ANTHROPIC_API_KEY must not contain control characters");
        if (values.containsKey(MODEL) && values.get(MODEL).isBlank()) throw invalid("model / MODEL_ID must not be blank");
        String url = values.getOrDefault(URL, AppConfig.DEFAULT_BASE_URL);
        try {
            URI parsed = URI.create(url);
            if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme())) || parsed.getHost() == null
                    || parsed.getUserInfo() != null || parsed.getRawQuery() != null || parsed.getRawFragment() != null)
                throw invalid("baseUrl / ANTHROPIC_BASE_URL must be an HTTP(S) URL without credentials, query or fragment");
        } catch (IllegalArgumentException failure) { throw invalid("Invalid baseUrl / ANTHROPIC_BASE_URL"); }
        String tokens = values.get(TOKENS);
        if (tokens != null && !tokens.isBlank() && !tokens.strip().equalsIgnoreCase("auto")) {
            try {
                if (Integer.parseInt(tokens.strip()) <= 0) throw new NumberFormatException();
            } catch (NumberFormatException failure) { throw invalid("maxTokens / MAX_TOKENS must be auto or a positive integer"); }
        }
    }

    /** 固定格式、无 cause 的失败；不把可能含凭据的底层消息拼进诊断。 */
    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
}
