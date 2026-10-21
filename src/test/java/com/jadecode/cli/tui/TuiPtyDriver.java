package com.jadecode.cli.tui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.hooks.Hooks;
import com.jadecode.llm.ModelResponse;
import com.jadecode.llm.ScriptedLlmClient;
import com.jadecode.messages.Message;
import com.jadecode.messages.TextBlock;
import com.jadecode.session.SessionRuntime;

/**
 * PTY 冒烟测试的独立子进程入口：用脚本模型启动真实 TuiApplication，不读取真实配置/凭据。
 * 仅测试使用；普通 Maven 构建不会把它打进生产 jar。
 */
public final class TuiPtyDriver {
    public static void main(String[] args) throws Exception {
        Path preferences = Path.of(args[0]);
        Files.createDirectories(preferences.getParent());
        if (!Files.exists(preferences)) Files.writeString(preferences, "{\"version\":1,\"tui\":{\"theme\":\"dark\"}}");
        var response = new ModelResponse(Message.assistant(List.of(
                new TextBlock("PTY reply\n\n- alpha\n- beta"))), new ModelResponse.Usage(5, 7), "end_turn");
        int code = new TuiApplication().run(wiring -> {
            var engine = new AgentExecutor(new ScriptedLlmClient(response), Map.of(), "pty-test", 1000, new Hooks());
            return new SessionRuntime(engine, wiring.output(), wiring.errors());
        }, preferences, null);
        System.exit(code);
    }
}
