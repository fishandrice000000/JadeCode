package com.jadecode.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    private final BashTool bash = new BashTool();

    /** 构造 input 并执行:测试期的主循环替代品 */
    private String run(String command) {
        return run(bash, command);
    }

    private String run(BashTool tool, String command) {
        ObjectNode input = Json.mapper().createObjectNode();
        input.put("command", command);
        return tool.execute(input);
    }

    /** ① 正常执行:echo 输出原文(无换行) */
    @Test
    void executesSimpleCommand() {
        assertEquals("hello", run("echo hello"));
    }

    /** ② 空输出:返回占位符 */
    @Test
    void emptyOutputBecomesPlaceholder() {
        assertEquals("(no output)", run("true"));
    }

    /** ③ stderr 并入 stdout */
    @Test
    void stderrIsMergedIntoOutput() {
        assertEquals("err", run("echo err 1>&2"));
    }

    /** ④ 未知命令:shell 存在，stderr 含 not found；s11 起还明确报告非零退出。 */
    @Test
    void unknownCommandReturnsShellError() {
        String out = run("jadecode-nonexistent-cmd-xyz");
        assertTrue(out.contains("not found"));
    }

    /** ⑤ 超时:注入 2 秒超时 + sleep 5 → 超时消息随配置 */
    @Test
    void longRunningCommandTimesOut() {
        BashTool impatient = new BashTool(2);
        assertEquals("Error: Timeout (2s)", run(impatient, "sleep 5"));
    }

    /** ⑥ 超长输出截断到 50000 字符；s11 边执行边排空，不依赖管道缓冲够大。 */
    @Test
    void longOutputIsTruncated() {
        String out = run("awk 'BEGIN{for(i=0;i<60000;i++)printf \"x\"}'");
        assertEquals("x".repeat(50000), out);
    }

    /** ⑦ 缺 command 参数:返回错误字符串,不抛 NPE */
    @Test
    void missingCommandParameterReturnsError() {
        assertEquals("Error: Missing required parameter: command",
                bash.execute(Json.mapper().createObjectNode()));
    }
    /** 退役策略明确失败，不静默忽略，也不允许启动任何后台工作。 */
    @Test void rejectsRetiredBackgroundOptionsBeforeExecution() {
        var executions = new java.util.concurrent.atomic.AtomicInteger();
        try (var background = new com.jadecode.process.BackgroundManager(java.nio.file.Path.of(""), 10, (command, cwd, timeout, context) -> {
            executions.incrementAndGet(); return new com.jadecode.process.ShellCommand.Result("unexpected", 0, false);
        })) {
            var tool = new BashTool(background);
            for (String option : java.util.List.of("wait_for_completion", "allow_insertion", "wake_when_idle")) {
                var input = Json.mapper().createObjectNode().put("command", "touch must-not-exist")
                        .put("run_in_background", true).put(option, true);
                var result = tool.executeOutcome(input, com.jadecode.execution.ExecutionContext.create(
                        com.jadecode.execution.ExecutionContext.Source.USER, null));
                assertTrue(result.isError()); assertTrue(result.content().contains(option));
                assertFalse(background.hasPending()); assertEquals(0, executions.get());
            }
        }
    }
    /** 注入后端转入宿主路径时，运行中取消信号也必须终止实际进程。 */
    @Test void hostShellObservesCancellationAfterProcessStarts(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var context = com.jadecode.execution.ExecutionContext.create(
                com.jadecode.execution.ExecutionContext.Source.USER, null, cancelled::get);
        try (var shell = new com.jadecode.process.ShellCommand(workspace, 10)) {
            var result = java.util.concurrent.CompletableFuture.supplyAsync(() -> shell.run(
                    "printf ready > ready; sleep 20; printf late > late", context));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!java.nio.file.Files.exists(workspace.resolve("ready")) && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(java.nio.file.Files.exists(workspace.resolve("ready")));
            cancelled.set(true);
            assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS).output().contains("cancelled"));
            assertFalse(java.nio.file.Files.exists(workspace.resolve("late")));
        }
    }
}
