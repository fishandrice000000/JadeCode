package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.agent.ExecutionContext;
import com.jadecode.llm.LlmException;
import com.jadecode.subagent.SubagentRuntime;

/**
 * task:委派子代理——用全新对话上下文跑一个子任务,只回传它的最终文本。
 *
 * <p>
 * 薄壳:参数校验在此,嵌套循环与消息隔离全在 {@link SubagentRuntime}。
 * 子代理工具表不含本工具,故只有一层委派。
 * 委派在当前执行线程同步运行，父任务等待返回；子历史不进入父历史，只有最终文本作为
 * 这次 task 调用的结果。执行身份另行继承，以保证来源审批和会话关闭不因委派而丢失。
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
     * 为无上下文的旧调用方提供兼容入口，创建无人工审批通道的独立 USER 身份。
     * 生产调用应使用双参数重载，以继承真实来源和父会话关闭信号。
     *
     * @param input 模型参数,须含 prompt
     * @return 子代理最终文本、轮数耗尽提示或 Error 文本；不直接修改父历史
     * @see #execute(ObjectNode, ExecutionContext)
     */
    @Override
    public String execute(ObjectNode input) {
        return execute(input, ExecutionContext.create(ExecutionContext.Source.USER, null));
    }

    /**
     * 校验委派参数后同步运行子代理，传递可信身份，但不传父历史或父收件箱。
     *
     * <p>context 来自父执行器，不从 input 恢复。定时任务委派后仍是 SCHEDULED，不能
     * 利用子代理申请人工批准；会话关闭视图也沿用，防止子模型的晚到回复启动新工具。</p>
     * @param input 非 null 参数对象；检查 prompt 是否缺失，沿用已有 asText 转换行为，不修改输入
     * @param context 非 null 的父执行身份，同步传给 SubagentRuntime
     * @return 子代理最终文本或轮数上限提示；缺参、LlmException 转为 Error 文本，
     *         由父执行器包装为原 task 调用的 tool_result。其它意外运行异常不在此伪装为成功
     * @throws NullPointerException context 为 null 且已经进入子运行器
     */
    @Override
    public String execute(ObjectNode input, ExecutionContext context) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return "Error: Missing required parameter: " + key;
            }
        }

        String prompt = input.get("prompt").asText();
        try {
            return runtime.run(prompt, context);
        } catch (LlmException e) {
            return "Error: " + e.getMessage();
        }
    }
}
