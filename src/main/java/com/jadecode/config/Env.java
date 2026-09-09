package com.jadecode.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * .env 文件解析器 + 配置查询入口。
 *
 * <p>
 * 解析规则:逐行 trim;空行与 # 注释跳过;无 '=' 或 key 为空跳过;重复 key 后者胜。
 * 查询时先查 .env 解析结果,再回退 {@link System#getenv(String)}——.env 覆盖系统环境,
 * 与课程 override=True 语义一致。
 * </p>
 */
public final class Env {
    /** .env 文件的解析结果(key → value,已 trim) */
    private final Map<String, String> vars;

    /** 私有构造:统一从 load / loadDotEnv / of 进入 */
    private Env(Map<String, String> map) {
        this.vars = new HashMap<String, String>();
        vars.putAll(map);
    }

    /**
     * 解析指定 .env 文件。
     *
     * @param file .env 文件路径
     * @return 解析出的配置集
     * @throws IOException 文件读取失败
     */
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

    /** 从当前目录加载 .env;文件不存在时返回空配置(不报错——缺项由 AppConfig 判) */
    public static Env loadDotEnv() {
        return loadDotEnv(Path.of("")); // 空路径即当前目录,行为与原来一致
    }

    /**
     * 从指定目录加载 .env。
     *
     * @param dir 目录;其中不存在 .env 时返回空配置
     * @return 解析出的配置集
     * @throws UncheckedIOException 文件存在但读取失败
     */
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

    /** 测试用:直接从内存 Map 构造,绕开文件系统 */
    static Env of(Map<String, String> map) {
        return new Env(map);
    }

    /**
     * 查询可选配置:.env 优先,回退系统环境变量。
     *
     * @param key 配置名
     * @return 配置值;两处都没有则 null
     */
    public String get(String key) {
        String value = vars.get(key);
        return value != null ? value : System.getenv(key);
    }

    /**
     * 查询必填配置:.env 优先,回退系统环境变量。
     *
     * @param key 配置名
     * @return 配置值(非 null)
     * @throws IllegalStateException 两处都没有该配置
     */
    public String required(String key) throws IllegalStateException {
        String result = get(key);
        if (result != null)
            return result;
        else
            throw new IllegalStateException("必填配置不存在: " + key);
    }
}
