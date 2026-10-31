package com.jadecode.sandbox;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.*;
import com.jadecode.safety.*;
import com.jadecode.safety.approval.*;
import com.jadecode.team.TeamManager.ScopeDecision;
import com.jadecode.tools.*;
import com.jadecode.util.*;

/** 测试调用真实 Gate 和沙箱适配器；Docker 用例显式选择，普通用例在容器创建前检查失败。 */
class AssignmentSandboxTest {
    @TempDir Path dir;

    @Test void disabledSandboxAndShellHostFallbackCannotBypassAssignmentEvenInFullAccess() throws Exception {
        var state = PermissionState.defaults(); state.select(PermissionProfile.FULL_ACCESS);
        Path workspace = Files.createDirectory(dir.resolve("workspace"));
        var gate = new PermissionGate(new PathResolver(workspace), state, r -> ApprovalAnswer.DENY);
        var context = context(AssignmentScope.EMPTY, null);
        for (var current : List.of(context, context.withActorScope("child"))) {
            for (String tool : List.of("read_file", "write_file", "edit_file", "glob", "bash"))
                assertTrue(gate.check(tool, Json.mapper().createObjectNode(), current).contains("Docker sandbox"));
        }
        try (var runtime = runtime(false)) {
            var sandbox = new SandboxSession("agent_fixture", workspace, dir.resolve("private"), state, gate, new ApprovalCoordinator(null), runtime);
            assertNull(sandbox.shellRunner().run("touch bypass", workspace, 5, context).exitCode());
            assertFalse(Files.exists(workspace.resolve("bypass")));
            assertTrue(gate.check("connect_mcp", Json.mapper().createObjectNode(), context).contains("Assignment isolation"));
        }
    }

    /** 等待期间换参、改链接或换权限档位，都不能把 Leader 对旧操作的许可用于新操作。 */
    @Test void scopeApprovalBindsInputResolvedTargetAndRevision() throws Exception {
        for (String change : List.of("input", "link", "revision", "deny")) {
            Path workspace = Files.createDirectory(dir.resolve(change));
            Path one = Files.writeString(workspace.resolve("one"), "original"), two = Files.writeString(workspace.resolve("two"), "other");
            Files.createSymbolicLink(workspace.resolve("alias"), one);
            var state = PermissionState.defaults(); var gate = new PermissionGate(new PathResolver(workspace), state, r -> ApprovalAnswer.DENY);
            gate.sandboxed(true);
            var input = Json.mapper().createObjectNode().put("path", change.equals("link") ? "alias" : "one");
            var context = context(AssignmentScope.EMPTY, null);
            try (var runtime = runtime(false)) {
                var sandbox = new SandboxSession("agent_fixture", workspace, dir.resolve("private"), state, gate, new ApprovalCoordinator(null), runtime);
                sandbox.scopeApproval((request, original) -> {
                    assertEquals(change.equals("link") ? "alias" : "one", request.input().path("operation").path("path").asText());
                    switch (change) {
                        case "input" -> input.put("path", "two");
                        case "link" -> { Files.delete(workspace.resolve("alias")); Files.createSymbolicLink(workspace.resolve("alias"), two); }
                        case "revision" -> state.select(PermissionProfile.READ_ONLY);
                        default -> { return ScopeDecision.DENY; }
                    }
                    return ScopeDecision.APPROVE_ONCE;
                });
                assertNull(gate.check("read_file", input, context));
                var result = sandbox.wrap(new ReadFileTool(new PathResolver(workspace))).executeOutcome(input, context);
                assertTrue(result.isError(), change + ": " + result.content());
                assertEquals(0, runtime.containerCount());
                assertEquals("original", Files.readString(one)); assertEquals("other", Files.readString(two));
            }
        }
    }

