package com.jadecode.cli.tui.bridge;

import java.util.function.Consumer;
import com.jadecode.cli.tui.UiEvent;
import com.jadecode.cli.tui.model.TranscriptEntry;
import com.jadecode.llm.ResponseStream;

/**
 * 一次 Leader 正式请求的显示预览通道：正文/思考/工具参数。
 *
 * <p>每个请求创建新对象；retry 由 AgentExecutor 生成新 requestId 并调用 requestStarted，
 * 旧预览不能续写到新请求。工具参数只转发为 UI 事件，不拼历史、不执行工具、不保存签名/
 * 加密思考。complete(stopReason) 最终收尾；failed 幂等，保留已显示片段。
 * 本类不持有 TuiState/组件，只投递不可变事件。</p>
 */
public final class StreamingAdapter implements ResponseStream {
    private final Consumer<UiEvent> sink;
    private final String actor;
    private String request = "";
    private boolean ended;

    /** @param sink 已带 generation 的事件接收器；actor 为真实 Agent 身份 */
    public StreamingAdapter(Consumer<UiEvent> sink) { this(sink, "leader"); }

    /** 显式 actor；目前正式流仅 Leader 使用。 */
    public StreamingAdapter(Consumer<UiEvent> sink, String actor) {
        this.sink = sink; this.actor = actor == null ? "leader" : actor;
    }

    @Override public synchronized void requestStarted(String requestId) {
        request = requestId == null ? "" : requestId;
        ended = false;
        sink.accept(new UiEvent.StreamStarted(request, actor));
    }

    @Override public synchronized void text(String text) {
        if (!ended && text != null && !text.isEmpty())
            sink.accept(new UiEvent.Delta(request, actor, TranscriptEntry.Kind.REPLY, text));
    }

    @Override public synchronized void thinking(String text) {
        if (!ended && text != null && !text.isEmpty())
            sink.accept(new UiEvent.Delta(request, actor, TranscriptEntry.Kind.THINKING, text));
    }

    @Override public synchronized void toolBlockStarted(int blockIndex, String callId, String name, String initialInputJson) {
        if (ended) return;
        sink.accept(new UiEvent.ToolPreviewStarted(request, actor, blockIndex, callId, name,
                initialInputJson == null ? "" : initialInputJson, null));
    }

    @Override public synchronized void toolInputDelta(int blockIndex, String fragment) {
        if (!ended && fragment != null && !fragment.isEmpty())
            sink.accept(new UiEvent.ToolPreviewDelta(request, blockIndex, fragment));
    }

    @Override public synchronized void toolBlockClosed(int blockIndex) {
        if (!ended) sink.accept(new UiEvent.ToolPreviewClosed(request, blockIndex));
    }

    @Override public synchronized void complete(String stopReason) { end(false, stopReason); }

    @Override public synchronized void complete() { end(false, ""); }

    @Override public synchronized void failed() { end(true, ""); }

    private void end(boolean failed, String stopReason) {
        if (ended) return;
        ended = true;
        sink.accept(new UiEvent.StreamEnd(request, failed, stopReason == null ? "" : stopReason));
    }
}
