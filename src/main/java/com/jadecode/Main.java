package com.jadecode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.agent.AgentLoop;
import com.jadecode.cli.ReplCommand;
import com.jadecode.cli.RunCommand;
import com.jadecode.config.AppConfig;
import com.jadecode.config.Env;
import com.jadecode.llm.AnthropicSdkLlmClient;
import com.jadecode.messages.Message;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.safety.CommandPolicy;
import com.jadecode.safety.Hooks;
import com.jadecode.safety.PathGuard;
import com.jadecode.safety.PathResolver;
import com.jadecode.safety.PermissionGate;
import com.jadecode.subagent.SubagentOutcome;
import com.jadecode.subagent.SubagentRuntime;
import com.jadecode.todo.TodoManager;
import com.jadecode.tools.BashTool;
import com.jadecode.tools.EditFileTool;
import com.jadecode.tools.GlobTool;
import com.jadecode.tools.ReadFileTool;
import com.jadecode.tools.SubagentTool;
import com.jadecode.tools.TodoWriteTool;
import com.jadecode.tools.Tool;
import com.jadecode.tools.WriteFileTool;
import com.jadecode.util.Ansi;
import com.jadecode.util.Json;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * JadeCode 的 CLI 入口(picocli):顶层命令不带参数只打印用法,
 * 实际动作在 run / repl 两个子命令里。
 *
 * <p>buildLoop() 是全项目唯一的装配点:配置、安全闸门、七把工具、hook 事件表、
 * TodoManager 都在这里接线——之后加工具或加 hook,先来这看注册顺序。</p>
 */
@Command(name = "jadecode", version = "jadecode 0.1.0-SNAPSHOT", mixinStandardHelpOptions = true,

        subcommands = { RunCommand.class, ReplCommand.class }, description = "JadeCode:学习用 CLI coding agent")
public class Main implements Callable<Integer> {
    private static final String BASH_TOOL = "bash";
    private static final String READ_FILE_TOOL = "read_file";
    private static final String WRITE_FILE_TOOL = "write_file";
    private static final String EDIT_FILE_TOOL = "edit_file";
    private static final String GLOB_TOOL = "glob";
    private static final String TODO_WRITE_TOOL = "todo_write";
    private static final String TASK_TOOL = "task";

    /** 进程入口:picocli 解析参数并执行,退出码透传给操作系统 */
    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    /**
     * 顶层命令(不带子命令)的默认行为:打印用法,提示用户用 run / repl。
     *
     * @return 退出码 0
     */
    @Override
    public Integer call() {
        CommandLine.usage(new Main(), System.out);
        return 0;
    }

    /**
     * 装配完整的 agent:环境配置 → 安全组件(路径围栏/命令策略/审批闸门)→
     * TodoManager 与七把工具 → hook 事件表(注册顺序即触发顺序)→ AgentLoop。
     *
     * <p>run 与 repl 两个子命令共用这份装配,保证任何入口行为一致。</p>
     *
     * @return 组装好的 AgentLoop,调用方拿它 run(messages)
     */
    public static AgentLoop buildLoop() {
        AppConfig config = AppConfig.fromEnv(Env.loadDotEnv());
        Path cwd = Path.of("").toAbsolutePath();
        PathResolver resolver = new PathResolver(cwd);
        PathGuard guard = new PathGuard(resolver);
        CommandPolicy policy = new CommandPolicy();
        PermissionGate gate = new PermissionGate(policy, guard);
        TodoManager manager = new TodoManager();

        Hooks hooks = new Hooks();

        // 子代理工具表(D1):五把基础工具——不含 task(只有一层委派)、不含 todo_write(聚焦子任务无需计划表)
        Map<String, Tool> subTools = Map.of(
                BASH_TOOL, new BashTool(),
                READ_FILE_TOOL, new ReadFileTool(resolver),
                WRITE_FILE_TOOL, new WriteFileTool(resolver),
                EDIT_FILE_TOOL, new EditFileTool(resolver),
                GLOB_TOOL, new GlobTool(resolver));

        AnthropicSdkLlmClient llm = new AnthropicSdkLlmClient(config); // 父子共用同一客户端实例
        SubagentRuntime subagentRuntime = new SubagentRuntime(llm, subTools, subSystemPrompt(cwd),
                config.maxTokens(), hooks);

        // 父工具表 = 子代理五把 + todo_write + task(超集;membershipHook 校验的是这张表)
        Map<String, Tool> tools = Map.of(
                BASH_TOOL, new BashTool(),
                READ_FILE_TOOL, new ReadFileTool(resolver),
                WRITE_FILE_TOOL, new WriteFileTool(resolver),
                EDIT_FILE_TOOL, new EditFileTool(resolver),
                GLOB_TOOL, new GlobTool(resolver),
                TODO_WRITE_TOOL, new TodoWriteTool(manager),
                TASK_TOOL, new SubagentTool(subagentRuntime));

        hooks.register(Hooks.USER_PROMPT_SUBMIT, promptContextHook(cwd));
        hooks.register(Hooks.PRE_TOOL_USE, membershipHook(tools));
        hooks.register(Hooks.PRE_TOOL_USE, permissionHook(gate));
        hooks.register(Hooks.PRE_TOOL_USE, logCallHook());
        hooks.register(Hooks.POST_TOOL_USE, largeOutputHook());
        hooks.register(Hooks.POST_TOOL_USE, todoStatusHook());
        hooks.register(Hooks.SUBAGENT_START, subagentStartHook());
        hooks.register(Hooks.SUBAGENT_TOOL, subagentToolHook());
        hooks.register(Hooks.SUBAGENT_END, subagentEndHook());
        hooks.register(Hooks.STOP, summaryHook());

        return new AgentLoop(llm,
                tools,
                systemPrompt(cwd),
                config.maxTokens(),
                hooks);
    }

