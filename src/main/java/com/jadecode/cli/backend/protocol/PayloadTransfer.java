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

    /**
     * 大资料只依赖事件出口，分块过程不需要知道当前有没有客户端。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     */
    private record Part(String type, ObjectNode payload, String sessionId, Long generation) {
    }

    private final AtomicLong ids = new AtomicLong();

    /**
     * 将大载荷分块出口接到事件队列，生成的各块沿原会话身份按序入队，由 outbox 写线程实际发送。
     *
     * @param outbox 当前连接的有界事件发送队列
     */
    public PayloadTransfer(EventOutbox outbox) {
        sink = part -> outbox.offer(part.type(), part.payload(), part.sessionId(), part.generation(), null);
    }

    /**
     * 将一个完整文本转换为 begin、连续 chunk 和 end 事件，返回正式载荷可使用的 blob 引用。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>统一空文本、生成唯一 blob ID，计算整段 UTF-8 字节数与 SHA-256，并发送声明帧。</li>
     * <li>按字符串下标选择接近块字节预算的片段，在 Unicode 代理对边界调整，再按递增 index 发送文本块。</li>
     * <li>发送包含实际块数与相同哈希的结束帧。</li>
     * </ol>
     *
     * <p>所有片段沿同一会话身份依次排入事件出口；正式业务帧随后通过返回 ID 引用完整资料。</p>
     *
     * @param kind 逻辑文本种类，如 text、inputJson、detail 或 encodedEntries
     * @param text 本次处理的完整文本，按该入口执行编码、脱敏、显示或组装
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     * @param blobIdPrefix 出站大文本引用 ID 的前缀
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
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1)))
                end++;
            // 粗调：把小段逐步扩展到接近目标字节数，避免块太小。
            while (end < value.length()
                    && value.substring(offset, end).getBytes(StandardCharsets.UTF_8).length < CHUNK_BYTES) {
                end = Math.min(value.length(), end + Math.max(1, (CHUNK_BYTES / 4)));
                if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1)))
                    end++;
            }
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1)))
                end--;
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

    /**
     * 计算十六进制 sha256，用于 blob 完整性校验。
     *
     * @param bytes 需要解码、校验或计算哈希的完整字节数组
     * @return 输入字节的十六进制 SHA-256
     * @throws IllegalStateException 当前 Java 运行环境无法提供 SHA-256 算法
     */
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
     * 也只属于发送连接。任何未知 id 的分块都会被拒绝，离开代次/断连时整体清空。</p>
     */
    public static final class Assembler {
        /**
         * 保存一份入站文本的种类、声明字节数、哈希、字节缓冲及已收块序号和数量，供结束帧到达时校验完整性。
         *
         * @param kind 逻辑文本种类，如 text、inputJson、detail 或 encodedEntries
         * @param totalBytes 该逻辑文本声明的 UTF-8 总字节数
         * @param hash 大文本或来源声明的 SHA-256 哈希
         * @param data 本逻辑载荷累计接收的字节缓冲
         * @param nextIndex 下一块应使用的连续下标
         * @param chunks 当前已经接收的块数量
         */
        private record Assembly(String kind, int totalBytes, String hash,
                ByteArrayOutputStream data, int nextIndex, int chunks) {
        }

        private final Map<String, Assembly> active = new HashMap<>();
        private long budget;

        /**
         * 开始一个入站 blob；超出并发或总预算时明确失败。
         *
         * @param id 本逻辑文本的 blob ID，用于关联 begin、chunk 和 end
         * @param kind 逻辑文本种类，如 text、inputJson、detail 或 encodedEntries
         * @param totalBytes 该逻辑文本声明的 UTF-8 总字节数
         * @param hash 大文本或来源声明的 SHA-256 哈希
         * @throws ProtocolException ID 重复、声明大小非法、并发数量或总字节预算超限
         */
        public synchronized void begin(String id, String kind, int totalBytes, String hash) throws ProtocolException {
            if (active.containsKey(id))
                throw new ProtocolException(ProtocolError.DUPLICATE, "Duplicate payload id: " + id);
            if (totalBytes < 0 || totalBytes > MAX_PAYLOAD_BYTES)
                throw new ProtocolException(ProtocolError.PAYLOAD_TOO_LARGE,
                        "Payload declared " + totalBytes + " bytes");
            if (active.size() >= MAX_ASSEMBLIES)
                throw new ProtocolException(ProtocolError.OVERLOADED, "Too many payloads are being assembled");
            if (budget + totalBytes > MAX_PAYLOAD_BYTES)
                throw new ProtocolException(ProtocolError.OVERLOADED, "Payload assembly budget is exhausted");
            active.put(id, new Assembly(kind, totalBytes, hash, new ByteArrayOutputStream(totalBytes), 0, 0));
            budget += totalBytes;
        }

        /**
         * 追加一块；块序号必须连续，且不得越过 begin 声明的大小。
         *
         * @param id 本逻辑文本的 blob ID，用于关联 begin、chunk 和 end
         * @param index 此逻辑文本中当前分块的连续下标，从 0 开始
         * @param text 本次处理的完整文本，按该入口执行编码、脱敏、显示或组装
         * @throws ProtocolException ID 未登记、分块序号不连续或累计字节超过声明
         */
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

        /**
         * 结束并校验；成功返回完整文本，失败不留下部分资料。
         *
         * @param id 本逻辑文本的 blob ID，用于关联 begin、chunk 和 end
         * @param count 结束帧声明的总块数
         * @param hash 大文本或来源声明的 SHA-256 哈希
         * @return 通过块数、长度和哈希检查后的完整 UTF-8 文本
         * @throws ProtocolException ID 未登记，或实际块数、累计字节数、SHA-256 与开始和结束声明不一致
         */
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

        /**
         * 读取当前仍在等待结束校验的组装数量，供传输容量和诊断查询使用。
         *
         * @return 当前尚未接收完整结束帧的载荷组装数量
         */
        public synchronized int activeCount() {
            return active.size();
        }

        /**
         * 断连或代次切换时丢弃所有未完成组装。
         */
        public synchronized void clear() {
            active.clear();
            budget = 0;
        }
    }
}
