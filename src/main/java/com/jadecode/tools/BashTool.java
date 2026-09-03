package com.jadecode.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class BashTool implements Tool {
    private static final List<String> DANGEROUS = List.of("rm -rf /", "sudo", "shutdown", "reboot", "> /dev/");
    private static final int MAX_OUTPUT = 50000;

    private final long timeoutSeconds;

    public BashTool() {
        this(120);
    }

    public BashTool(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Run a shell command.";
    }

    @Override
    public Map<String, String> properties() {
        return Map.of("command", "string");
    }

    @Override
    public List<String> required() {
        return List.of("command");
    }

    @Override
    public String execute(ObjectNode input) {
        JsonNode commandNode = input.get("command");
        if (commandNode == null) {
            return "Error: Missing required parameter: command";
        }
        return run(commandNode.asText());
    }

    private String run(String command) {
        // 1. 确认 command 无害
        for (String d : DANGEROUS) {
            if (command.contains(d)) {
                return "Error: Dangerous command blocked";
            }
        }

        // 2. 启动进程:sh -c 执行,stderr 并流进 stdout
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return "Error: " + e.getMessage(); // 进程起不来(比如 sh 不存在)
        }

        // 3. 等待结束,带超时——先等后读(坑 3 的顺序)
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断标志:Java 惯例,不吞中断
            process.destroyForcibly();
            return "Error: Interrupted";
        }

        if (!finished) { // 超时:必须杀掉,否则留孤儿进程
            process.destroyForcibly();
            return "Error: Timeout (" + timeoutSeconds + "s)";
        }

        // 4. 进程已结束,读输出(UTF-8,坏字节自动替换成 U+FFFD)
        String out;
        try {
            byte[] bytes = process.getInputStream().readAllBytes();
            out = new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }

        // 5. 空输出与截断
        if (out.isEmpty()) {
            return "(no output)";
        }
        return out.length() > MAX_OUTPUT ? out.substring(0, MAX_OUTPUT) : out;
    }
}
