package com.jadecode.mcp;

import java.util.Objects;

/**
 * SDK 返回值进入 JadeCode 后的文本契约，不包含 SDK content 类型或工具调用 ID。
 * @param text 所有受支持文本块按顺序合并后的正文
 * @param isError 服务失败或连接、协议、内容类型等预期错误；不代表整个任务结束
 */
public record McpCallResult(String text, boolean isError) {
    /** 校验正文，不猜测 Error 前缀，不产生 IO。 */
    public McpCallResult { Objects.requireNonNull(text, "text"); }
    /** 生成明确失败；调用者仍需用原 tool_use_id 回填。 */
    public static McpCallResult failure(String reason) { return new McpCallResult("Error: MCP " + reason, true); }
}
