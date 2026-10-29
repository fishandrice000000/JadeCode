package com.jadecode.cli.backend.protocol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 内部协议 v1 的 UTF-8 / NDJSON 编解码与结构校验。
 *
 * <p>职责边界：本类只负责“字节 ↔ 一条消息”。它不执行命令、不拥有会话，也不知道 UI 语义。
 * 这样 socket 读取线程不会因为业务错误而卡住，业务层也能独立测试。</p>
 *
 * <p>分帧规则：一行一个 JSON 对象，以 {@code \n} 结束。read 可能把一帧拆成任意多段，也可能
 * 一次返回多帧，所以必须按字节累积到换行，不能假设“一次 read 就是一帧”。单帧上限
 * {@link #MAX_FRAME_BYTES}，超限明确失败而不是截断后继续。JSON 字符串里的原始换行一律被
 * 编码器转义，因此按 {@code \n} 切分不会把正文劈开。</p>
 */
public final class WireCodec {
    /** 协议版本；JAR 与前端必须一致。 */
    public static final int PROTOCOL_VERSION = 1;
    /** 单帧（含换行）的 UTF-8 字节上限。 */
    public static final int MAX_FRAME_BYTES = 1024 * 1024;

    /** Node → Java 命令类型白名单。 */
    public static final Set<String> COMMANDS = Set.of(
            "service.workspace", "service.cancel", "service.open", "service.list", "service.stop", "service.close", "client.hello", "session.ready", "session.submit", "session.promoteBuffer", "session.new",
            "session.list", "session.find", "session.resume", "session.rename", "session.thinking", "workspace.complete", "team.report",
            "settings.patch", "permission.select", "permission.prepareFullAccess", "permission.presented",
            "permission.cancelFullAccess", "permission.default", "approval.presented", "approval.answer",
            "terminal.suspend", "terminal.resume", "terminal.invalidate", "state.refresh", "session.stop", "session.close", "client.shutdown");

    /** Java → Node 事件类型白名单。 */
    public static final Set<String> EVENTS = Set.of(
            "service.opened", "service.result", "client.detached", "server.hello", "server.bootstrap", "session.snapshot.begin", "session.snapshot.chunk",
            "session.snapshot.end", "command.result", "command.error", "stream.started", "stream.delta",
            "stream.preview.started", "stream.preview.delta", "stream.preview.closed", "stream.ended",
            "execution.notice", "display.clock", "state.snapshot", "message", "task.submitted", "task.result",
            "task.failed", "approval.requested", "approval.removed", "settings.saved", "settings.failed",
            "diagnostic", "server.stopped", "payload.begin", "payload.chunk", "payload.end");

    private WireCodec() { }

    /**
     * 把一条消息编码成“JSON + LF”的 UTF-8 字节。
     *
     * @param message 待发送消息；调用方负责先递增 seq
     * @throws ProtocolException 编码后超过单帧上限
     */
    public static byte[] encode(WireMessage message) throws IOException, ProtocolException {
        ObjectNode envelope = Json.mapper().createObjectNode();
        envelope.put("version", message.version());
        envelope.put("connectionId", message.connectionId() == null ? "" : message.connectionId());
        envelope.put("seq", Long.toString(message.seq()));
        envelope.put("type", message.type());
        if (message.commandId() == null) envelope.putNull("commandId");
        else envelope.put("commandId", message.commandId());
        if (message.sessionId() == null) envelope.putNull("sessionId");
        else envelope.put("sessionId", message.sessionId());
        if (message.generation() == null) envelope.putNull("generation");
        else envelope.put("generation", Long.toString(message.generation()));
        envelope.set("payload", message.payload() == null ? Json.mapper().createObjectNode() : message.payload());
        String text = Json.mapper().writeValueAsString(envelope);
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (body.length + 1 > MAX_FRAME_BYTES)
            throw new ProtocolException(ProtocolError.PAYLOAD_TOO_LARGE,
                    "A single frame must stay under " + MAX_FRAME_BYTES + " UTF-8 bytes");
        byte[] frame = new byte[body.length + 1];
        System.arraycopy(body, 0, frame, 0, body.length);
        frame[body.length] = '\n';
        return frame;
    }

    /**
     * 从流中读取恰好一帧（不含换行）并解析成消息。
     *
     * @return 解析后的消息；流在帧开始时已结束则返回 null
     * @throws EOFException 读到半帧就遇到流结束
     */
    public static WireMessage readFrame(InputStream input) throws IOException, ProtocolException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        int next;
        boolean any = false;
        while ((next = input.read()) != -1) {
            any = true;
            if (next == '\n') return parse(decodeUtf8(buffer.toByteArray()));
            if (buffer.size() >= MAX_FRAME_BYTES)
                throw new ProtocolException(ProtocolError.PAYLOAD_TOO_LARGE,
                        "Incoming frame exceeded " + MAX_FRAME_BYTES + " bytes");
            buffer.write(next);
        }
        if (!any) return null;
        if (buffer.size() == 0) return null;
        throw new EOFException("Connection ended in the middle of a frame");
    }

    /**
     * 把一行 UTF-8 JSON 解析成经过白名单和字段校验的消息。
     *
     * <p>校验顺序：JSON 结构 → 版本 → 类型白名单 → envelope 字段类型。任何一步失败都抛
     * {@link ProtocolException}，且不会执行任何命令。</p>
     */
    public static WireMessage parse(String line) throws ProtocolException {
        JsonNode root;
        try {
            root = Json.mapper().readTree(line);
        } catch (JsonProcessingException invalid) {
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Frame is not valid JSON", invalid);
        }
        if (root == null || !root.isObject())
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Frame must be a JSON object");
        int version = root.path("version").asInt(-1);
        if (version != PROTOCOL_VERSION)
            throw new ProtocolException(ProtocolError.VERSION_MISMATCH,
                    "Protocol version " + root.path("version") + " is not supported by this backend");
        String type = text(root, "type", false);
        if (!COMMANDS.contains(type) && !EVENTS.contains(type))
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Unknown message type: " + type);
        String connectionId = text(root, "connectionId", true);
        long seq = longText(root, "seq", false);
        String commandId = text(root, "commandId", true);
        String sessionId = text(root, "sessionId", true);
        Long generation = root.path("generation").isNull() || !root.has("generation")
                ? null : longText(root, "generation", false);
        JsonNode payload = root.path("payload");
        if (!payload.isObject() && !payload.isMissingNode())
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "payload must be a JSON object");
        ObjectNode objectPayload = payload.isObject() ? (ObjectNode) payload : Json.mapper().createObjectNode();
        return new WireMessage(version, connectionId, seq, type, commandId, sessionId, generation, objectPayload);
    }

    /** 必填/可空文本字段；类型不对时明确失败，不做隐式字符串化。 */
    private static String text(JsonNode root, String field, boolean nullable) throws ProtocolException {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) {
            if (nullable) return null;
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, field + " is required");
        }
        if (!node.isTextual())
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, field + " must be a string");
        return node.textValue();
    }

    /** 十进制字符串形式的 Java long；JS 不会因此丢失精度。 */
    private static long longText(JsonNode root, String field, boolean nullable) throws ProtocolException {
        String value = text(root, field, nullable);
        if (value == null) throw new ProtocolException(ProtocolError.INVALID_MESSAGE, field + " is required");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, field + " must be a decimal integer string", invalid);
        }
    }

    /** 严格 UTF-8 解码：非法字节是协议错误，不能用替换字符掩盖。 */
    private static String decodeUtf8(byte[] bytes) throws ProtocolException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException invalid) {
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Frame is not valid UTF-8", invalid);
        }
    }
}
