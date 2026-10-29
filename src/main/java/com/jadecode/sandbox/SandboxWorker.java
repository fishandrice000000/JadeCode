package com.jadecode.sandbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.process.ShellCommand;
import com.jadecode.tools.*;
import com.jadecode.util.*;

/**
 * 只在 Docker 镜像内运行的一次性执行端。没有模型、审批、宿主配置或 Docker 权限。
 * 输入一份有界 JSON，执行一次已批准操作，输出一份带失败状态的 JSON；原始输出作为数据返回。
 */
public final class SandboxWorker {
    private SandboxWorker() { }
    /** 入口不加载 AppConfig，避免将认证与 CLI/TUI 生命周期带进工作容器。 */
    public static void main(String[] ignored) throws Exception {
        byte[] bytes = System.in.readNBytes(1_048_577);
        ObjectNode result = Json.mapper().createObjectNode();
        long initialOom = oomKills();
        try {
            if (bytes.length > 1_048_576) throw new IllegalArgumentException("Worker input exceeds limit");
            var request = Json.mapper().readTree(bytes);
            Path cwd = Path.of(request.path("cwd").asText()).toAbsolutePath().normalize();
            var input = (ObjectNode) request.path("input");
            String tool = request.path("tool").asText();
            if (tool.equals("managed.git")) {
                result.put("content",com.jadecode.workspace.GitWorker.execute(cwd,input)).put("isError",false);
            } else if (tool.equals("bash")) {
                // 每个操作单独创建 Shell；后台编排在宿主，不在这里 fork 常驻工作者。
                try (var shell = new ShellCommand(cwd, request.path("timeout").asLong(120))) {
                    var value = shell.run(input.path("command").asText());
                    result.put("content", value.text()).put("isError", value.exitCode() == null || value.exitCode() != 0)
                            .put("truncated", value.truncated()).put("rawOutput", value.output());
                    if (value.exitCode() != null) result.put("exitCode", value.exitCode());
                }
            } else {
                var resolver = new PathResolver(cwd);
                Tool selected = Map.<String, Tool>of("read_file", new ReadFileTool(resolver),
                        "write_file", new WriteFileTool(resolver), "edit_file", new EditFileTool(resolver),
                        "glob", new GlobTool(resolver)).get(tool);
                if (selected == null) throw new IllegalArgumentException("Unsupported worker operation");
                var outcome = selected.executeOutcome(input, null);
                result.put("content", outcome.content()).put("isError", outcome.isError());
            }
        } catch (Exception failure) {
            String detail=failure.getClass().getSimpleName();
            if (requestTool(bytes).equals("managed.git") && failure.getMessage()!=null)
                detail += ": "+failure.getMessage().substring(0,Math.min(2048,failure.getMessage().length()));
            result.put("content", "Error: Worker operation failed: " + detail).put("isError", true);
        }
        long finalOom = oomKills(); result.put("oomCount", finalOom);
        if (initialOom >= 0 && finalOom > initialOom) {
            result.put("content", "Error: Sandbox OOM kill observed during this operation.\n" + result.path("content").asText()).put("isError", true);
            result.remove("exitCode"); result.remove("rawOutput");
        }
        // 复用容器一次只运行一个操作。清除脱离 shell 原进程组的后代，保护下一次工具调用。
        long self = ProcessHandle.current().pid();
        ProcessHandle.allProcesses().filter(p -> p.pid() != 1 && p.pid() != self).forEach(ProcessHandle::destroyForcibly);
        long cleanupDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        boolean clean;
        do {
            clean = ProcessHandle.allProcesses().noneMatch(p -> p.pid() != 1 && p.pid() != self && p.isAlive());
            if (!clean) Thread.sleep(20);
        } while (!clean && System.nanoTime() < cleanupDeadline);
        if (!clean) {
            result.put("content", "Error: Sandbox descendants did not stop; container must be retired.\n" + result.path("content").asText())
                    .put("isError", true);
            result.remove("exitCode"); result.remove("rawOutput");
        }
        result.put("clean", clean);
        result.put("protocol", 1);
        System.out.write(Json.mapper().writeValueAsString(result).getBytes(StandardCharsets.UTF_8));
    }
    /** 仅用于受控 Git 的有界诊断，不把其它工具异常中的参数或环境配置扩展到输出。 */
    private static String requestTool(byte[] bytes) {
        try { return Json.mapper().readTree(bytes).path("tool").asText(); }
        catch (Exception invalid) { return ""; }
    }
    /** cgroup v2 的本容器 OOM 计数；不可读返回未知，不能伪造“没有 OOM”。 */
    private static long oomKills() {
        try {
            for (String line : java.nio.file.Files.readAllLines(Path.of("/sys/fs/cgroup/memory.events")))
                if (line.startsWith("oom_kill ")) return Long.parseLong(line.substring(9).strip());
        } catch (Exception unknown) { return -1; }
        return -1;
    }
}