    /** sandboxed Gate 会把外部路径的范围审批推迟到沙箱；Leader 点允许仍必须经过真实人工。 */
    @Test void leaderCannotGrantOutsideUserWorkspaceWithoutHumanConfirmation() throws Exception {
        Path workspace = Files.createDirectory(dir.resolve("workspace")); Path external = Files.writeString(dir.resolve("outside"), "fixture");
        var state = PermissionState.defaults(); var gate = new PermissionGate(new PathResolver(workspace), state, r -> ApprovalAnswer.DENY);
        gate.sandboxed(true); var userAsked = new AtomicInteger();
        var context = context(AssignmentScope.EMPTY, request -> { userAsked.incrementAndGet(); return ApprovalAnswer.DENY; });
        try (var runtime = runtime(false)) {
            var sandbox = new SandboxSession("agent_fixture", workspace, dir.resolve("private"), state, gate, new ApprovalCoordinator(null), runtime);
            sandbox.scopeApproval((request, original) -> {
                assertFalse(request.input().path("within_user_permissions").asBoolean(true)); return ScopeDecision.APPROVE_ONCE;
            });
            var input = Json.mapper().createObjectNode().put("path", external.toString());
            assertNull(gate.check("read_file", input, context));
            var result = sandbox.wrap(new ReadFileTool(new PathResolver(workspace))).executeOutcome(input, context);
            assertTrue(result.isError()); assertEquals(1, userAsked.get()); assertEquals(0, runtime.containerCount());
        }
    }

    /** 真实容器内验证读全项目、局部写和 Full Access 无法宿主绕过；显式新请求才获一次扩展。 */
    @Test void dockerEnforcesReadAndWriteScopesAndOneCallExtension() throws Exception {
        try (var runtime = runtime(true)) {
            Path workspace = Files.createDirectory(dir.resolve("workspace"));
            Files.createDirectory(workspace.resolve("owned")); Files.createDirectory(workspace.resolve("other"));
            Files.writeString(workspace.resolve("other/reference"), "reference");
            var state = PermissionState.defaults(); state.select(PermissionProfile.FULL_ACCESS);
            var gate = new PermissionGate(new PathResolver(workspace), state, r -> ApprovalAnswer.DENY); gate.sandboxed(true);
            var sandbox = new SandboxSession("agent_fixture", workspace, dir.resolve("private"), state, gate, new ApprovalCoordinator(null), runtime);
            var approvals = new AtomicInteger(); sandbox.scopeApproval((request, original) -> { approvals.incrementAndGet(); return ScopeDecision.APPROVE_ONCE; });
            Tool bash = sandbox.wrap(new BashTool(workspace, sandbox.shellRunner()));
            var context = context(new AssignmentScope(List.of("."), List.of("owned")), null);
            assertFalse(run(gate, bash, context, "printf ok > owned/result; cat other/reference").isError());
            assertEquals("ok", Files.readString(workspace.resolve("owned/result")));
            // 解析器看不到 python 内部写入；只能由容器内核拒绝，不产生自动扩权或重放。
            assertTrue(run(gate, bash, context, "python3 -c 'open(\"other/denied\",\"w\").write(\"bad\")'").isError());
            assertFalse(Files.exists(workspace.resolve("other/denied"))); assertEquals(0, approvals.get());
            var input = Json.mapper().createObjectNode().put("command", "printf extra > other/approved");
            input.putArray("access_paths").addObject().put("path", "other").put("write", true);
            assertNull(gate.check("bash", input, context));
            assertFalse(bash.executeOutcome(input, context).isError());
            assertEquals("extra", Files.readString(workspace.resolve("other/approved")));
            assertTrue(approvals.get() >= 1);
            assertTrue(run(gate, bash, context, "python3 -c 'open(\"other/later\",\"w\").write(\"bad\")'").isError());
            assertFalse(Files.exists(workspace.resolve("other/later")));
            assertEquals(1, runtime.containerCount(), "Only the original restricted baseline is retained; one-call expansion is removed");
            runtime.endTask("agent_fixture", context.id()); assertEquals(0, runtime.containerCount());
        }
    }

