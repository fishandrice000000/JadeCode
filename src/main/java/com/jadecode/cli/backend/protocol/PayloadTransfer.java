package com.jadecode.cli.backend.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 大资料的分块传输：长历史正文、完整工具参数、长用户输入都不能因为“一帧上限”被截断。
 *
 * <p>两个方向共用 {@code payload.begin/chunk/end} 三帧。发送方按 UTF-8 字节切块（不会切开
 * 代理对），接收方只在三帧齐全且总长/sha256 校验通过后才允许正式消息引用该 blob。</p>
 *
 * <p>限额：单逻辑资料最多 64MiB，同时最多 16 个组装，总预算 64MiB。超限返回明确错误，
 * 不截断后继续，也不把未校验的半个参数交给审批界面。</p>
 */
public final class PayloadTransfer {
    /** 单个逻辑载荷上限。 */
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
    /** 同时组装的载荷数量上限。 */
    public static final int MAX_ASSEMBLIES = 16;
    /** 发送时每块的目标字节数（JSON 转义后仍远低于单帧上限）。 */
    public static final int CHUNK_BYTES = 32 * 1024;

    private final java.util.function.Consumer<Part> sink;
    /** 大资料只依赖事件出口，分块过程不需要知道当前有没有客户端。 */
    private record Part(String type, ObjectNode payload, String sessionId, Long generation) { }
    private final AtomicLong ids = new AtomicLong();

    public PayloadTransfer(EventOutbox outbox) {
        sink = part -> outbox.offer(part.type(), part.payload(), part.sessionId(), part.generation(), null);
    }

    /** 常驻会话使用的出口；离线分块仍由服务保存/投影，不写失效的 socket。 */
    public PayloadTransfer(EventEndpoint endpoint) {
        sink = part -> endpoint.sendEvent(part.type(), part.payload(), part.sessionId(), part.generation());
    }

    /**
     * 把一段文本拆成 blob 事件送出。
     *
     * @return blobId，供正式消息以 {@code *BlobRef} 字段引用
     */
    public String sendText(String kind, String text, String sessionId, Long generation, String blobIdPrefix) {
        String value = text == null ? "" : text;
        String id = blobIdPrefix + "-" + ids.incrementAndGet();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes);
        ObjectNode begin = Json.mapper().createObjectNode();
        begin.put("id", id);
        begin.put("kind", kind);
        begin.put("totalBytes", bytes.length);
        begin.put("sha256", hash);
        sink.accept(new Part("payload.begin", begin, sessionId, generation));
        int index = 0;
        int offset = 0;
        while (offset < value.length()) {
            // 按 UTF-16 下标前进，但要保证不把代理对劈开：提升到码点边界。
            int end = Math.min(value.length(), offset + CHUNK_BYTES / 4 + 1);
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end++;
            // 粗调：把小段逐步扩展到接近目标字节数，避免块太小。
            while (end < value.length()
                    && value.substring(offset, end).getBytes(StandardCharsets.UTF_8).length < CHUNK_BYTES) {
                end = Math.min(value.length(), end + Math.max(1, (CHUNK_BYTES / 4)));
                if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end++;
            }
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            ObjectNode chunk = Json.mapper().createObjectNode();
            chunk.put("id", id);
            chunk.put("index", index++);
            chunk.put("text", value.substring(offset, end));
            sink.accept(new Part("payload.chunk", chunk, sessionId, generation));
            offset = end;
        }
        ObjectNode end = Json.mapper().createObjectNode();
        end.put("id", id);
        end.put("count", index);
        end.put("sha256", hash);
        sink.accept(new Part("payload.end", end, sessionId, generation));
        return id;
    }

    /** 计算十六进制 sha256，用于 blob 完整性校验。 */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }

    /**
     * 接收方向的组装器：Node 发出的长文本（提交、会话名等）先分块，再由这里校验。
     *
     * <p>它与发送器分开是因为方向、错误处理和生命周期不同：入站 blob 跟连接走，出站 blob
     * 跟会话代次走。任何未知 id 的分块都会被拒绝，离开代次/断连时整体清空。</p>
     */
    public static final class Assembler {
        private record Assembly(String kind, int totalBytes, String hash,
                ByteArrayOutputStream data, int nextIndex, int chunks) { }

        private final Map<String, Assembly> active = new HashMap<>();
        private long budget;

        /** 开始一个入站 blob；超出并发或总预算时明确失败。 */
        public synchronized void begin(String id, String kind, int totalBytes, String hash) throws ProtocolException {
            if (active.containsKey(id))
                throw new ProtocolException(ProtocolError.DUPLICATE, "Duplicate payload id: " + id);
            if (totalBytes < 0 || totalBytes > MAX_PAYLOAD_BYTES)
                throw new ProtocolException(ProtocolError.PAYLOAD_TOO_LARGE, "Payload declared " + totalBytes + " bytes");
            if (active.size() >= MAX_ASSEMBLIES)
                throw new ProtocolException(ProtocolError.OVERLOADED, "Too many payloads are being assembled");
            if (budget + totalBytes > MAX_PAYLOAD_BYTES)
                throw new ProtocolException(ProtocolError.OVERLOADED, "Payload assembly budget is exhausted");
            active.put(id, new Assembly(kind, totalBytes, hash, new ByteArrayOutputStream(totalBytes), 0, 0));
            budget += totalBytes;
        }

        /** 追加一块；块序号必须连续，且不得越过 begin 声明的大小。 */
        public synchronized void chunk(String id, int index, String text) throws ProtocolException {
            Assembly assembly = active.get(id);
            if (assembly == null)
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Unknown payload id: " + id);
            if (index != assembly.nextIndex())
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE,
                        "Payload chunk " + index + " arrived after " + assembly.nextIndex());
            byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
            if (assembly.data().size() + bytes.length > assembly.totalBytes())
                throw new ProtocolException(ProtocolError.PAYLOAD_TOO_LARGE, "Payload exceeded its declared size");
            assembly.data().writeBytes(bytes);
            active.put(id, new Assembly(assembly.kind(), assembly.totalBytes(), assembly.hash(),
                    assembly.data(), index + 1, assembly.chunks() + 1));
        }

        /** 结束并校验；成功返回完整文本，失败不留下部分资料。 */
        public synchronized String end(String id, int count, String hash) throws ProtocolException {
            Assembly assembly = active.get(id);
            if (assembly == null)
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Unknown payload id: " + id);
            active.remove(id);
            budget -= assembly.totalBytes();
            byte[] bytes = assembly.data().toByteArray();
            if (count != assembly.chunks())
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Payload chunk count mismatch");
            if (bytes.length != assembly.totalBytes())
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Payload size mismatch");
            if (!sha256(bytes).equalsIgnoreCase(hash == null ? "" : hash) || !hash.equalsIgnoreCase(assembly.hash()))
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Payload hash mismatch");
            return new String(bytes, StandardCharsets.UTF_8);
        }

        /** 取回已完成但不在此处消费的资料标识（供测试/诊断）。 */
        public synchronized int activeCount() { return active.size(); }

        /** 断连或代次切换时丢弃所有未完成组装。 */
        public synchronized void clear() {
            active.clear();
            budget = 0;
        }
    }
}
