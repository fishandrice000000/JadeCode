package com.jadecode.sandbox;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import com.jadecode.util.Json;
import com.jadecode.util.PathResolver;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.safety.*;
import com.jadecode.safety.approval.*;
import com.jadecode.tools.ReadFileTool;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Docker 边界测试；显式 -Djade.sandbox.integration=true 才访问 daemon，不调用模型。 */
class DockerSandboxTest {
    @TempDir Path temporary;
    private ExecutionPlan plan(Path workspace, String command, boolean readOnly, boolean reusable, List<ExecutionPlan.Mount> extra) {
        var mounts = new java.util.ArrayList<ExecutionPlan.Mount>();
        mounts.add(new ExecutionPlan.Mount(workspace, workspace, !readOnly)); mounts.addAll(extra);
        return new ExecutionPlan("test-agent", "test-run", 0, "bash", Json.mapper().createObjectNode().put("command", command),
                workspace, readOnly, false, reusable, mounts);
    }
    private DockerRuntime runtime() {
        Assumptions.assumeTrue(Boolean.getBoolean("jade.sandbox.integration"), "Explicit Docker integration opt-in required");
        return new DockerRuntime(SandboxSettings.defaults(), temporary.resolve("cache"), System.err::println);
    }
    /** 顶层占用从真实容器挂载前领取；冲突时无写入，删除后才允许另一组取得范围。 */
    @Test void serviceWritableMountClaimsAreHeldUntilContainerRemoval() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("jade.sandbox.integration"));
        Path a=Files.createDirectory(temporary.resolve("a")), b=Files.createDirectory(temporary.resolve("b"));
        var claims=new com.jadecode.service.WorkspaceClaims();
        var first=claims.owner("first");var other=claims.owner("other");
        var held=other.acquire(List.of(b));
        try(var root=first.acquire(List.of(a));
            var runtime=new DockerRuntime(SandboxSettings.defaults(),temporary.resolve("cache"),System.err::println,first::acquire)){
            var operation=plan(a,"printf fixture > "+b.resolve("result"),false,true,List.of(new ExecutionPlan.Mount(b,b,true)));
            var refused=runtime.execute(operation,10,()->false);
            assertTrue(refused.error());assertTrue(refused.content().contains("held by session"),refused.content());
            assertEquals(0,runtime.containerCount());assertFalse(Files.exists(b.resolve("result")));
            held.close();
            var accepted=runtime.execute(operation,10,()->false);
            assertFalse(accepted.error(),accepted.content());assertEquals("fixture",Files.readString(b.resolve("result")));
            assertThrows(java.io.IOException.class,()->other.acquire(List.of(b)));
            runtime.endTask("test-agent","test-run");
            assertEquals(0,runtime.containerCount());
            try(var released=other.acquire(List.of(b))){assertNotNull(released);}
        } finally { held.close(); }
    }
    /** 验证真实内核视图：可写工作区、只读/隐藏子路径、外部路径和网络隔离以及复用。 */
    @Test void boundariesAndReuse() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "outside-fixture");
        Files.writeString(workspace.resolve(".env"), "SYNTHETIC_TEST_VALUE=fixture");
        Files.createDirectory(workspace.resolve(".git")); Files.writeString(workspace.resolve(".git/config"), "fixture");
        Files.createDirectory(workspace.resolve(".ssh")); Files.writeString(workspace.resolve(".ssh/synthetic.txt"), "fixture");
        try (var runtime = runtime()) {
            var basic = runtime.execute(plan(workspace, "printf ok > result.txt; cat result.txt", false, true, List.of()), 15, () -> false);
            assertFalse(basic.error(), basic.content()); assertEquals("ok", Files.readString(workspace.resolve("result.txt")));
            assertEquals(1, runtime.containerCount());
            var identity = runtime.execute(plan(workspace,
                    "java -XshowSettings:properties -version 2>&1 | grep user.home; python3 -c 'import os; print(\"ANTHROPIC_API_KEY\" in os.environ, \"OPENAI_API_KEY\" in os.environ)'",
                    false, true, List.of()), 15, () -> false);
            assertFalse(identity.error(), identity.content()); assertTrue(identity.content().contains("/home/jade"), identity.content());
            assertTrue(identity.content().contains("False False"), identity.content());
            assertTrue(runtime.execute(plan(workspace, "cat .env", false, true, List.of()), 15, () -> false).error());
            assertTrue(runtime.execute(plan(workspace, "printf bad > .git/config", false, true, List.of()), 15, () -> false).error());
            assertTrue(runtime.execute(plan(workspace, "cat " + outside, false, true, List.of()), 15, () -> false).error());
            assertEquals(1, runtime.containerCount(), "Ordinary denied commands should still reuse the same boundary");
            var glob = new ExecutionPlan("test-agent", "test-run", 0, "glob", Json.mapper().createObjectNode().put("pattern", "*.txt"),
                    workspace, false, false, true, List.of(new ExecutionPlan.Mount(workspace, workspace, true)));
            var listing = runtime.execute(glob, 15, () -> false);
            assertFalse(listing.error(), listing.content()); assertTrue(listing.content().contains("result.txt"), listing.content());
            assertTrue(listing.content().contains("unreadable paths omitted"), listing.content());
            assertFalse(listing.content().contains("synthetic.txt"), listing.content());
            var network = runtime.execute(plan(workspace,
                    "python3 -c 'import socket; s=socket.socket(); s.settimeout(1); s.connect((\"1.1.1.1\",443))'", false, true, List.of()), 15, () -> false);
            assertTrue(network.error(), network.content());
            var grant = runtime.execute(plan(workspace, "cat " + outside, false, false,
                    List.of(new ExecutionPlan.Mount(outside, outside, false))), 15, () -> false);
            assertFalse(grant.error(), grant.content()); assertEquals("outside-fixture", grant.content());
            assertEquals(1, runtime.containerCount(), "One-call expanded container must be removed");
            runtime.endTask("test-agent", "test-run"); assertEquals(1, runtime.containerCount(), "Session baseline can remain idle across tasks");
            assertTrue(runtime.execute(plan(workspace, "echo stale", false, true, List.of()), 10, () -> false).error());
            var next = new ExecutionPlan("test-agent", "next-run", 0, "bash", Json.mapper().createObjectNode().put("command", "cat result.txt"),
                    workspace, false, false, true, List.of(new ExecutionPlan.Mount(workspace, workspace, true)));
            assertFalse(runtime.execute(next, 10, () -> false).error()); assertEquals(1, runtime.containerCount());
            runtime.endActor("test-agent"); assertEquals(0, runtime.containerCount());
        }
    }
    /** 只读工作区不能写；客户端超时后容器也必须消失，不能留下后台副作用。 */
    @Test void readOnlyAndTimeoutCleanup() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        try (var runtime = runtime()) {
            var readOnly = runtime.execute(plan(workspace, "touch denied.txt", true, true, List.of()), 10, () -> false);
            assertTrue(readOnly.error(), readOnly.content()); assertFalse(Files.exists(workspace.resolve("denied.txt")));
            var timeout = runtime.execute(plan(workspace, "sleep 30", false, false, List.of()), 1, () -> false);
            assertTrue(timeout.error(), timeout.content());
            assertEquals(1, runtime.containerCount(), "Only the original reusable read-only container remains");
        }
    }
    /** 逐文件访问由 Harness 自动取得一次任务目录授权；其它任务不继承该授权。 */
    @Test void taskDirectoryGrantIsReusedAndBoundToRun() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path repo = Files.createDirectory(temporary.resolve("repository")); Files.createDirectory(repo.resolve(".git"));
        for (int n = 0; n < 4; n++) Files.writeString(repo.resolve("file" + n), "fixture-" + n);
        var state = PermissionState.defaults(); var gate = new PermissionGate(new PathResolver(workspace), state, request -> ApprovalAnswer.DENY);
        gate.sandboxed(true);
        AtomicInteger asks = new AtomicInteger();
        var context = ExecutionContext.create(ExecutionContext.Source.USER, request -> {
            asks.incrementAndGet(); assertEquals("CURRENT_TASK", request.input().path("scope").asText());
            assertEquals(repo.toString(), request.input().path("host_path").asText()); return ApprovalAnswer.ALLOW_ONCE;
        }).withGoal("调研 " + repo);
        try (var runtime = runtime()) {
            var session = new SandboxSession("reader", workspace, temporary.resolve("private"), state, gate, new ApprovalCoordinator(null), runtime);
            var read = session.wrap(new ReadFileTool(new PathResolver(workspace)));
            for (int n = 0; n < 4; n++) {
                var input = Json.mapper().createObjectNode().put("path", repo.resolve("file" + n).toString());
                assertNull(gate.check("read_file", input, context));
                var result = read.executeOutcome(input, context); assertFalse(result.isError(), result.content());
                assertEquals("fixture-" + n, result.content()); assertEquals(1, runtime.containerCount());
            }
            assertEquals(1, asks.get());
            var other = ExecutionContext.create(ExecutionContext.Source.USER, request -> ApprovalAnswer.DENY);
            var input = Json.mapper().createObjectNode().put("path", repo.resolve("file0").toString());
            assertNull(gate.check("read_file", input, other)); assertTrue(read.executeOutcome(input, other).isError());
            session.endTask(context.id()); assertEquals(0, runtime.containerCount());
        }
    }
    /** 未声明目录时，第二个不同文件触发合并申请，不依赖模型选择容器或申请期限。 */
    @Test void repeatedRepositoryReadsTriggerScopeConsolidation() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path repo = Files.createDirectory(temporary.resolve("repository")); Files.createDirectory(repo.resolve(".git"));
        Files.writeString(repo.resolve("one"), "one"); Files.writeString(repo.resolve("two"), "two");
        var state = PermissionState.defaults(); var gate = new PermissionGate(new PathResolver(workspace), state, request -> ApprovalAnswer.DENY);
        gate.sandboxed(true); var scopes = new java.util.ArrayList<String>();
        var context = ExecutionContext.create(ExecutionContext.Source.USER, request -> { scopes.add(request.input().path("scope").asText()); return ApprovalAnswer.ALLOW_ONCE; });
        try (var runtime = runtime()) {
            var session = new SandboxSession("reader", workspace, temporary.resolve("private"), state, gate, new ApprovalCoordinator(null), runtime);
            var read = session.wrap(new ReadFileTool(new PathResolver(workspace)));
            for (String file : List.of("one", "two", "one")) {
                var input = Json.mapper().createObjectNode().put("path", repo.resolve(file).toString());
                assertNull(gate.check("read_file", input, context)); var result = read.executeOutcome(input, context);
                assertFalse(result.isError(), result.content());
            }
            assertEquals(List.of("THIS_CALL", "CURRENT_TASK"), scopes); assertEquals(1, runtime.containerCount());
        }
    }
    /** 回收物理实例不清空任务授权；缓存和工作区也保持。 */
    @Test void idleContainersAreEvictedWithoutDeletingWorkspace() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("jade.sandbox.integration"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        var settings = new SandboxSettings(true, SandboxSettings.defaults().image(), 512, 1, 128, 32, 1, 1, 3);
        try (var runtime = new DockerRuntime(settings, temporary.resolve("cache"), System.err::println)) {
            var result = runtime.execute(plan(workspace, "echo persisted > kept.txt", false, true, List.of()), 10, () -> false);
            assertFalse(result.error(), result.content()); assertEquals(1, runtime.containerCount());
            Thread.sleep(1100); runtime.reapIdle(); assertEquals(0, runtime.containerCount());
            assertEquals("persisted\n", Files.readString(workspace.resolve("kept.txt")));
            assertFalse(runtime.execute(plan(workspace, "cat kept.txt", false, true, List.of()), 10, () -> false).error());
        }
    }
    /** 真实 Factory/Agent/后台/文件工具接线使用假模型，模型和认证不会进入工作容器。 */
    @Test void productionFactoryRunsBackgroundAndFilesInDocker() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("jade.sandbox.integration"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.writeString(workspace.resolve("note.txt"), "Error: ordinary file contents");
        var d = com.jadecode.config.JadeSettings.defaults();
        var settings = new com.jadecode.config.JadeSettings(d.tui(), d.compaction(), d.limits(),
                new com.jadecode.config.JadeSettings.Memory(false, 100, 100, 1000), d.modelMetadata(), d.thinking(),
                d.permissions(), new com.jadecode.config.JadeSettings.Mcp(java.util.Map.of()), d.contextCompaction(), SandboxSettings.defaults());
        AtomicInteger calls = new AtomicInteger();
        com.jadecode.llm.LlmClient fake = request -> {
            int step = calls.getAndIncrement();
            if (step == 0) return new com.jadecode.llm.ModelResponse(com.jadecode.messages.Message.assistant(List.of(
                    new com.jadecode.messages.ToolUseBlock("background", "bash", Json.mapper().createObjectNode()
                            .put("command", "sleep 2; printf finished > background.txt").put("run_in_background", true)))), null);
            if (step == 1) return new com.jadecode.llm.ModelResponse(com.jadecode.messages.Message.assistant(List.of(
                    new com.jadecode.messages.ToolUseBlock("read", "read_file", Json.mapper().createObjectNode().put("path", "note.txt")))), null);
            var read = request.messages().stream().flatMap(m -> m.blocksOfType(com.jadecode.messages.ToolResultBlock.class).stream())
                    .filter(r -> r.toolUseId().equals("read")).findFirst().orElseThrow();
            assertFalse(read.isError(), read.content()); assertEquals("Error: ordinary file contents", read.content());
            return new com.jadecode.llm.ModelResponse(com.jadecode.messages.Message.assistant(List.of(new com.jadecode.messages.TextBlock("done"))), null);
        };
        try (var session = com.jadecode.bootstrap.JadeCodeFactory.create(
                new com.jadecode.config.AppConfig("offline", "http://127.0.0.1:1", "offline", 100), workspace, fake,
                ignored -> { }, ignored -> { }, (hooks, cwd, output) -> { }, () -> com.jadecode.llm.ResponseStream.NONE,
                com.jadecode.execution.ExecutionObserver.NONE, settings, settings.permissions().state(null),
                new com.jadecode.config.JadePaths(temporary.resolve("state")))) {
            session.run(new java.util.ArrayList<>(List.of(com.jadecode.messages.Message.userText("run fixture and read note"))));
            assertEquals("finished", Files.readString(workspace.resolve("background.txt"))); assertTrue(calls.get() >= 3);
        }
    }
    /** 镜像内的固定 Fetch 通过真实 SDK 握手，服务关闭释放容器；不发 HTTP 或模型请求。 */
    @Test void stdioMcpRunsInsideItsOwnContainer() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        var state = PermissionState.defaults();
        try (var runtime = runtime()) {
            var config = new com.jadecode.mcp.McpServerConfig(true, "/host/venv/not-exposed/python", List.of("-m", "mcp_server_fetch"),
                    "", 20, 5, 20_000, java.util.Set.of("fetch"), false);
            try (var manager = new com.jadecode.mcp.McpManager(java.util.Map.of("fetch", config), workspace,
                    System.err::println, runtime, "reader", state)) {
                var tools = manager.connect("fetch"); assertTrue(tools.stream().anyMatch(t -> t.name().equals("fetch")));
                assertEquals(1, runtime.containerCount()); runtime.reapIdle(); assertEquals(1, runtime.containerCount());
                state.select(PermissionProfile.READ_ONLY);
                assertEquals(0, runtime.containerCount()); assertTrue(manager.tools().isEmpty());
                assertFalse(manager.connect("fetch").isEmpty()); assertEquals(1, runtime.containerCount());
            }
            assertEquals(0, runtime.containerCount());
        }
    }
    /** 即使 Shell 分析看不到脚本内的读取，显式路径 deny 仍由挂载视图阻断。 */
    @Test void pathDenyIsEnforcedInsideOpaqueProgram() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path blocked = Files.writeString(workspace.resolve("blocked.txt"), "synthetic fixture");
        var rule = new com.jadecode.safety.rules.PermissionRule("test-deny", com.jadecode.safety.rules.PermissionRule.Effect.DENY,
                com.jadecode.safety.rules.PermissionRule.Kind.PATH, "*", workspace.toString(), "READ", blocked.toString(), false, List.of());
        var state = new PermissionState(PermissionProfile.WORKSPACE, List.of(rule), java.util.Set.of(), null);
        var gate = new PermissionGate(new PathResolver(workspace), state, request -> ApprovalAnswer.DENY); gate.sandboxed(true);
        var context = ExecutionContext.create(ExecutionContext.Source.USER, request -> ApprovalAnswer.DENY);
        try (var runtime = runtime()) {
            var session = new SandboxSession("reader", workspace, temporary.resolve("private"), state, gate, new ApprovalCoordinator(null), runtime);
            var bash = session.wrap(new com.jadecode.tools.BashTool(workspace, session.shellRunner()));
            var input = Json.mapper().createObjectNode().put("command", "python3 -c 'print(open(\"blocked.txt\").read())'");
            assertNull(gate.check("bash", input, context)); var result = bash.executeOutcome(input, context);
            assertTrue(result.isError(), result.content()); assertTrue(result.content().contains("Permission denied"), result.content());
        }
    }
    /** 模型写过的缓存若含父目录链接，下一次初始化不得沿链接向外写 toolchains.xml。 */
    @Test void poisonedCacheCannotRedirectHarnessWrites() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("jade.sandbox.integration"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path cache = Files.createDirectory(temporary.resolve("cache")); Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(cache.resolve(".m2"), outside);
        try (var runtime = new DockerRuntime(SandboxSettings.defaults(), cache, System.err::println)) {
            var result = runtime.execute(plan(workspace, "echo should-not-run > marker", false, true, List.of()), 10, () -> false);
            assertTrue(result.error()); assertEquals(0, runtime.containerCount());
            assertFalse(Files.exists(outside.resolve("toolchains.xml"))); assertFalse(Files.exists(workspace.resolve("marker")));
        }
    }
    /** 单文件长期允许不能因重复读取启发式被无审批升级为整个仓库允许。 */
    @Test void permanentFileAllowanceCannotSilentlyWidenToRepository() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path repo = Files.createDirectory(temporary.resolve("repository")); Files.createDirectory(repo.resolve(".git"));
        for (String file : List.of("one", "two", "three")) Files.writeString(repo.resolve(file), file);
        var rule = new com.jadecode.safety.rules.PermissionRule("one-file", com.jadecode.safety.rules.PermissionRule.Effect.ALLOW,
                com.jadecode.safety.rules.PermissionRule.Kind.PATH, "read_file", workspace.toString(), "READ", repo.resolve("two").toString(), false, List.of());
        var state = new PermissionState(PermissionProfile.WORKSPACE, List.of(rule), java.util.Set.of(), null);
        var gate = new PermissionGate(new PathResolver(workspace), state, request -> ApprovalAnswer.DENY); gate.sandboxed(true);
        var scopes = new java.util.ArrayList<String>();
        var context = ExecutionContext.create(ExecutionContext.Source.USER, request -> {
            String scope = request.input().path("scope").asText(); scopes.add(scope);
            return scope.equals("THIS_CALL") ? ApprovalAnswer.ALLOW_ONCE : ApprovalAnswer.DENY;
        });
        try (var runtime = runtime()) {
            var session = new SandboxSession("reader", workspace, temporary.resolve("private"), state, gate, new ApprovalCoordinator(null), runtime);
            var read = session.wrap(new ReadFileTool(new PathResolver(workspace)));
            for (String file : List.of("one", "two", "three")) {
                var input = Json.mapper().createObjectNode().put("path", repo.resolve(file).toString());
                assertNull(gate.check("read_file", input, context)); var result = read.executeOutcome(input, context);
                assertEquals(file.equals("three"), result.isError(), result.content()); assertEquals(0, runtime.containerCount());
            }
            assertEquals(List.of("THIS_CALL", "CURRENT_TASK"), scopes);
        }
    }
    /** 子进程实际超过容器内存上限时，内核计数必须反映为 OOM，而非普通成功或猜测重试。 */
    @Test void memoryLimitReportsKernelOomKill() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("jade.sandbox.integration"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        var settings = new SandboxSettings(true, SandboxSettings.defaults().image(), 256, 1, 128, 32, 600, 2, 4);
        try (var runtime = new DockerRuntime(settings, temporary.resolve("cache"), System.err::println)) {
            var result = runtime.execute(plan(workspace, "python3 -c 'a=bytearray(512*1024*1024)'", false, false, List.of()), 15, () -> false);
            assertTrue(result.error(), result.content()); assertTrue(result.content().contains("OOM"), result.content());
            assertEquals(0, runtime.containerCount());
        }
    }

    /** 满额时旧的闲置环境应让位，不能因 maxIdle 尚未超标拒绝新策略。 */
    @Test void capacityReclaimsAnIdleContainer() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("jade.sandbox.integration"));
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        var settings = new SandboxSettings(true, SandboxSettings.defaults().image(), 512, 1, 128, 32, 600, 1, 1);
        try (var runtime = new DockerRuntime(settings, temporary.resolve("cache"), System.err::println)) {
            assertFalse(runtime.execute(plan(workspace, "true", false, true, List.of()), 10, () -> false).error());
            var next = runtime.execute(plan(workspace, "true", true, true, List.of()), 10, () -> false);
            assertFalse(next.error(), next.content()); assertEquals(1, runtime.containerCount());
        }
    }

    /** 不透明 stdio RPC 也不能读到显式禁止的工作区文件，不能只在内置文件工具检查。 */
    @Test void stdioMcpCannotBypassAnExplicitReadDeny() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path blocked = Files.writeString(workspace.resolve("blocked.txt"), "synthetic fixture");
        var rule = new com.jadecode.safety.rules.PermissionRule("mcp-deny", com.jadecode.safety.rules.PermissionRule.Effect.DENY,
                com.jadecode.safety.rules.PermissionRule.Kind.PATH, "*", workspace.toString(), "READ", blocked.toString(), false, List.of());
        var state = new PermissionState(PermissionProfile.WORKSPACE, List.of(rule), java.util.Set.of(), null);
        String server = """
                from mcp.server.mcpserver import MCPServer
                m = MCPServer('sandbox-fixture')
                @m.tool()
                def read_fixture() -> str:
                    return open('blocked.txt').read()
                m.run(transport='stdio')
                """;
        var config = new com.jadecode.mcp.McpServerConfig(true, "/opt/jadecode/fetch/bin/python", List.of("-c", server),
                "", 20, 5, 20_000, java.util.Set.of("read_fixture"), false);
        try (var runtime = runtime(); var manager = new com.jadecode.mcp.McpManager(java.util.Map.of("fixture", config), workspace,
                System.err::println, runtime, "reader", state)) {
            var descriptor = manager.connect("fixture").stream().filter(tool -> tool.name().equals("read_fixture")).findFirst().orElseThrow();
            var result = manager.call(descriptor, Json.mapper().createObjectNode());
            assertTrue(result.isError(), result.text()); assertFalse(result.text().contains("synthetic fixture"), result.text());
        }
    }

    /** 强制让后台线程晚于任务结束再启动，确认已接收操作保留许可，完成后仍回收实例。 */
    @Test void acceptedDetachedBackgroundSurvivesTaskEndBeforeWorkerStarts() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        var state = PermissionState.defaults();
        var gate = new PermissionGate(new PathResolver(workspace), state, request -> ApprovalAnswer.DENY); gate.sandboxed(true);
        var context = ExecutionContext.create(ExecutionContext.Source.USER, request -> ApprovalAnswer.DENY);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        var completed = new java.util.concurrent.CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<com.jadecode.process.BackgroundManager.Completion>();
        try (var runtime = runtime()) {
            var session = new SandboxSession("reader", workspace, temporary.resolve("private"), state, gate, new ApprovalCoordinator(null), runtime);
            var backend = session.shellRunner();
            com.jadecode.process.ShellCommand.Runner delayed = (command, cwd, timeout, execution) -> {
                entered.countDown();
                try { if (!proceed.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timed out"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return backend.run(command, cwd, timeout, execution);
            };
            try (var background = new com.jadecode.process.BackgroundManager(workspace, 20, delayed)) {
                background.onResult(value -> { result.set(value); completed.countDown(); });
                var bash = session.wrap(new com.jadecode.tools.BashTool(background));
                var input = Json.mapper().createObjectNode().put("command", "printf completed > completed.txt")
                        .put("run_in_background", true).put("wait_for_completion", false);
                assertNull(gate.check("bash", input, context));
                assertFalse(bash.executeOutcome(input, context).isError());
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                session.endTask(context.id()); proceed.countDown();
                assertTrue(completed.await(20, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, result.get().exitCode(), result.get().output());
                assertEquals("completed", Files.readString(workspace.resolve("completed.txt")));
                assertEquals(0, runtime.containerCount());
            } finally { proceed.countDown(); }
        }
    }

    /** 父任务失败取消必需后台时，应终止实际 Docker 进程，不能只从宿主结果队列删除它。 */
    @Test void cancellingRequiredBackgroundStopsItsContainer() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        var state = PermissionState.defaults();
        var gate = new PermissionGate(new PathResolver(workspace), state, request -> ApprovalAnswer.DENY); gate.sandboxed(true);
        var context = ExecutionContext.create(ExecutionContext.Source.USER, request -> ApprovalAnswer.DENY);
        try (var runtime = runtime()) {
            var session = new SandboxSession("reader", workspace, temporary.resolve("private"), state, gate, new ApprovalCoordinator(null), runtime);
            try (var background = new com.jadecode.process.BackgroundManager(workspace, 20, session.shellRunner())) {
                var bash = session.wrap(new com.jadecode.tools.BashTool(background));
                var input = Json.mapper().createObjectNode().put("command", "printf started > started.txt; sleep 5; printf unexpected > late.txt")
                        .put("run_in_background", true);
                assertNull(gate.check("bash", input, context)); assertFalse(bash.executeOutcome(input, context).isError());
                long startDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (!Files.exists(workspace.resolve("started.txt")) && System.nanoTime() < startDeadline) Thread.sleep(20);
                assertTrue(Files.exists(workspace.resolve("started.txt")), "background command did not start");
                background.cancelRequired(context.id());
                long stopDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (runtime.containerCount() != 0 && System.nanoTime() < stopDeadline) Thread.sleep(20);
                assertEquals(0, runtime.containerCount()); assertFalse(Files.exists(workspace.resolve("late.txt")));
            }
        }
    }
}
