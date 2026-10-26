package com.jadecode.config;

import java.util.Map;

/**
 * 已完成来源选择与合并的配置快照，只在内存中查询，不读取 .env 或其它磁盘文件。
 *
 * <p>ModelConfigLoader 统一决定 JSON 与进程环境的优先级；本类复制最终键值，供 AppConfig
 * 转换使用。缺项直接返回 null，不再隐式查询 System.getenv，保证校验结果不会被第二次回退改变。</p>
 */
public final class Env {
    private final Map<String, String> values;

    /** 复制输入 Map，避免调用方后续修改改变本次启动配置；没有 IO 副作用。 */
    private Env(Map<String, String> values) { this.values = Map.copyOf(values); }

    /** 配置层与同包测试传入已选定键值；不会加载机器环境或工作目录中的任何文件。 */
    static Env of(Map<String, String> values) { return new Env(values); }

    /** 查询可选项；缺项返回 null，原样保留显式字符串供后续类型校验。 */
    public String get(String key) { return values.get(key); }

    /** 查询必填项；缺项只报告键名，不打印已有值，也不尝试从其它来源补齐。 */
    public String required(String key) {
        String value = get(key);
        if (value == null) throw new IllegalStateException("Required configuration is missing: " + key);
        return value;
    }
}
