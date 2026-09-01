package com.jadecode.llm;

import java.util.List;

import com.jadecode.messages.Message;

/**
 * CompleteRequest 一次调用 LLM 的输入
 * 
 * @param system    系统提示词
 * @param messages  调用发送的消息
 * @param maxTokens
 * 
 */
public record CompleteRequest(String system, List<Message> messages, int maxTokens) {
    // system 可为 null:SDK 层见到 null 就不注入 system prompt
}
