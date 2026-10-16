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
    @Parameters(paramLabel = "PROMPT", description = "任务描述(含空格请加引号)")
    private String prompt;

    /** 完成返回 0；执行、输入或中断失败返回 1，关闭时清理所有会话命令。 */
    @Override
    public Integer call() {
        // 输入拒绝发生在装配之前：不为无效任务加载配置，更不能把执行中的同类异常误报成拒收。
        if (prompt == null || prompt.isBlank()) {
            System.err.println("任务描述不能为空");
            return 1;
        }
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
