package com.jadecode.messages;

import java.util.ArrayList;
import java.util.List;

/**
 * Message -- 会话中的一条消息的记录
 * 
 * 形如:
 * {
 * "role": "assistant",
 * "content": [
 * {"type": "text", "text": "好的,我先看看目录里有什么。"},
 * {"type": "tool_use", "id": "toolu_01", "name": "bash", "input": {"command":
 * "ls"}}
 * ]
 * }
 * 
 * @param role    一条消息的发送者角色
 * @param content 该条消息的内容 (可能有多轮工具调用的结果, 因此是数组)
 */
public record Message(Role role, List<ContentBlock> content) {

    /**
     * @param text 用户发来的纯文本
     * @return 装有用户纯文本的消息
     * 
     */
    public static Message userText(String text) {
        return new Message(Role.USER, List.of(new TextBlock(text)));
    }

    /**
     * @param blocks 用户发来的块列表
     * @return 装有用户块列表的消息
     */
    public static Message userBlocks(List<ContentBlock> blocks) {
        return new Message(Role.USER, blocks);
    }

    /**
     * @param blocks LLM 发来的块列表
     * @return 装有 LLM 块列表的消息
     */
    public static Message assistant(List<ContentBlock> blocks) {
        return new Message(Role.ASSISTANT, blocks);
    }

    /**
     * @return 返回当前消息记录中所有 LLM 生成的对话文本
     */
    public String extractText() {
        List<String> texts = new ArrayList<>();
        for (ContentBlock b : content) {
            if (b instanceof TextBlock t) {
                texts.add(t.text());
            }
        }
        return String.join("\n", texts);
    }

}
