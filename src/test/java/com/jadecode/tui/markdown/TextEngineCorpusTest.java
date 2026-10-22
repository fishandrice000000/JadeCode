package com.jadecode.tui.markdown;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 固定语料同源差分：Java 引擎结果必须与开发期 Node oracle 生成的 fixture 完全一致。
 * fixture 由 tools/tui-text-engine/oracle.mjs 在相同 bundle 上生成；普通构建不需要 Node。
 */
class TextEngineCorpusTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsTextEngine engine;
    private static JsonNode corpus;

    @BeforeAll static void start() throws Exception {
        engine = JsTextEngine.create();
        try (InputStream input = TextEngineCorpusTest.class.getResourceAsStream("/tui/text-engine-corpus.json")) {
            assertNotNull(input, "missing corpus fixture");
            corpus = MAPPER.readTree(input);
        }
    }

    @AfterAll static void stop() {
        if (engine != null) engine.close();
    }

    @Test void lexMatchesNodeOracleForEveryFixedSample() throws Exception {
        Iterator<Map.Entry<String, JsonNode>> samples = corpus.fields();
        int checked = 0;
        while (samples.hasNext()) {
            var entry = samples.next();
            String name = entry.getKey();
            String source = entry.getValue().path("source").asText();
            JsonNode expected = entry.getValue().path("lex");
            JsonNode actual = MAPPER.readTree(lexJson(source));
            assertEquals(expected, actual, name);
            checked++;
        }
        assertEquals(10, checked);
    }

    private String lexJson(String source) throws Exception {
        return engine.lexJson(source).get(30, TimeUnit.SECONDS);
    }

    @Test void highlightLatexAndMermaidMatchNodeOracle() throws Exception {
        assertEquals(corpus.path("code-java").path("highlightJava").asText(),
                engine.highlight("int x = 1;", "java").get(20, TimeUnit.SECONDS));
        assertEquals(corpus.path("latex").path("latexAlpha").asText(),
                engine.latex("\\alpha", false).get(20, TimeUnit.SECONDS));
        assertEquals(corpus.path("mermaid").path("mermaid").asText(),
                engine.mermaid("graph TD; A-->B;").get(20, TimeUnit.SECONDS));
    }
}
