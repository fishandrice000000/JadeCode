package com.jadecode.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

import com.jadecode.Main;
import com.jadecode.agent.AgentLoop;
import com.jadecode.agent.CronRuntime;
import com.jadecode.llm.LlmException;
import com.jadecode.util.Ansi;

import picocli.CommandLine.Command;

/**
 * repl 子命令:交互式多轮对话。
 *
 * <p>
 * 循环:收一条 query → 触发 UserPromptSubmit → 追加 user 消息 → 跑 AgentLoop →
 * 打印最终回复。CronRuntime 持有跨迭代 history，并串行接收人工请求与到期 prompt；
 * 等待终端输入在回合锁外，定时任务能在此时自动开始。
 * 调用模型失败只报错不退出循环——交互会话要韧,一次失败不杀整个会话。
 * </p>
 */
@Command(name = "repl", description = "进入交互循环:多轮对话,行尾 \\ 续行,q/exit 或 Ctrl+D 退出")
public class ReplCommand implements Callable<Integer> {
    /**
     * REPL 主循环:每轮收一条任务、跑一次 agent、打印回复,直到退出词或 EOF。
     *
     * @return 退出码:0 正常退出;1 读取输入失败(输入流异常)
     */
    @Override
    public Integer call() {
        // 1. 装配
        // 管理器跨用户轮次存活，只在 q/EOF/异常离开整个 REPL 时关闭；不能每轮提前取消。
        try (AgentLoop loop = Main.buildLoop();
                CronRuntime runtime = new CronRuntime(loop, loop.cronScheduler(), System.out::println, System.err::println)) {

            // 2. 输入流:UTF-8 逐行读
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));

            // 3. Runtime 持有跨用户轮次的 history 和回合锁，计时器只入队、消费者在空闲时调用模型。
            // readLine 保持在锁外，等用户输入不会阻止定时任务发起，也不会与定时回合并发修改历史。
            runtime.start();

            System.out.println("JadeCode REPL — 输入任务回车发送;行尾 \\ 可跨行续写;q/exit 或 Ctrl+D 退出");
            while (true) {
                String query;
                try {
                    query = collectQuery(reader); // null = 退出(EOF 或退出词)
                } catch (IOException e) {
                    // 输入流坏了:打印后退出,try-with-resources 仍关闭后台命令
                    System.err.println("读取输入失败: " + e.getMessage());
                    return 1;
                }
                if (query == null) {
                    break; // EOF(Ctrl+D)或退出词
                }

                try {
                    runtime.runUser(query);
                } catch (LlmException e) {
                    System.err.println("调用模型失败: " + e.getMessage());
                    // 不退出循环，也不取消后台任务：后续用户输入仍能收集完成事件
                }
            }
            return 0;
        }
    }

    /** 收集一条 query:行尾恰好一个反斜杠 → 剥掉它、软换行继续收(Claude Code 同款交互);
     * 两个反斜杠结尾是字面量,不续行。续行中途 EOF 时把已收集的按现状提交 */
    private static String collectQuery(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        System.out.print(Ansi.cyan("JadeCode >> "));
        System.out.flush();
        String line = reader.readLine();
        while (line != null && endsWithSoftBreak(line)) {
            sb.append(line, 0, line.length() - 1).append('\n'); // 续行符本身不进任务文本
            System.out.print(Ansi.gray("> ")); // rlwrap 会把无换行的输出识别为新提示符
            System.out.flush();
            line = reader.readLine();
        }
        if (line != null) {
            sb.append(line);
        }
        return normalize(sb);
    }

    /** 行尾恰好一个反斜杠才续行(源码里 \\ 即字面双反斜杠,不续) */
    private static boolean endsWithSoftBreak(String line) {
        return line.endsWith("\\") && !line.endsWith("\\\\");
    }

    /** strip 后判退出词:空行/q/exit → null(退出);否则返回整条任务文本(可含软换行) */
    private static String normalize(StringBuilder sb) {
        String query = sb.toString().strip();
        return query.isEmpty() || query.equalsIgnoreCase("q") || query.equalsIgnoreCase("exit") ? null : query;
    }
}
