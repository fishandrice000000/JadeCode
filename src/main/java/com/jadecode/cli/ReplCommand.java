package com.jadecode.cli;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;

import com.jadecode.Main;
import com.jadecode.agent.AgentLoop;
import com.jadecode.agent.SessionRuntime;

import picocli.CommandLine.Command;

/**
 * REPL 是人工生产者和唯一终端输入所有者，不再直接启动推理或修改历史。
 *
 * <p>消费者执行期间仍可提交后续请求，它们 FIFO 排队；审批答复由 ConsoleInput 关联后
 * 直接交给等待的审批通道，不进入任务队列。q/EOF 是立即关闭会话，不等待队列排空。</p>
 */
@Command(name = "repl", description = "多轮会话:请求排队，行尾反斜杠续行，q/exit 或 Ctrl+D 退出")
public class ReplCommand implements Callable<Integer> {
    /** 装配并启动会话；输入失败退出码 1，单项任务失败由运行器报告而不杀掉 REPL。 */
    @Override
    public Integer call() {
        try (AgentLoop loop = Main.buildLoop();
                SessionRuntime runtime = new SessionRuntime(loop, System.out::println, System.err::println);
                ConsoleInput terminal = new ConsoleInput()) {
            runtime.start(true);
            System.out.println("JadeCode REPL — 输入任务回车提交并排队；q/exit 或 Ctrl+D 退出");
            while (true) {
                String query = terminal.readQuery();
                if (query == null) return 0;
                var result = runtime.submit(query, terminal);
                // 按失败类型区分拒收与执行失败，不能把“执行得很快但失败”误报成从未接收。
                result.whenComplete((answer, failure) -> {
                    if (failure instanceof RejectedExecutionException) System.err.println("[Rejected] " + failure.getMessage());
                });
            }
        } catch (IOException failure) {
            System.err.println("读取输入失败: " + failure.getMessage());
            return 1;
        }
    }
}
