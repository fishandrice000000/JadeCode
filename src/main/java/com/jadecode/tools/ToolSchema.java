package com.jadecode.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

public final class ToolSchema {
    private ToolSchema() {
    }

    /**
     * 构造 {"type":"object","properties":{...},"required":[...]}
     * properties:属性名 → JSON Schema 类型名("string" / "integer" / ...)
     * required 中的每个 key 必须出现在 properties 里,否则抛 IllegalArgumentException
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
