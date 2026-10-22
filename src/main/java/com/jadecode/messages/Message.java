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

    /**
     * 辅助模型返回 JSON 时允许夹带 thinking，但不能把工具调用或结果当成候选 JSON。
     *
     * <p>Memory 仍只用 extractText 解析完整正文；思考里的数组或命令不会被执行或写盘。
     * 空列表在这里形状合法，随后由各辅助服务自己的 JSON 校验拒绝空正文。</p>
     * @return content 非 null，且每块都是正文或思考；null 块、工具块均返回 false
     */
    public boolean hasOnlyTextAndThinking() {
        return content != null && content.stream().allMatch(block -> block instanceof TextBlock
                || block instanceof ThinkingBlock || block instanceof RedactedThinkingBlock);
    }

    /**
     * 按运行期类型筛选本条消息的 content 块,保持原有顺序。
     *
     * <p>
     * 用法:{@code message.blocksOfType(ToolUseBlock.class)}、
     * {@code message.blocksOfType(ToolResultBlock.class)}、{@code message.blocksOfType(TextBlock.class)}。
     * </p>
     *
     * <p>
     * 只做类型筛选:不校验角色、不检查 ID 或调用配对,也不改变消息的 JSON 形态。
     * 返回的列表不可修改,不暴露原 content 的结构修改能力;无匹配时返回空列表。
     * </p>
     *
     * @param type 要筛选的块类型,不能为 null
     * @param <T>  块类型,必须是 {@link ContentBlock} 的子类型
     * @return 按原顺序排列的匹配块;不可修改
     * @throws IllegalArgumentException type 为 null
     */
    public <T extends ContentBlock> List<T> blocksOfType(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        List<T> matched = new ArrayList<>();
        for (ContentBlock block : content) {
            if (type.isInstance(block)) {
                matched.add(type.cast(block));
            }
        }
        return List.copyOf(matched);
    }

}
