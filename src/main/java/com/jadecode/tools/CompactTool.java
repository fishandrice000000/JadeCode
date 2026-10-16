package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * compact 工具：模型主动表示“这一段可以压缩了”。
 *
 * <p>它自己不压缩，只是一个请求。自动机制只看上下文有多大，而模型知道自己是不是做完了一个阶段，
 * 所以这个工具没有参数。执行成功只说明模型提出了压缩请求，不代表历史已经压缩；
 * 到底压不压、什么时候压，由 harness 决定。
 *
 * <p>为什么不在 execute 里直接压缩：模型同一条回复里可能既有 compact 又有别的工具调用。
 * 如果在这里就裁剪历史，同批其它工具的结果还没回灌，历史里就会出现没有结果的调用；
 * 更糟的是，已经写过的文件记录被删掉，模型可能重复同一个副作用。所以父循环会先等整批结果
 * 回灌完，再根据这个工具的固定输出判断要不要压缩。
 *
 * <p>它是一个普通工具，会走完整的工具生命周期。本类不写文件、不调模型、不改历史，也不保存状态。
 */
public final class CompactTool implements Tool {

    /** 固定输出：父循环看到这个文字，就知道本批提出过压缩请求。 */
    public static final String OUTPUT = "Compaction requested after this tool batch.";

    /**
     * @return 工具名 {@code "compact"};模型在 tool_use 的 name 字段里用它选中本工具
     */
    @Override
    public String name() {
        return "compact";
    }

    /**
     * @return 给模型看的说明(课程原文):"总结较早的对话,以释放上下文空间"
     */
    @Override
    public String description() {
        return "Summarize earlier conversation to free context space.";
    }

    /**
     * @return 空参数表:本工具不接受任何参数,模型只表达"想压缩"这件事
     */
    @Override
    public Map<String, String> properties() {
        return Map.of();
    }

    /**
     * @return 空必填表:既然没有参数,也就没有必填项
     */
    @Override
    public List<String> required() {
        return List.of();
    }

    /**
     * 执行一次"压缩请求":只回一句稳定文案,不做任何实际工作。
     *
     * <p>
     * <b>为什么不读入参</b>:本工具没有参数(见 {@link #properties()} 与 {@link #required()}),
     * 压缩的范围与时机由 harness 在批次闭合处决定,不来自模型的参数。所以 {@code input} 是 null、
     * 空对象、还是带了额外字段,行为都完全一样:返回 {@link #OUTPUT}。
     * </p>
     *
     * <p>
     * <b>为什么不能在这里压缩</b>:本方法执行时,同一条 assistant 回复里的其它工具调用可能还没跑完,
     * 整批 tool_result 也还没回灌。此刻裁剪历史会拆开工具交换、丢失执行记录(详见类说明)。
     * 真正的压缩属于"批次闭合之后"的 harness 动作,不在工具里做。
     * </p>
     *
     * <p>
     * <b>为什么没有副作用</b>:不压缩、不归档、不调模型、不写文件、不改历史、不改入参,
     * 也不保存"已经请求过"的标志——标志由调用方按这条输出自行判断。
     * </p>
     *
     * @param input 被忽略;不读取、不修改(可传 null)
     * @return 恒定文案 {@link #OUTPUT};父循环靠它识别本批请求过压缩
     */
    @Override
    public String execute(ObjectNode input) {
        // 无参数工具:入参只是为了满足 Tool 接口的统一签名,这里不读、不校验、不改。
        // 返回固定文案 = "请求已提出"的信号;真正的压缩等整批交换闭合后由循环处理。
        return OUTPUT;
    }
}
