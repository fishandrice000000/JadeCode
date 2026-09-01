package com.jadecode.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolSchemaTest {

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
