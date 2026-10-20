package com.jadecode.mcp;

import com.jadecode.bootstrap.JadeCodeFactory;
import com.jadecode.config.AppConfig;
import com.jadecode.config.JadeSettings;
import com.jadecode.execution.ExecutionObserver;
import com.jadecode.llm.*;
import com.jadecode.messages.*;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 假模型、真实工厂与 SDK 子进程，验证从连接到历史回填的完整主流程，不产生 API 费用。 */
class McpAgentIntegrationTest {
    @TempDir Path workspace;

    /** 完整生产接线中，工具下一轮出现、宿主只读权限生效，关闭 Session 后子进程退出。 */
    @Test void factoryDiscoversCallsAndClosesMcp() throws Exception {
        Path pidFile = workspace.resolve("pid");
        var server = new McpServerConfig(true, Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        McpFixtureServer.class.getName(), pidFile.toString()), "", 15, 5, 20_000, Set.of("echo"));
        var d = JadeSettings.defaults();
        var settings = new JadeSettings(d.tui(), d.compaction(), d.limits(), new JadeSettings.Memory(false, 100, 100, 100, 1000, 1000),
                d.modelMetadata(), d.thinking(), d.permissions(), new JadeSettings.Mcp(Map.of("fixture", server)));
        AtomicInteger requests = new AtomicInteger();
        LlmClient fake = request -> {
            int step = requests.getAndIncrement();
            if (step == 0) {
                assertFalse(request.tools().stream().anyMatch(t -> t.name().startsWith("mcp__")));
                return new ModelResponse(Message.assistant(List.of(new ToolUseBlock("c", "connect_mcp",
                        Json.mapper().createObjectNode().put("name", "fixture")))), null);
            }
            if (step == 1) {
                assertTrue(request.tools().stream().anyMatch(t -> t.name().equals("mcp__fixture__echo")));
                return new ModelResponse(Message.assistant(List.of(new ToolUseBlock("e", "mcp__fixture__echo",
                        Json.mapper().createObjectNode().put("text", "hello")))), null);
            }
            assertEquals("hello", request.messages().getLast().blocksOfType(ToolResultBlock.class).getFirst().content());
            return new ModelResponse(Message.assistant(List.of(new TextBlock("done"))), null);
        };
        try (var runtime = JadeCodeFactory.create(new AppConfig("offline", "http://127.0.0.1:1", "offline", 100), workspace,
                fake, ignored -> { }, ignored -> { }, (hooks, cwd, output) -> { }, () -> ResponseStream.NONE,
                ExecutionObserver.NONE, settings)) {
            runtime.run(new java.util.ArrayList<>(List.of(Message.userText("connect fixture and echo hello"))));
            assertEquals(3, requests.get());
        }
        long pid = Long.parseLong(Files.readString(pidFile));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
