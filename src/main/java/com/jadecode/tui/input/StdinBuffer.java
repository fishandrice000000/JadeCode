package com.jadecode.tui.input;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 把任意分片的终端字节组装成完整文字、转义序列和整段粘贴。
 *
 * <p>终端的 read 回调不保证一次给出完整序列：`ESC[<35;20;5m` 可能拆成三次到达。若把分片
 * 当正文，`[<35` 会插进编辑器，鼠标/审批也会误触发。因此本类只做字节边界组装，不解释
 * 按键语义；{@link InputDecoder} 才把完整序列变成 {@link InputEvent}。唯一的 UTF-8 解码点
 * 也在这里：Editor/审批只接收已经正确拼接的字符串，不会重复处理代理对或半字符。</p>
 *
 * <p>括号粘贴开始后直到结束标记的内容作为一个 PASTE 块发出，其中嵌入的回车、Esc、终端
 * 快捷键都不是按键。孤立 Esc 只有超过期限才作为 ESCAPE 发出，避免 SSH 下拆开的方向键被
 * 误当 Esc。本类只由输入线程调用。</p>
 */
public final class StdinBuffer {
    private static final byte ESC = 0x1b;
    private static final byte[] PASTE_START = "\u001b[200~".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PASTE_END = "\u001b[201~".getBytes(StandardCharsets.US_ASCII);
    /** 与 Pi 一致的未完整转义序列恢复期限；不能用孤立 Esc 的 10/100ms 拆开真实序列。 */
    private static final long SEQUENCE_TIMEOUT_NANOS = 50_000_000L;
    /** 不完整 CSI/SGR 被冲刷后，短暂吞掉紧随其后的协议尾部，避免它作为正文插入。 */
    private static final long SWALLOW_TAIL_NANOS = 250_000_000L;
    /**
     * 孤立 Esc 的第二阶段等待：JLine/tmux 可能把 `ESC` 与 `[A` 分两次投递且间隔可达
     * 100ms 以上（实测）。到达 escapeTimeout 后先挂起，再等这段宽限期；期间任何后续字节
     * 都按同一序列/Alt 组合处理。超过宽限期仍无字节才作为真正 Esc 发出。
     */
    private static final long ESC_CONTINUATION_GRACE_NANOS = 200_000_000L;

    /** 组装结果的种类。SEQUENCE 是完整转义序列，TEXT 是普通 UTF-8 文字。 */
    public enum Kind { TEXT, SEQUENCE, PASTE, ESCAPE }

    /**
     * 一个完整输入块。
     * @param kind 块种类
     * @param text 文本内容；SEQUENCE 使用 ISO-8859-1 保存原始字节值，解码器按 ASCII 处理
     * @param sequence 输入块序号，从 1 开始
     * @param atNanos 组装完成时刻（单调时钟），用于输入水位
     */
    public record Chunk(Kind kind, String text, long sequence, long atNanos) { }

    private final Consumer<Chunk> sink;
    private final java.util.function.LongSupplier clock;
    private final long escapeTimeoutNanos;
    private byte[] pending = new byte[0];
    private long lastByteAt;
    private long sequence;
    private boolean pasting;
    private final StringBuilder pasteBuffer = new StringBuilder();
    /** 已冲刷的不完整 CSI/SGR 类型；其后紧跟的续尾按同一序列吞掉，不进入编辑器。 */
    private String swallowKind;
    private long swallowUntilNanos;
    /** 已到 escapeTimeout 但仍在等可能的序列续尾的孤立 ESC。 */
    private boolean escapeSuspended;
    private long escapeSuspendedAt;

    /**
     * @param escapeTimeoutMillis 孤立 Esc 的等待期限；SSH 下通常更长
     * @param sink 接收完整块的消费者；不能回调本类
     * @param clock 可注入单调时钟，便于测试超时
     */
    public StdinBuffer(long escapeTimeoutMillis, Consumer<Chunk> sink, java.util.function.LongSupplier clock) {
        this.sink = sink;
        this.clock = clock;
        this.escapeTimeoutNanos = Math.max(1, escapeTimeoutMillis) * 1_000_000L;
    }

    /** 追加一段终端读取到的字节并尽可能多地发出完整块。 */
    public synchronized void feed(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        pending = concat(pending, bytes);
        lastByteAt = clock.getAsLong();
        // 挂起的 ESC 等到后续字节：取消挂起，让 ESC+续尾走正常序列/Alt 组装。
        if (escapeSuspended && pending.length > 1) escapeSuspended = false;
        if (consumeProtocolTail()) return;
        process(false);
    }

