package com.jadecode.sandbox;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import com.jadecode.config.JadeConfigFile;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.safety.*;
import com.jadecode.safety.approval.*;
import com.jadecode.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 不启动 Docker 的授权/配置回归，覆盖错误配置和批准后换参，而非镜像命令拼接细节。 */
class SandboxAuthorizationTest {
    @TempDir Path temporary;
    @Test void productionDefaultsAreSandboxedAndInvalidConfigurationFailsClosed() throws Exception {
        Path config = temporary.resolve("config.json");
        assertTrue(new JadeConfigFile(config).load().sandbox().enabled());
        Files.writeString(config, "{\"sandbox\":{\"enabled\":\"false\"}}");
        assertThrows(java.io.IOException.class, () -> new JadeConfigFile(config).load());
        Files.writeString(config, "{\"sandbox\":{\"maxIdle\":9,\"maxContainers\":2}}");
        assertThrows(java.io.IOException.class, () -> new JadeConfigFile(config).load());
        Files.writeString(config, "{\"sandbox\":{\"enabledd\":false}}");
        assertThrows(java.io.IOException.class, () -> new JadeConfigFile(config).load());
    }
    @Test void changedInputAndRevisionCannotConsumeOldToolApproval() throws Exception {
        var state = PermissionState.defaults();
        var gate = new PermissionGate(new PathResolver(temporary), state, request -> ApprovalAnswer.ALLOW_ONCE);
        var context = ExecutionContext.create(ExecutionContext.Source.USER, request -> ApprovalAnswer.ALLOW_ONCE);
        var input = Json.mapper().createObjectNode().put("command", "echo fixture");
        assertNull(gate.check("bash", input, context));
        input.put("command", "echo changed");
        assertThrows(IllegalStateException.class, () -> gate.consume("bash", input, context));
        assertNull(gate.check("bash", input, context)); state.select(PermissionProfile.READ_ONLY);
        assertThrows(IllegalStateException.class, () -> gate.consume("bash", input, context));
    }
    @Test void hardLinkAliasesCannotBypassProtectedPathView() throws Exception {
        Path file = Files.writeString(temporary.resolve("file"), "fixture");
        Files.createLink(temporary.resolve("alias"), file);
        assertThrows(java.io.IOException.class, () -> PathProtection.scan(List.of(new ExecutionPlan.Mount(temporary, temporary, true))));
    }
    @Test void childInvocationCannotConsumeSiblingApproval() {
        var state = PermissionState.defaults();
        var gate = new PermissionGate(new PathResolver(temporary), state, request -> ApprovalAnswer.ALLOW_ONCE);
        var parent = ExecutionContext.create(ExecutionContext.Source.USER, request -> ApprovalAnswer.ALLOW_ONCE);
        var first = parent.withActorScope("first");
        var second = parent.withActorScope("second");
        var input = Json.mapper().createObjectNode().put("command", "echo fixture");
        assertNull(gate.check("bash", input, first));
        assertThrows(IllegalStateException.class, () -> gate.consume("bash", input, second));
        assertEquals(parent.id(), first.id()); assertEquals(parent.source(), first.source());
    }

    /** 第二层挂载范围审批等待期间换参，也必须拒绝执行；此用例不需要 Docker daemon。 */
    @Test void changedParametersDuringScopeApprovalDoNotReachRuntime() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "fixture");
        var state = PermissionState.defaults();
        var gate = new PermissionGate(new PathResolver(workspace), state, request -> ApprovalAnswer.DENY); gate.sandboxed(true);
        var input = Json.mapper().createObjectNode().put("path", outside.toString());
        var context = ExecutionContext.create(ExecutionContext.Source.USER, request -> {
            input.put("path", temporary.resolve("changed.txt").toString()); return ApprovalAnswer.ALLOW_ONCE;
        });
        try (var runtime = new DockerRuntime(SandboxSettings.defaults(), temporary.resolve("cache"), ignored -> { })) {
            var session = new SandboxSession("reader", workspace, temporary.resolve("private"), state, gate, new ApprovalCoordinator(null), runtime);
            assertNull(gate.check("read_file", input, context));
            var result = session.wrap(new com.jadecode.tools.ReadFileTool(new PathResolver(workspace))).executeOutcome(input, context);
            assertTrue(result.isError()); assertTrue(result.content().contains("parameters changed"), result.content());
            assertEquals(0, runtime.containerCount());
        }
    }
}
