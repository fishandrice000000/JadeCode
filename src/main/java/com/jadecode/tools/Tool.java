package com.jadecode.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.llm.ToolDefinition;

/**
 * 模型可以调用的工具：声明完整参数结构，并对一次已通过权限检查的调用返回明确结果。
 *
 * <p>执行器固定本次工具目录并完成权限检查，再调用 executeOutcome。具体工具按 schema 校验参数，
 * 执行操作后以正文和 isError 交回结果。意外异常交给执行器保留已有结果、补齐本批剩余调用并结束失败轮次。</p>
 */
public interface Tool {
    /**
     * 声明工具的固定注册名，供模型定义与收到 tool_use 后的目录查找使用。
     *
     * @return 固定工具名，用于与模型 tool_use 的 name 精确匹配
     */
    String name();

    /**
     * 声明工具的业务用途、输入边界与结果说明，供模型选择工具。
     *
     * @return 模型选择工具时使用的用途、边界和结果说明
     */
    String description();

    /**
     * 提供完整 JSON Schema；简单参数可以复用 ToolSchema 的静态构造器。
     *
     * @return 非 null 的参数对象结构，不读文件、不执行工具，也不授予权限
     */
    ObjectNode inputSchema();

    /**
     * 固定本次请求的模型定义。定义自行复制 Schema，不携带执行对象或可变权限状态。
     *
     * @return 名称、说明和完整结构的快照；动态工具在每次请求时重新读取
     */
    default ToolDefinition definition() {
        return new ToolDefinition(name(), description(), inputSchema());
    }

    /**
     * 在权限通过后执行一次调用；不修改输入，不在这里重放失败操作。
     *
     * @param input 模型参数，具体工具负责判型和缺参检查
     * @param context Harness 提供的任务来源、取消条件与调用级许可；正式执行器始终提供，
     * 直接离线检查可传 null，需要可信身份的工具必须明确拒绝
     * @return 非 null 的正文和明确错误标志；预期错误可交回模型继续处理
     * @throws RuntimeException 意外执行故障，由执行器保留已发生结果并结束任务
     */
    ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context);
}
