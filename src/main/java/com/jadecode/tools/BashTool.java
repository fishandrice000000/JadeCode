package com.jadecode.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.process.BackgroundManager;
import com.jadecode.process.ShellCommand;

/**
 * 将模型的 bash 调用接到会话 Shell 服务。同步模式返回最终执行结果，后台模式返回启动编号，
 * 并由会话通知链交付最终输出。已接入 BackgroundManager 的会话支持两种模式，独立子代理入口使用同步模式。
 */
public class BashTool implements Tool {
    private final Path cwd;
    private final long timeoutSeconds;
    private final BackgroundManager background;
    private final ShellCommand.Runner runner;

    /**
     * 建立当前工作目录的同步 Shell 工具，默认每条命令超时 120 秒。
     */
    public BashTool() {
        this(120);
    }

    /**
     * 建立当前工作目录的同步 Shell 工具，使用指定的正数超时。
     *
     * @param timeoutSeconds 每次命令或容器操作的正数等待上限，单位秒
     */
    public BashTool(long timeoutSeconds) {
        this(Path.of(""), timeoutSeconds, null, null);
    }

    /**
     * 建立指定工作目录的同步 Shell 工具，每次调用以该目录为起点，默认超时 120 秒。
     *
     * @param cwd 本次操作起始工作目录，相对路径以此为基准
     */
    public BashTool(Path cwd) {
        this(cwd, 120, null, null);
    }

    /**
     * 为指定工作目录绑定同步受限执行后端，供子代理沿本次上下文执行已授权计划。
     *
     * @param cwd 本次操作起始工作目录，相对路径以此为基准
     * @param runner Shell 执行后端；null 时使用本地进程入口
     * @throws NullPointerException runner 为 null
     */
    public BashTool(Path cwd, ShellCommand.Runner runner) {
        this(cwd, 120, null, Objects.requireNonNull(runner));
    }

    /**
     * 绑定会话共用的 BackgroundManager，供模型选择前台同步或后台执行，并由同一会话采用后台结果。
     *
     * @param background 会话共用的后台命令管理器，负责编号、交付和停止
     * @throws NullPointerException background 为 null
     */
    public BashTool(BackgroundManager background) {
        this(Path.of(""), 120, Objects.requireNonNull(background), null);
    }

    /**
     * 校验正数超时并保存工作目录、可选后台管理器和可选 runner。后续 executeOutcome 按已装配能力选择执行入口。
     *
     * @param cwd 本次操作起始工作目录，相对路径以此为基准
     * @param timeoutSeconds 每次命令或容器操作的正数等待上限，单位秒
     * @param background 会话共用的后台命令管理器，负责编号、交付和停止
     * @param runner Shell 执行后端；null 时使用本地进程入口
     * @throws IllegalArgumentException 每条命令的 timeoutSeconds 不是正数
     * @throws NullPointerException cwd 为 null
     */
    private BashTool(Path cwd, long timeoutSeconds, BackgroundManager background, ShellCommand.Runner runner) {
        if (timeoutSeconds <= 0)
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        this.cwd = Objects.requireNonNull(cwd);
        this.timeoutSeconds = timeoutSeconds;
        this.background = background;
        this.runner = runner;
    }

    /**
     * 工具名也是权限规则匹配键，同步和后台共用同一入口。
     *
     * @return 该工具的稳定注册名
     */
    @Override
    public String name() {
        return "bash";
    }

    /**
     * 按本实例实际是否有结果接收端，向模型说明可用执行模式。
     *
     * @return 根据当前已装配能力生成的模型可见说明
     */
    @Override
    public String description() {
        return background == null ? "Run a shell command synchronously."
                : "Run a shell command. run_in_background=true returns an ID immediately; the final result"
                        + " is inserted at a safe tool boundary. The starting turn waits for it before completing."
                        + " Interrupting the turn releases its wait but leaves accepted commands running; closing the session stops them.";
    }

    /**
     * 根据是否装配后台管理器返回 command 字符串定义及可选 run_in_background 布尔定义，供 schema 与输入校验使用。
     *
     * @return 当前工具支持的参数名到 JSON 类型的映射
     */

    private Map<String, String> properties() {
        return background == null ? Map.of("command", "string")
                : Map.of("command", "string", "run_in_background", "boolean");
    }

    /**
     * 返回必填参数 command；省略 run_in_background 时按同步执行处理。
     *
     * @return 该工具要求提供的参数名列表
     */

    private List<String> required() {
        return List.of("command");
    }

    /**
     * 校验非空 command、支持字段和布尔后台标记。后台模式要求管理器及执行上下文，start 成功后返回启动 ID 回执；
     * 同步模式调用共享前台 Shell 或本次独立 Shell，按实际退出码构造结果。参数和启动错误返回 isError=true，
     * 网络与额外路径由外层沙箱计划解释。
     *
     * @param input 本次工具参数 JSON 对象，包含该工具 schema 约定的字段
     * @param context 当前轮次的来源、身份、审批通道、派工范围和取消检查
     * @return 同步执行的最终结果，或后台接纳回执；失败分支的 isError 为 true
     */
    @Override
    public ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context) {
        var command = input.get("command");
        if (command == null)
            return error("Missing required parameter: command");
        if (!command.isTextual() || command.textValue().isBlank())
            return error("command must be a non-empty string");
        var fields = input.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!Set.of("command", "run_in_background", "network_access", "access_paths").contains(field))
                return error("Unsupported bash parameter: " + field);
        }
        var requested = input.get("run_in_background");
        if (requested != null && !requested.isBoolean())
            return error("run_in_background must be a boolean");
        boolean asynchronous = requested != null && requested.booleanValue();
        if (asynchronous) {
            if (background == null)
                return error("Background execution is not available in this agent");
            if (context == null)
                return error("Background execution requires a Harness task context");
            try {
                String id = background.start(command.textValue(), context);
                return new ToolOutcome(
                        "[Background task " + id + " started] Final output will be delivered as a notification."
                                + " The current turn must adopt it before completing.",
                        false);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                return error(failure.getMessage());
            }
        }
        // 同步模式不投递通知；退出码而非 stdout 文本决定成功，正常 Error 开头输出不被误判。
        if (background != null)
            return outcome(background.runForegroundResult(command.textValue(), context));
        try (var shell = new ShellCommand(cwd, timeoutSeconds, runner)) {
            return outcome(shell.run(command.textValue(), context));
        }
    }

    /**
     * 将 Shell 文本与实际退出码转为 ToolOutcome：退出码为 0 时成功，非零或缺失时标为错误。
     *
     * @param result Shell 实际执行后的有界输出、退出码和截断标记
     * @return 按实际退出码确定错误标记的工具结果
     */
    private static ToolOutcome outcome(ShellCommand.Result result) {
        return new ToolOutcome(result.text(), result.exitCode() == null || result.exitCode() != 0);
    }

    /**
     * 将参数或启动错误加上 Error 前缀，返回错误 ToolOutcome 供执行器配对调用 ID。
     *
     * @param message 需要返回给调用者的结果或错误说明
     * @return 带 Error 前缀且 isError 为 true 的工具结果
     */
    private static ToolOutcome error(String message) {
        return new ToolOutcome("Error: " + message, true);
    }

    /**
     * 根据此实例实际支持的参数和必填项生成完整 object schema，供模型工具目录使用。
     *
     * @return 完整的 object 参数 schema，含字段类型与必填声明
     */
    @Override
    public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
        return com.jadecode.tools.ToolSchema.objectSchema(properties(), required());
    }
}
