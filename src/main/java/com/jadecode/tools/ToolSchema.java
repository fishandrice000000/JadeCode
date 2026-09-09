package com.jadecode.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 平铺工具 JSON Schema 的静态工厂,供 {@link Tool#inputSchema()} 默认实现使用。
 *
 * <p>只表达单层标量参数表;需要数组 / 嵌套对象 / 枚举的工具绕过这里,
 * override {@link Tool#inputSchema()} 用 Json.mapper() 手拼(例:TodoWriteTool 的 todos)。</p>
 */
public final class ToolSchema {
    /** 纯静态工具类,禁止实例化 */
    private ToolSchema() {
    }

    /**
     * 构造 {"type":"object","properties":{...},"required":[...]}。
     *
     * <p>先做一致性校验:required 里的每个 key 必须已声明在 properties 里,否则是
     * 实现类自相矛盾(必填却无参数描述),立即抛错让开发期尽早暴露。</p>
     *
     * @param properties 属性名 → JSON Schema 类型名("string" / "integer" / ...)
     * @param required   required 数组要列的 key,必须是 properties 的子集
     * @return 拼好的 schema 根节点
     * @throws IllegalArgumentException properties 中缺少 required 已声明的属性
     */
    public static ObjectNode objectSchema(Map<String, String> properties, List<String> required) {
        // 1. 检验代码写的 properties 有没有漏参数
        List<String> lostKeys = new ArrayList<>();
        for (String key : required) {
            if (!properties.containsKey(key)) {
                lostKeys.add(key);
            }
        }
        if (!lostKeys.isEmpty()) {
            throw new IllegalArgumentException("properties 中缺少 required 中已声明的属性: " + lostKeys);
        }

        // 2. 创建 root
        ObjectNode root = Json.mapper().createObjectNode();

        // 3. 放入 "type": "object"
        root.put("type", "object");

        // 4. 构建 properties
        ObjectNode props = root.putObject("properties");
        for (Map.Entry<String, String> e : properties.entrySet()) {
            props.putObject(e.getKey()).put("type", e.getValue());
        }

        // 5. 构建 required
        ArrayNode arr = root.putArray("required");
        for (String key : required) {
            arr.add(key);
        }

        return root;
    }
}
