package com.jadecode.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.process.BackgroundManager;
import com.jadecode.process.ShellCommand;

/**
 * 把模型给出的 shell 命令交给 ShellCommand 或 BackgroundManager 运行。
 *
 * <p>默认等命令结束再返回。父 Agent 可以设置 run_in_background=true，
 * 让工具先返回 bg_id，命令继续在后台跑。wait_for_completion 则决定当前任务是否必须
 * 处理它的结果，默认是 true；工具仍立即返回，只是 SessionRuntime 不能提前宣布任务结束。</p>
 *
 * <p>两种方式都要先经过 PreToolUse 检查权限。这里不询问用户、不写 history，也不调用模型。
 * 当前子代理没有后台结果处理功能，所以只提供同步命令。</p>
 */
public class BashTool implements Tool {
    /** 没有管理器时 shell 使用的工作目录，有管理器则用它自己的目录。 */
    private final Path cwd;
    /** 没有管理器时，每条同步命令的超时秒数。 */
    private final long timeoutSeconds;
    /** null 表示只支持同步执行，例如当前的子代理。 */
    private final BackgroundManager background;

    /**
     * 在当前进程目录执行同步命令，默认最多 120 秒；不提供后台功能，也不立即执行命令。
     */
    public BashTool() {
        this(120);
    }

    /**
     * 使用当前进程目录，只提供同步命令。
     * @param timeoutSeconds 每条命令最多执行多少秒
     * @throws IllegalArgumentException 不是正数
     */
    public BashTool(long timeoutSeconds) {
        this(Path.of(""), timeoutSeconds, null);
    }

    /**
     * 在给定目录提供同步命令，默认 120 秒，供子代理使用。
     * @param cwd shell 工作目录；某次命令里 cd 不改变其它命令的初始目录
     */
    public BashTool(Path cwd) {
        this(cwd, 120, null);
    }

    /**
     * 父 Agent 使用：同步和后台命令都交给同一个管理器，退出时一起停止。
     * @param background 必须与 SessionRuntime 使用同一对象，才能找到本工具启动的后台命令
     * @throws NullPointerException background 为 null
     */
    public BashTool(BackgroundManager background) {
        this(Path.of(""), 120, java.util.Objects.requireNonNull(background));
    }

    /**
     * 保存执行命令要用的对象和设置，不启动线程或命令。
     * @param cwd 没有管理器时，局部 shell 使用的工作目录
     * @param timeoutSeconds 没有管理器时，每条命令的超时秒数，必须大于零
     * @param background 可以为 null；提供它时才支持后台命令
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

    /**
     * @return 给模型的用法说明；没有后台管理器时，只告诉模型能执行同步命令
     */
    @Override
    public String description() {
        return background == null ? "Run a shell command synchronously."
                : "Run a shell command. run_in_background=true returns an ID immediately, not completion."
                + " wait_for_completion defaults to true for background commands: the current task waits for"
                + " their results and resumes automatically. Set it to false only to detach from the task;"
                + " allow_insertion=false by default defers results until the finishing/waiting phase."
                + " wake_when_idle=false by default does not start a new turn for detached results."
                + " Set wake_when_idle=true when a detached result needs an automatic follow-up."
                + " All commands stop when the CLI exits.";
    }

    /**
     * 告诉模型可以传哪些参数。有后台管理器才提供后台模式及三项结果处理设置。
     * @return command，以及 run_in_background/wait_for_completion/allow_insertion/wake_when_idle
     */
    @Override
    public Map<String, String> properties() {
        return background == null ? Map.of("command", "string")
                : Map.of("command", "string", "run_in_background", "boolean", "wait_for_completion", "boolean",
                        "allow_insertion", "boolean", "wake_when_idle", "boolean");
    }

    /** @return command 必填；省略后台参数保持同步默认。 */
    @Override
    public List<String> required() {
        return List.of("command");
    }

    /**
     * 旧调用者不传任务信息时使用，直接调用双参数 execute，不单独写一份执行代码。
     * @param input 非 null 参数对象，不修改；command 必须是非空白字符串
     * @return 同步输出或 Error 文字；即使有管理器，请求后台也会因没有任务信息而被拒绝
     * @see #execute(ObjectNode, ExecutionContext)
     */
    @Override
    public String execute(ObjectNode input) {
        // 普通同步命令不需要任务 ID；后台命令要用双参数版本，程序才能记住它属于谁。
        return execute(input, null);
    }

    /**
     * 检查参数，再同步运行或放到后台，结果由调用者写进 history。
     *
     * <p>比如模型要后台构建再检查报告：设置 run_in_background=true，
     * 本方法立即返回 bg_id，模型可以先调用 read_file。wait_for_completion 默认 true，
     * 表示之后必须处理构建结果才能结束任务。不是在本方法里等，而是 SessionRuntime
     * 等结果后再次请模型继续。设 false 才表示这项任务不用等，但退出 CLI 时命令仍会停止。</p>
     *
     * <p>只接受真正的 JSON boolean，不接受字符串 "true" 或显式 null。
     * wait_for_completion 只有后台模式才能传，不能默默忽略它，让模型误以为设置生效。
     * 调用者必须先做权限检查；这里不再次询问用户，也不请求模型。</p>
     * @param input 非 null 参数对象，不修改；command 必填，后台模式及结果处理开关可选
     * @param context 程序记录的任务信息；后台必须提供，用来找回所属任务的结果，同步可为 null
     * @return 同步时为输出或错误；后台时为 bg_id 和启动说明，不代表跑完。
     *         参数不对、当前 Agent 不支持后台或管理器不能接收时，返回 Error 文字
     */
    @Override
    public String execute(ObjectNode input, ExecutionContext context) {
        // 1. 所有开关都检查完再执行；不适用的参数报错，不能让模型以为设置已经生效。
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
        for (String flag : List.of("allow_insertion", "wake_when_idle")) {
            if (input.has(flag) && !input.get(flag).isBoolean()) return "Error: " + flag + " must be a boolean";
            if (input.has(flag) && !asynchronous) return "Error: " + flag + " requires run_in_background=true";
        }
        if (asynchronous && background == null) {
            return "Error: Background execution is not available in this agent";
        }
        if (asynchronous) {
            // 2. 所属任务由程序另外传入，不能由模型填写；调用这里前已经检查过权限。
            if (context == null) return "Error: Background execution requires a Harness task context";
            try {
                boolean required = waiting == null || waiting.booleanValue();
                String id = background.start(command.textValue(), context, required,
                        input.path("allow_insertion").asBoolean(false), input.path("wake_when_idle").asBoolean(false));
                return "[Background task " + id + " started] " + (required
                        ? "The current task must process its result before finishing; it will resume automatically."
                        : "Detached from the current task. Its result is a later session notification.");
            } catch (IllegalArgumentException | IllegalStateException failure) {
                return "Error: " + failure.getMessage();
            }
        }
        // 3. 同步命令直接返回最终结果，不再发后台通知。有管理器就用它，否则执行后关闭局部 shell。
        if (background != null) {
            return background.runForeground(command.textValue(), context);
        }
        try (ShellCommand shell = new ShellCommand(cwd, timeoutSeconds)) {
            return shell.run(command.textValue(), context).text();
        }
    }
}
