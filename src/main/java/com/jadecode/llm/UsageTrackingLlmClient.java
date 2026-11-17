package com.jadecode.llm;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 为模型请求统一记录用途、Agent 和用量。取得响应后先登记实际 usage，再交给业务校验；请求失败时登记缺失报告，
 * 使正式、队友和辅助请求都进入同一账本。
 */
public final class UsageTrackingLlmClient implements LlmClient {
    private final LlmClient delegate;
    private final String model, actor;
    private final UsageRecord.Purpose purpose;
    private final Consumer<UsageRecord> sink;
    private final Runnable beforeRequest;
    private final Supplier<ThinkingSettings> thinking;

    /**
     * 绑定底层客户端、模型、Agent、用途、账本出口和请求前检查。可选 thinking 来源供正式请求读取当前思考档位，
     * null 时沿用请求中的设置。
     *
     * @param delegate 被包装的原始客户端或工具，实际操作继续由它执行
     * @param model 用于请求与用量归属的模型标识
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param purpose 本次模型请求的用途，用于区分正式、摘要、审批和记忆用量
     * @param sink 向共享用量账本提交 UsageRecord 的接收者
     * @param beforeRequest 发送模型请求前的账本可写与额度检查
     * @param thinking 本 Agent 的思考设置或当前值来源
     * @throws NullPointerException delegate、sink、beforeRequest 为 null
     */
    public UsageTrackingLlmClient(LlmClient delegate, String model, String actor, UsageRecord.Purpose purpose,
            Consumer<UsageRecord> sink, Runnable beforeRequest, Supplier<ThinkingSettings> thinking) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.model = model;
        this.actor = actor;
        this.purpose = purpose;
        this.sink = java.util.Objects.requireNonNull(sink);
        this.beforeRequest = java.util.Objects.requireNonNull(beforeRequest);
        this.thinking = thinking;
    }

    /**
     * 返回底层客户端的模型元数据，供调用方沿用相同的窗口和输出预算。
     *
     * @return 当前模型的窗口和输出限额元数据
     */
    @Override
    public ModelMetadata metadata() {
        return delegate.metadata();
    }

    /**
     * 返回底层客户端支持的思考档位，供会话与界面使用同一组可选值。
     *
     * @return 当前模型适配的可选思考档位
     */
    @Override
    public List<ThinkingSettings> thinkingChoices() {
        return delegate.thinkingChoices();
    }

    /**
     * 交给底层客户端生成思考档位的显示名称，使包装前后的模型名称规则一致。
     *
     * @param value 需要转换为界面名称的思考档位
     * @return 供界面显示的思考档位名称
     */
    @Override
    public String thinkingLabel(ThinkingSettings value) {
        return delegate.thinkingLabel(value);
    }

    /**
     * 先检查账本可用状态并分配用量事件 ID，按可选思考来源派生实际请求，再调用底层客户端。成功记录报告后返回响应；
     * 失败记录 usage 缺失并重新抛出原异常，记账异常附为 suppressed。
     *
     * @param request 本次完整模型请求，含 system、历史、工具目录、额度与思考设置
     * @param stream 本次模型请求独占的增量接收者
     * @return 完整助手消息、停止原因和可选用量报告
     * @throws LlmException 底层模型请求失败，已登记缺失用量后重新抛出
     */
    @Override
    public ModelResponse complete(CompleteRequest request, ResponseStream stream) throws LlmException {
        java.util.Objects.requireNonNull(stream, "stream");
        beforeRequest.run();
        String id = UUID.randomUUID().toString();
        CompleteRequest actual = thinking == null ? request : request.withThinking(thinking.get());
        ModelResponse response;
        try {
            response = delegate.complete(actual, stream);
        } catch (LlmException | RuntimeException failure) {
            // 服务端可能已经收费，但没提供可用报告。只登记缺失，不假装知道实际数字。
            try {
                sink.accept(new UsageRecord(id, model, actor, purpose, null));
            } catch (RuntimeException recording) {
                failure.addSuppressed(recording);
            }
            throw failure;
        }
        sink.accept(new UsageRecord(id, model, actor, purpose, response.usage()));
        return response;
    }
}
