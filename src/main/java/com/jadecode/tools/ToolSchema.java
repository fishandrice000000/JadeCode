package com.jadecode.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 拼接平铺工具参数结构（JSON Schema），供 {@link Tool#inputSchema()} 的默认实现使用。
 *
 * <p>只支持单层标量参数。需要数组、嵌套对象或枚举的工具不用它，
 * 自己重写 {@link Tool#inputSchema()} 手拼（例如 todo_write 的 todos 数组）。
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
     * <p>属性名按自然顺序生成，避免调用方 Map 的遍历顺序改变发给模型的字段排列。
     * required 数组保留调用方顺序；不修改传入的 Map 或 List，也不访问文件。</p>
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
            throw new IllegalArgumentException("properties is missing an attribute declared in required: " + lostKeys);
        }

        // 2. 创建 root
        ObjectNode root = Json.mapper().createObjectNode();

        // 3. 放入 "type": "object"
        root.put("type", "object");

        // 4. 用排序副本构建 properties；不要原地修改工具自己的参数表。
        ObjectNode props = root.putObject("properties");
        for (Map.Entry<String, String> e : new TreeMap<>(properties).entrySet()) {
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
