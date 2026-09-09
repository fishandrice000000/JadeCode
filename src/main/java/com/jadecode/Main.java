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
import com.jadecode.tools.BashTool;
import com.jadecode.tools.EditFileTool;
import com.jadecode.tools.GlobTool;
import com.jadecode.tools.ReadFileTool;
import com.jadecode.tools.Tool;
import com.jadecode.tools.WriteFileTool;
import com.jadecode.util.Ansi;
import com.jadecode.util.Json;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "jadecode", version = "jadecode 0.1.0-SNAPSHOT", mixinStandardHelpOptions = true,

        subcommands = { RunCommand.class, ReplCommand.class }, description = "JadeCode:学习用 CLI coding agent")
public class Main implements Callable<Integer> {
    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() {
        CommandLine.usage(new Main(), System.out); // 无子命令:打印用法,返回 0
        return 0;
    }

    /** 装配:唯一一处把全部零件接起来的地方 */
    public static AgentLoop buildLoop() {
        AppConfig config = AppConfig.fromEnv(Env.loadDotEnv());
        Path cwd = Path.of("").toAbsolutePath();
        PathResolver resolver = new PathResolver(cwd); // 四个文件工具共享同一解析器
        PathGuard guard = new PathGuard(resolver); // 分类器:高危硬拒 + 越界询问,给闸门
        CommandPolicy policy = new CommandPolicy();
        PermissionGate gate = new PermissionGate(policy, guard);
        Map<String, Tool> tools = Map.of(
                "bash", new BashTool(),
                "read_file", new ReadFileTool(resolver),
                "write_file", new WriteFileTool(resolver),
                "edit_file", new EditFileTool(resolver),
                "glob", new GlobTool(resolver));
        Hooks hooks = new Hooks();

        hooks.register(Hooks.USER_PROMPT_SUBMIT, promptContextHook(cwd));
        hooks.register(Hooks.PRE_TOOL_USE, membershipHook(tools));
        hooks.register(Hooks.PRE_TOOL_USE, permissionHook(gate));
        hooks.register(Hooks.PRE_TOOL_USE, logCallHook());
        hooks.register(Hooks.POST_TOOL_USE, largeOutputHook());
        hooks.register(Hooks.STOP, summaryHook());

        return new AgentLoop(new AnthropicSdkLlmClient(config),
                tools,
                systemPrompt(cwd), // 启动时取一次 cwd
                config.maxTokens(),
                hooks);
    }

    /* ----- 内置 hook 的实现 ----- */

    // UserPromptSubmit:记录当前工作目录。返回值调用方忽略
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
        return "You are a coding agent at " + cwd + ". Use bash to solve tasks. Act, don't explain."
                + " All destructive operations require user approval.";
    }
}
