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
    /** 无管理器的同步路径使用的工作目录；有管理器时以管理器绑定目录为准。 */
    private final Path cwd;
    /** 局部同步 ShellCommand 的最长执行秒数，不是模型等待结果的轮数。 */
    private final long timeoutSeconds;
    /** null 表示没有通知接收方，只提供同步能力，例如聚焦子代理。 */
    private final BackgroundManager background;

    /** 兼容旧装配：当前进程目录、120 秒，仅同步。 */
    public BashTool() {
        this(120);
    }

    /**
     * 使用当前进程目录创建仅同步工具，不启动命令。
     * @param timeoutSeconds 每条命令最长执行秒数
     * @throws IllegalArgumentException 超时不是正数
     */
    public BashTool(long timeoutSeconds) {
        this(Path.of(""), timeoutSeconds, null);
    }

    /**
     * 显式绑定工作区和默认 120 秒超时，不提供后台能力，供聚焦子代理使用。
     * @param cwd 子代理 shell 工作目录，不受命令内 cd 对其它调用的影响
     */
    public BashTool(Path cwd) {
        this(cwd, 120, null);
    }

    /**
     * 父 CLI 装配：同步与后台命令交给同一管理器，退出由 AgentLoop.close 清理。
     * @param background 必须非 null，须与父循环注入同一个实例
     * @throws NullPointerException background 为 null
     */
    public BashTool(BackgroundManager background) {
        this(Path.of(""), 120, java.util.Objects.requireNonNull(background));
    }

    /**
     * 统一绑定依赖，不启动线程/命令。
     * @param cwd 无管理器路径的工作目录
     * @param timeoutSeconds 无管理器路径的正数秒超时
     * @param background 可选会话命令管理器，有它才声明并支持后台参数
     * @throws IllegalArgumentException timeoutSeconds 不是正数
     */
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

    /**
     * 根据装配能力声明模型可见参数，不向无通知接收方的子代理承诺后台执行。
     * @return command 字符串，以及仅父工具提供的 run_in_background/wait_for_completion 布尔参数
     */
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
     * 不带任务身份的兼容入口，复用同一参数校验与同步执行实现。
     * @param input 非 null 的模型参数对象，不修改；command 必须是非空白字符串
     * @return 同步输出或 Error 文本；即使装配了管理器，显式后台调用仍因缺少可信上下文而拒绝
     * @see #execute(ObjectNode, ExecutionContext)
     */
    @Override
    public String execute(ObjectNode input) {
        // 兼容普通同步调用；后台调用必须走运行器提供可信执行身份的重载。
        return execute(input, null);
    }

    /**
     * 后台路径默认必等，仍立即返回；身份来自 Harness，不允许模型在 input 里伪造所属任务。
     * 同步路径若显式传 wait_for_completion 则拒绝，避免无效参数让模型误以为改变了执行方式。
     *
     * <p>两个维度应分开理解：run_in_background 决定工具调用是否立即返回；
     * wait_for_completion 决定运行器能否在未处理结果时结束当前任务。默认后台必等，不是在
     * 本方法里阻塞，而是由 SessionRuntime 稍后进入 WAITING_BACKGROUND 并接结果续跑。
     * false 表示脱离当前目标，仍受整个 CLI 关闭约束，不能当成长驻服务。</p>
     *
     * <p>调用前必须已走 PreToolUse；本工具不自行审批，不写会话历史，不调用模型。
     * command 以及可选参数类型严格检查，字符串 "true"、显式 null 不是合法布尔值。</p>
     * @param input 非 null 参数对象；command 必填，可选 run_in_background，后台时可选
     *              wait_for_completion；只读，不修改
     * @param context Harness 传来的所属身份；同步可为 null，后台必须存在以绑定结果归属
     * @return 同步路径返回最终输出/错误；后台路径返回 bg_id 启动回执，最终完成通过通知交付。
     *         参数非法、能力缺失或管理器拒绝启动返回 Error 文本，不伪造成功
     */
    @Override
    public String execute(ObjectNode input, ExecutionContext context) {
        // 1. 先完整校验两个开关再执行，不能“忽略不适用字段”让模型误判命令的等待语义。
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
            // 2. 身份由调用方额外传入，不从 input 获取；后台登记只负责执行，审批已在此前完成。
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
        // 3. 同步命令不会产生第二份完成事件：父工具用会话资源，子代理/旧装配用局部资源。
        if (background != null) {
            return background.runForeground(command.textValue());
        }
        try (ShellCommand shell = new ShellCommand(cwd, timeoutSeconds)) {
            return shell.run(command.textValue()).text();
        }
    }
}
