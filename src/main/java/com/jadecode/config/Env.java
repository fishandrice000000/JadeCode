package com.jadecode.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class Env {
    // .env 文件的解析结果
    private final Map<String, String> vars;

    private Env(Map<String, String> map) {
        this.vars = new HashMap<String, String>();
        vars.putAll(map);
    }

    // 指定目录解析 env 文件, 内容存储到 vars 中
    public static Env load(Path file) throws IOException {
        Map<String, String> map = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#"))
                continue; // 空行、注释
            int eq = trimmed.indexOf('=');
            if (eq <= 0)
                continue; // 没有 '=' 或 key 为空(eq==0),跳过
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            map.put(key, value); // 重复 key:put 覆盖,天然"后胜"
        }
        return new Env(map);
    }

    // 尝试在默认目录解析 env 文件, 内容存储到 vars 中
    public static Env loadDotEnv() {
        return loadDotEnv(Path.of("")); // 空路径即当前目录,行为与原来一致
    }

    public static Env loadDotEnv(Path dir) {
        Path dotenv = dir.resolve(".env");
        if (!Files.exists(dotenv)) {
            return new Env(Map.of());
        }
        try {
            return load(dotenv);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 .env 失败", e);
        }
    }

    // 测试用, 直接从内存中的 Map 中加载到 vars 中
    static Env of(Map<String, String> map) {
        return new Env(map);
    }

    // 查询一个可选配置是否在 vars 中, 没有就查 System.getenv(), 再没有返回 null
    public String get(String key) {
        String value = vars.get(key);
        return value != null ? value : System.getenv(key);
    }

    // 查询一个必填配置是否在 vars 或者在系统变量中, 没有就抛异常
    public String required(String key) throws IllegalStateException {
        String result = get(key);
        if (result != null)
            return result;
        else
            throw new IllegalStateException("必填配置不存在: " + key);
    }
}
