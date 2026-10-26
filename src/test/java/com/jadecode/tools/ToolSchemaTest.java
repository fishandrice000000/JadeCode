package com.jadecode.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolSchemaTest {

    /** 同义参数表即使插入顺序相反，也生成相同 JSON 文本；保留必填数组与原输入顺序。 */
    @Test void serializesPropertiesInStableOrderWithoutMutatingInputs() {
        var properties = new java.util.LinkedHashMap<String, String>();
        properties.put("zeta", "string"); properties.put("alpha", "integer");
        List<String> required = List.of("zeta", "alpha");
        ObjectNode first = ToolSchema.objectSchema(properties, required);
        ObjectNode second = ToolSchema.objectSchema(Map.of("alpha", "integer", "zeta", "string"), required);

        assertEquals(first.toString(), second.toString());
        assertEquals(List.of("alpha", "zeta"), first.get("properties").properties().stream().map(Map.Entry::getKey).toList());
        assertEquals("zeta", first.get("required").get(0).asText());
        assertEquals("alpha", first.get("required").get(1).asText());
        assertEquals(List.of("zeta", "alpha"), List.copyOf(properties.keySet()));
        assertEquals(List.of("zeta", "alpha"), required);
    }

    /** ① 基本形状:type/properties/required 三块齐全 */
    @Test
    void buildsBasicSchema() {
        ObjectNode schema = ToolSchema.objectSchema(Map.of("command", "string"), List.of("command"));

        assertEquals("object", schema.get("type").asText());
        assertEquals("string", schema.get("properties").get("command").get("type").asText());
        assertEquals(1, schema.get("required").size());
        assertEquals("command", schema.get("required").get(0).asText());
    }

    /** ② 多属性多类型:required 可以是 properties 的子集 */
    @Test
    void buildsMultiPropertySchema() {
        ObjectNode schema = ToolSchema.objectSchema(
                Map.of("path", "string", "limit", "integer"),
                List.of("path"));

        assertEquals(2, schema.get("properties").size());
        assertEquals("integer", schema.get("properties").get("limit").get("type").asText());
        assertEquals(1, schema.get("required").size());
        assertEquals("path", schema.get("required").get(0).asText());
    }

    /** ③ required 引用未声明的属性:一次报告所有缺失的 key */
    @Test
    void reportsAllUnDeclaredRequiredKeys() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ToolSchema.objectSchema(Map.of("command", "string"), List.of("comand", "commnad")));

        assertTrue(e.getMessage().contains("comand"));
        assertTrue(e.getMessage().contains("commnad"));
    }

    /** ④ 空 required:字段仍在,数组为空 */
    @Test
    void emptyRequiredIsAllowed() {
        ObjectNode schema = ToolSchema.objectSchema(Map.of("command", "string"), List.of());

        assertNotNull(schema.get("required"));
        assertEquals(0, schema.get("required").size());
    }

    /** ⑤ 空 properties:空对象 + 空数组 */
    @Test
    void emptyPropertiesIsAllowed() {
        ObjectNode schema = ToolSchema.objectSchema(Map.of(), List.of());

        assertEquals(0, schema.get("properties").size());
        assertEquals(0, schema.get("required").size());
    }
}