    /**
     * 由输入循环定期调用；只处理超时的输入。
     *
     * <p>只有 pending 恰好是一个 ESC 字节时才按孤立 Esc 期限释放；只要后面已经跟着
     * `[`、`O` 或其它字节，就说明用户按的是方向键/鼠标/Alt 序列，必须等到序列完整或
     * 50ms 序列期限后整体冲刷。旧实现不检查长度，会把 `ESC[<65;29;` 拆成 Esc+正文，
     * 造成 `[<65;29;21M` 泄漏到输入框。</p>
     */
    public synchronized void tick() {
        if (pending.length == 0) return;
        long now = clock.getAsLong();
        if (pasting) return;
        if (pending.length == 1) {
            if (pending[0] != ESC || now - lastByteAt < escapeTimeoutNanos) return;
            if (!escapeSuspended) {
                escapeSuspended = true;
                escapeSuspendedAt = now;
                return;
            }
            if (now - escapeSuspendedAt >= ESC_CONTINUATION_GRACE_NANOS) {
                escapeSuspended = false;
                emit(Kind.ESCAPE, "\u001b");
                pending = new byte[0];
            }
            return;
        }
        // 只有长度>1 的未完整转义序列才走序列超时；孤立 ESC 永远走上面的挂起/宽限逻辑，
        // 不能因为 SSH 的 100ms Esc 期限大于 50ms 序列期限而被当成畸形序列提前冲刷。
        if (pending.length < 2 || pending[0] != ESC || now - lastByteAt < SEQUENCE_TIMEOUT_NANOS) return;
        // OSC/DCS/APC 回复可能很长，Pi 会一直等到终止符；不能按普通序列超时冲刷成正文。
        String raw = new String(pending, StandardCharsets.ISO_8859_1);
        if (isReplyHeader(raw)) return;
        // 畸形/超时的转义序列：整体作为一个 SEQUENCE 发出，让解码器明确忽略未知序列。
        emit(Kind.SEQUENCE, raw);
        rememberSwallow(raw, now);
        pending = new byte[0];
    }

    /** @return 是否识别为 OSC/DCS/APC 回复头（等待终止符，不按序列超时冲刷）。 */
    private static boolean isReplyHeader(String raw) {
        return raw.startsWith("\u001b]") || raw.startsWith("\u001bP")
                || raw.startsWith("\u001b_") || raw.startsWith("\u001b^");
    }

    /**
     * 记录不完整 CSI/SGR 前缀，供紧随其后的协议尾部吞掉。只对可识别的控制序列前缀生效；
     * 普通正文（例如用户真的输入 `[A`）不经过 ESC，因此不会被误吞。
     */
    private void rememberSwallow(String raw, long now) {
        String kind = null;
        if (raw.matches("\\u001b\\[<\\d+(;\\d+)*;?")) kind = "sgr";
        else if (raw.matches("\\u001b\\[[\\d;]*")) kind = "csi";
        else if (raw.equals("\u001bO")) kind = "ss3";
        swallowKind = kind;
        swallowUntilNanos = kind == null ? 0 : now + SWALLOW_TAIL_NANOS;
    }

    /**
     * 如果处于协议尾吞掉窗口，且当前 pending 正是所缺的尾部，就消费掉它。
     * @return true 表示本次 feed 已被吞掉、无需再走普通组装
     */
    private boolean consumeProtocolTail() {
        if (swallowKind == null) return false;
        long now = clock.getAsLong();
        if (now > swallowUntilNanos) { swallowKind = null; return false; }
        String text = new String(pending, StandardCharsets.ISO_8859_1);
        boolean matches = switch (swallowKind) {
            case "sgr" -> text.matches("[\\d;]*[Mm]?");
            case "csi" -> text.matches("[\\d;]*[A-Za-z~]?");
            case "ss3" -> text.length() <= 1;
            default -> false;
        };
        if (!matches) { swallowKind = null; return false; }
        boolean complete = switch (swallowKind) {
            case "sgr" -> text.endsWith("M") || text.endsWith("m");
            case "csi" -> text.matches("[\\d;]*[A-Za-z~]");
            case "ss3" -> text.length() == 1;
            default -> false;
        };
        pending = new byte[0];
        if (complete) swallowKind = null;
        return true;
    }

    /** @return 尚未组装完成的字节数；测试/诊断用，不暴露内部数组。 */
    public synchronized int pendingBytes() { return pending.length; }

    private void process(boolean flushed) {
        int index = 0;
        while (index < pending.length) {
            if (pasting) {
                int end = indexOf(pending, PASTE_END, index);
                if (end < 0) {
                    pasteBuffer.append(new String(pending, index, pending.length - index, StandardCharsets.UTF_8));
                    index = pending.length;
                    break;
                }
                pasteBuffer.append(new String(pending, index, end - index, StandardCharsets.UTF_8));
                emit(Kind.PASTE, pasteBuffer.toString());
                pasteBuffer.setLength(0);
                pasting = false;
                index = end + PASTE_END.length;
                continue;
            }
            int value = pending[index] & 0xff;
            if (value == (ESC & 0xff)) {
                int end = escapeEnd(pending, index);
                if (end < 0) break;
                if (startsWith(pending, index, PASTE_START)) {
                    pasting = true; pasteBuffer.setLength(0); index += PASTE_START.length; continue;
                }
                emit(Kind.SEQUENCE, new String(pending, index, end - index, StandardCharsets.ISO_8859_1));
                index = end;
                continue;
            }
            int start = index;
            while (index < pending.length && (pending[index] & 0xff) != (ESC & 0xff)) index++;
            int consumed = decodeText(pending, start, index);
            if (consumed < index) { index = consumed; break; }
        }
        pending = index >= pending.length ? new byte[0]
                : java.util.Arrays.copyOfRange(pending, index, pending.length);
    }

