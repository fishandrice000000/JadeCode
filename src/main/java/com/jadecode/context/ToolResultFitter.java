package com.jadecode.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.ToolResultBlock;

/**
 * 第三层 B：兜底把最长的工具结果存盘。
 *
 * <p>经过前面几层整理后，整段历史仍可能超过目标长度。这时本层把体积最大的结果搬到磁盘，
 * 历史里只留路径和一小段预览，需要原文时按路径读回。
 *
 * <p>与 {@link MicroCompactor} 的区别：micro 只动模型已经读过的旧结果，还会保留最近几条；
 * fit 是兜底，连最新、模型还没读过的那批也可以存盘，因为存盘是可恢复的，不是有损压缩。
 * 两者都不调模型，也不改写正文。本类不保存会话状态，父子循环可以安全共享。
 */
final class ToolResultFitter {

    /** 阈值集合;fit 目前只用到 {@link CompactionLimits#fitPreviewChars()} 这一个分量 */
    private final CompactionLimits limits;

    /** 工具结果落盘与预览 */
    private final ToolOutputStore toolOutputStore;

    /**
     * @param limits          阈值集合;预览长度取其中的 {@code fitPreviewChars()}
     *                        (生产默认 1000 个 Java char,测试可注入更小值)
     * @param toolOutputStore 工具结果落盘与预览
     */
    ToolResultFitter(CompactionLimits limits, ToolOutputStore toolOutputStore) {
        this.limits = limits;
        this.toolOutputStore = toolOutputStore;
    }

    /**
     * 第三层兜底:把当前最长的工具结果逐个落盘,直到整段 JSON 估算降到目标以内,或候选耗尽。
     *
     * <p>
     * <b>处理顺序</b>:收集<b>所有 user 消息</b>里的 {@link ToolResultBlock}(不做"已消费/未消费"
     * 区分),按各自当前文本的 {@link String#length()} 从大到小处理——先处理体积最大的,
     * 同样的目标往往能用更少的落盘达成。长度相同时保持历史中的出现顺序(稳定排序)。
     * 候选用"第几条消息的第几个 block"定位,不用工具 ID 当唯一键,因为 ID 只要求"同一次交换内唯一",
     * 不同交换可能重复,按位置定位才是无歧义的。
     * </p>
     *
     * <p>
     * <b>两次缩短检查</b>:一个候选要同时通过两关才会被采纳。
     * </p>
     * <ul>
     * <li><b>① 正文必须严格更短</b>:外壳文本要比原结果短,否则套上外壳只是让上下文变大。</li>
     * <li><b>② 序列化后的完整历史必须严格更短</b>:外壳里新增的换行在 JSON 中会被转义成两个字符,
     * 标准外壳固定含 4 个换行,所以"正文短 1~4 个字符"的替换完全可能让整段 JSON 变长甚至等长
     * (短 4 个字符时正好等长)。第 ① 关只看正文长度,单独用它做守卫会漏掉这种情况,
     * 必须再按实际序列化结果确认一次。</li>
     * </ul>
     * <p>
     * 两关都通过才写入工作列表;任一关不通过就跳过这个候选、继续试下一个,历史保持原样。
     * 已有可信落盘路径的结果会直接复用(只把预览收窄、不重复落盘);工具原文里伪造的
     * {@code Full output: ...} 不会被信任,一律当普通正文重新落盘。
     * </p>
     *
     * <p>
     * <b>目标判定</b>:每次真正替换后都用 {@link ContextSerialization#estimateChars} 重新估算
     * <b>整段 JSON</b>。不能拿"替换前后正文长度之差"来推算——正文里的引号、反斜线、换行在 JSON 里
     * 会被转义,字符数变化与正文长度变化并不相等。
     * </p>
     *
     * <p>
     * <b>IO 粒度</b>:外壳是"先生成、再比较长度"的。所以当某个候选最终被缩短检查拒绝时,
     * 如果它本来就没有可信路径,那份完整原文<b>已经落盘</b>了——磁盘上会多一个当前没被上下文引用的文件
     * (数据不丢,最坏多一个文件)。真正零 IO 的只有两条路径:整体本来已达标、以及没有任何工具结果候选。
     * </p>
     *
     * <p>
     * <b>不可变约定</b>:不修改输入的消息对象图——本方法只新建列表与 {@link Message},
     * 从不写入调用方传入的列表或其中的 content。只有真正采纳替换时才重建外层列表与该消息的 content
     * (逐层重建见方法内注释);没有实际替换时返回入参本身。
     * 因此无论候选落盘/读取失败抛出,还是候选被两次缩短检查拒绝,
     * <b>调用方手里的原列表始终完整</b>:此前已采纳的替换只写进本方法的局部工作列表,不在调用方的对象图里。
     * </p>
     *
     * <p>
     * 候选全部试完仍未达标属于正常结果:返回当前实际列表,由上层决定要不要继续压缩(第四层摘要),
     * 本方法不会为了"达标"删内容或抛异常。
     * </p>
     *
     * @param messages    消息列表;不修改入参
     * @param targetChars 目标字符数(整段 JSON 的字符数,来自调用方)
     * @return 处理后的列表;整体本来已达标或无任何实际替换时,原样返回入参
     * @throws IllegalStateException 建目录、写文件或读取可信预览失败(带 cause)
     */
    List<Message> fitToolResults(List<Message> messages, int targetChars) {
        // 步骤 1:整体已经达标就是真正的 no-op——不收集候选、不落盘、不读预览,零 IO。
        int currentChars = ContextSerialization.estimateChars(messages);
        if (currentChars <= targetChars) {
            return messages;
        }

        // 步骤 2:按"消息下标 → content 下标"收集所有 user 消息里的工具结果。
        // 只记位置不记内容:替换不改变消息数与 block 数,所以位置在处理过程中始终有效,
        // 需要读当前文本时再按位置取,天然拿到同一消息里前几次替换后的最新状态。
        List<ResultPosition> candidates = new ArrayList<>();
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            Message message = messages.get(messageIndex);
            if (message.role() != Role.USER) {
                continue; // 工具结果只可能出现在 user 消息里;assistant 消息整条不参与
            }
            for (int blockIndex = 0; blockIndex < message.content().size(); blockIndex++) {
                if (message.content().get(blockIndex) instanceof ToolResultBlock) {
                    candidates.add(new ResultPosition(messageIndex, blockIndex));
                }
            }
        }

