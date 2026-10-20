package com.jadecode.tools;

import java.util.Objects;

/**
 * 一次工具执行的正文与明确失败标志，不携带调用 ID，也不修改历史。
 *
 * <p>MCP 的 isError 是服务明确报告的事实，不能在转成字符串时丢失，也不能靠正文
 * 是否以 Error 开头猜测。旧工具仍由 Tool 的默认入口包装；有结构化状态的工具主动提供本记录。</p>
 * @param content 给模型与界面看的非 null 文本
 * @param isError true 表示本次工具失败，不代表整个 Agent 任务必须结束
 */
public record ToolOutcome(String content, boolean isError) {
    /** 校验正文；构造没有 IO，不根据正文猜测成功与否。 */
    public ToolOutcome { Objects.requireNonNull(content, "content"); }
}
