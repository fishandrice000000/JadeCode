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

@Command(name = "run", description = "执行一次性任务:给出一条指令,跑完打印最终回复")
public class RunCommand implements Callable<Integer> {
    @Parameters(paramLabel = "PROMPT", description = "任务描述(含空格请加引号)")
    private String prompt;

    @Override
    public Integer call() {
        AgentLoop loop = Main.buildLoop();

        List<Message> history = new ArrayList<>();

        loop.hooks().trigger(Hooks.USER_PROMPT_SUBMIT, Map.of(Hooks.PARAM_QUERY, prompt));
        history.add(Message.userText(prompt));
        try {
            loop.run(history);

            System.out.println(history.getLast().extractText());
            return 0;
        } catch (LlmException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }
}
