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
 * R3-01 真实装配：regular 模式下脚本回复 80 行（超过 80x24 三屏），用于验证 Application
 * 交给 renderer 的是完整文档而不是首屏裁剪。测试专用，不进生产 jar。
 */
public final class TuiRegularPtyDriver {
    public static void main(String[] args) throws Exception {
        Path preferences = Path.of(args[0]);
        Files.createDirectories(preferences.getParent());
        Files.writeString(preferences, "{\"version\":1,\"tui\":{\"theme\":\"dark\",\"mode\":\"regular\"}}");
        var body = new StringBuilder();
        for (int i = 0; i < 80; i++) body.append("line-").append(i).append("\n\n");
        var response = new ModelResponse(Message.assistant(List.of(new TextBlock(body.toString()))),
                new ModelResponse.Usage(5, 200), "end_turn");
        int code = new TuiApplication().run(wiring -> {
            var engine = new AgentExecutor(new ScriptedLlmClient(response), Map.of(), "regular-pty", 1000, new Hooks());
            return new SessionRuntime(engine, wiring.output(), wiring.errors());
        }, preferences, null);
        System.exit(code);
    }
}
