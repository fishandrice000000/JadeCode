package com.jadecode.cli.backend;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.llm.ResponseStream;
import com.jadecode.util.Json;

/**
 * 一次正式 Leader 请求的纯增量流：正文、思考、工具参数预览。
 *
 * <p>它实现现有 {@link ResponseStream}，所以 Agent/模型循环完全不用改；与旧
 * {@code StreamingAdapter} 的差别只在出口：这里发内部协议事件，不做任何 Markdown/ANSI/
 * 折行，也不保存转录。每个请求（含 retry）使用新对象，旧预览不能续写到新请求。</p>
 *
 * <p>offset 以 UTF-16 code unit 定义：Node 的 JSON 字符串同样是 UTF-16 语义，追加时不会
 * 因为代理对被网络分块切开而损坏。合并发生在 {@code EventOutbox}，本类只保证相邻性。</p>
 */
public final class BackendStream implements ResponseStream {
    private final WireContext wire;
    private final String actor;
    private String request = "";
    private int replyOffset;
    private int thinkingOffset;
    private boolean ended;

    /**
     * @param session   协议出口；事件最终由它分配 seq
     * @param actor     真实 Agent 身份（工厂绑定，不取自模型参数）
     */
    public BackendStream(WireContext wire, String actor) {
        this.wire = wire;
        this.actor = actor == null ? "leader" : actor;
    }

    @Override public synchronized void requestStarted(String requestId) {
        request = requestId == null || requestId.isBlank() ? "rq-" + System.nanoTime() : requestId;
        replyOffset = 0;
        thinkingOffset = 0;
        ended = false;
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request);
        payload.put("actor", actor);
        wire.session().sendEvent("stream.started", payload, wire.sessionId(), wire.generation());
    }

    @Override public synchronized void text(String text) {
        delta("REPLY", text);
    }

    @Override public synchronized void thinking(String text) {
        delta("THINKING", text);
    }

    private void delta(String channel, String text) {
        if (ended || text == null || text.isEmpty()) return;
        if (request.isEmpty()) requestStarted(null);
        int offset = "REPLY".equals(channel) ? replyOffset : thinkingOffset;
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request);
        payload.put("channel", channel);
        payload.put("offset", offset);
        payload.put("text", text);
        if ("REPLY".equals(channel)) replyOffset += text.length(); else thinkingOffset += text.length();
        wire.session().sendStreamEvent("stream.delta", payload, wire.sessionId(), wire.generation(),
                "delta:" + wire.generation() + ":" + request + ":" + channel);
    }

    @Override public synchronized void toolBlockStarted(int blockIndex, String callId, String name, String initialInputJson) {
        if (ended) return;
        if (request.isEmpty()) requestStarted(null);
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request);
        payload.put("blockIndex", blockIndex);
        payload.put("callId", callId == null ? "" : callId);
        payload.put("name", name == null ? "" : name);
        payload.put("initialInputJson", initialInputJson == null ? "" : initialInputJson);
        wire.session().sendEvent("stream.preview.started", payload, wire.sessionId(), wire.generation());
    }

    @Override public synchronized void toolInputDelta(int blockIndex, String fragment) {
        if (ended || fragment == null || fragment.isEmpty()) return;
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request);
        payload.put("blockIndex", blockIndex);
        payload.put("fragment", fragment);
        wire.session().sendStreamEvent("stream.preview.delta", payload, wire.sessionId(), wire.generation(),
                "preview:" + wire.generation() + ":" + request + ":" + blockIndex);
    }

    @Override public synchronized void toolBlockClosed(int blockIndex) {
        if (ended) return;
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request);
        payload.put("blockIndex", blockIndex);
        wire.session().sendEvent("stream.preview.closed", payload, wire.sessionId(), wire.generation());
    }

    @Override public synchronized void complete(String stopReason) {
        end(false, stopReason);
    }

    @Override public synchronized void complete() { end(false, ""); }

    @Override public synchronized void failed() { end(true, ""); }

    /** 结束事件不会越过已入队正文：它走同一 outbox，且不参与合并。 */
    private void end(boolean failed, String stopReason) {
        if (ended) return;
        ended = true;
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request);
        payload.put("failed", failed);
        payload.put("stopReason", stopReason == null ? "" : stopReason);
        wire.session().sendEvent("stream.ended", payload, wire.sessionId(), wire.generation());
    }
}
