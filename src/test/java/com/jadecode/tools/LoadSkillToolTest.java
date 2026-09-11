package com.jadecode.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.skills.SkillLoader;
import com.jadecode.util.Json;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LoadSkillTool 单元测试:薄壳契约、缺参短路、命中与未命中的透传。
 * 用真实 {@link SkillLoader} 指向 {@link TempDir} 里的技能树,不碰仓库文件。
 */
class LoadSkillToolTest {

    @TempDir
    Path ws;

    /** SKILL.md 的逐字原文:命中时应当原样透传 */
    private static final String RAW = "---\ndescription: A 技能\n---\nA 正文";

    private LoadSkillTool tool;

    @BeforeEach
    void setUp() throws IOException {
        Path skills = Files.createDirectories(ws.resolve("skills").resolve("alpha"));
        Files.writeString(skills.resolve("SKILL.md"), RAW, StandardCharsets.UTF_8);
        tool = new LoadSkillTool(new SkillLoader(ws.resolve("skills")));
    }

    private static ObjectNode input(String key, String value) {
        return Json.mapper().createObjectNode().put(key, value);
    }

    /** ① 契约:四个元数据 + 默认 inputSchema 的平铺结构 */
    @Test
    void definesLoadSkillToolContract() {
        assertEquals("load_skill", tool.name());
        assertEquals("Load the full SKILL.md content by skill name.", tool.description());
        assertEquals(Map.of("name", "string"), tool.properties());
        assertEquals(List.of("name"), tool.required());

        ObjectNode schema = tool.inputSchema(); // required 必须是 properties 的子集,否则这里会抛
        assertEquals("object", schema.get("type").asText());
        assertEquals("string", schema.get("properties").get("name").get("type").asText());
        assertEquals(1, schema.get("required").size());
        assertEquals("name", schema.get("required").get(0).asText());
    }

    /** ② 缺参:回统一文案,不触碰 loader */
    @Test
    void missingNameReturnsError() {
        assertEquals("Error: Missing required parameter: name",
                tool.execute(Json.mapper().createObjectNode()));
    }

    /** ③ 命中:原样透传完整 SKILL.md 原文 */
    @Test
    void hitReturnsRawContent() {
        assertEquals(RAW, tool.execute(input("name", "alpha")));
    }

    /** ④ 未命中:原样透传 loader 的未知技能文案(工具层不改写) */
    @Test
    void missPassesThroughLoaderMessage() {
        assertEquals("Error: Unknown skill 'nope'. Available: alpha",
                tool.execute(input("name", "nope")));
    }

    /** ⑤ 空串名字:过得了缺参检查,走 registry 未命中 */
    @Test
    void blankNameFallsThroughToRegistryMiss() {
        assertEquals("Error: Unknown skill ''. Available: alpha", tool.execute(input("name", "")));
    }
}
