package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.llm.ToolDefinition;

/**
 * 模型可以调用的工具。AgentExecutor 按 tool_use 中的 name 找到实现，再依次调用 execute。
 *
 * <p>每个工具要告诉模型自己的名字、用途、参数和必填字段，实际读取的参数要和说明一致。
 * 普通参数错误或可预期的执行失败返回 Error 文字，让模型决定是否换方法。
 * 如果实现意外抛运行异常，父执行器会补上这一批缺失的结果说明，再结束任务，
 * 不能当作工具已经成功。</p>
 */
public interface Tool {
    /**
     * 生成本次模型请求所需的数据快照；真正执行仍由本接口的 execute 完成。
     * 每次请求重新读取工具说明，保留原来的请求时机，不缓存可变化的工具定义。
     * @return 名称、说明与完整 schema 的快照，不包含本工具实例或执行回调
     */
    default ToolDefinition definition() {
        return new ToolDefinition(name(), description(), inputSchema());
    }

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
     * 执行模型的一次工具调用，工具实现自己检查缺参和字段类型。
     * @param input 模型给出的参数对象，字段可能缺失或类型错误
     * @return 成功时的结果文字，或预期失败时以 Error 开头的说明；
     *         执行器会用原调用 ID 把它写成 tool_result，加进 history
     */
    String execute(ObjectNode input);

    /**
     * 执行工具时，额外传入程序记录的任务信息；父 Agent 和子 Agent 都在检查权限后调用它。
     *
     * <p>后台 bash 需要任务 ID 来保存结果，task 工具需要把任务来源和退出检查传给子代理，
     * 所以它们重写这个方法。普通工具不需要这些信息，默认直接调用原来的 execute(input)，
     * 不用为此重写。这里不另开线程、不自动检查权限，也不修改 history。</p>
     *
     * <p>context 不写进工具参数说明，也不能从模型 input 中读取同名字段代替。
     * 否则模型只要自称“用户任务”，就可能绕过定时任务不能询问用户的限制。</p>
     * @param input 模型给出的非 null 参数对象，具体字段由工具自己检查
     * @param context 程序提供的任务 ID、来源、询问用户的函数和退出检查；
     *                默认实现不使用它，重写的方法自行规定能否为 null
     * @return 工具的普通结果文字，仍由执行器配上原 tool_use_id 写进 history
     */
    default String execute(ObjectNode input, ExecutionContext context) {
        return execute(input);
    }

    /**
     * 提供带明确状态的结果，父子执行器在权限通过后调用，不另行执行第二次工具。
     * 旧工具仍通过 execute(input, context) 执行一次，保留原来的文本契约；默认不猜测 Error 前缀。
     * 有结构化失败标志的工具重写此入口，返回失败时仍允许模型继续处理。
     * @param input 模型参数，具体字段由工具检查，不应修改
     * @param context 程序提供的可信任务上下文
     * @return 正文和失败标志，不包含 tool_use_id，不修改历史
     */
    default ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context) {
        return new ToolOutcome(execute(input, context), false);
    }
}