    /* ----- 内置 hook 的实现 ----- */

    /** UserPromptSubmit:记录当前工作目录。返回值调用方忽略 */
    private static Hooks.Hook promptContextHook(Path cwd) {
        return params -> {
            System.out.println(Ansi.gray("[HOOK] UserPromptSubmit: working in " + cwd));
            return null;
        };
    }

    /** PreToolUse 查表:不在分发表中直接报错,不进权限闸门(注册必须在权限 hook 之前,否则顺序失效) */
    private static Hooks.Hook membershipHook(Map<String, Tool> tools) {
        return params -> {
            ToolUseBlock u = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            return tools.containsKey(u.name()) ? null : "Error: Unknown tool: " + u.name();
        };
    }

    /** PreToolUse 权限:s03 三道闸门整体搬进来,闸门类不动;带原因回灌(前缀随 hook 搬家) */
    private static Hooks.Hook permissionHook(PermissionGate gate) {
        return params -> {
            ToolUseBlock u = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            ObjectNode node = u.input() instanceof ObjectNode o ? o : Json.mapper().createObjectNode();
            String denied = gate.check(u.name(), node);
            return denied == null ? null : "Permission denied: " + denied;
        };
    }

    /** PreToolUse 日志:注册在权限之后 → 被拦的工具不出日志行 */
    private static Hooks.Hook logCallHook() {
        return params -> {
            ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            System.out.println(Ansi.gray("[HOOK] " + block.name()));
            return null;
        };
    }

    /** PostToolUse:大输出提醒 */
    private static Hooks.Hook largeOutputHook() {
        return params -> {
            ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            String output = (String) params.get(Hooks.PARAM_OUTPUT);
            if (output.length() > 100000) {
                System.out.println(Ansi.yellow("[HOOK] WARNING: Large output from " + block.name()));
            }
            return null;
        };
    }

    /**
     * PostToolUse 状态展示(s05 D2):todo_write 的输出就是渲染好的任务列表
     * (TodoManager.render 的文本),用黄色 "## Current Tasks" 标题打在终端上,
     * 让用户随时看得到模型的计划。工具本身保持纯净(execute 只返回字符串),
     * 终端展示全归 hook。
     */
    private static Hooks.Hook todoStatusHook() {
        return params -> {
            ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            String output = (String) params.get(Hooks.PARAM_OUTPUT);
            if (block.name().equals(TODO_WRITE_TOOL)) {
                System.out.println("\n" + Ansi.yellow("## Current Tasks\n" + output));
            }
            return null;
        };
    }

    /** SubagentStart:委派开始,洋红横幅(D2) */
    private static Hooks.Hook subagentStartHook() {
        return params -> {
            System.out.println(Ansi.magenta("[Subagent started]"));
            return null;
        };
    }

    /** SubagentTool:每条子工具调用后一行灰色摘要(D2);输出取前 100 字符 */
    private static Hooks.Hook subagentToolHook() {
        return params -> {
            ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
            String output = (String) params.get(Hooks.PARAM_OUTPUT);
            System.out.println(Ansi.gray("  [sub] " + block.name() + ": " + truncate(output, 100)));
            return null;
        };
    }

    /** SubagentEnd:委派结束,终态决定横幅文案(D2) */
    private static Hooks.Hook subagentEndHook() {
        return params -> {
            SubagentOutcome outcome = (SubagentOutcome) params.get(Hooks.PARAM_OUTCOME);
            String banner = switch (outcome) {
                case DONE -> "[Subagent done]";
                case STOPPED -> "[Subagent stopped]";
                case FAILED -> "[Subagent failed]";
            };
            System.out.println(Ansi.magenta(banner));
            return null;
        };
    }

    /** 取字符串前 max 个字符(子代理工具调用摘要用,课程同款 output[:100]) */
    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Stop:收尾统计(tool_result 计数),恒返回 null = 允许退出 */
    private static Hooks.Hook summaryHook() {
        return params -> {
            @SuppressWarnings("unchecked")
            List<Message> messages = (List<Message>) params.get(Hooks.PARAM_MESSAGES);
            long n = messages.stream().flatMap(m -> m.content().stream())
                    .filter(b -> b instanceof ToolResultBlock).count();
            System.out.println(Ansi.gray("[HOOK] Stop: session used " + n + " tool calls"));
            return null;
        };
    }

    /** 纯函数:拼出课程原文的 system prompt(参数化 cwd 是为了可测试) */
    static String systemPrompt(Path cwd) {
        return "You are Jade Code, a coding agent at " + cwd + ". Use bash to solve tasks. Act, don't explain."
                + " Use task for focused exploration or a self-contained subtask."
                + " Before starting any multi-step task, use todo_write to plan your steps. Update status as you go."
                + " All destructive operations require user approval.";

    }

    /** 纯函数:子代理的 system prompt(D3 课程原文,保留 s01 起的自称;参数化 cwd 是为了可测试) */
    static String subSystemPrompt(Path cwd) {
        return "You are Jade Code, a coding agent at " + cwd
                + ". Complete the given task, then return a concise final answer.";
    }
}
