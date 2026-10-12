package com.jadecode.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.ToolResultBlock;

/**
 * ToolResultBudgetCompactor -- 第一层:最新结果批次的预算控制。
 *
 * <p>
 * 只看最后一条 user 消息里的最新结果批次,超预算就从最大项开始落盘并留预览,不调用模型,
 * 内容可完整取回。旧结果不归它管(那是 {@link MicroCompactor} / {@link ToolResultFitter} 的事)。
 * </p>
 *
 * <p>
 * 本类<b>没有可变会话状态</b>,父子循环可以安全共享同一个实例。
 * </p>
 */
final class ToolResultBudgetCompactor {

    /** 阈值集合 */
    private final CompactionLimits limits;

    /** 工具结果落盘与预览 */
    private final ToolOutputStore toolOutputStore;

    /**
     * @param limits          阈值集合
     * @param toolOutputStore 工具结果落盘与预览
     */
    ToolResultBudgetCompactor(CompactionLimits limits, ToolOutputStore toolOutputStore) {
        this.limits = limits;
        this.toolOutputStore = toolOutputStore;
    }

    /**
     * 只看最后一条 user 消息中的最新结果批次:批次总量超过预算时,从最大项开始,
     * 把超过单项上限的完整内容落盘,上下文里只留可信路径与前若干字符预览,
     * 直到批次回到预算内或没有符合条件的项。
     *
     * <p>
     * 非末尾批次一律不动——旧结果归 {@link MicroCompactor#microCompact} 管。
     * </p>
     *
     * <p>
     * TODO(s08-user): 实现本层。注意"从最大项开始"与"每替换一项就重新统计"。
     * </p>
     *
     * @param messages 消息列表
     * @return 处理后的列表;无需处理时原样返回入参
     */
    List<Message> toolResultBudget(List<Message> messages) {
        // 边界条件 1: 消息列表为空
        if (messages.isEmpty()) {
            return messages;
        }

        // 边界条件 2: 最新消息不是用户消息
        Message latest = messages.getLast();
        if (latest.role() != Role.USER) {
            return messages;
        }

        // 1. 收集最新消息中的工具结果及其位置。

        // 新内容列表
        List<ContentBlock> budgetedContents = new ArrayList<>(latest.content());
        // 工具结果在内容列表中的索引的集合
        List<Integer> resultIndexes = new ArrayList<>();

        // 构建索引集合
        for (int i = 0; i < budgetedContents.size(); i++) {
            if (budgetedContents.get(i) instanceof ToolResultBlock) {
                resultIndexes.add(i);
            }
        }

        // 按对应结果大小排序索引
        resultIndexes.sort(
                Comparator.comparingInt((Integer i) -> ((ToolResultBlock) budgetedContents.get(i)).content().length())
                        .reversed());

        // 工具结果总长度.
        int totalLength = 0;
        for (int i : resultIndexes) {
            totalLength += ((ToolResultBlock) budgetedContents.get(i)).content().length();
        }

        // 边界条件 3: 最新消息的工具结果足够小
        if (totalLength <= limits.toolResultBatchCharLimit()) {
            return messages;
        }

        boolean hasChanged = false;
        // 2. 按结果长度，从大到小选择处理对象。
        for (int i : resultIndexes) {
            // 3. 每次处理前检查：批次是否已经回到预算内？
            if (totalLength <= limits.toolResultBatchCharLimit()) {
                break;
            }
            // 4. 只处理长度超过单项门槛的结果。
            ToolResultBlock u = (ToolResultBlock) budgetedContents.get(i);
            if (u.content().length() <= limits.largeResultCharLimit()) {
                continue;
            }
            hasChanged = true;

            String id = u.toolUseId();
            String content = u.content();

            // 5. 调用 persistLargeOutput(id, content)，得到路径与预览文本。
            String persistedContent = persistLargeOutput(id, content);

            // 6. 用新 ToolResultBlock 替换对应位置，更新批次总长度。
            ToolResultBlock budgetedResult = new ToolResultBlock(id, persistedContent);
            budgetedContents.set(i, budgetedResult);
            totalLength -= content.length();
            totalLength += persistedContent.length();
        }

        // 边界条件 4: 每一个工具结果的大小都足够小, 都不会被转存
        if (!hasChanged) {
            return messages;
        }

        // 7. 返回重建后的消息列表。
        List<Message> budgetedMessages = new ArrayList<>(messages);
        budgetedMessages.set(budgetedMessages.size() - 1, new Message(Role.USER, budgetedContents));
        return budgetedMessages;
    }

    /**
     * 第一层的单项处理:超过单项上限才落盘,否则原样返回。
     *
     * <p>
     * TODO(s08-user): 实现本方法(很薄,但阈值判断在这一层)。
     * </p>
     *
     * @param toolUseId 工具调用 ID
     * @param output    完整原文
     * @return 原文,或落盘预览外壳
     */
    String persistLargeOutput(String toolUseId, String output) {
        if (output.length() <= limits.largeResultCharLimit()) {
            return output;
        }
        return toolOutputStore.persistedPreview(toolUseId, output, limits.budgetPreviewChars());
    }
}
