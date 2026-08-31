package com.jadecode.messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessagesJsonTest {

    private static final ObjectMapper M = Json.mapper();

    /** ① 三种块序列化往返:线上字段名(尤其 tool_use_id)与值都保持一致 */
    @Test
    void roundTripAllThreeBlockTypes() throws Exception {
        Message msg = Message.assistant(List.of(
                new TextBlock("好的"),
                new ToolUseBlock("toolu_01", "bash", M.readTree("{\"command\":\"ls\"}")),
                new ToolResultBlock("toolu_01", "pom.xml\nREADME.md")));

        String json = M.writeValueAsString(msg);
        JsonNode tree = M.readTree(json);

        // 顶层:role 的线上值是小写 "assistant"(验证 @JsonValue)
        assertEquals("assistant", tree.get("role").asText());
        JsonNode content = tree.get("content");
        assertEquals(3, content.size());

        // text 块
        assertEquals("text", content.get(0).get("type").asText());
        assertEquals("好的", content.get(0).get("text").asText());

        // tool_use 块:id/name/input 均按线上名
        JsonNode use = content.get(1);
        assertEquals("tool_use", use.get("type").asText());
        assertEquals("toolu_01", use.get("id").asText());
        assertEquals("bash", use.get("name").asText());
        assertEquals("ls", use.get("input").get("command").asText());

        // tool_result 块:线上必须是 tool_use_id,不能出现 Java 驼峰名 toolUseId
        JsonNode result = content.get(2);
        assertEquals("tool_result", result.get("type").asText());
        assertEquals("toolu_01", result.get("tool_use_id").asText());
        assertEquals("pom.xml\nREADME.md", result.get("content").asText());
        assertNull(result.get("toolUseId"));

        // 读回来与原对象相等(record 逐字段深比较)
        assertEquals(msg, M.readValue(json, Message.class));
    }

    /** ② 未知字段容错:块级和顶层多余字段都不报错,已知字段照常解析 */
    @Test
    void toleratesUnknownFields() throws Exception {
        String json = """
                {"role": "assistant",
                 "content": [{"type": "text", "text": "hi", "future_block_field": 42}],
                 "future_top_level": true}""";

        Message msg = M.readValue(json, Message.class);

        assertEquals(Role.ASSISTANT, msg.role());
        assertEquals(1, msg.content().size());
        assertEquals("hi", ((TextBlock) msg.content().get(0)).text());
    }

    /** ③ userText 归一化:字符串入口 → 恰一个 TextBlock 的数组 */
    @Test
    void userTextNormalizesToSingleTextBlock() throws Exception {
        Message msg = Message.userText("你好");

        assertEquals(Role.USER, msg.role());
        assertEquals(1, msg.content().size());
        assertInstanceOf(TextBlock.class, msg.content().get(0));
        assertEquals("你好", ((TextBlock) msg.content().get(0)).text());

        JsonNode tree = M.readTree(M.writeValueAsString(msg));
        assertEquals("user", tree.get("role").asText());
        assertEquals(1, tree.get("content").size());
        assertEquals("text", tree.get("content").get(0).get("type").asText());
    }

    /** ④ extractText:按序只摘文本块、跳过工具块,空 content 返回 "" */
    @Test
    void extractTextJoinsTextBlocks() throws Exception {
        Message msg = Message.assistant(List.of(
                new TextBlock("第一行"),
                new ToolUseBlock("toolu_01", "bash", M.readTree("{}")),
                new TextBlock("第二行")));

        assertEquals("第一行\n第二行", msg.extractText());

        Message onlyTools = Message.assistant(List.of(
                new ToolUseBlock("toolu_01", "bash", M.readTree("{}"))));
        assertEquals("", onlyTools.extractText());

        assertEquals("", Message.assistant(List.of()).extractText());
    }
}