        // 步骤 3:按当前文本长度降序排列候选。List.sort 是稳定排序,长度相同的候选
        // 保持步骤 2 的遍历顺序;排序只改变"处理的先后",不改变历史里消息、block 与 ID 的顺序。
        candidates.sort(Comparator.comparingInt(
                (ResultPosition position) -> currentLength(messages, position)).reversed());

        // 步骤 4:逐个候选尝试落盘替换。工作列表只有在"候选被采纳"时才换成新列表,
        // 所以"没有实际替换"的情况仍然返回入参本身。
        List<Message> fittedMessages = messages;
        for (ResultPosition position : candidates) {
            // 4.1 处理下一个候选之前先看目标:上一次替换可能已经足够,后面的候选保持原样。
            if (currentChars <= targetChars) {
                break;
            }

            // 4.2 从"当前工作列表"按位置取出结果。同一消息里前面已经替换过的 block,
            // 在这里读到的是替换后的新内容,不会退回旧状态。
            Message message = fittedMessages.get(position.messageIndex());
            ToolResultBlock result = (ToolResultBlock) message.content().get(position.blockIndex());
            String output = result.content();

            // 4.3 生成可恢复外壳:已有可信路径就直接复用(从原文件回读预览),否则先把完整原文落盘;
            //     伪造的外部路径与缺失文件都不可信,会当普通正文重新落盘。写/读失败在这里带 cause 抛出:
            //     此前已采纳的替换只写进了本方法的局部工作列表,调用方的原对象图从未被修改,所以原列表依旧完整。
            String replacement = toolOutputStore.persistedPreview(result.toolUseId(), output,
                    limits.fitPreviewChars());

            // 4.4 第一关:正文严格更短。外壳更长或等长就放弃这个候选。
            //     例:结果本身很短、或已经是更窄的占位时,套外壳只会让上下文变大;
            //     "没变短"不等于失败,继续试下一个候选。
            if (replacement.length() >= output.length()) {
                continue;
            }

            // 4.5 第二关:序列化后的完整历史也要严格更短。
            //     正文短不代表 JSON 短:外壳多了几个换行,JSON 里每个换行要写成 "\n" 两个字符,
            //     所以"正文短 1 个字符、JSON 反而长 2 个字符"是真实存在的边界;正文等长/更长的
            //     情况已经被上一关挡住,这里补的是"正文略短但转义更多"这一段。
            //     做法:先按"如果采纳"重建一份候选列表,用与最终判定同一套估算比较大小;
            //     没有变小就整份丢掉,工作列表一点不动。
            List<Message> replacedMessages = withReplacement(fittedMessages, message, position, result,
                    replacement);
            int replacedChars = ContextSerialization.estimateChars(replacedMessages);
            if (replacedChars >= currentChars) {
                continue;
            }

            // 4.6 两关都过:采纳这份候选列表。逐层重建的含义:
            //     ① 外层列表在最外层浅拷贝一次(复制引用,不复制元素);
            //     ② 该消息的 content 列表单独浅拷贝——不能直接改原列表,它是调用方对象图的一部分;
            //     ③ 用新 content 生成新 Message 来承接它。
            //     record 只是"分量字段不能再赋值",它引用的 List 并不会因此变成不可变集合:
            //     所以既不能原地改旧 content,也不能指望"改了 block 就等于改了历史"——
            //     必须复制 content、再用新 Message 承接新列表。只复制外层而直接改 content 会污染入参;
            //     只换 block 不换 Message,新 block 也进不了历史。
            fittedMessages = replacedMessages;

            // 4.7 下一轮比较用的数值就是这份候选列表的估算值(不是正文长度差):
            //     正文里的引号、反斜线、换行都会被转义,只有整段序列化才是可信口径。
            currentChars = replacedChars;
        }

