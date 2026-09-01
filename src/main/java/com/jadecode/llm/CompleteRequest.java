package com.jadecode.llm;

import java.util.List;

import com.jadecode.messages.Message;
import com.jadecode.tools.Tool;

/**
 * CompleteRequest 一次调用 LLM 的输入
 * 
 * @param system    系统提示词
 * @param messages  调用发送的消息
 * @param maxTokens 单次输出最大 Token 数
 * @param tools     可用的工具列表
 * 
 */
public record CompleteRequest(String system, List<Message> messages, int maxTokens, List<Tool> tools) {
    // system 可为 null:SDK 层见到 null 就不注入 system prompt
}
