package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.llm.LlmException;
import com.jadecode.subagent.SubagentRuntime;

/**
 * task:委派子代理——用全新对话上下文跑一个子任务,只回传它的最终文本。
 *
 * <p>
 * 薄壳:参数校验在此,嵌套循环与消息隔离全在 {@link SubagentRuntime}。
 * 子代理工具表不含本工具,故只有一层委派。
 * </p>
 */
public class SubagentTool implements Tool {
    /** 子代理运行时:构造器注入(仿 GlobTool 注入 PathResolver) */
    private final SubagentRuntime runtime;

    /**
     * @param runtime 子代理运行时
     */
    public SubagentTool(SubagentRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * @return 工具名 "task"
     */
    @Override
    public String name() {
        return "task";
    }

    /**
     * @return 给模型的说明:全新上下文跑子任务,只返回最终文本
     */
    @Override
    public String description() {
        return "Run a subagent with fresh conversation context and return its final text.";
    }

    /**
     * @return 单参数 prompt(string)
     */
    @Override
    public Map<String, String> properties() {
        return Map.of("prompt", "string");
    }

    /**
     * @return 必填 [prompt]
     */
    @Override
    public List<String> required() {
        return List.of("prompt");
    }

    /**
     * 缺参检查后透传 {@link SubagentRuntime#run(String)}。
     *
     * @param input 模型参数,须含 prompt
     * @return 子代理最终文本;缺参回 "Error: Missing required parameter: prompt"
     */
    @Override
    public String execute(ObjectNode input) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return "Error: Missing required parameter: " + key;
            }
        }

        String prompt = input.get("prompt").asText();
        try {
            return runtime.run(prompt);
        } catch (LlmException e) {
            return "Error: " + e.getMessage();
        }
    }
}
