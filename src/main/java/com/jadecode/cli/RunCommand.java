package com.jadecode.cli;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import com.jadecode.Main;
import com.jadecode.agent.AgentLoop;
import com.jadecode.agent.SessionRuntime;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * 执行一次任务后退出，使用和 REPL 相同的 SessionRuntime，但不开定时检查。
 *
 * <p>主线程只在操作需要用户同意时读终端，不接收第二项任务。
 * 当前任务必须等待的后台结果仍要处理完；结束后关闭会话，所有剩余命令也会停止。</p>
 */
@Command(name = "run", description = "执行一次任务并退出")
public class RunCommand implements Callable<Integer> {
    /** picocli 解析到的任务文字，一次只执行这一项。 */
    @Parameters(paramLabel = "PROMPT", description = "任务描述(含空格请加引号)")
    private String prompt;

    /**
     * 先检查任务文字，再创建会话、提交任务并等最终结果，不等未来的定时任务。
     *
     * <p>ConsoleInput.await 负责必要的批准/拒绝，SessionRuntime 负责显示最终回答。
     * 方法返回时会关闭资源，所以设置“不必等它”的命令也不能在 CLI 退出后继续运行。</p>
     * @return 任务正常走到结束为 0；请求为空、排队失败、执行失败、输入失败、中断或取消为 1。
     *         0 不代表模型业务判断一定正确；命令失败若被模型正常处理，也可能以 0 结束
     */
    @Override
    public Integer call() {
        // 没有任务就直接报错，不先读配置；执行中出的参数错误不能在这里当成空请求处理。
        if (prompt == null || prompt.isBlank()) {
            System.err.println("任务描述不能为空");
            return 1;
        }
        // 返回时反序关闭：拒绝没回答的申请，停止会话和命令，最后再次关闭 AgentLoop。
        try (AgentLoop loop = Main.buildLoop();
                SessionRuntime runtime = new SessionRuntime(loop, System.out::println, System.err::println);
                ConsoleInput terminal = new ConsoleInput()) {
            runtime.start(false);
            terminal.await(runtime.submit(prompt, terminal));
            return 0;
        } catch (ExecutionException failure) {
            // 运行器已经显示执行错误，不重复打印；拿到后台 ID 本身也不能当成任务成功。
            if (failure.getCause() instanceof java.util.concurrent.RejectedExecutionException) {
                System.err.println("请求未被接收: " + failure.getCause().getMessage());
            }
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (IOException | IllegalStateException failure) {
            System.err.println("执行未完成: " + failure.getMessage());
            return 1;
        }
    }
}
