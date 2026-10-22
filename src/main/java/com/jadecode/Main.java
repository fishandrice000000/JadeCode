package com.jadecode;

import java.util.concurrent.Callable;

import com.jadecode.cli.ReplCommand;
import com.jadecode.cli.RunCommand;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** JadeCode 的 picocli 顶层入口；run/repl 自行从 bootstrap 取得会话。 */
@Command(name = "jadecode", version = "jadecode 0.1.0-SNAPSHOT", mixinStandardHelpOptions = true,
        subcommands = { RunCommand.class, ReplCommand.class }, description = "JadeCode: a CLI coding agent for learning")
public class Main implements Callable<Integer> {
    /**
     * 让 picocli 解析命令行并执行，最后把返回码交给操作系统。
     * @param args 命令行参数，例如 repl 或 run 加任务文字
     */
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

}
