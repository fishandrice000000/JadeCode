package com.jadecode.tools;

import java.util.Objects;

/**
 * 保存一次工具执行返回的正文与错误标记，供执行器关联原调用 ID 并构造 tool_result。
 * MCP、Shell 和本地工具分别按实际执行结果提供 isError，模型据此决定下一步处理。
 *
 * @param content 给模型与界面显示的结果文本
 * @param isError 本次工具执行是否失败
 */
public record ToolOutcome(String content, boolean isError) {
    /**
     * 校验正文；构造没有 IO，不根据正文猜测成功与否。
     *
     * @param content 给模型与界面读取的完整工具结果文本
     * @param isError 本次工具调用是否执行失败
     * @throws NullPointerException content 为 null
     */
    public ToolOutcome {
        Objects.requireNonNull(content, "content");
    }
}
