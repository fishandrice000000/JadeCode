package com.jadecode.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * RecordingLlmClient 记录假客户端
 *
 * 包装另一个 LlmClient,记录每次收到的请求供测试断言
 * (如构造注入的 system / maxTokens / tools 是否透传)。
 *
 * <p>
 * 记录的是<b>快照</b>:CompleteRequest 持有的是调用方的可变 messages 列表,循环会持续往里追加,
 * 直接存引用的话事后读到的会是"跑完之后"的最终状态,而不是本次真正发出去的内容。
 * </p>
 */
public class RecordingLlmClient implements LlmClient {
    private final LlmClient delegate;
    private final List<CompleteRequest> requests = new ArrayList<>();

    public RecordingLlmClient(LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public ModelResponse complete(CompleteRequest request) throws LlmException {
        requests.add(new CompleteRequest(request.system(), List.copyOf(request.messages()),
                request.maxTokens(), request.tools()));
        return delegate.complete(request);
    }

    /** 测试断言用:收到的全部请求 */
    public List<CompleteRequest> requests() {
        return requests;
    }
}
