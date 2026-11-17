package com.jadecode.sandbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.process.ShellCommand;
import com.jadecode.tools.*;
import com.jadecode.util.*;

/**
 * 容器内的一次性工具执行入口。从 stdin 读取一份有界 JSON，按工具类型执行 Shell、文件或受管 Git 操作，
 * 收集输出、OOM 与后代进程清理状态，再向 stdout 返回结构化结果。
 */
public final class SandboxWorker {
    /**
     * 汇集容器内工具执行、进程清理和结果编码入口。
     */
    private SandboxWorker() {
    }

    /**
     * 读取最多 1 MiB 的请求并解析 cwd、工具和参数，调用对应容器内执行器，收集结果或错误说明。比较执行前后 OOM 计数，
     * 清除本次残留进程并最多等待一秒，最后输出带 protocol、clean、content 和 isError 的 JSON，
     * Shell 结果附带退出码与原始输出。
     *
     * @param ignored 容器 Worker 的命令行参数；操作信息通过标准输入 JSON 提供
     * @throws Exception 结果编码、输出写入或末尾进程清理等待无法完成；工具执行异常先转为错误 JSON
     */
    public static void main(String[] ignored) throws Exception {
        byte[] bytes = System.in.readNBytes(1_048_577);
        ObjectNode result = Json.mapper().createObjectNode();
        long initialOom = oomKills();
        try {
            if (bytes.length > 1_048_576)
                throw new IllegalArgumentException("Worker input exceeds limit");
            var request = Json.mapper().readTree(bytes);
            Path cwd = Path.of(request.path("cwd").asText()).toAbsolutePath().normalize();
            var input = (ObjectNode) request.path("input");
            String tool = request.path("tool").asText();
            if (tool.equals("managed.git")) {
                result.put("content", com.jadecode.workspace.GitWorker.execute(cwd, input)).put("isError", false);
            } else if (tool.equals("bash")) {
                // 每个操作单独创建 Shell；后台编排在宿主，不在这里 fork 常驻工作者。
                try (var shell = new ShellCommand(cwd, request.path("timeout").asLong(120))) {
                    var value = shell.run(input.path("command").asText());
                    result.put("content", value.text())
                            .put("isError", value.exitCode() == null || value.exitCode() != 0)
                            .put("truncated", value.truncated()).put("rawOutput", value.output());
                    if (value.exitCode() != null)
                        result.put("exitCode", value.exitCode());
                }
            } else {
                var resolver = new PathResolver(cwd);
                Tool selected = Map.<String, Tool>of("read_file", new ReadFileTool(resolver),
                        "write_file", new WriteFileTool(resolver), "edit_file", new EditFileTool(resolver),
                        "glob", new GlobTool(resolver)).get(tool);
                if (selected == null)
                    throw new IllegalArgumentException("Unsupported worker operation");
                var outcome = selected.executeOutcome(input, null);
                result.put("content", outcome.content()).put("isError", outcome.isError());
            }
        } catch (Exception failure) {
            String detail = failure.getClass().getSimpleName();
            if (requestTool(bytes).equals("managed.git") && failure.getMessage() != null)
                detail += ": " + failure.getMessage().substring(0, Math.min(2048, failure.getMessage().length()));
            result.put("content", "Error: Worker operation failed: " + detail).put("isError", true);
        }
        long finalOom = oomKills();
        result.put("oomCount", finalOom);
        if (initialOom >= 0 && finalOom > initialOom) {
            result.put("content",
                    "Error: Sandbox OOM kill observed during this operation.\n" + result.path("content").asText())
                    .put("isError", true);
            result.remove("exitCode");
            result.remove("rawOutput");
        }
        // 复用容器一次只运行一个操作。清除脱离 shell 原进程组的后代，保护下一次工具调用。
        long self = ProcessHandle.current().pid();
        ProcessHandle.allProcesses().filter(p -> p.pid() != 1 && p.pid() != self)
                .forEach(ProcessHandle::destroyForcibly);
        long cleanupDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        boolean clean;
        do {
            clean = ProcessHandle.allProcesses().noneMatch(p -> p.pid() != 1 && p.pid() != self && p.isAlive());
            if (!clean)
                Thread.sleep(20);
        } while (!clean && System.nanoTime() < cleanupDeadline);
        if (!clean) {
            result.put("content",
                    "Error: Sandbox descendants did not stop; container must be retired.\n"
                            + result.path("content").asText())
                    .put("isError", true);
            result.remove("exitCode");
            result.remove("rawOutput");
        }
        result.put("clean", clean);
        result.put("protocol", 1);
        System.out.write(Json.mapper().writeValueAsString(result).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析请求中的工具名，供受管 Git 失败时选择有界详细诊断；请求解析失败返回空串。
     *
     * @param bytes 需要解码、校验或计算哈希的完整字节数组
     * @return 请求中声明的工具名；JSON 无法解析时为空串
     */
    private static String requestTool(byte[] bytes) {
        try {
            return Json.mapper().readTree(bytes).path("tool").asText();
        } catch (Exception invalid) {
            return "";
        }
    }

    /**
     * 读取本容器 cgroup v2 的 oom_kill 计数；文件不可读、字段缺失或格式错误时返回 -1，供执行前后比较使用。
     *
     * @return 本容器 cgroup 的 oom_kill 计数；无法读取时为 -1
     */
    private static long oomKills() {
        try {
            for (String line : java.nio.file.Files.readAllLines(Path.of("/sys/fs/cgroup/memory.events")))
                if (line.startsWith("oom_kill "))
                    return Long.parseLong(line.substring(9).strip());
        } catch (Exception unknown) {
            return -1;
        }
        return -1;
    }
}
