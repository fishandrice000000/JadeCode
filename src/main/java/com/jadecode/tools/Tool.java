package com.jadecode.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.llm.ToolDefinition;

/**
 * 模型可以调用的工具：声明完整参数结构，并对一次已通过权限检查的调用返回明确结果。
 *
 * <p>执行器先固定工具目录、检查权限，再调用 executeOutcome。工具自行校验模型参数，
 * 以 isError 报告预期失败；不能扫描文件正文或模型文本的 Error 前缀猜测结果。
 * 意外异常继续交给执行器补齐整批结果，已经发生的文件或进程副作用不会回滚。</p>
 */
public interface Tool {
    /** @return 固定工具名，用于与模型 tool_use 的 name 精确匹配 */
    String name();

    /** @return 模型选择工具时使用的用途、边界和结果说明 */
    String description();

    /**
     * 提供完整 JSON Schema；简单参数可以复用 ToolSchema 的静态构造器。
     * @return 非 null 的参数对象结构，不读文件、不执行工具，也不授予权限
     */
    ObjectNode inputSchema();

    /**
     * 固定本次请求的模型定义。定义自行复制 Schema，不携带执行对象或可变权限状态。
     * @return 名称、说明和完整结构的快照；动态工具在每次请求时重新读取
     */
    default ToolDefinition definition() {
        return new ToolDefinition(name(), description(), inputSchema());
    }

    /**
     * 在权限通过后执行一次调用；不修改输入，不在这里重放失败操作。
     * @param input 模型参数，具体工具负责判型和缺参检查
     * @param context Harness 提供的任务来源、取消条件与调用级许可；正式执行器始终提供，
     *                直接离线检查可传 null，需要可信身份的工具必须明确拒绝
     * @return 非 null 的正文和明确错误标志；预期错误可交回模型继续处理
     * @throws RuntimeException 意外执行故障，由执行器保留已发生结果并结束任务
     */
    ToolOutcome executeOutcome(ObjectNode input, ExecutionContext context);
}
