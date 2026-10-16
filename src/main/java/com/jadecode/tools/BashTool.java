package com.jadecode.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.agent.ExecutionContext;

/**
 * bash 工具：缺省同步；父循环可显式 run_in_background=true 后先拿启动回执。
 *
 * <p>两条路径都必须先经调用方 PreToolUse，不能因换线程而跳过权限检查。后台命令由
 * BackgroundManager 拥有，运行器收集完成事件；本工具不写历史、不调模型。后台执行方式与
 * 是否阻止当前任务结束由两个独立参数表达。</p>
 */
public class BashTool implements Tool {
    private final Path cwd;
    private final long timeoutSeconds;
    /** null 表示没有通知接收方，只提供同步能力，例如聚焦子代理。 */
    private final BackgroundManager background;

    /** 兼容旧装配：当前进程目录、120 秒，仅同步。 */
    public BashTool() {
        this(120);
    }

    /** 兼容旧定向测试：当前进程目录，仅同步；超时须为正数。 */
    public BashTool(long timeoutSeconds) {
        this(Path.of(""), timeoutSeconds, null);
    }

    /** 子代理显式绑定工作区，但不接后台通知队列。 */
    public BashTool(Path cwd) {
        this(cwd, 120, null);
    }

    /**
     * 父 CLI 装配：同步与后台命令交给同一管理器，退出由 AgentLoop.close 清理。
     * @param background 必须非 null，须与父循环注入同一个实例
     */
    public BashTool(BackgroundManager background) {
        this(Path.of(""), 120, java.util.Objects.requireNonNull(background));
    }

    /** 统一构造，不启动线程/命令；能力取决于是否注入管理器。 */
    private BashTool(Path cwd, long timeoutSeconds, BackgroundManager background) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        this.cwd = cwd;
        this.timeoutSeconds = timeoutSeconds;
        this.background = background;
    }

    /** @return 仍叫 bash，后台也适用原命令权限规则。 */
    @Override
    public String name() {
        return "bash";
    }

    /** @return 不向没有接收方的子代理承诺后台通知。 */
    @Override
    public String description() {
        return background == null ? "Run a shell command synchronously."
                : "Run a shell command. run_in_background=true returns an ID immediately, not completion."
                + " wait_for_completion defaults to true for background commands: the current task waits for"
                + " their results and resumes automatically. Set it to false only to detach from the task;"
                + " detached results are later reference notifications. All commands stop when the CLI exits.";
    }

    /** @return 有管理器才声明可选 boolean；字符串 \"true\" 不算布尔。 */
    @Override
    public Map<String, String> properties() {
        return background == null ? Map.of("command", "string")
                : Map.of("command", "string", "run_in_background", "boolean", "wait_for_completion", "boolean");
    }

    /** @return command 必填；省略后台参数保持同步默认。 */
    @Override
    public List<String> required() {
        return List.of("command");
    }

    /**
     * 校验再执行：只有真实 boolean true 走后台，不猜命令关键词。
     * @param input 不修改；非空字符串 command，可选布尔 run_in_background
     * @return 同步结果、启动回执或 Error；回执不证明进程已经成功启动或完成
     */
    @Override
    public String execute(ObjectNode input) {
        // 兼容普通同步调用；后台调用必须走运行器提供可信执行身份的重载。
        return execute(input, null);
    }

    /**
     * 后台路径默认必等，仍立即返回；身份来自 Harness，不允许模型在 input 里伪造所属任务。
     * 同步路径若显式传 wait_for_completion 则拒绝，避免无效参数让模型误以为改变了执行方式。
     */
    @Override
    public String execute(ObjectNode input, ExecutionContext context) {
        JsonNode command = input.get("command");
        if (command == null) {
            return "Error: Missing required parameter: command";
        }
        if (!command.isTextual() || command.textValue().isBlank()) {
            return "Error: command must be a non-empty string";
        }
        JsonNode requested = input.get("run_in_background");
        if (requested != null && !requested.isBoolean()) {
            return "Error: run_in_background must be a boolean";
        }
        boolean asynchronous = requested != null && requested.booleanValue();
        JsonNode waiting = input.get("wait_for_completion");
        if (waiting != null && !waiting.isBoolean()) return "Error: wait_for_completion must be a boolean";
        if (waiting != null && !asynchronous) return "Error: wait_for_completion requires run_in_background=true";
        if (asynchronous && background == null) {
            return "Error: Background execution is not available in this agent";
        }
        if (asynchronous) {
            if (context == null) return "Error: Background execution requires a Harness task context";
            try {
                boolean required = waiting == null || waiting.booleanValue();
                String id = background.start(command.textValue(), context, required);
                return "[Background task " + id + " started] " + (required
                        ? "The current task must process its result before finishing; it will resume automatically."
                        : "Detached from the current task. Its result is a later session notification.");
            } catch (IllegalArgumentException | IllegalStateException failure) {
                return "Error: " + failure.getMessage();
            }
        }
        // 父循环使用会话所有者；旧装配/子代理使用执行后立即关闭的局部执行器。
        if (background != null) {
            return background.runForeground(command.textValue());
        }
        try (ShellCommand shell = new ShellCommand(cwd, timeoutSeconds)) {
            return shell.run(command.textValue()).text();
        }
    }
}
