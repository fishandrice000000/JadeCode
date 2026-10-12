package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CompactTool 薄壳行为测试:元数据、空 object schema,以及"忽略参数、恒定输出"的执行契约。
 *
 * <p>
 * 这些断言保障的 Agent 行为是:模型能<b>看见</b>一个无参数工具来表达"想压缩"的请求;
 * 工具执行只产生一条稳定信号,不碰历史、不调模型、不产生 IO——真正的压缩由后续集成的循环
 * 在整批工具交换闭合之后处理。因此这里不 mock 任何依赖,也不做文件系统探针:
 * 无依赖、无副作用本身就是契约的一部分。
 * </p>
 */
class CompactToolTest {

    /** 被测工具(无构造参数、无状态,可以直接复用同一个实例) */
    private final CompactTool tool = new CompactTool();

    /** 元数据:工具名、课程原文描述、空参数表与空必填表 */
    @Test
    void exposesNoArgumentMetadata() {
        assertEquals("compact", tool.name(), "模型用这个名字在 tool_use 里选中本工具");
        assertEquals("Summarize earlier conversation to free context space.", tool.description(),
                "描述必须沿用课程原文,让模型知道何时该请求压缩");
        assertEquals(Map.of(), tool.properties(), "无参数工具:参数表为空");
        assertEquals(List.of(), tool.required(), "无参数工具:必填表为空");
    }

    /**
     * 默认 schema 必须正好是空 object:模型看到"不需要任何参数"。
     * 本工具不 override {@code inputSchema()},所以这里同时固定了"复用 Tool 默认实现"这一点,
     * 并确认没有引入额外字段或额外限制。
     */
    @Test
    void defaultSchemaIsAnEmptyObjectSchema() throws Exception {
        JsonNode schema = tool.inputSchema();
        JsonNode expected = Json.mapper().readTree("{\"type\":\"object\",\"properties\":{},\"required\":[]}");

        assertEquals(expected, schema, "schema 必须与 Tool.inputSchema() 的默认形状一致");
        assertEquals(3, schema.size(), "只应有 type / properties / required,不新增 additionalProperties 等限制");
        assertTrue(schema.get("properties").isObject(), "properties 必须是空对象");
        assertTrue(schema.get("required").isArray(), "required 必须是空数组");
        assertEquals(0, schema.get("properties").size());
        assertEquals(0, schema.get("required").size());
    }

    /**
     * execute 忽略入参并恒定返回 {@link CompactTool#OUTPUT}:null、空对象、带额外字段的对象都一样;
     * 且不修改入参、重复调用结果稳定。
     */
    @Test
    void executeIgnoresInputAndReturnsStableOutput() {
        // null 与空对象:都不读、不校验,直接返回稳定信号。
        assertEquals(CompactTool.OUTPUT, tool.execute(null));
        assertEquals(CompactTool.OUTPUT, tool.execute(Json.mapper().createObjectNode()));

        // 带额外字段:同样被忽略,并且入参对象本身不被修改。
        ObjectNode withExtras = Json.mapper().createObjectNode();
        withExtras.put("unexpected", "value");
        withExtras.putArray("more").add(1).add(2);
        String before = withExtras.toString();

        assertEquals(CompactTool.OUTPUT, tool.execute(withExtras));
        assertEquals(before, withExtras.toString(), "execute 只能读接口签名,不得修改入参");
        assertEquals(CompactTool.OUTPUT, tool.execute(withExtras), "重复调用必须返回同一文本");

        assertEquals("Compaction requested after this tool batch.", CompactTool.OUTPUT,
                "输出是父循环识别'本批请求过压缩'的稳定契约");
    }
}