    /**
     * 解码一段普通字节并返回实际消费到的绝对下标。末尾不完整 UTF-8 序列不产生替换字符，
     * 由调用方保留到下一次 feed；这样拆开的 emoji/中文不会被插入两个乱码。
     */
    private int decodeText(byte[] bytes, int start, int end) {
        // 先截到最后一个完整 UTF-8 字符。CoderResult 在 endOfInput=true 时会把截断字符
        // 当 malformed 替换成 U+FFFD；那会把跨分片的中文/emoji 永久写坏，所以必须自己保留。
        int complete = completeUtf8End(bytes, start, end);
        if (complete <= start) return start;
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer input = ByteBuffer.wrap(bytes, start, complete - start);
        CharBuffer output = CharBuffer.allocate(Math.max(1, complete - start));
        CoderResult result = decoder.decode(input, output, true);
        output.flip();
        if (output.length() > 0) emit(Kind.TEXT, output.toString());
        int consumed = start + input.position();
        if (result.isError() && consumed == start) return Math.min(end, start + 1);
        return consumed;
    }

    /** @return 最后一个完整 UTF-8 字符之后的绝对下标；截断的 lead 字节留给下一次 feed。 */
    private static int completeUtf8End(byte[] bytes, int start, int end) {
        if (end <= start) return start;
        int from = Math.max(start, end - 3);
        int candidate = end - 1;
        // 先从末尾跳过 continuation 字节，找到真正的 lead；否则会把 0x80-0xBF 误当非法
        // lead 并立刻替换，跨分片的中文仍会坏掉。
        while (candidate >= from && (bytes[candidate] & 0xc0) == 0x80) candidate--;
        if (candidate < from) return end;
        int lead = bytes[candidate] & 0xff;
        int expected;
        if ((lead & 0x80) == 0) expected = 1;
        else if ((lead & 0xe0) == 0xc0) expected = 2;
        else if ((lead & 0xf0) == 0xe0) expected = 3;
        else if ((lead & 0xf8) == 0xf0) expected = 4;
        else return end; // 非法 lead：交给 decoder 替换，不能无限等待。
        return candidate + expected > end ? candidate : end;
    }

    private void emit(Kind kind, String text) {
        sink.accept(new Chunk(kind, text, ++sequence, clock.getAsLong()));
    }

    /**
     * @return 从 start（ESC）到序列结束的排他下标；不完整返回 -1。
     * 规则与固定 Pi 的 isCompleteSequence 一致：CSI 需要最终字节，OSC/DCS/APC 需要
     * BEL 或 ST；SS3/Meta 单字符即可。
     */
    private static int escapeEnd(byte[] data, int start) {
        int length = data.length;
        if (start + 1 >= length) return -1;
        int kind = data[start + 1] & 0xff;
        if (kind == '[') {
            if (start + 2 < length && (data[start + 2] & 0xff) == 'M') {
                return start + 6 <= length ? start + 6 : -1;
            }
            int i = start + 2;
            while (i < length) {
                int c = data[i] & 0xff;
                if (c >= 0x40 && c <= 0x7e) {
                    // SGR 鼠标必须以完整参数结束；否则等待剩余分片。
                    if (i > start + 2 && (data[start + 2] & 0xff) == '<'
                            && (c == 'M' || c == 'm')) {
                        String body = new String(data, start + 3, i - start - 3, StandardCharsets.ISO_8859_1);
                        if (!body.matches("\\d+;\\d+;\\d+")) return -1;
                    }
                    return i + 1;
                }
                i++;
            }
            return -1;
        }
        if (kind == ']' || kind == 'P' || kind == '_' || kind == '^' || kind == 'X') {
            int i = start + 2;
            while (i < length) {
                int c = data[i] & 0xff;
                if (c == 0x07) return i + 1;
                if (c == ESC && i + 1 < length && (data[i + 1] & 0xff) == '\\') return i + 2;
                i++;
            }
            return -1;
        }
        if (kind == 'O') return start + 3 <= length ? start + 3 : -1;
        // Meta/Alt + 单个字节；完整。
        return start + 2;
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (offset + prefix.length > data.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[offset + i] != prefix[i]) return false;
        return true;
    }

    private static int indexOf(byte[] data, byte[] needle, int from) {
        outer:
        for (int i = from; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        if (first.length == 0) return second;
        if (second.length == 0) return first;
        byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
