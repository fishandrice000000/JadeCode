package com.jadecode.cli.backend;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 运行期诊断兜底：把第三方库/领域代码的 {@code System.out}/{@code System.err} 输出转成
 * {@code diagnostic} 事件，而不是让它们在 Node 独占终端时直接写屏幕。
 *
 * <p>为什么需要它：旧 Java TUI 用 {@code RoutingPrintStream} 截获这些输出再画进界面；
 * 迁移后 Java 不能再画终端，但底层库仍可能打印。这里保留同样的“按线程缓冲、换行才成一条”
 * 规则，避免两个线程的半行拼成一条通知。它不是最终回答来源，也不把 stdout 日志当协议。</p>
 *
 * <p>安装必须成对：{@link #close()} 恢复原始流；未恢复会让后续 Java 输出丢失。</p>
 */
public final class BackendDiagnostics implements AutoCloseable {
    /** 逐线程行缓冲，避免并发 println 交织成一条无法阅读的警告。 */
    private static final class Lines extends OutputStream {
        private final Consumer sink;
        private final boolean error;
        private final Map<Thread, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();

        Lines(Consumer sink, boolean error) { this.sink = sink; this.error = error; }

        @Override public void write(int value) {
            ByteArrayOutputStream buffer = buffers.computeIfAbsent(Thread.currentThread(),
                    ignored -> new ByteArrayOutputStream());
            if (value == '\n') {
                emit(buffer);
                buffers.remove(Thread.currentThread());
            } else {
                buffer.write(value);
            }
        }

        private void emit(ByteArrayOutputStream buffer) {
            if (buffer.size() > 0) sink.accept(new String(buffer.toByteArray(), StandardCharsets.UTF_8), error);
        }

        void finish() {
            buffers.values().forEach(this::emit);
            buffers.clear();
        }
    }

    /** 一条已收到的诊断行。 */
    @FunctionalInterface
    interface Consumer {
        void accept(String line, boolean error);
    }

    private final PrintStream originalOut;
    private final PrintStream originalErr;
    private final Lines normalLines;
    private final Lines errorLines;
    private boolean closed;

    private BackendDiagnostics(PrintStream originalOut, PrintStream originalErr, Lines normalLines, Lines errorLines) {
        this.originalOut = originalOut;
        this.originalErr = originalErr;
        this.normalLines = normalLines;
        this.errorLines = errorLines;
    }

    /**
     * 安装路由流；诊断事件通过 {@code emit} 投递，同时写回原 stderr 供启动失败/日志排查。
     *
     * <p>注意：写回原 stderr 只在 Node 已经退出或尚未接管终端时才安全；正常运行期间 Node
     * 独占终端，因此这里默认只投递事件，不重复打印。</p>
     */
    public static BackendDiagnostics install(WireContext wire) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        Consumer sink = (line, error) -> {
            ObjectNode payload = Json.mapper().createObjectNode();
            payload.put("severity", error ? "warning" : "info");
            payload.put("message", line);
            wire.session().sendEvent("diagnostic", payload, wire.sessionId(), wire.generation());
        };
        Lines normal = new Lines(sink, false);
        Lines errors = new Lines(sink, true);
        System.setOut(new PrintStream(normal, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
        return new BackendDiagnostics(originalOut, originalErr, normal, errors);
    }

    /** 恢复原始流并提交尚未换行的尾巴。 */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        normalLines.finish();
        errorLines.finish();
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    /** @return 原始 stderr，用于 Node 退出后的最终故障输出。 */
    public PrintStream originalErr() { return originalErr; }
}
