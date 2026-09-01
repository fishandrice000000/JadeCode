package com.jadecode.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * RecordingLlmClient 记录假客户端
 *
 * 包装另一个 LlmClient,记录每次收到的请求供测试断言
 * (如构造注入的 system / maxTokens / tools 是否透传)。
 */
public class RecordingLlmClient implements LlmClient {
    private final LlmClient delegate;
    private final List<CompleteRequest> requests = new ArrayList<>();

    public RecordingLlmClient(LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public ModelResponse complete(CompleteRequest request) throws LlmException {
        requests.add(request);
        return delegate.complete(request);
    }

    /** 测试断言用:收到的全部请求 */
    public List<CompleteRequest> requests() {
        return requests;
    }
}
