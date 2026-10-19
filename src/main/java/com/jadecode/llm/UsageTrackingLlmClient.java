package com.jadecode.llm;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 包在真实客户端外层，为正式、队友及辅助请求统一记账，不改变模型回复和流式片段。
 * 先收到响应并保存用量，再把响应交给业务校验：即使业务随后拒绝坏 JSON，模型花费仍存在。
 * 接收者由 session 注入，本类不认识日志目录、Inbox 或 TUI。
 */
public final class UsageTrackingLlmClient implements LlmClient {
    private final LlmClient delegate;
    private final String model, actor;
    private final UsageRecord.Purpose purpose;
    private final Consumer<UsageRecord> sink;
    private final Runnable beforeRequest;
    private final Supplier<ThinkingSettings> thinking;

    /**
     * 绑定一次用途，不启动 IO。delegate 应是底层客户端，不叠加另一个统计包装。
     * beforeRequest 检查账本是否仍可写；thinking 为 null 表示辅助请求保留自己的默认模式。
     */
    public UsageTrackingLlmClient(LlmClient delegate, String model, String actor, UsageRecord.Purpose purpose,
            Consumer<UsageRecord> sink, Runnable beforeRequest, Supplier<ThinkingSettings> thinking) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.model = model; this.actor = actor; this.purpose = purpose;
        this.sink = java.util.Objects.requireNonNull(sink);
        this.beforeRequest = java.util.Objects.requireNonNull(beforeRequest); this.thinking = thinking;
    }

    @Override public ModelMetadata metadata() { return delegate.metadata(); }
    @Override public List<ThinkingSettings> thinkingChoices() { return delegate.thinkingChoices(); }
    @Override public String thinkingLabel(ThinkingSettings value) { return delegate.thinkingLabel(value); }

    @Override public ModelResponse complete(CompleteRequest request) throws LlmException { return call(request, null); }
    @Override public ModelResponse complete(CompleteRequest request, ResponseStream stream) throws LlmException { return call(request, stream); }

    /**
     * 两种调用只经过这里一次。流式返回的完整 usage 才计入，不把每个 delta 当成新增花费。
     * 网络/流失败没有完整报告时登记缺失并原样抛出；记账失败不能触发业务重试。
     */
    private ModelResponse call(CompleteRequest request, ResponseStream stream) throws LlmException {
        beforeRequest.run();
        String id = UUID.randomUUID().toString();
        CompleteRequest actual = thinking == null ? request : request.withThinking(thinking.get());
        ModelResponse response;
        try { response = stream == null ? delegate.complete(actual) : delegate.complete(actual, stream); }
        catch (LlmException | RuntimeException failure) {
            // 服务端可能已经收费，但没提供可用报告。只登记缺失，不假装知道实际数字。
            try { sink.accept(new UsageRecord(id, model, actor, purpose, null)); }
            catch (RuntimeException recording) { failure.addSuppressed(recording); }
            throw failure;
        }
        sink.accept(new UsageRecord(id, model, actor, purpose, response.usage()));
        return response;
    }
}
