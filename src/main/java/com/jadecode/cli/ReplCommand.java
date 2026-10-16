package com.jadecode.cli;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;

import com.jadecode.Main;
import com.jadecode.agent.AgentLoop;
import com.jadecode.agent.SessionRuntime;

import picocli.CommandLine.Command;

/**
 * 反复读用户输入，把新任务交给 SessionRuntime 排队，不在这里请求模型或改 history。
 *
 * <p>模型做任务时，用户仍可输入下一项任务。有操作等待批准时，y/n 由 ConsoleInput 处理，
 * 不会排成新任务。q/exit 或输入结束就退出整个会话，不等剩余排队任务执行。</p>
 */
@Command(name = "repl", description = "多轮会话:Enter 提交，Alt+Enter 换行，多行粘贴，q/exit 或 Ctrl+D 退出")
public class ReplCommand implements Callable<Integer> {
    /**
     * 创建会话和终端读取器，启动任务处理及定时检查，然后不断读用户输入。
     *
     * <p>submit 立即返回，所以循环可以继续读下一项任务。运行中出错由 SessionRuntime 显示；
     * 这里只额外显示“队列已满或退出，没能排进去”，免得同一个错误显示两遍。</p>
     * @return 正常退出为 0，读取输入出错为 1；不是每项任务的业务成功结论
     */
    @Override
    public Integer call() {
        // 先创建终端，连初始化日志也能正确显示；退出时最后才关闭它，让任务清理日志仍可输出。
        try (TerminalDisplay display = TerminalDisplay.open();
                AgentLoop loop = Main.buildLoop();
                SessionRuntime runtime = new SessionRuntime(loop, System.out::println, System.err::println);
                ConsoleInput terminal = new ConsoleInput(display)) {
            runtime.start(true);
            System.out.println("JadeCode REPL — Enter 提交，Alt+Enter 换行，支持多行粘贴；q/exit 或 Ctrl+D 退出");
            while (true) {
                String query = terminal.readQuery();
                // 用户要退出就立即返回，不能把退出动作排在当前任务后面。
                if (query == null) return 0;
                var result = runtime.submit(query, terminal);
                // 只额外显示排队失败；任务虽然很快出错，也不能误报为根本没排进去。
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
