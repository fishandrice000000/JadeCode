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
 * 权限检查之后的 Shell 工具：同步返回最终结果，后台立即返回启动回执并由 Inbox 交付最终文本。
 * 后台结果始终允许插入，只有启动它的大轮次正常结束前必须等待；取消轮次不终止已接纳命令。
 * 本类不写历史、不请求模型、不重新审批；子代理没有结果接收端时只提供同步模式。
 */
public class BashTool implements Tool {
    private final Path cwd;
    private final long timeoutSeconds;
    private final BackgroundManager background;
    private final ShellCommand.Runner runner;

    /** 当前目录的同步 Shell，默认 120 秒；构造不执行命令。 */
    public BashTool() { this(120); }
    /** 当前目录的同步 Shell；非正数超时在构造时失败。 */
    public BashTool(long timeoutSeconds) { this(Path.of(""), timeoutSeconds, null, null); }
    /** 指定每次调用的初始目录；命令中的 cd 不影响后续调用。 */
    public BashTool(Path cwd) { this(cwd, 120, null, null); }
    /** 子代理注入受限后端；执行许可从每次上下文取得，不跨调用复用。 */
    public BashTool(Path cwd, ShellCommand.Runner runner) { this(cwd, 120, null, Objects.requireNonNull(runner)); }
    /** 会话和工具必须共用管理器，保证启动记录、通知交付和关闭资源对应。 */
    public BashTool(BackgroundManager background) { this(Path.of(""), 120, Objects.requireNonNull(background), null); }
    /** 保存不可变依赖；仅明确装配的宿主/离线入口允许 runner=null。 */
    private BashTool(Path cwd, long timeoutSeconds, BackgroundManager background, ShellCommand.Runner runner) {
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds must be positive");
        this.cwd = Objects.requireNonNull(cwd); this.timeoutSeconds = timeoutSeconds;
        this.background = background; this.runner = runner;
    }

    /** 工具名也是权限规则匹配键，同步和后台共用同一入口。 */
    @Override public String name() { return "bash"; }
    /** 按本实例实际是否有结果接收端，向模型说明可用执行模式。 */
    @Override public String description() {
        return background == null ? "Run a shell command synchronously."
                : "Run a shell command. run_in_background=true returns an ID immediately; the final result"
                + " is inserted at a safe tool boundary. The starting turn waits for it before completing."
                + " Interrupting the turn releases its wait but leaves accepted commands running; closing the session stops them.";
    }
    /** 只暴露真实支持的模式参数；沙箱额外字段由外层包装器添加。 */
    @Override public Map<String, String> properties() {
        return background == null ? Map.of("command", "string")
                : Map.of("command", "string", "run_in_background", "boolean");
    }
    /** command 必填，省略后台模式保持同步默认。 */
    @Override public List<String> required() { return List.of("command"); }
    /** 离线同步兼容入口；后台模式仍要求正式执行身份。 */
    @Override public String execute(ObjectNode input) { return execute(input, null); }
    /** 文字入口复用同一实现；正式调用方通过 ToolOutcome 保留错误标志。 */
    @Override public String execute(ObjectNode input, ExecutionContext context) { return executeOutcome(input, context).content(); }

    /**
     * 完整校验后执行一次；不修改输入，旧策略参数或未知字段在任何命令启动前明确拒绝。
     * network_access/access_paths 由外层沙箱解释，本层允许传递但不自行授予权限。
     * 后台返回启动说明，不用同一个 tool_use_id 再返回第二份 tool_result；最终输出交给通知链。
     * @param input 非 null，command 为非空白文本；run_in_background 如提供必须是真正 boolean
     * @param context 正式身份和取消条件；同步离线入口可为 null，后台必须提供
     * @return 同步最终输出/状态，或后台启动回执；校验失败没有执行副作用
     */
    @Override public ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context) {
        var command = input.get("command");
        if (command == null) return error("Missing required parameter: command");
        if (!command.isTextual() || command.textValue().isBlank()) return error("command must be a non-empty string");
        var fields = input.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!Set.of("command", "run_in_background", "network_access", "access_paths").contains(field))
                return error("Unsupported bash parameter: " + field);
        }
        var requested = input.get("run_in_background");
        if (requested != null && !requested.isBoolean()) return error("run_in_background must be a boolean");
        boolean asynchronous = requested != null && requested.booleanValue();
        if (asynchronous) {
            if (background == null) return error("Background execution is not available in this agent");
            if (context == null) return error("Background execution requires a Harness task context");
            try {
                String id = background.start(command.textValue(), context);
                return new ToolOutcome("[Background task " + id + " started] Final output will be delivered as a notification."
                        + " The current turn must adopt it before completing.", false);
            } catch (IllegalArgumentException | IllegalStateException failure) { return error(failure.getMessage()); }
        }
        // 同步模式不投递通知；退出码而非 stdout 文本决定成功，正常 Error 开头输出不被误判。
        if (background != null) return outcome(background.runForegroundResult(command.textValue(), context));
        try (var shell = new ShellCommand(cwd, timeoutSeconds, runner)) { return outcome(shell.run(command.textValue(), context)); }
    }
    /** 缺失退出码也属于失败；保留真实的工具输出。 */
    private static ToolOutcome outcome(ShellCommand.Result result) {
        return new ToolOutcome(result.text(), result.exitCode() == null || result.exitCode() != 0);
    }
    /** 参数错误发生在执行之前，沿用工具错误格式。 */
    private static ToolOutcome error(String message) { return new ToolOutcome("Error: " + message, true); }
}
