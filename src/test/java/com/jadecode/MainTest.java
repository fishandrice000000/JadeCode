package com.jadecode;

import java.nio.file.Path;

import com.jadecode.cli.RunCommand;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    /** ① --version:打印版本即返回,不触发任何任务执行,退出码 0 */
    @Test
    void versionOptionExitsWithoutExecuting() {
        assertEquals(0, new CommandLine(new Main()).execute("--version"));
    }

    /** ② --help:打印用法即返回,退出码 0 */
    @Test
    void helpOptionExitsWithoutExecuting() {
        assertEquals(0, new CommandLine(new Main()).execute("--help"));
    }

    /** ③ run 子命令:解析出 RunCommand,位置参数捕获 prompt(parseArgs 只解析不执行) */
    @Test
    void runSubcommandCapturesPrompt() {
        ParseResult result = new CommandLine(new Main()).parseArgs("run", "hello world");

        assertTrue(result.hasSubcommand());
        ParseResult sub = result.subcommand();
        assertInstanceOf(RunCommand.class, sub.commandSpec().userObject());
        assertEquals("hello world", sub.matchedPositional(0).getValue());
    }

    /** ④ systemPrompt:课程原文 + cwd 拼接 */
    @Test
    void systemPromptIncludesCwd() {
        assertEquals(
                "You are a coding agent at /home/fishandrice. Use bash to solve tasks. Act, don't explain.",
                Main.systemPrompt(Path.of("/home/fishandrice")));
    }

    /** ⑤ 空参数:只有 Main 自己,无子命令 */
    @Test
    void emptyArgsHasNoSubcommand() {
        ParseResult result = new CommandLine(new Main()).parseArgs();

        assertFalse(result.hasSubcommand());
        assertInstanceOf(Main.class, result.commandSpec().userObject());
    }
}
