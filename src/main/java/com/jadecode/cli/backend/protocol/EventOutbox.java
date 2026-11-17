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
 * 为一条连接提供有界事件队列、单写线程和相邻增量合并。
 *
 * <p>正文和预览按相同请求与通道的合并键拼接，结构事件保持顺序。写线程按 16 毫秒窗口、
 * 32 KiB 批次预算或命令回执的刷新请求发送，单批最多 256 个条目。
 * 队列达到 4096 项或 8 MiB 预算时记录过载并通知关闭连接；领域线程完成入队后由写线程承担 socket IO。</p>
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

    /**
     * 真正写出一帧的出口；序号分配与 socket 写入由 {@link ProtocolSession} 负责。
     */
    @FunctionalInterface
    public interface FrameSink {
        /**
         * 按给定事件类型、载荷、会话代次及命令 ID 写出一帧。协议层负责分配发送序号，IO 失败抛给队列写线程。
         *
         * @param type 当前协议事件或命令结果的类型名
         * @param payload 协议事件的完整 JSON 业务载荷
         * @param sessionId 持久会话 ID，用于关联本次事件与历史
         * @param generation 客户端看到的会话代次，切换会话后随新代次更新
         * @param commandId 客户端命令 ID，供命令回执和重复请求查找使用
         * @throws IOException 编码后的协议帧无法完整写入连接
         */
        void write(String type, ObjectNode payload, String sessionId, Long generation, String commandId)
                throws IOException;
    }

    /**
     * 表示一条排队待发的事件，保存协议身份、可选合并键和载荷字节数，供有界队列计量与批量发送。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     * @param commandId 客户端命令 ID，供命令回执和重复请求查找使用
     * @param mergeKey 增量合并键；null 表示结构事件单独发送
     * @param bytes 需要解码、校验或计算哈希的完整字节数组
     */
    private record Pending(String type, ObjectNode payload, String sessionId, Long generation,
            String commandId, String mergeKey, int bytes) {
    }

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
     * 绑定唯一帧出口和连接失败回调，创建并启动本连接的单写线程。
     *
     * @param sink 唯一真实写出口
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
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     * @param mergeKey 只有相邻且 mergeKey 完全相同的增量才会合并；结构事件传 null
     */
    public void offer(String type, ObjectNode payload, String sessionId, Long generation, String mergeKey) {
        Pending pending = new Pending(type, payload, sessionId, generation, null, mergeKey, sizeOf(payload));
        enqueue(pending);
    }

    /**
     * 投递一个命令答复；答复不与其他事件合并，并触发一次立即刷新以降低界面等待。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param commandId 客户端命令 ID，供命令回执和重复请求查找使用
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     */
    public void offerResult(String type, ObjectNode payload, String commandId, String sessionId, Long generation) {
        enqueue(new Pending(type, payload, sessionId, generation, commandId, null, sizeOf(payload)));
        flushNow();
    }

    /**
     * 在队列锁内登记待发事件并累计字节数，首项入队时记录合并窗口起点，然后唤醒写线程。
     * 数量或字节预算超限时记录过载、清空队列并通知连接收尾；已关闭或过载时直接返回。
     *
     * @param pending 本次待处理的队列事件或审批记录
     */
    private void enqueue(Pending pending) {
        synchronized (this) {
            if (closed || overloaded)
                return;
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
            if (queue.size() == 1)
                firstQueuedAt = System.nanoTime();
            notifyAll();
        }
    }

    /**
     * 请求把当前队列尽快送出（命令答复、关闭等结构边界）。
     */
    public synchronized void flushNow() {
        flushRequested = true;
        notifyAll();
    }

    /**
     * 队列中是否还有未送出的数据。
     *
     * @return 队列仍有待发送事件时为 true
     */
    public synchronized boolean hasPending() {
        return !queue.isEmpty();
    }

    /**
     * 读取该发送队列首次记录的致命失败，供协议层决定关闭连接。
     *
     * @return 传输层/写出口的致命失败；非 null 表示连接不可继续。
     */
    public synchronized RuntimeException failure() {
        return failure;
    }

    /**
     * 运行当前连接唯一的写线程，把有界队列中的事件按批发送。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>在队列锁内等待首项到达；关闭且队列为空时退出。</li>
     * <li>等到立即刷新、条数、字节或合并窗口条件成立，取出并合并一批相邻增量。</li>
     * <li>释放队列锁，按批次顺序调用帧出口，让业务投递线程继续入队。</li>
     * <li>写入异常记录失败并触发连接收尾；finally 发出 stopped 信号，结束关闭等待。</li>
     * </ol>
     */
    private void drain() {
        try {
            while (true) {
                List<Pending> batch;
                synchronized (this) {
                    while (!overloaded) {
                        if (queue.isEmpty()) {
                            // 关闭后队列已空才退出；关闭前必须把 server.stopped/命令答复送出。
                            if (closed)
                                return;
                            wait();
                            continue;
                        }
                        if (closed || flushRequested || queue.size() >= 256 || queuedBytes >= FLUSH_BYTES
                                || elapsedMillis() >= COALESCE_MILLIS)
                            break;
                        wait(Math.max(1, COALESCE_MILLIS - elapsedMillis()));
                    }
                    if (overloaded)
                        return;
                    batch = takeBatch();
                }
                writeBatch(batch);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failed) {
            synchronized (this) {
                if (failure == null)
                    failure = failed instanceof RuntimeException runtime
                            ? runtime
                            : new IllegalStateException(failed.getMessage(), failed);
                overloaded = true;
            }
            onOverload.accept(failure);
        } finally {
            stopped.countDown();
        }
    }

    /**
     * 按立即刷新标记、256 条事件、字节预算或合并窗口时长，返回当前队列是否达到批量发送条件。
     *
     * @return 已请求刷新或达到条数、字节、时间条件时为 true
     */
    private synchronized boolean readyToFlush() {
        return flushRequested || queue.size() >= 256 || queuedBytes >= FLUSH_BYTES
                || elapsedMillis() >= COALESCE_MILLIS;
    }

    /**
     * 将当前单调时间与首项入队时间之差换算成毫秒，供合并窗口判断使用。
     *
     * @return 首项入队以来的毫秒数
     */
    private long elapsedMillis() {
        return (System.nanoTime() - firstQueuedAt) / 1_000_000L;
    }

    /**
     * 从队首取出一批待发事件并扣除队列字节数，按字节与条数控制批次；相邻且同合并键的文本片段拼接。
     * 达到上限的下一项放回队首，剩余队列重新计时，最后清除立即刷新标记。
     *
     * @return 保持原顺序并完成相邻增量合并的待发送批次
     */
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
        if (!queue.isEmpty())
            firstQueuedAt = System.nanoTime();
        flushRequested = false;
        return batch;
    }

    /**
     * 合并两个相邻的正文增量：保留前一个的 offset/身份，只有 text/fragment 串接。
     * 返回 false 表示两个载荷形状不同，必须分开送出。
     *
     * @param previous 紧邻当前项的前一条增量
     * @param next 待尝试合并的下一条增量
     * @return 成功修改 previous 的文本或参数片段时为 true；载荷形状不同为 false
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

    /**
     * 按批次中的原顺序将事件交给帧出口，保留会话身份和命令 ID；写入失败以 IOException 交给 drain 处理。
     *
     * @param batch 本次按原顺序准备写出的事件批次
     * @throws IOException 批次中某一帧写入失败，后续由 drain 关闭出口
     */
    private void writeBatch(List<Pending> batch) throws IOException {
        for (Pending pending : batch)
            sink.write(pending.type(), pending.payload(), pending.sessionId(), pending.generation(),
                    pending.commandId());
    }

    /**
     * 计算 JSON 载荷序列化后的 UTF-8 字节数，作为队列与批次预算的计量单位；null 载荷计为 0。
     *
     * @param payload 协议事件的完整 JSON 业务载荷
     * @return 载荷 JSON 的 UTF-8 字节数；null 为 0
     */
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
            if (!stopped.await(5, TimeUnit.SECONDS)) {
                synchronized (this) {
                    if (failure == null) {
                        failure = new IllegalStateException("The outbox did not drain within its shutdown budget");
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
