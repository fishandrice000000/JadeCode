package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * compact:模型主动请求"一个阶段结束后压缩历史,只留摘要"。
 *
 * <p>
 * <b>它本身不压缩,只是一次请求。</b>自动阈值只知道上下文有多大;模型才知道自己是不是做完了一个阶段,
 * 所以这个工具<b>没有参数</b>(空 object schema),唯一作用是表态。它执行成功只说明"模型提出了压缩请求",
 * <b>不代表历史已经被压缩</b>——真正压不压、什么时候压,由 harness 决定。
 * </p>
 *
 * <p>
 * <b>为什么必须等整批工具交换闭合</b>:模型的同一条回复里可能既有 compact、也有普通工具调用。
 * 如果在 {@link #execute} 里就地裁剪历史,后面那些工具的结果还没回灌,历史里就会留下"有调用没结果"
 * 的半个交换;更糟的是,把已经发生过的文件写入记录删掉,模型可能重复同一个副作用。
 * 所以顺序是固定的:先给整批 tool_use 全部补齐 tool_result(交换闭合),再由循环决定是否压缩这个回合。
 * </p>
 *
 * <p>
 * <b>它仍然是一个正式工具</b>:后续集成注册之后,和别的工具一样走 membership / PreToolUse /
 * PostToolUse 全套生命周期,成功调用也必须按 {@code toolUseId} 写入一条正常 tool_result;
 * 父循环再根据这条<b>稳定输出</b>({@link #OUTPUT})识别"本批请求过压缩"。
 * </p>
 *
 * <p>
 * <b>本类没有任何副作用,也不持有状态</b>:它不依赖压缩器、历史、模型客户端或存储,不写文件、
 * 不调用模型、不改历史,也没有"已请求过压缩"的标志——那个判断留给调用方在批次闭合处做。
 * 本轮只实现工具自身(元数据 + 恒定输出):<b>后续集成</b>才负责把它注册进 Main、接入父子循环,
 * 以及实现"批次闭合后压缩"。
 * </p>
 */
public final class CompactTool implements Tool {

    /** 稳定输出:批次闭合后据此置位"本批请求过压缩"(父循环会引用这个常量) */
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
