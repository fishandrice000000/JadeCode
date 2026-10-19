package com.jadecode.cli.tui.bridge;

import java.util.UUID;
import java.util.function.Consumer;
import com.jadecode.cli.tui.UiEvent;
import com.jadecode.llm.ResponseStream;

/** 一次 Leader 请求的显示通道；只投递片段，协议拼装、签名回传与工具执行仍在原模块。 */
public final class StreamingAdapter implements ResponseStream {
    private final String id = UUID.randomUUID().toString();
    private final Consumer<UiEvent> sink;
    private boolean ended;
    public StreamingAdapter(Consumer<UiEvent> sink) { this.sink = sink; }
    @Override public synchronized void text(String text) { append("Reply", text); }
    @Override public synchronized void thinking(String text) { append("Thinking", text); }
    private void append(String kind, String text) { if (!ended && !text.isEmpty()) sink.accept(new UiEvent.Delta(id, kind, text)); }
    @Override public synchronized void complete() { end(false); }
    @Override public synchronized void failed() { end(true); }
    private void end(boolean failed) {
        if (ended) return; ended = true; sink.accept(new UiEvent.StreamEnd(id, failed));
    }
}
