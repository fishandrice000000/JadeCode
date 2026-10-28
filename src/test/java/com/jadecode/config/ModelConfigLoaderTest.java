package com.jadecode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 所有文件和环境均为临时夹具，不加载用户真实 model.json/.env，也不创建模型客户端。 */
class ModelConfigLoaderTest {
    @TempDir Path directory;

    private Path workspace() throws Exception { return Files.createDirectories(directory.resolve("workspace")); }
    private Path global() { return directory.resolve("home/config/model.json"); }

    private Path write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text);
    }

    @Test
    void globalJsonWorksAcrossDirectoriesAndNeverMergesProjectDotenv() throws Exception {
        write(global(), """
                {"apiKey":"global-key","baseUrl":"https://global.example/anthropic","model":"global-model","maxTokens":12000}
                """);
        Path first = workspace();
        write(first.resolve(".env"), "ANTHROPIC_API_KEY=project-key\nANTHROPIC_BASE_URL=https://project.example\n");
        Path second = Files.createDirectory(directory.resolve("another-project"));
        for (Path cwd : new Path[] {first, second}) {
            AppConfig config = ModelConfigLoader.load(cwd, global(), null, Map.of());
            assertEquals("global-key", config.apiKey());
            assertEquals("https://global.example/anthropic", config.baseUrl());
            assertEquals("global-model", config.model());
            assertEquals(12000, config.maxTokens());
        }
        assertFalse(Files.exists(second.resolve(".env")));
        assertFalse(Files.exists(second.resolve(".jadecode")), "loading configuration must not create workspace data");
    }

    @Test
    void explicitFileReplacesGlobalAndProcessOverridesOnlyTheSelectedFile() throws Exception {
        write(global(), "{\"apiKey\":\"global-key\",\"model\":\"global-model\"}");
        Path cwd = workspace();
        Path selected = write(cwd.resolve("profiles/local.json"), "{\"apiKey\":\"selected-key\",\"model\":\"selected-model\"}");
        AppConfig config = ModelConfigLoader.load(cwd, global(), cwd.relativize(selected),
                Map.of("MODEL_ID", "process-model", "MAX_TOKENS", "9000"));
        assertEquals("selected-key", config.apiKey());
        assertEquals("process-model", config.model());
        assertEquals(9000, config.maxTokens());
        assertThrows(IllegalStateException.class,
                () -> ModelConfigLoader.load(cwd, global(), Path.of("missing.json"), Map.of("ANTHROPIC_API_KEY", "env-key")));
        write(selected, "{\"model\":\"without-a-key\"}");
        assertThrows(IllegalStateException.class, () -> ModelConfigLoader.load(cwd, global(), selected, Map.of()),
                "explicit JSON must not borrow a key from the global JSON");
        assertEquals("env-key", ModelConfigLoader.load(cwd, global(), selected, Map.of("ANTHROPIC_API_KEY", "env-key")).apiKey());
    }

    @Test
    void dotenvIsIgnoredEvenWithoutJsonAndProcessOnlyStillWorks() throws Exception {
        Path cwd = workspace();
        write(cwd.resolve(".env"), "ANTHROPIC_API_KEY=legacy-key\nMODEL_ID=legacy-model\nMAX_TOKENS=8000\n");
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> ModelConfigLoader.load(cwd, global(), null, Map.of()));
        assertTrue(missing.getMessage().contains("Missing apiKey"), "a project .env must never supply credentials");
        AppConfig process = ModelConfigLoader.load(cwd, global(), null,
                Map.of("ANTHROPIC_API_KEY", "env-key", "MODEL_ID", "process-model"));
        assertEquals("env-key", process.apiKey());
        assertEquals("process-model", process.model());
        assertEquals(AppConfig.AUTO_MAX_TOKENS, process.maxTokens());
        Files.delete(cwd.resolve(".env"));
        // 用目录占住旧文件名：若后端仍尝试读取它，这里就无法仅凭环境正常启动。
        Files.createDirectory(cwd.resolve(".env"));
        AppConfig environmentOnly = ModelConfigLoader.load(cwd, global(), null, Map.of("ANTHROPIC_API_KEY", "env-key"));
        assertEquals("env-key", environmentOnly.apiKey());
        assertEquals(AppConfig.DEFAULT_MODEL, environmentOnly.model());
    }

    @Test
    void endpointOverridesCannotBorrowCredentialsFromTheOtherSource() throws Exception {
        Path cwd = workspace();
        write(global(), "{\"apiKey\":\"file-key\",\"baseUrl\":\"https://first.example\"}");
        assertThrows(IllegalStateException.class, () -> ModelConfigLoader.load(cwd, global(), null,
                Map.of("ANTHROPIC_BASE_URL", "https://second.example")));
        AppConfig changed = ModelConfigLoader.load(cwd, global(), null,
                Map.of("ANTHROPIC_BASE_URL", "https://second.example", "ANTHROPIC_API_KEY", "env-key"));
        assertEquals("env-key", changed.apiKey());
        assertEquals("https://second.example", changed.baseUrl());
        assertEquals("file-key", ModelConfigLoader.load(cwd, global(), null,
                Map.of("ANTHROPIC_BASE_URL", "https://first.example")).apiKey());
        Files.delete(global());
        write(cwd.resolve(".env"), "ANTHROPIC_BASE_URL=https://project.example\n");
        assertEquals(AppConfig.DEFAULT_BASE_URL, ModelConfigLoader.load(cwd, global(), null,
                Map.of("ANTHROPIC_API_KEY", "process-key")).baseUrl());
    }

    @Test
    void malformedAndInvalidConfigurationFailsWithoutDisclosingValuesOrFallingBack() throws Exception {
        Path cwd = workspace();
        write(cwd.resolve(".env"), "ANTHROPIC_API_KEY=legacy-key\n");
        String secret = "DO_NOT_DISCLOSE_THIS_FIXTURE_VALUE";
        for (String content : new String[] {
                "[]", "{} {}", "{\"apiKey\":\"" + secret + "\",", "{\"apiKey\":\"first\",\"apiKey\":\"" + secret + "\"}",
                "{\"" + secret + "\":true}", "{\"apiKey\":null}", "{\"apiKey\":\"\"}",
                "{\"apiKey\":\"" + secret + "\",\"baseUrl\":\"invalid\"}",
                "{\"apiKey\":\"" + secret + "\",\"model\":false}",
                "{\"apiKey\":\"" + secret + "\",\"maxTokens\":\"8000\"}",
                "{\"apiKey\":\"" + secret + "\",\"maxTokens\":0}",
                "{\"apiKey\":\"" + secret + "\",\"maxTokens\":1.2}",
                "{\"apiKey\":\"" + secret + "\",\"maxTokens\":2147483648}", " ".repeat(65537)}) {
            write(global(), content);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> ModelConfigLoader.load(cwd, global(), null, Map.of()));
            assertFalse(failure.toString().contains(secret));
            assertNull(failure.getCause(), "raw parser failures must not leak a JSON excerpt through the cause chain");
        }
    }

    @Test
    void diagnosticsRedactCredentialsAndEnvironmentValidationDoesNotEchoValues() throws Exception {
        Path cwd = workspace();
        String secret = "DO_NOT_PRINT_FIXTURE_KEY";
        AppConfig config = ModelConfigLoader.load(cwd, global(), null, Map.of("ANTHROPIC_API_KEY", secret));
        assertFalse(config.toString().contains(secret));
        assertTrue(config.toString().contains("<redacted>"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ModelConfigLoader.load(cwd,
                global(), null, Map.of("ANTHROPIC_API_KEY", secret, "MAX_TOKENS", secret)));
        assertFalse(failure.toString().contains(secret));
        assertNull(failure.getCause());
    }

    /** Go 显式选择后使用自己的缺省端点/模型；不读取其它凭据来源或继承直连缺省值。 */
    @Test void goProfileUsesItsOwnDefaultsAndRejectsModelsOutsideTheAuthorizedScope() throws Exception {
        Path cwd = workspace();
        write(global(), "{\"provider\":\"opencode-go\",\"apiKey\":\"fixture-go-key\"}");
        AppConfig go = ModelConfigLoader.load(cwd, global(), null, Map.of());
        assertEquals(ModelProvider.OPENCODE_GO, go.provider());
        assertEquals("https://opencode.ai/inference/go/anthropic", go.baseUrl());
        assertEquals("deepseek-v4.1-flash", go.model());
        assertEquals(AppConfig.AUTO_MAX_TOKENS, go.maxTokens());
        // 非目标 ID 只做配置拒绝验证，不为其它模型发送请求或声明能力。
        assertThrows(IllegalStateException.class, () -> ModelConfigLoader.load(cwd, global(), null,
                Map.of("MODEL_ID", "outside-authorized-scope")));
        assertThrows(IllegalStateException.class, () -> ModelConfigLoader.load(cwd, global(), null,
                Map.of("ANTHROPIC_BASE_URL", "https://opencode.ai/inference/anthropic", "ANTHROPIC_API_KEY", "fixture-key")));
    }

    /** provider 也能改变 URL/认证缺省值；环境切换必须同源提供 Key，不借用文件的旧凭据。 */
    @Test void changingProviderRequiresAnExplicitCredentialAndOldConfigurationStillWorks() throws Exception {
        Path cwd = workspace();
        write(global(), "{\"apiKey\":\"fixture-direct-key\"}");
        assertEquals(ModelProvider.ANTHROPIC_COMPATIBLE, ModelConfigLoader.load(cwd, global(), null, Map.of()).provider());
        assertThrows(IllegalStateException.class, () -> ModelConfigLoader.load(cwd, global(), null,
                Map.of("MODEL_PROVIDER", "opencode-go")));
        AppConfig changed = ModelConfigLoader.load(cwd, global(), null,
                Map.of("MODEL_PROVIDER", "opencode-go", "ANTHROPIC_API_KEY", "fixture-go-key"));
        assertEquals(ModelProvider.OPENCODE_GO, changed.provider());
        assertEquals("deepseek-v4.1-flash", changed.model());
        assertEquals("fixture-go-key", changed.apiKey());
        String secret = "DO_NOT_DISCLOSE_PROVIDER_FIXTURE";
        var failure = assertThrows(IllegalStateException.class, () -> ModelConfigLoader.load(cwd, global(), null,
                Map.of("MODEL_PROVIDER", secret)));
        assertFalse(failure.toString().contains(secret)); assertNull(failure.getCause());
    }

    /** Go URL 不能沿用旧默认身份；在发请求前直接指出缺少 provider，避免服务端才报 MissingSessionID。 */
    @Test void goEndpointWithoutGoProviderFailsBeforeCreatingAClient() throws Exception {
        Path cwd = workspace();
        String endpoint = "https://opencode.ai/inference/go/anthropic";
        write(global(), "{\"apiKey\":\"fixture-secret\",\"baseUrl\":\"" + endpoint
                + "\",\"model\":\"deepseek-v4.1-flash\"}");
        var failure = assertThrows(IllegalStateException.class,
                () -> ModelConfigLoader.load(cwd, global(), null, Map.of()));
        assertTrue(failure.getMessage().contains("provider"));
        assertTrue(failure.getMessage().contains("opencode-go"));
        assertFalse(failure.getMessage().contains("fixture-secret")); assertNull(failure.getCause());
        assertThrows(IllegalArgumentException.class,
                () -> new AppConfig("fixture-key", endpoint, "deepseek-v4.1-flash", 8000));
        // URL 是配置检查依据，不把相似路径或另一个主机自动当成 Go 服务。
        assertDoesNotThrow(() -> new AppConfig("fixture-key", "https://proxy.example/inference/go/anthropic", "fixture", 8000));
        write(global(), "{\"apiKey\":\"fixture-key\",\"provider\":\"opencode-go\"}");
        assertEquals(ModelProvider.OPENCODE_GO, ModelConfigLoader.load(cwd, global(), null, Map.of()).provider());
    }
}
