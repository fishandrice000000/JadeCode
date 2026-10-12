package com.jadecode.context;

import java.util.ArrayList;
import java.util.List;

import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.ToolResultBlock;

/**
 * MicroCompactor -- 第三层 A:缩短模型已经消费过的旧工具结果。
 *
 * <p>
 * 最新、尚未被模型读取的整个结果批次<b>不得</b>在此被语义压缩——它们要原样交给主模型。
 * 确定性、可恢复,不调用模型。
 * </p>
 *
 * <p>
 * 本类<b>没有可变会话状态</b>,父子循环可以安全共享同一个实例。
 * </p>
 */
final class MicroCompactor {

    /** 阈值集合(最短处理长度、保留条数、目标比例都从这里取) */
    private final CompactionLimits limits;

    /** 工具结果落盘与预览 */
    private final ToolOutputStore toolOutputStore;

    /**
     * @param limits          阈值集合
     * @param toolOutputStore 工具结果落盘与预览
     */
    MicroCompactor(CompactionLimits limits, ToolOutputStore toolOutputStore) {
        this.limits = limits;
        this.toolOutputStore = toolOutputStore;
    }

    /**
     * 缩短模型已经消费过的旧工具结果:保留最近若干条已消费结果,更早且超过最短门槛的
     * 结果先完整落盘、再替换为可信路径占位,直到估算降到目标以下。
     *
     * <p>
     * 最新、尚未被模型读取的整个结果批次<b>不得</b>在此被语义压缩——它们要原样交给主模型。
     * 已有可信 marker 的直接复用,避免 marker 套 marker。
     * </p>
     *
     * <p>
     * 先按消息位置收集已消费结果,扣除最近保留的若干条,再从最早的候选开始处理。
     * 每次替换后重新估算整段 JSON,避免正文转义导致字符估算漂移。
     * </p>
     *
     * @param messages    消息列表
     * @param targetChars 目标字符数;null = 不做目标检查,一路处理到没有可处理项
     * @return 处理后的列表;无改动时原样返回入参
     */
    List<Message> microCompact(List<Message> messages, Integer targetChars) {
        int currentChars = ContextSerialization.estimateChars(messages);
        if (targetChars != null && currentChars <= targetChars) {
            return messages;
        }

        // 最后一条 assistant 之后的整批结果尚未被模型读取,即使尾部还有 user 文本。
        int lastAssistantIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.ASSISTANT) {
                lastAssistantIndex = i;
                break;
            }
        }

        // 按消息、block 顺序收集已消费结果,保留条数按结果计算,不是按消息计算。
        List<ResultPosition> consumed = new ArrayList<>();
        for (int i = 0; i < lastAssistantIndex; i++) {
            Message message = messages.get(i);
            if (message.role() != Role.USER) {
                continue;
            }
            for (int j = 0; j < message.content().size(); j++) {
                if (message.content().get(j) instanceof ToolResultBlock) {
                    consumed.add(new ResultPosition(i, j));
                }
            }
        }

        int candidateCount = Math.max(0, consumed.size() - limits.keepRecentResults());
        // 首次真正替换时才复制外层列表,无变化时保留入参身份。
        List<Message> compactedMessages = messages;
        for (int index = 0; index < candidateCount; index++) {
            if (targetChars != null && currentChars <= targetChars) {
                break;
            }

            ResultPosition position = consumed.get(index);
            Message message = compactedMessages.get(position.messageIndex());
            ToolResultBlock result = (ToolResultBlock) message.content().get(position.blockIndex());
            String output = result.content();
            if (output.length() <= limits.microResultMinChars()) {
                continue;
            }

            String savedPath = toolOutputStore.persistedOutputPath(output);
            if (savedPath == null) {
                savedPath = toolOutputStore.saveOutput(result.toolUseId(), output).toString();
            }
            String replacement = "[Earlier tool result saved at " + savedPath + "]";
            if (replacement.length() >= output.length()) {
                continue;
            }

            if (compactedMessages == messages) {
                compactedMessages = new ArrayList<>(messages);
            }
            List<ContentBlock> content = new ArrayList<>(message.content());
            content.set(position.blockIndex(), new ToolResultBlock(result.toolUseId(), replacement));
            compactedMessages.set(position.messageIndex(), new Message(message.role(), content));
            currentChars = ContextSerialization.estimateChars(compactedMessages);
        }

        return compactedMessages;
    }

    /** 一条结果在原历史中的位置,替换不改变消息数和 block 数。 */
    private record ResultPosition(int messageIndex, int blockIndex) {
    }
}
