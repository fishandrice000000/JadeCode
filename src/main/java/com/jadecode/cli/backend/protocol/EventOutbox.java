package com.jadecode.cli.backend.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 后端唯一的事件出口：有界队列 + 单写线程 + 相邻增量合并。
 *
 * <p>为什么需要它：模型正文每几个字符就会产生一个增量，如果每个增量都立即写 socket，
 * SSH/tmux 上的界面会被帧洪水和 JSON 开销拖慢；而如果无界缓存，慢终端又会让 JVM 内存
 * 无限增长。这里折中为：只合并“同一代次、同一请求、同一通道的相邻正文增量”，并且只在
 * 16ms 窗口、32KiB 或结构边界送出；开始/结束/工具/审批/切换等结构事件永不合并，也不会
 * 被正文越过。</p>
 *
 * <p>队列预算：8MiB 或 4096 个事件，先触发者生效。超出后标记 OVERLOADED，通过
 * {@code onOverload} 异步收口连接，绝不丢正文还假装成功。所有领域线程只调用 {@link #offer}
 * 立即返回，不在持有领域锁时等待 socket。</p>
 */
public final class EventOutbox implements AutoCloseable {
    /** 单次批量输出的目标上限（UTF-8 字节）。 */
    public static final int FLUSH_BYTES = 32 * 1024;
    /** 合并窗口：同一波增量最多等待这么久再送出。 */
    public static final long COALESCE_MILLIS = 16;
    /** 待发事件数量上限。 */
    public static final int MAX_EVENTS = 4096;
    /** 待发数据字节上限。 */
    public static final long MAX_BYTES = 8L * 1024 * 1024;

    /** 真正写出一帧的出口；序号分配与 socket 写入由 {@link ProtocolSession} 负责。 */
    @FunctionalInterface
    public interface FrameSink {
        void write(String type, ObjectNode payload, String sessionId, Long generation, String commandId) throws IOException;
    }

    private record Pending(String type, ObjectNode payload, String sessionId, Long generation,
            String commandId, String mergeKey, int bytes) { }

    private final FrameSink sink;
    private final Consumer<RuntimeException> onOverload;
    private final Deque<Pending> queue = new ArrayDeque<>();
    private final Thread worker;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private long queuedBytes;
    private boolean closed;
    private boolean overloaded;
    private boolean flushRequested;
    private long firstQueuedAt;
    private RuntimeException failure;

    /**
     * @param sink       唯一真实写出口
     * @param onOverload 队列预算耗尽时调用一次；调用方应异步关闭连接
     */
    public EventOutbox(FrameSink sink, Consumer<RuntimeException> onOverload) {
        this.sink = sink;
        this.onOverload = onOverload;
        this.worker = new Thread(this::drain, "jade-wire-outbox");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 投递一个事件；不会阻塞在 socket 上。
     *
     * @param mergeKey 只有相邻且 mergeKey 完全相同的增量才会合并；结构事件传 null
     */
    public void offer(String type, ObjectNode payload, String sessionId, Long generation, String mergeKey) {
        Pending pending = new Pending(type, payload, sessionId, generation, null, mergeKey, sizeOf(payload));
        enqueue(pending);
    }

    /** 投递一个命令答复；答复不与其他事件合并，并触发一次立即刷新以降低界面等待。 */
    public void offerResult(String type, ObjectNode payload, String commandId, String sessionId, Long generation) {
        enqueue(new Pending(type, payload, sessionId, generation, commandId, null, sizeOf(payload)));
        flushNow();
    }

    private void enqueue(Pending pending) {
        synchronized (this) {
            if (closed || overloaded) return;
            if (queue.size() + 1 > MAX_EVENTS || queuedBytes + pending.bytes() > MAX_BYTES) {
                overloaded = true;
                queue.clear();
                queuedBytes = 0;
                notifyAll();
                RuntimeException failure = new IllegalStateException(
                        "The frontend is not consuming events fast enough; the connection must close");
                this.failure = failure;
                onOverload.accept(failure);
                return;
            }
            queue.addLast(pending);
            queuedBytes += pending.bytes();
            if (queue.size() == 1) firstQueuedAt = System.nanoTime();
            notifyAll();
        }
    }

    /** 请求把当前队列尽快送出（命令答复、关闭等结构边界）。 */
    public synchronized void flushNow() {
        flushRequested = true;
        notifyAll();
    }

    /** 队列中是否还有未送出的数据。 */
    public synchronized boolean hasPending() { return !queue.isEmpty(); }

    /** @return 传输层/写出口的致命失败；非 null 表示连接不可继续。 */
    public synchronized RuntimeException failure() { return failure; }

    private void drain() {
        try {
            while (true) {
                List<Pending> batch;
                synchronized (this) {
                    while (!closed && !overloaded && (queue.isEmpty() || !readyToFlush())) {
                        long waitMillis = queue.isEmpty() ? 0 : Math.max(1, COALESCE_MILLIS - elapsedMillis());
                        wait(waitMillis);
                        if (!queue.isEmpty() && elapsedMillis() >= COALESCE_MILLIS) break;
                    }
                    if (closed || overloaded) return;
                    batch = takeBatch();
                }
                writeBatch(batch);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failed) {
            synchronized (this) {
                if (failure == null) failure = failed instanceof RuntimeException runtime
                        ? runtime : new IllegalStateException(failed.getMessage(), failed);
                overloaded = true;
            }
            onOverload.accept(failure);
        } finally {
            stopped.countDown();
        }
    }

    private synchronized boolean readyToFlush() {
        return flushRequested || queue.size() >= 256 || queuedBytes >= FLUSH_BYTES || elapsedMillis() >= COALESCE_MILLIS;
    }

    private long elapsedMillis() { return (System.nanoTime() - firstQueuedAt) / 1_000_000L; }

    private List<Pending> takeBatch() {
        List<Pending> batch = new ArrayList<>();
        int bytes = 0;
        while (!queue.isEmpty()) {
            Pending next = queue.removeFirst();
            queuedBytes -= next.bytes();
            if (!batch.isEmpty() && (bytes + next.bytes() > FLUSH_BYTES || batch.size() >= 256)) {
                queue.addFirst(next);
                queuedBytes += next.bytes();
                break;
            }
            Pending previous = batch.isEmpty() ? null : batch.getLast();
            if (previous != null && next.mergeKey() != null && next.mergeKey().equals(previous.mergeKey())
                    && mergeInPlace(previous, next)) {
                bytes += next.bytes();
                continue;
            }
            batch.add(next);
            bytes += next.bytes();
        }
        if (!queue.isEmpty()) firstQueuedAt = System.nanoTime();
        flushRequested = false;
        return batch;
    }

    /**
     * 合并两个相邻的正文增量：保留前一个的 offset/身份，只有 text/fragment 串接。
     * 返回 false 表示两个载荷形状不同，必须分开送出。
     */
    private static boolean mergeInPlace(Pending previous, Pending next) {
        ObjectNode target = previous.payload();
        ObjectNode addition = next.payload();
        if (target.has("text") && addition.has("text")) {
            target.put("text", target.path("text").asText() + addition.path("text").asText());
            return true;
        }
        if (target.has("fragment") && addition.has("fragment")) {
            target.put("fragment", target.path("fragment").asText() + addition.path("fragment").asText());
            return true;
        }
        return false;
    }

    private void writeBatch(List<Pending> batch) throws IOException {
        for (Pending pending : batch)
            sink.write(pending.type(), pending.payload(), pending.sessionId(), pending.generation(), pending.commandId());
    }

    private static int sizeOf(ObjectNode payload) {
        return payload == null ? 0 : payload.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 关闭队列：等待写线程处理完当前批次后返回；写失败或过载都会让调用方看到真实状态。
     */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        try {
            stopped.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