    /** 不挂载整个工作区时，cwd 仍可用，未声明兄弟目录不可读；子代理保留相同约束。 */
    @Test void dockerHidesUnlistedPathsAndChildUsesSameScope() throws Exception {
        try (var runtime = runtime(true)) {
            Path workspace = Files.createDirectory(dir.resolve("workspace")); Files.createDirectory(workspace.resolve("visible"));
            Files.writeString(workspace.resolve("visible/input"), "allowed"); Files.writeString(workspace.resolve("hidden"), "hidden");
            var state = PermissionState.defaults(); state.select(PermissionProfile.FULL_ACCESS);
            var gate = new PermissionGate(new PathResolver(workspace), state, r -> ApprovalAnswer.DENY); gate.sandboxed(true);
            var sandbox = new SandboxSession("agent_fixture/subagent", workspace, dir.resolve("private"), state, gate, new ApprovalCoordinator(null), runtime);
            Tool bash = sandbox.wrap(new BashTool(workspace, sandbox.shellRunner()));
            var context = context(new AssignmentScope(List.of("visible"), List.of()), null).withActorScope("child");
            assertFalse(run(gate, bash, context, "cat visible/input").isError());
            assertTrue(run(gate, bash, context, "python3 -c 'print(open(\"hidden\").read())'").isError());
            assertTrue(run(gate, bash, context, "python3 -c 'open(\"visible/output\",\"w\").write(\"bad\")'").isError());
            assertFalse(Files.exists(workspace.resolve("visible/output")));
        }
    }

    /** 后台线程携带冻结计划；即使父轮不等待，它也不能退回 Full Access 宿主执行。 */
    @Test void dockerBackgroundRetainsAssignmentMounts() throws Exception {
        try (var runtime = runtime(true)) {
            Path workspace = Files.createDirectory(dir.resolve("workspace")); Files.createDirectory(workspace.resolve("visible"));
            var state = PermissionState.defaults(); state.select(PermissionProfile.FULL_ACCESS);
            var gate = new PermissionGate(new PathResolver(workspace), state, r -> ApprovalAnswer.DENY); gate.sandboxed(true);
            var sandbox = new SandboxSession("agent_fixture", workspace, dir.resolve("private"), state, gate, new ApprovalCoordinator(null), runtime);
            try (var background = new com.jadecode.process.BackgroundManager(workspace, 15, sandbox.shellRunner())) {
                var result = new java.util.concurrent.CompletableFuture<com.jadecode.process.BackgroundManager.Completion>();
                background.onResult(result::complete);
                var context = context(new AssignmentScope(List.of("visible"), List.of()), null);
                var input = Json.mapper().createObjectNode().put("command", "python3 -c 'open(\"visible/denied\",\"w\").write(\"bad\")'")
                        .put("run_in_background", true);
                assertNull(gate.check("bash", input, context));
                assertFalse(sandbox.wrap(new BashTool(background)).executeOutcome(input, context).isError());
                // 提交返回临时结果；最终结果必须由真实后台线程和 Docker 执行后产生。
                var completion = result.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertNotEquals(0, completion.exitCode()); assertEquals("failed", completion.status());
                assertFalse(Files.exists(workspace.resolve("visible/denied")));
            }
        }
    }

    private ToolOutcome run(PermissionGate gate, Tool bash, ExecutionContext context, String command) {
        var input = Json.mapper().createObjectNode().put("command", command);
        assertNull(gate.check("bash", input, context)); return bash.executeOutcome(input, context);
    }
    private ExecutionContext context(AssignmentScope scope, Approval approval) {
        return ExecutionContext.create(ExecutionContext.Source.USER, approval).withAssignment(
                new AssignmentContext("assignment_fixture", "task_fixture", "agent_fixture", "leader_run", scope));
    }
    /** 默认单测不访问 Docker；真实边界用例必须显式 opt-in。 */
    private DockerRuntime runtime(boolean integration) {
        if (integration) Assumptions.assumeTrue(Boolean.getBoolean("jade.sandbox.integration"), "Explicit Docker integration opt-in required");
        return new DockerRuntime(SandboxSettings.defaults(), dir.resolve("cache"), ignored -> { });
    }
}
