package com.jadecode.cli.backend;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.llm.ResponseStream;
import com.jadecode.util.Json;

/**
 * 把一次模型响应的正文、思考和工具参数预览转换为前端事件。
 *
 * <p>每次请求固定独立 requestId，正文和思考分别按 UTF-16 字符单位累计 offset。
 * 相邻增量带合并键交给 EventOutbox，开始、结束与预览结构事件按原顺序发送。完整业务历史由会话采用模型回复时记录。
 * </p>
 *
 * @see com.jadecode.llm.ResponseStream
 * @see com.jadecode.cli.backend.protocol.EventOutbox
 */
public final class BackendStream implements ResponseStream {
    private final WireContext wire;
    private final String actor;
    private String request = "";
    private int replyOffset;
    private int thinkingOffset;
    private boolean ended;

    /**
     * 绑定本次模型请求使用的协议出口和 Agent 身份；actor 为 null 时使用 leader，
     * 调用 requestStarted 后开始发送本次请求的增量。
     *
     * @param wire 当前会话代次的事件出口
     * @param actor 产生回复的 Agent 身份
     */
    public BackendStream(WireContext wire, String actor) {
        this.wire = wire;
        this.actor = actor == null ? "leader" : actor;
    }

    /**
     * 登记本次模型请求 ID，重置正文和思考的字符偏移及结束标记，并发送 stream.started。请求 ID 为空时在本地生成。
     *
     * @param requestId 一次模型请求的观察 ID，用于区分不同响应流
     */
    @Override
    public synchronized void requestStarted(String requestId) {
        request = requestId == null || requestId.isBlank() ? "rq-" + System.nanoTime() : requestId;
        replyOffset = 0;
        thinkingOffset = 0;
        ended = false;
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request);
        payload.put("actor", actor);
        wire.session().sendEvent("stream.started", payload, wire.sessionId(), wire.generation());
    }

    /**
     * 将正文片段交给 REPLY 通道发送，偏移由 delta 按 Java 字符串长度累计。
     *
     * @param text 当前收到的正文或思考片段；null 和空串跳过
     */
    @Override
    public synchronized void text(String text) {
        delta("REPLY", text);
    }

    /**
     * 将思考片段交给 THINKING 通道发送，思考与正文分别累计偏移。
     *
     * @param text 当前收到的正文或思考片段；null 和空串跳过
     */
    @Override
    public synchronized void thinking(String text) {
        delta("THINKING", text);
    }

    /**
     * 为非空片段附加请求 ID、通道和起始偏移，推进该通道的 UTF-16 字符偏移，再投递可合并的 stream.delta 事件。
     * 首次片段会补发开始事件；本请求结束后停止接收片段。
     *
     * @param channel 正文 REPLY 或思考 THINKING 通道
     * @param text 当前收到的正文或思考片段；null 和空串跳过
     */
    private void delta(String channel, String text) {
        if (ended || text == null || text.isEmpty())
            return;
        if (request.isEmpty())
            requestStarted(null);
        int offset = "REPLY".equals(channel) ? replyOffset : thinkingOffset;
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request);
        payload.put("channel", channel);
        payload.put("offset", offset);
        payload.put("text", text);
        if ("REPLY".equals(channel))
            replyOffset += text.length();
        else
            thinkingOffset += text.length();
        wire.session().sendStreamEvent("stream.delta", payload, wire.sessionId(), wire.generation(),
                "delta:" + wire.generation() + ":" + request + ":" + channel);
    }

    /**
     * 发送工具参数预览的开始事件，携带内容块下标、调用 ID、工具名和初始 JSON；必要时先创建请求身份。
     * null 字符串转为空串，本请求结束后结束处理。
     *
     * @param blockIndex 工具调用在模型内容块中的下标，用于把参数片段追加到同一个预览块
     * @param callId 模型返回的工具调用 ID，用于关联工具结果
     * @param name 预览工具的名称；null 以空串编码
     * @param initialInputJson 工具块开始事件携带的初始 JSON，null 以空串处理
     */
    @Override
    public synchronized void toolBlockStarted(int blockIndex, String callId, String name, String initialInputJson) {
        if (ended)
            return;
        if (request.isEmpty())
            requestStarted(null);
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request);
        payload.put("blockIndex", blockIndex);
        payload.put("callId", callId == null ? "" : callId);
        payload.put("name", name == null ? "" : name);
        payload.put("initialInputJson", initialInputJson == null ? "" : initialInputJson);
        wire.session().sendEvent("stream.preview.started", payload, wire.sessionId(), wire.generation());
    }

    /**
     * 将工具参数 JSON 片段按请求 ID 和内容块下标发送为可合并预览事件；空片段或已结束请求直接返回。
     *
     * @param blockIndex 工具调用在模型内容块中的下标，用于把参数片段追加到同一个预览块
     * @param fragment 当前工具参数 JSON 的新增文本片段
     */
    @Override
    public synchronized void toolInputDelta(int blockIndex, String fragment) {
        if (ended || fragment == null || fragment.isEmpty())
            return;
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request);
        payload.put("blockIndex", blockIndex);
        payload.put("fragment", fragment);
        wire.session().sendStreamEvent("stream.preview.delta", payload, wire.sessionId(), wire.generation(),
                "preview:" + wire.generation() + ":" + request + ":" + blockIndex);
    }

    /**
     * 发送指定内容块的预览结束事件，使前端完成这一块工具参数的展示；本请求结束后直接返回。
     *
     * @param blockIndex 工具调用在模型内容块中的下标，用于把参数片段追加到同一个预览块
     */
    @Override
    public synchronized void toolBlockClosed(int blockIndex) {
        if (ended)
            return;
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request);
        payload.put("blockIndex", blockIndex);
        wire.session().sendEvent("stream.preview.closed", payload, wire.sessionId(), wire.generation());
    }

    /**
     * 以成功状态结束本次模型流，并将模型停止原因交给统一结束事件。
     *
     * @param stopReason 模型报告的停止原因，null 以空串编码
     */
    @Override
    public synchronized void complete(String stopReason) {
        end(false, stopReason);
    }

    /**
     * 行动回复失败仍可有真实停止原因；max_tokens 继续触发前端原有的未完成提示。
     *
     * @param stopReason 模型报告的停止原因，null 以空串编码
     */
    @Override
    public synchronized void failed(String stopReason) {
        end(true, stopReason);
    }

    /**
     * 结束事件不会越过已入队正文：它走同一 outbox，且不参与合并。
     *
     * @param failed 本次模型流是否以失败状态结束
     * @param stopReason 模型报告的停止原因，null 以空串编码
     */
    private void end(boolean failed, String stopReason) {
        if (ended)
            return;
        ended = true;
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request);
        payload.put("failed", failed);
        payload.put("stopReason", stopReason == null ? "" : stopReason);
        wire.session().sendEvent("stream.ended", payload, wire.sessionId(), wire.generation());
    }
}
