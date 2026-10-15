package com.jadecode.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.jadecode.Main;
import com.jadecode.agent.AgentLoop;
import com.jadecode.llm.LlmException;
import com.jadecode.messages.Message;
import com.jadecode.safety.Hooks;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * run 子命令:执行一次性任务——给一条指令,跑完打印最终回复。
 *
 * <p>
 * 与 repl 共用 {@link Main#buildLoop()} 装配,差别只在:单条 prompt、无循环、
 * 模型调用失败直接返回退出码 1(不重试、不续会话)。
 * </p>
 */
@Command(name = "run", description = "执行一次性任务:给出一条指令,跑完打印最终回复")
public class RunCommand implements Callable<Integer> {
    /** 位置参数:任务描述(picocli 注入;含空格需加引号) */
    @Parameters(paramLabel = "PROMPT", description = "任务描述(含空格请加引号)")
    private String prompt;

    /**
     * 跑一条任务:装配 agent → 触发 UserPromptSubmit → 追加 user 消息 → 跑循环 → 打印最终回复。
     *
     * @return 退出码:0 成功;1 模型调用失败(错误信息打到 stderr)
     */
    @Override
    public Integer call() {
        // 一次性 CLI 的生命周期就是这次 call；成功或异常退出都终止尚未完成的后台命令。
        try (AgentLoop loop = Main.buildLoop()) {
            List<Message> history = new ArrayList<>();
            loop.hooks().trigger(Hooks.USER_PROMPT_SUBMIT, Map.of(Hooks.PARAM_QUERY, prompt));
            history.add(Message.userText(prompt));
            loop.run(history);

            System.out.println(history.getLast().extractText());
            return 0;
        } catch (LlmException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }
}
