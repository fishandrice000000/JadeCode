package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具接口:模型可调用的函数。每个实现类注册进 {@code Map<String, Tool>} 分发表后,
 * AgentLoop 收到 model 的 tool_use 块按 name 查表、串行执行。
 *
 * <p>实现类契约:</p>
 * <ul>
 *   <li>execute 绝不抛异常——出错也返回以 "Error: " 开头的字符串,文案回灌给模型自纠;</li>
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
     *         失败:"Error: ..." 开头的文案;绝不抛异常
     */
    String execute(ObjectNode input);
}
