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
    /**
     * 启动交互会话：UI 线程持续生产人工请求，运行器独立串行消费，并启用定时生产者。
     *
     * <p>submit 不等待任务完成，因此用户可以在当前任务运行/等后台时继续排队。
     * 审批控制输入由 ConsoleInput 消费，不进入任务队列。正常任务失败由运行器输出，
     * 这里只报告收件箱拒收，避免把“任务已执行但失败”误报为“未接收”。</p>
     * @return q/exit/EOF 正常退出为 0，输入 IO 失败为 1；不是每项排队任务的业务成功证明
     */
    @Override
    public Integer call() {
        // try-with-resources 按声明逆序关闭：先拒绝待答审批，再停会话，最后幂等关闭执行器。
        try (AgentLoop loop = Main.buildLoop();
                SessionRuntime runtime = new SessionRuntime(loop, System.out::println, System.err::println);
                ConsoleInput terminal = new ConsoleInput()) {
            runtime.start(true);
            System.out.println("JadeCode REPL — 输入任务回车提交并排队；q/exit 或 Ctrl+D 退出");
            while (true) {
                String query = terminal.readQuery();
                // 退出属于即时 UI 控制，不能排在某个长时间等待的业务目标后面。
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
