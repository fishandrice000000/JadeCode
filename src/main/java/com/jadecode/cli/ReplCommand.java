package com.jadecode.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.jadecode.Main;
import com.jadecode.agent.AgentLoop;
import com.jadecode.llm.LlmException;
import com.jadecode.messages.Message;

import picocli.CommandLine.Command;

@Command(name = "repl", description = "进入交互循环:多轮对话,输入 q 或 Ctrl+D 退出")
public class ReplCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        // 1. 装配
        AgentLoop loop = Main.buildLoop();

        // 2. 输入流:UTF-8 逐行读
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        // 3. history 必须在循环外——多轮对话的连续性全靠它跨迭代存活
        List<Message> history = new ArrayList<>();

        System.out.println("JadeCode REPL — 输入任务回车发送,q/exit 或 Ctrl+D 退出");
        while (true) {
            System.out.print("s01 >> ");
            System.out.flush();

            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                // 输入流坏了:打印后退出,返回 1
                System.err.println("读取输入失败: " + e.getMessage());
                return 1;
            }
            if (line == null) {
                break; // EOF(Ctrl+D)
            }
            String query = line.strip();
            if (query.isEmpty() || query.equalsIgnoreCase("q") || query.equalsIgnoreCase("exit")) {
                break;
            }

            history.add(Message.userText(query));
            try {
                loop.run(history);
                System.out.println(history.getLast().extractText());
            } catch (LlmException e) {
                System.err.println("调用模型失败: " + e.getMessage());
                // 不退出循环:交互会话要韧,一次失败不杀整个会话
            }
        }
        return 0;
    }
}
