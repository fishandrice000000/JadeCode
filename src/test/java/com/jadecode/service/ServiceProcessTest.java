package com.jadecode.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.cli.backend.protocol.*;
import com.jadecode.util.Json;

/** Java 服务进程的真实身份前导与远端 relay 生命周期；用户 CLI 断言已迁至 Node/发行验证。 */
class ServiceProcessTest {
    @TempDir
    Path root;

    /** 同连接探测不创建会话；relay EOF 不关闭 daemon，显式管理命令才停止它。 */
    @Test
    void identityPreludeAndRelayEofPreserveDaemonUntilExplicitStop() throws Exception {
        Process relay = launch();
        ProcessHandle daemon = null;
        try {
            send(relay, "service.identity");
            var identity = WireCodec.readFrame(relay.getInputStream());
            assertEquals("service.result", identity.type());
            assertEquals(ServerBuild.load().buildId(), identity.payload().path("buildId").asText());
            assertEquals(1, identity.payload().path("protocolVersion").asInt());
            daemon = ProcessHandle.of(identity.payload().path("pid").asLong()).orElseThrow();
            assertTrue(daemon.isAlive());
            send(relay, "service.list");
            assertTrue(WireCodec.readFrame(relay.getInputStream()).payload().path("sessions").isEmpty());
            assertTrue(relay.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, relay.exitValue());
            assertTrue(daemon.isAlive());
            Process detached = launch();
            send(detached, "service.identity");
            assertEquals(identity.payload().path("pid"),
                    WireCodec.readFrame(detached.getInputStream()).payload().path("pid"));
            detached.getOutputStream().close();
            assertTrue(detached.waitFor(5, TimeUnit.SECONDS));
            assertTrue(daemon.isAlive());
        } finally {
            if (relay.isAlive())
                relay.destroyForcibly();
            if (daemon != null) {
                Process stopping = launch();
                send(stopping, "service.stop");
                assertEquals("stopped",
                        WireCodec.readFrame(stopping.getInputStream()).payload().path("status").asText());
                stopping.waitFor(5, TimeUnit.SECONDS);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (daemon.isAlive() && System.nanoTime() < until)
                    Thread.sleep(20);
                if (daemon.isAlive())
                    daemon.destroyForcibly();
                assertFalse(daemon.isAlive());
            }
        }
    }

    /** Java JAR 不再接受客户端用法，不能悄悄回到旧转发路径。 */
    @Test
    void clientArgumentsAreRejectedWithoutStartingAService() {
        assertEquals(2, com.jadecode.Main.run(new String[] { "--headless", "task" }));
    }

    /** 仅启动本测试数据根的 relay，移除机器模型覆盖，日志留在临时目录。 */
    private Process launch() throws Exception {
        var args = List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Duser.home=" + root, "-cp", System.getProperty("java.class.path"), "com.jadecode.Main",
                "--service-relay");
        var builder = new ProcessBuilder(args).redirectError(root.resolve("relay.log").toFile());
        for (String name : List.of("ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL", "MODEL_ID", "MAX_TOKENS",
                "MODEL_PROVIDER"))
            builder.environment().remove(name);
        return builder.start();
    }

    /** 前导独立于会话握手，不需要模型配置或工作区。 */
    private void send(Process process, String type) throws Exception {
        process.getOutputStream().write(WireCodec.encode(WireMessage.event(1, "", type, null, null,
                Json.mapper().createObjectNode())));
        process.getOutputStream().flush();
    }
}
