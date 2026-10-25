package com.jadecode.workflow;

import java.io.IOException;
import java.util.Set;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 校验流程实际需要的 JSON Schema 子集，挡住“下游代码把散文当对象”的失败。
 * 只支持这里列出的关键字；不支持的约束明确拒绝，不能忽略后假称满足完整 JSON Schema。
 * 所有方法只读入参，不修改共享 ObjectMapper 或 schema，无网络/文件副作用。
 */
public final class WorkflowSchema {
    private static final Set<String> KEYS = Set.of("type", "properties", "required", "additionalProperties",
            "items", "enum", "minItems", "maxItems", "minLength", "maxLength", "minimum", "maximum", "description");
    private static final Set<String> TYPES = Set.of("object", "array", "string", "boolean", "integer", "number", "null");
    private WorkflowSchema() { }

    /** 检查 schema 自身；递归限深，错误在注册或调用前发生，不触发模型修正请求。 */
    public static void checkSchema(JsonNode schema) { checkSchema(schema, 0); }

    /** 逐层检查关键字与类型，拒绝拼错字段或 schema 引用，而不是静默放过。 */
    private static void checkSchema(JsonNode schema, int depth) {
        if (depth > 32 || schema == null || !schema.isObject() || !TYPES.contains(schema.path("type").asText()))
            throw new IllegalArgumentException("Unsupported workflow schema type or depth");
        schema.fieldNames().forEachRemaining(key -> {
            if (!KEYS.contains(key)) throw new IllegalArgumentException("Unsupported workflow schema keyword: " + key);
        });
        if (schema.has("properties")) {
            if (!schema.get("properties").isObject()) throw new IllegalArgumentException("properties must be an object");
            schema.get("properties").forEach(child -> checkSchema(child, depth + 1));
        }
        if (schema.has("required")) {
            var required = schema.get("required");
            if (!required.isArray()) throw new IllegalArgumentException("required must be an array");
            var unique = new java.util.HashSet<String>();
            for (var key : required) {
                if (!key.isTextual() || !schema.path("properties").has(key.asText()) || !unique.add(key.asText()))
                    throw new IllegalArgumentException("required must name unique declared properties");
            }
        }
        if (schema.has("items")) checkSchema(schema.get("items"), depth + 1);
        if (schema.has("additionalProperties") && !schema.get("additionalProperties").isBoolean())
            throw new IllegalArgumentException("additionalProperties must be boolean");
        if (schema.has("enum") && (!schema.get("enum").isArray() || schema.get("enum").isEmpty()))
            throw new IllegalArgumentException("enum must be non-empty");
        for (String key : Set.of("minItems", "maxItems", "minLength", "maxLength")) {
            if (schema.has(key) && (!schema.get(key).canConvertToInt() || !schema.get(key).isIntegralNumber() || schema.get(key).asInt() < 0))
                throw new IllegalArgumentException(key + " must be a non-negative integer");
        }
        for (String key : Set.of("minimum", "maximum")) {
            if (schema.has(key) && !schema.get(key).isNumber()) throw new IllegalArgumentException(key + " must be numeric");
        }
    }

    /** 按已检查的 schema 验证值，失败抛可读的字段路径；不包含模型原始正文。 */
    public static void validate(JsonNode value, JsonNode schema) {
        checkSchema(schema);
        validate(value, schema, "$", 0);
    }

    /** 先检查本层类型，再进入子字段；整数与布尔值不混用，未知属性按显式契约拒绝。 */
    private static void validate(JsonNode value, JsonNode schema, String path, int depth) {
        if (value == null || depth > 32) throw new IllegalArgumentException("Invalid output at " + path);
        boolean matches = switch (schema.get("type").asText()) {
            case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean(); case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber(); case "null" -> value.isNull(); default -> false;
        };
        if (!matches) throw new IllegalArgumentException("Wrong value type at " + path);
        if (schema.has("enum")) {
            boolean found = false;
            for (var option : schema.get("enum")) found |= option.equals(value);
            if (!found) throw new IllegalArgumentException("Value outside enum at " + path);
        }
        if (value.isObject()) {
            for (var required : schema.path("required")) {
                if (!value.has(required.asText())) throw new IllegalArgumentException("Missing field at " + path + "." + required.asText());
            }
            value.fields().forEachRemaining(field -> {
                JsonNode child = schema.path("properties").get(field.getKey());
                if (child != null) validate(field.getValue(), child, path + "." + field.getKey(), depth + 1);
                else if (schema.has("additionalProperties") && !schema.get("additionalProperties").asBoolean())
                    throw new IllegalArgumentException("Unknown field at " + path + "." + field.getKey());
            });
        } else if (value.isArray()) {
            bounds(value.size(), schema, "minItems", "maxItems", path);
            if (schema.has("items")) {
                for (int i = 0; i < value.size(); i++) validate(value.get(i), schema.get("items"), path + "[" + i + "]", depth + 1);
            }
        } else if (value.isTextual()) {
            bounds(value.asText().codePointCount(0, value.asText().length()), schema, "minLength", "maxLength", path);
        } else if (value.isNumber()) {
            if (schema.has("minimum") && value.decimalValue().compareTo(schema.get("minimum").decimalValue()) < 0
                    || schema.has("maximum") && value.decimalValue().compareTo(schema.get("maximum").decimalValue()) > 0)
                throw new IllegalArgumentException("Numeric bound exceeded at " + path);
        }
    }

    /** 共用数组长度/字符串字数边界；缺少上界或下界代表该方向不作约束。 */
    private static void bounds(int size, JsonNode schema, String min, String max, String path) {
        if (schema.has(min) && size < schema.get(min).asInt() || schema.has(max) && size > schema.get(max).asInt())
            throw new IllegalArgumentException("Size bound exceeded at " + path);
    }

    /**
     * 严格读取一个完整 JSON 值，只额外接受整段 json 代码围栏；不从解释文字中猜取对象。
     * 该入口用于模型输出和存储；拒绝重复字段和尾随内容，失败不回显原文。
     */
    public static JsonNode parse(String text) throws IOException {
        String body = text.strip();
        if (body.startsWith("```json\n") && body.endsWith("\n```")) body = body.substring(8, body.length() - 4).strip();
        try (var parser = Json.mapper().getFactory().createParser(body)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode value = Json.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(parser);
            if (value == null) throw new IOException("Empty workflow JSON");
            return value;
        }
    }

    /** 对象字段递归排序，数组保持顺序，使同义 schema 不因 Map 枚举顺序变化而缓存失配。 */
    static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = Json.mapper().createObjectNode();
            var names = new java.util.TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            var array = Json.mapper().createArrayNode(); value.forEach(item -> array.add(canonical(item))); return array;
        }
        return value.deepCopy();
    }
}
