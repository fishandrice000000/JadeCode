package com.jadecode.mcp;

import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 使用独立 Java SDK 服务进程验证真实传输、失败状态与生命周期；不需要网络、Python 或模型。 */
class McpManagerTest {
    @TempDir Path workspace;

    /** 使用当前测试 JVM 对应的 Java 和 classpath，使测试不依赖系统默认 JDK。 */
    McpServerConfig config(String mode, int startup) {
        var args = new java.util.ArrayList<>(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                McpFixtureServer.class.getName(), workspace.resolve("pid-" + mode).toString()));
        if (!mode.isEmpty()) args.add(mode);
        return new McpServerConfig(true, Path.of(System.getProperty("java.home"), "bin", "java").toString(), args,
                "", startup, 3, 20_000, Set.of("echo"));
    }

    /** 真正握手、发现、调用；重复连接复用身份，结果状态与完整 schema 保留，关闭后进程退出。 */
    @Test void realStdioKeepsDefinitionsErrorsAndClosesProcess() throws Exception {
        McpToolDescriptor echo;
        var manager = new McpManager(Map.of("fixture", config("", 15)), workspace, ignored -> { });
        try {
            var tools = manager.connect("fixture");
            assertEquals(3, tools.size());
            assertEquals(tools, manager.connect("fixture"));
            echo = tools.stream().filter(t -> t.name().equals("echo")).findFirst().orElseThrow();
            assertTrue(echo.hostReadOnly());
            assertFalse(echo.inputSchema().path("additionalProperties").asBoolean(true));
            assertTrue(manager.call(echo, Json.mapper().createObjectNode().put("text", "fail")).isError());
            assertEquals("hello", manager.call(echo, Json.mapper().createObjectNode().put("text", "hello")).text());
            var structured = tools.stream().filter(t -> t.name().equals("structured")).findFirst().orElseThrow();
            assertTrue(manager.call(structured, Json.mapper().createObjectNode()).isError());
            var environment = tools.stream().filter(t -> t.name().equals("environment")).findFirst().orElseThrow();
            String names = manager.call(environment, Json.mapper().createObjectNode()).text();
            assertFalse(names.contains("ANTHROPIC_API_KEY"));
            assertFalse(names.contains("OPENAI_API_KEY"));
        } finally { manager.close(); }
        assertTrue(manager.tools().isEmpty());
        long pid = Long.parseLong(Files.readString(workspace.resolve("pid-")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        manager.close();
    }

    /** 未配置与启动程序缺失不能发布目录，下一次连接仍可明确重试。 */
    @Test void unknownAndMissingProgramsNeverPublishPartialTools() {
        var config = new McpServerConfig(true, workspace.resolve("missing-program").toString(), List.of(), "", 2, 2, 100, Set.of());
        try (var manager = new McpManager(Map.of("missing", config), workspace, ignored -> { })) {
            assertThrows(IllegalArgumentException.class, () -> manager.connect("unknown"));
            assertThrows(IllegalStateException.class, () -> manager.connect("missing"));
            assertTrue(manager.tools().isEmpty());
        }
    }

    /** 故意不回应初始化的进程被限时关闭，不留下后台服务或假成功目录。 */
    @Test void initializationTimeoutClosesOwnedProcess() throws Exception {
        try (var manager = new McpManager(Map.of("stall", config("stall", 2)), workspace, ignored -> { })) {
            assertThrows(IllegalStateException.class, () -> manager.connect("stall"));
            assertTrue(manager.tools().isEmpty());
        }
        long pid = Long.parseLong(Files.readString(workspace.resolve("pid-stall")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
