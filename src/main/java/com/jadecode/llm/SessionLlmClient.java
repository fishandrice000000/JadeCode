package com.jadecode.llm;

import java.util.List;
import java.util.UUID;

/**
 * 给一个 Agent 运行时绑定稳定路由身份，底层传输仍可被不同会话并发共享。
 *
 * <p>一个运行时的正文、摘要和其它辅助请求必须使用同一身份，否则 Go 无法稳定路由和复用缓存。
 * 构造时分配临时 UUID；CLI 读到日志后，在第一次请求前改用持久 UUID。身份只在本包装内改变，
 * 不修改共享 SDK 客户端。一次性子代理继承所属 Agent 的路由，常驻队友各有一个包装。</p>
 */
public final class SessionLlmClient implements LlmClient {
    private final LlmClient delegate;
    private String sessionId = UUID.randomUUID().toString();
    private boolean requested;

    /** 构造只绑定传输，不读取配置、日志或网络；delegate 不应是另一个会话包装。 */
    public SessionLlmClient(LlmClient delegate) { this.delegate = java.util.Objects.requireNonNull(delegate); }

    /**
     * 恢复前采用标准 UUID；请求开始后禁止换身份，避免进行中的正文与辅助请求分属不同路由。
     * 此处只更新内存，不写日志，不发请求；SessionRuntime 保证在 start 前调用。
     */
    public synchronized void restoreIdentity(String id) {
        // UUID.fromString 接受短写；额外比对标准形式，不能把任意字符串当作持久会话身份。
        if (id == null || !UUID.fromString(id).toString().equals(id))
            throw new IllegalArgumentException("Model session identity must be a complete UUID");
        if (requested) throw new IllegalStateException("Cannot change model session identity after a request has started");
        sessionId = id;
    }

    /** 每次调用先取身份快照；锁只覆盖内存读取，不在网络等待期间阻塞其它辅助请求。 */
    private synchronized CompleteRequest bind(CompleteRequest request) {
        requested = true;
        return request.withSessionId(sessionId);
    }

    @Override public ModelMetadata metadata() { return delegate.metadata(); }
    @Override public List<ThinkingSettings> thinkingChoices() { return delegate.thinkingChoices(); }
    @Override public String thinkingLabel(ThinkingSettings value) { return delegate.thinkingLabel(value); }
    @Override public ModelResponse complete(CompleteRequest request) throws LlmException {
        return delegate.complete(bind(request));
    }
    @Override public ModelResponse complete(CompleteRequest request, ResponseStream stream) throws LlmException {
        return delegate.complete(bind(request), stream);
    }
}
