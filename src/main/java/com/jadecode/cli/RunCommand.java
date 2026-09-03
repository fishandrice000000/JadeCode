package com.jadecode.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.jadecode.Main;
import com.jadecode.agent.AgentLoop;
import com.jadecode.llm.LlmException;
import com.jadecode.messages.Message;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "run", description = "执行一次性任务:给出一条指令,跑完打印最终回复")
public class RunCommand implements Callable<Integer> {
    @Parameters(paramLabel = "PROMPT", description = "任务描述(含空格请加引号)")
    private String prompt;

    @Override
    public Integer call() {
        // 1. AgentLoop loop = Main.buildLoop()
        AgentLoop loop = Main.buildLoop();

        // 2. history = new ArrayList<>();history.add(Message.userText(prompt))
        List<Message> history = new ArrayList<>();
        history.add(Message.userText(prompt));
        try {
            // 3. loop.run(history)
            loop.run(history);

            // 4. 打印 history 最后一条消息的 extractText()
            System.out.println(history.getLast().extractText());
            return 0;
        } catch (LlmException e) {
            // 5. catch LlmException → System.err 打印错误,返回 1;正常返回 0
            System.err.println(e.getMessage());
            return 1;
        }
    }
}
