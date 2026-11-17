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

    /**
     * 保存底层模型传输并分配临时会话 UUID，供同一 Agent 的正文、摘要与其他辅助请求共用稳定路由。
     *
     * @param delegate 被包装的原始客户端或工具，实际操作继续由它执行
     * @throws NullPointerException delegate 为 null
     */
    public SessionLlmClient(LlmClient delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
    }

    /**
     * 在首次请求前采用持久会话的完整 UUID；格式错误抛出 IllegalArgumentException，
     * 请求已开始后切换身份抛出 IllegalStateException。
     *
     * @param id 持久会话的完整标准 UUID
     * @throws IllegalArgumentException 输入不是完整标准 UUID
     * @throws IllegalStateException 此客户端已经开始模型请求，身份已固定
     */
    public synchronized void restoreIdentity(String id) {
        // UUID.fromString 接受短写；额外比对标准形式，不能把任意字符串当作持久会话身份。
        if (id == null || !UUID.fromString(id).toString().equals(id))
            throw new IllegalArgumentException("Model session identity must be a complete UUID");
        if (requested)
            throw new IllegalStateException("Cannot change model session identity after a request has started");
        sessionId = id;
    }

    /**
     * 在短同步区内标记请求已开始，并用当前会话 ID 派生新 CompleteRequest，供锁外调用底层模型。
     *
     * @param request 本次完整模型请求，含 system、历史、工具目录、额度与思考设置
     * @return 已经绑定当前稳定会话 ID 的新请求
     */
    private synchronized CompleteRequest bind(CompleteRequest request) {
        requested = true;
        return request.withSessionId(sessionId);
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
     * 将请求绑定到当前稳定会话身份，再交给底层客户端执行模型请求并转发流式回调。调用方传入的请求保持原样，
     * 模型失败以 LlmException 向上传递。
     *
     * @param request 本次完整模型请求，含 system、历史、工具目录、额度与思考设置
     * @param stream 本次模型请求独占的增量接收者
     * @return 完整助手消息、停止原因和可选用量报告
     * @throws LlmException 绑定会话身份后的底层模型请求失败
     * @throws NullPointerException stream 为 null
     */
    @Override
    public ModelResponse complete(CompleteRequest request, ResponseStream stream) throws LlmException {
        return delegate.complete(bind(request), java.util.Objects.requireNonNull(stream, "stream"));
    }
}
