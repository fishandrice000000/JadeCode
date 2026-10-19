package com.jadecode.cli.tui.bridge;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import com.jadecode.cli.tui.UiEvent;

/**
 * 兜底已有 System.out/err；按线程保存未换行片段，避免两个线程的 println 被拼成一条通知。
 * 只投递事件，不写终端。渲染器使用截获前建立的 Terminal writer，避免递归截获自己。
 */
public final class RoutingPrintStream extends PrintStream {
    private static final class Lines extends OutputStream {
        private final Consumer<UiEvent> sink;
        private final boolean error;
        private final java.util.Map<Thread, ByteArrayOutputStream> buffers = new java.util.concurrent.ConcurrentHashMap<>();
        Lines(Consumer<UiEvent> sink, boolean error) { this.sink = sink; this.error = error; }
        @Override public void write(int value) {
            var buffer = buffers.computeIfAbsent(Thread.currentThread(), ignored -> new ByteArrayOutputStream());
            if (value == '\n') {
                emit(buffer); buffers.remove(Thread.currentThread());
            } else buffer.write(value);
        }
        private void emit(ByteArrayOutputStream buffer) {
            if (buffer.size() > 0) sink.accept(new UiEvent.Message("System", error ? "Warning" : "Notice", buffer.toString(StandardCharsets.UTF_8), error));
        }
        private void finish() { buffers.values().forEach(this::emit); buffers.clear(); }
    }
    private final Lines lines;
    public RoutingPrintStream(Consumer<UiEvent> sink, boolean error) { this(new Lines(sink, error)); }
    private RoutingPrintStream(Lines lines) { super(lines, true, StandardCharsets.UTF_8); this.lines = lines; }
    /** 业务资源清理后提交尚未换行的尾部，再投递 Stopped；不关闭原终端流。 */
    public synchronized void finish() { lines.finish(); }
}
