package com.jadecode.llm;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * ScriptedLlmClient 脚本假客户端
 *
 * 测试期替代真实 LLM:构造时收一个响应脚本队列,complete 逐次弹出。
 * 脚本耗尽时抛 LlmException——测试失败得响亮,而不是 NPE 或死循环。
 */
public class ScriptedLlmClient implements LlmClient {
    private final Deque<ModelResponse> script = new ArrayDeque<>();

    public ScriptedLlmClient(ModelResponse... responses) {
        script.addAll(Arrays.asList(responses));
    }

    @Override
    public ModelResponse complete(CompleteRequest request) throws LlmException {
        if (script.isEmpty()) {
            throw new LlmException("ScriptedLlmClient 脚本耗尽:预置响应已用完");
        }
        return script.poll();
    }
}
