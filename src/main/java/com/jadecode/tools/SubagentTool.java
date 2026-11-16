package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.llm.LlmException;
import com.jadecode.subagent.SubagentRuntime;

/**
 * task 工具：让一个子 Agent 用独立的消息列表完成子任务，把最终文字返回给父 Agent。
 *
 * <p>这里只检查参数，实际请求模型和执行工具在 SubagentRuntime。
 * 调用 task 的线程会等子 Agent 返回，不是后台执行。子 Agent 的中间消息不加入父 history，
 * 父 Agent 只收到这次 task 的结果；子工具表没有 task，因此不能再派第三层子 Agent。</p>
 *
 * <p>子 Agent 仍沿用父任务的来源和退出检查。定时任务不能因为派了子 Agent 就申请人工批准，
 * 用户退出后，也不能让子 Agent 刚收到的模型回复继续启动工具。</p>
 */
public class SubagentTool implements Tool {
    /** 负责运行子 Agent 的对象，通过构造器传进来。 */
    private final SubagentRuntime runtime;

    /**
     * 记住实际运行子 Agent 的对象，构造时不执行任务。
     * @param runtime 子 Agent 的模型循环
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
     * @return 给模型看的说明：子任务从独立消息列表开始，只返回最终文字
     */
    @Override
    public String description() {
        return "Run a subagent with fresh conversation context and return its final text.";
    }

    /**
     * @return 单参数 prompt(string)
     */

    private Map<String, String> properties() {
        return Map.of("prompt", "string");
    }

    /**
     * @return 必填 [prompt]
     */

    private List<String> required() {
        return List.of("prompt");
    }

    /**
     * 检查 prompt 是否缺失，然后等子 Agent 做完；不给它父消息列表或父任务队列。
     *
     * <p>context 由父程序另外传入，不从 input 读取。定时任务仍是 SCHEDULED，不能询问用户。
     * 子 Agent 也用相同的方法检查会话是否退出，免得模型回复较晚，退出后还去执行工具。</p>
     * @param input 非 null 参数对象，prompt 沿用 asText 转换方式；不修改输入
     * @param context 非 null 的父任务信息；保留真实用户目标、来源、审批通道和退出检查
     * @return 执行结果（content 保留原输出，isError 由本方法失败分支给定）：最终文字或轮数耗尽提示；缺参及 LlmException 返回 Error 说明，
     *         父执行器再包装为本次 task 的 tool_result。其它意外运行异常继续抛出
     * @throws NullPointerException context 为 null，且已调用子运行器
     */
    @Override
    public com.jadecode.tools.ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return new com.jadecode.tools.ToolOutcome("Error: Missing required parameter: " + key, true);
            }
        }

        String prompt = input.get("prompt").asText();
        try {
            // 子任务正文来自模型，只用于安排工作；审批必须保留真实用户目标，避免模型自造授权。
            return new com.jadecode.tools.ToolOutcome(runtime.run(prompt, context), false);
        } catch (LlmException e) {
            return new com.jadecode.tools.ToolOutcome("Error: " + e.getMessage(), true);
        }
    }

    /** 提供完整参数结构；标量构造器只负责组装 Schema，不授予执行权限。 */
    @Override
    public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
        return com.jadecode.tools.ToolSchema.objectSchema(properties(), required());
    }
}
