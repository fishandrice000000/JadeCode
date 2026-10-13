package com.jadecode.context;

import java.util.ArrayList;
import java.util.List;

import com.jadecode.messages.Message;

/**
 * SnipCompactor -- 第二层:消息条数过多时归档中段。
 *
 * <p>
 * 完整历史写进 transcript,再用一条 archive marker 换掉中段:结果 = 最初若干条 + marker + 最近若干条。
 * 两个切点都要避开完整的工具交换(见 {@link ToolExchangeBoundary})。
 * </p>
 *
 * <p>
 * 本类<b>没有可变会话状态</b>,父子循环可以安全共享同一个实例。
 * </p>
 */
final class SnipCompactor {

    /** 阈值集合 */
    private final CompactionLimits limits;

    /** transcript 归档与归档标记识别 */
    private final TranscriptStore transcriptStore;

    /**
     * @param limits          阈值集合
     * @param transcriptStore transcript 归档与归档标记识别
     */
    SnipCompactor(CompactionLimits limits, TranscriptStore transcriptStore) {
        this.limits = limits;
        this.transcriptStore = transcriptStore;
    }

    /**
     * 消息数超过阈值时,先把完整历史写进 transcript,再用一条 archive marker 换掉中段:
     * 结果 = 最初若干条 + marker + 最近若干条。
     *
     * <p>
     * 两个切点都要避开完整的工具交换(见 {@link ToolExchangeBoundary#safeHeadEnd} /
     * {@link ToolExchangeBoundary#safeTailStart})。
     * 切不出合法结构时宁可整次不做;中段本来就只有一条可信 marker 时也不要重复写归档。
     * </p>
     *
     * <p>
     * TODO(s08-user): 实现本层。先算切点、判幂等,再落归档、拼新列表。
     * </p>
     *
     * @param messages 消息列表
     * @return 裁剪后的列表;无需或无法裁剪时原样返回入参
     */
    List<Message> snipCompact(List<Message> messages) {
        int totalLength = messages.size();
        // 边界条件 1: 消息数量太少, 不裁剪直接返回
        if (totalLength <= limits.snipMaxMessages()) {
            return messages;
        }

        int tailStart = ToolExchangeBoundary.safeTailStart(messages,
                totalLength - limits.snipMaxMessages() + limits.snipHeadMessages() + 1);
        int headEnd = ToolExchangeBoundary.safeHeadEnd(messages, limits.snipHeadMessages());

        // 边界条件 2: 准备裁剪的中段, 长度为 0, 不裁剪, 直接返回
        if (tailStart - headEnd < 1) {
            return messages;
        }

        // 边界条件 3: 准备裁剪的中段, 已经有且只有一条可信的归档标记, 不重复裁剪, 直接返回
        if (tailStart - headEnd == 1 && transcriptStore.isArchiveMarker(messages.get(headEnd))) {
            return messages;
        }

        List<Message> head = new ArrayList<>(messages.subList(0, headEnd));
        List<Message> tail = new ArrayList<>(messages.subList(tailStart, totalLength));

        // 先归档本层收到的完整历史(含头尾),再用 marker 替换中段。
        // marker 的 N 只表示从模型可见历史移走的消息数,不是归档文件行数;
        // 写入失败会在此抛出,下面的重建不会执行,调用方仍持有完整原列表。
        Message archivedMid = Message.userText(
                "[" + (tailStart - headEnd) + " messages archived at "
                        + transcriptStore.writeTranscript(messages).toString() + "]");

        // 返回重建的消息列表
        List<Message> snippedMessages = new ArrayList<>();
        snippedMessages.addAll(head);
        snippedMessages.add(archivedMid);
        snippedMessages.addAll(tail);
        return snippedMessages;
    }
}
