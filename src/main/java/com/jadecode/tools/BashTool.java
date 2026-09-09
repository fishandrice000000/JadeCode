package com.jadecode.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * bash:执行 shell 命令的工具,模型最常用的动作入口。
 *
 * <p>实现要点:经 {@code /bin/sh -c} 启动子进程;stderr 并流进 stdout(模型只看到一路
 * 输出);默认 120 秒超时,到点强杀进程防孤儿;stdout 超过 50000 字符截断。</p>
 */
public class BashTool implements Tool {
    private static final int MAX_OUTPUT = 50000;

    /** 单条命令的超时秒数 */
    private final long timeoutSeconds;

    /** 默认构造:120 秒超时 */
    public BashTool() {
        this(120);
    }

    /**
     * @param timeoutSeconds 单条命令的超时秒数;超时杀进程并回 "Error: Timeout (Ns)"
     */
    public BashTool(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * @return 工具名 "bash"
     */
    @Override
    public String name() {
        return "bash";
    }

    /**
     * @return 给模型的说明:跑 shell 命令
     */
    @Override
    public String description() {
        return "Run a shell command.";
    }

    /**
     * @return 单参数 command(string);多行、管道都在这一条字符串里
     */
    @Override
    public Map<String, String> properties() {
        return Map.of("command", "string");
    }

    /**
     * @return 必填 [command]
     */
    @Override
    public List<String> required() {
        return List.of("command");
    }

    /**
     * 缺 command 直接报错;否则透传 {@link #run(String)}。
     *
     * @param input 模型参数,须含 command 字符串
     * @return 执行结果或 "Error: ..." 文案(超时/中断/IO 失败也走字符串,不抛)
     */
    @Override
    public String execute(ObjectNode input) {
        JsonNode commandNode = input.get("command");
        if (commandNode == null) {
            return "Error: Missing required parameter: command";
        }
        return run(commandNode.asText());
    }

    /**
     * 跑一条命令并等它结束:先 waitFor 再读输出(先读后等会被管道写满死锁),
     * 超时强杀;输出 UTF-8 解码、去首尾空白、超长截断、空则回 "(no output)"。
     */
    private String run(String command) {
        // 1. 启动进程:sh -c 执行,stderr 并流进 stdout
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return "Error: " + e.getMessage(); // 进程起不来(比如 sh 不存在)
        }

        // 2. 等待结束,带超时——先等后读(坑 3 的顺序)
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

        // 3. 进程已结束,读输出(UTF-8,坏字节自动替换成 U+FFFD)
        String out;
        try {
            byte[] bytes = process.getInputStream().readAllBytes();
            out = new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }

        // 4. 空输出与截断
        if (out.isEmpty()) {
            return "(no output)";
        }
        return out.length() > MAX_OUTPUT ? out.substring(0, MAX_OUTPUT) : out;
    }
}
