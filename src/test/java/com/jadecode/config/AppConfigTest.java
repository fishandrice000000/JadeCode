package com.jadecode.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    /** ① 四个配置齐全:字段全部正确,maxTokens 完成类型转换 */
    @Test
    void fullConfigMapsAllFields() {
        AppConfig c = AppConfig.fromEnv(Env.of(Map.of(
                "ANTHROPIC_API_KEY", "sk-test",
                "ANTHROPIC_BASE_URL", "https://example.com/anthropic",
                "MODEL_ID", "glm-5.2",
                "MAX_TOKENS", "16000")));

        assertEquals("sk-test", c.apiKey());
        assertEquals("https://example.com/anthropic", c.baseUrl());
        assertEquals("glm-5.2", c.model());
        assertEquals(16000, c.maxTokens());
    }

    /** ② 只配 apiKey:三个可选项取默认值 */
    @Test
    void missingOptionalsTakeDefaults() {
        AppConfig c = AppConfig.fromEnv(Env.of(Map.of("ANTHROPIC_API_KEY", "sk-test")));

        assertEquals("https://api.deepseek.com/anthropic", c.baseUrl());
        assertEquals("deepseek-v4-flash", c.model());
        assertEquals(8000, c.maxTokens());
    }

    /** ③ MAX_TOKENS 为空串也走默认值 */
    @Test
    void blankMaxTokensTakesDefault() {
        AppConfig c = AppConfig.fromEnv(Env.of(Map.of(
                "ANTHROPIC_API_KEY", "sk-test",
                "MAX_TOKENS", "")));

        assertEquals(8000, c.maxTokens());
    }

    /** ④ 缺 apiKey:启动即炸,异常消息指出缺少哪个配置 */
    @Test
    void missingApiKeyFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AppConfig.fromEnv(Env.of(Map.of())));

        assertTrue(e.getMessage().contains("ANTHROPIC_API_KEY"));
    }

    /** ⑤ MAX_TOKENS 不是数字:异常消息带上原始值 */
    @Test
    void invalidMaxTokensFailsFastWithValue() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AppConfig.fromEnv(Env.of(Map.of(
                        "ANTHROPIC_API_KEY", "sk-test",
                        "MAX_TOKENS", "abc"))));

        assertTrue(e.getMessage().contains("abc"));
    }
}
