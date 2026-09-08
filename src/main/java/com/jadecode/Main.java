package com.jadecode;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

import com.jadecode.agent.AgentLoop;
import com.jadecode.cli.ReplCommand;
import com.jadecode.cli.RunCommand;
import com.jadecode.config.AppConfig;
import com.jadecode.config.Env;
import com.jadecode.llm.AnthropicSdkLlmClient;
import com.jadecode.safety.CommandPolicy;
import com.jadecode.safety.PathGuard;
import com.jadecode.safety.PathResolver;
import com.jadecode.safety.PermissionGate;
import com.jadecode.tools.BashTool;
import com.jadecode.tools.EditFileTool;
import com.jadecode.tools.GlobTool;
import com.jadecode.tools.ReadFileTool;
import com.jadecode.tools.WriteFileTool;

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
        PermissionGate gate = new PermissionGate(policy, guard); // 装配路径:默认审批(控制台 y/N)
        return new AgentLoop(new AnthropicSdkLlmClient(config),
                Map.of("bash", new BashTool(),
                        "read_file", new ReadFileTool(resolver),
                        "write_file", new WriteFileTool(resolver),
                        "edit_file", new EditFileTool(resolver),
                        "glob", new GlobTool(resolver)),
                systemPrompt(cwd), // 启动时取一次 cwd
                config.maxTokens(),
                gate);
    }

    /** 纯函数:拼出课程原文的 system prompt(参数化 cwd 是为了可测试) */
    static String systemPrompt(Path cwd) {
        return "You are a coding agent at " + cwd + ". Use bash to solve tasks. Act, don't explain."
                + " All destructive operations require user approval.";
    }
}
