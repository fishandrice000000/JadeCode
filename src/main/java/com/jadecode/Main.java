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
import com.jadecode.tools.BashTool;

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
        return new AgentLoop(new AnthropicSdkLlmClient(config),
                Map.of("bash", new BashTool()),
                systemPrompt(Path.of("").toAbsolutePath()), // 启动时取一次 cwd
                config.maxTokens());
    }

    /** 纯函数:拼出课程原文的 system prompt(参数化 cwd 是为了可测试) */
    static String systemPrompt(Path cwd) {
        return "You are a coding agent at " + cwd + ". Use bash to solve tasks. Act, don't explain.";
    }
}
