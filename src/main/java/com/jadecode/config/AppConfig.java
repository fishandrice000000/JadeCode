package com.jadecode.config;

public record AppConfig(String apiKey, String baseUrl, String model, int maxTokens) {
    public static AppConfig fromEnv(Env env) {
        return new AppConfig(getApiKeyFrom(env), getBaseUrlFrom(env), getModelFrom(env), getMaxTokensFrom(env));
    }

    private static String getApiKeyFrom(Env env) {
        try {
            return env.required("ANTHROPIC_API_KEY");
        } catch (IllegalStateException e) {
            System.err.println("复制 .env.example 为 .env 并填入");
            throw e;
        }
    }

    private static String getBaseUrlFrom(Env env) {
        String s = env.get("ANTHROPIC_BASE_URL");
        return s == null ? "https://api.deepseek.com/anthropic" : s;
    }

    private static String getModelFrom(Env env) {
        String s = env.get("MODEL_ID");
        return s == null ? "deepseek-v4-flash" : s;
    }

    private static int getMaxTokensFrom(Env env) {
        String s = env.get("MAX_TOKENS");
        if (s == null || s.isBlank()) {
            return 8000;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("MAX_TOKENS 必须是数字,当前值:" + s);
        }
    }
}
