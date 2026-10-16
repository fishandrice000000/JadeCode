package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.agent.ExecutionContext;

/**
 * 工具接口:模型可调用的函数。每个实现类注册进 {@code Map<String, Tool>} 分发表后,
 * AgentLoop 收到 model 的 tool_use 块按 name 查表、串行执行。
 *
 * <p>实现类契约:</p>
 * <ul>
 *   <li>预期的参数/执行错误返回以 "Error: " 开头的字符串,文案回灌给模型自纠；
 *       意外运行异常不是普通工具失败，父执行器会闭合批次后终止当前任务，不能假装成功;</li>
 *   <li>name 与模型 tool_use 块的 name 字段精确对应,查表靠它;</li>
 *   <li>properties/required 声明的参数须与 execute 实际读取的一致(缺参检查靠 required)。</li>
 * </ul>
 */
public interface Tool {
    /**
     * @return 工具名,如 "bash";模型 tool_use 块的 name 字段用它匹配
     */
    String name();

    /**
     * @return 给模型看的说明:何时用、怎么用、返回什么
     */
    String description();

    /**
     * @return 参数表:参数名 → JSON Schema 类型名("string" / "integer" / ...);
     *         单层标量表表达不了数组/嵌套时,返回空表并 override {@link #inputSchema()}
     */
    Map<String, String> properties();

    /**
     * @return 必填参数名列表,execute 据此检查缺失并回 "Error: Missing required parameter: ..."
     */
    List<String> required();

    /**
     * 参数结构的完整 JSON Schema,随工具定义序列化后发给模型。
     *
     * <p>默认实现把平铺的 properties/required 合成 {"type":"object",...},只支持单层
     * 标量;需要数组、嵌套对象或枚举的工具必须 override 手拼完整 schema
     * (例:todo_write 的 todos 数组,见 {@link TodoWriteTool})。</p>
     *
     * @return JSON Schema 根节点
     */
    default ObjectNode inputSchema() {
        return ToolSchema.objectSchema(properties(), required());
    }

    /**
     * 执行一次工具调用。
     *
     * @param input 模型按 schema 传来的参数对象(可能缺参、类型不符,实现自己兜)
     * @return 成功:结果文本,作为 tool_result 原样回灌给模型;
     *         预期失败:"Error: ..." 开头的文案，不以异常替代模型可处理的普通错误
     */
    String execute(ObjectNode input);

    /**
     * 带 Harness 身份的执行入口，由父/子执行器在通过 PreToolUse 之后同步调用。
     *
     * <p>只有后台 bash 和子代理委派等需要关联执行身份的工具覆盖本方法。context 不进入
     * 工具 schema，不允许模型在 input 中声明自己是人工任务以绕过审批。</p>
     *
     * <p>默认实现只委托单参数 execute，不额外执行权限检查、开线程或写历史。上下文是
     * Harness 的旁路元数据，不是模型 content 的一部分；结果仍由执行器绑定原 tool_use_id。
     * 普通工具无需为这次事件化重写实现。预期工具错误遵循 Error 文本契约；意外运行异常
     * 不是成功结果，父执行器会补齐批次并报告任务失败。</p>
     * @param input 模型提交的非 null 参数对象，具体字段合法性由实现检查
     * @param context Harness 提供的真实来源、执行 ID、审批通道与关闭视图；需要这些信息的
     *                实现自行约束 null，默认实现不读取它
     * @return 原工具结果，不因为传递身份而改变 tool_result 协议；本方法不自己回灌历史
     */
    default String execute(ObjectNode input, ExecutionContext context) {
        return execute(input);
    }
}