        // 步骤 5:返回实际结果。候选耗尽仍未达标时也如实返回,不抛"必须达标"异常、不删除任何内容,
        // 是否继续走第四层摘要由上层判断。
        return fittedMessages;
    }

    /**
     * 按位置把一条工具结果换成新文本,返回一份<b>全新</b>的消息列表;入参完全不变。
     *
     * <p>
     * 只重建真正变化的那一条:外层列表、该消息的 content 列表、新 block,以及承接新列表的
     * {@link Message};其余消息与 block 原样复用引用。因为只读入参、从不写入参,
     * 候选被拒绝时调用方直接丢掉返回值即可,输入对象图毫无变化。
     * </p>
     *
     * <p>
     * 为什么必须这样重建:{@code record} 的"不可变"只到分量字段这一层——不能给字段重新赋值,
     * 但它引用的 List 并不会因此变成不可变集合。所以既不能原地改旧 content(浅拷贝陷阱,
     * 会污染调用方的对象图),也不能只替换 block 而不重建 Message(那样新 block 不会被历史引用到)。
     * </p>
     *
     * @param messages    当前工作列表
     * @param message     要修改的那条消息(必须是 {@code messages} 中的元素)
     * @param position    被替换结果在历史中的位置
     * @param result      被替换的原 block(提供 tool_use_id)
     * @param replacement 新的结果文本(可恢复外壳)
     * @return 替换后的新列表;入参不变
     */
    private static List<Message> withReplacement(List<Message> messages, Message message,
            ResultPosition position, ToolResultBlock result, String replacement) {
        List<Message> replaced = new ArrayList<>(messages); // ① 外层浅拷贝
        List<ContentBlock> content = new ArrayList<>(message.content()); // ② 该消息 content 单独拷贝
        content.set(position.blockIndex(), result.withContent(replacement));
        replaced.set(position.messageIndex(), new Message(message.role(), content)); // ③ 新 Message 承接
        return replaced;
    }

    /**
     * 读一条候选结果当前文本的长度(Java char 数)。
     *
     * <p>
     * 只在排序前调用:此时还没发生任何替换,所以用原 {@code messages} 计算即可。
     * 长度单位与 {@link ContextSerialization#estimateChars} 一样都是 Java char,
     * 但这里比的是"单条结果正文",不是整段 JSON。
     * </p>
     *
     * @param messages 消息列表
     * @param position 候选位置
     * @return 该位置工具结果的当前文本长度
     */
    private static int currentLength(List<Message> messages, ResultPosition position) {
        ToolResultBlock result = (ToolResultBlock) messages.get(position.messageIndex())
                .content().get(position.blockIndex());
        return result.content().length();
    }

    /**
     * 一条工具结果在历史里的位置。
     *
     * <p>
     * 用下标而不是工具 ID 定位:ID 只在同一次工具交换内唯一,不同交换可以重复,
     * 而"第几条消息的第几个 block"永远无歧义。替换不增删消息与 block,因此位置在整个处理过程中稳定。
     * </p>
     *
     * @param messageIndex 消息在历史列表中的下标
     * @param blockIndex   工具结果块在该消息 content 列表中的下标
     */
    private record ResultPosition(int messageIndex, int blockIndex) {
    }
}
