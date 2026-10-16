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
 * 单次执行也使用同一运行器，包括必等后台结果恢复，但不启动 cron 生产者。
 * 主线程只在需要审批时读取终端，不收第二项任务；任务结束后关闭整个会话。
 */
@Command(name = "run", description = "执行一次任务并退出")
public class RunCommand implements Callable<Integer> {
    /** picocli 提供的一项人工目标，不被当作可同时执行的多条队列请求。 */
    @Parameters(paramLabel = "PROMPT", description = "任务描述(含空格请加引号)")
    private String prompt;

    /**
     * 用同一会话运行器执行一个人工目标，处理完必等后台结果后退出，不启动 cron 生产。
     *
     * <p>UI 线程通过 ConsoleInput.await 只处理必要审批，不竞争读取新目标。运行器输出
     * 最终答复，此处只等完成 future。关闭资源会终止所有会话命令，所以 detached 并不
     * 意味着单次 run 退出后仍作为守护进程继续运行。</p>
     * @return 任务执行链完成为 0；空目标、拒收、执行/输入/取消/中断失败为 1。
     *         0 不保证模型给出的业务结论正确，非零命令结果也可能被模型正常处理后完成任务
     */
    @Override
    public Integer call() {
        // 输入拒绝发生在装配之前：不为无效任务加载配置，更不能把执行中的同类异常误报成拒收。
        if (prompt == null || prompt.isBlank()) {
            System.err.println("任务描述不能为空");
            return 1;
        }
        // 逆序清理先关闭审批通道，再关闭会话及 shell，最后幂等关闭执行器。
        try (AgentLoop loop = Main.buildLoop();
                SessionRuntime runtime = new SessionRuntime(loop, System.out::println, System.err::println);
                ConsoleInput terminal = new ConsoleInput()) {
            runtime.start(false);
            terminal.await(runtime.submit(prompt, terminal));
            return 0;
        } catch (ExecutionException failure) {
            // 运行器已经报告任务失败，不重复打印整份堆栈或把启动回执当作成功。
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
