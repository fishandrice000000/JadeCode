package com.jadecode.service;

import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.backend.BackendHistoryProjector.Entry;
import com.jadecode.cli.backend.protocol.*;
import com.jadecode.util.Json;

/**
 * 常驻会话的展示出口：保存当前显示投影，把事件转交给当前控制连接。
 * 断连只移除订阅；运行时继续投递，重连从投影同步，不重放业务工具。
 * 所有投影和切换在同一监视器内完成；socket 写入由连接自己的有界 outbox 承担。
 */
public final class SessionChannel implements EventEndpoint {
    private final LiveDisplay display = new LiveDisplay();
    private final PayloadTransfer.Assembler assembler = new PayloadTransfer.Assembler();
    private final Map<String, String> blobs = new LinkedHashMap<>();
    private ProtocolSession connection;
    private String connectionId;
    private long sequence;
    private long generation = -1;
    private boolean syncing;
    private java.util.function.BiConsumer<String,ObjectNode> audit = (kind,payload) -> { };
    /** 服务安装私有日志出口；只记录业务边界，不保存所有 token 增量。 */
    public synchronized void audit(java.util.function.BiConsumer<String,ObjectNode> value) { audit = value; }

    /** 独占领取控制连接；返回 false 表示另一个客户端仍持有控制权。 */
    public synchronized boolean attach(ProtocolSession candidate, String id) {
        if (connection != null) return false;
        connection = candidate; connectionId = id; syncing = true; return true;
    }

    /** 只允许确切的旧连接释放自身，迟到 EOF 不能摘掉新连接。 */
    public synchronized boolean detach(ProtocolSession candidate) {
        if (connection != candidate) return false;
        connection = null; syncing = false; return true;
    }

    /** 当前是否有控制客户端；只供管理和闲置回收，不授予执行权限。 */
    public synchronized boolean connected() { return connection != null; }

    /** 快照期间与事件使用同一把锁；基线仅在新会话代次安装一次。 */
    public synchronized List<Entry> snapshot(long nextGeneration, List<Entry> baseline) {
        if (generation != nextGeneration) {
            generation = nextGeneration; display.install(baseline);
        }
        return display.entries();
    }

    /** 快照已完整排入新连接后恢复增量；调用者必须在本监视器内同步发送整份快照。 */
    public synchronized void synchronizedDisplay() { syncing = false; }

    @Override public synchronized void sendEvent(String type, ObjectNode payload, String id, Long gen) {
        sequence++;
        if (Set.of("task.submitted","task.result","task.failed","approval.requested","approval.removed").contains(type)
                || type.equals("execution.notice") && Set.of("TOOL_START","TOOL_END").contains(payload.path("kind").asText()))
            audit.accept(type, payload);
        if (gen != null && generation != gen && !type.startsWith("session.snapshot")) {
            // 首次 open 的展示基线由 snapshot 安装；不能让上一代文字污染新会话。
            generation = gen; display.install(List.of()); blobs.clear();
        }
        try {
            String blob = payload.path("id").asText();
            switch (type) {
                case "payload.begin" -> assembler.begin(blob, payload.path("kind").asText(),
                        payload.path("totalBytes").asInt(), payload.path("sha256").asText());
                case "payload.chunk" -> assembler.chunk(blob, payload.path("index").asInt(), payload.path("text").asText());
                case "payload.end" -> {
                    String value = assembler.end(blob, payload.path("count").asInt(), payload.path("sha256").asText());
                    // 大资料只用于紧随其后的事件；缓存数量有界，显示正文随后独立保存。
                    while (blobs.size() >= 32) blobs.remove(blobs.keySet().iterator().next());
                    blobs.put(blob, value);
                }
                default -> display.accept(type, payload, ref -> blobs.getOrDefault(ref, ""));
            }
        } catch (ProtocolException invalid) {
            throw new IllegalStateException("Invalid server payload", invalid);
        }
        // 同步阶段的运行事件已进入投影；它们不越过快照直接发到尚未 ready 的界面。
        if (connection != null && (!syncing || control(type)))
            connection.sendEvent(type, payload, id, gen);
    }

    private boolean control(String type) {
        return type.startsWith("session.snapshot") || type.startsWith("payload.") || type.equals("state.snapshot");
    }

    @Override public void sendStreamEvent(String type, ObjectNode p, String id, Long gen, String key) {
        sendEvent(type, p, id, gen);
    }
    @Override public synchronized void sendResult(String commandId, String type, ObjectNode p, String id, Long gen) {
        if (connection == null) return;
        if (commandId != null && commandId.startsWith("@")) {
            String prefix = "@"+connectionId+"/";
            if (!commandId.startsWith(prefix)) return;
            commandId = commandId.substring(prefix.length());
        }
        connection.sendResult(commandId, type, p, id, gen);
    }
    @Override public void sendOk(String commandId, ObjectNode p, String id, Long gen) {
        sendResult(commandId, "command.result", p, id, gen);
    }
    @Override public void sendError(String commandId, ProtocolError error, String message, String id, Long gen) {
        sendResult(commandId, "command.error", Json.mapper().createObjectNode().put("status", "error")
                .put("code", error.name()).put("message", message).put("retryable", error.recoverable()), id, gen);
    }
    @Override public synchronized long sentSeq() { return sequence; }
    @Override public synchronized void expectReady(String id, Long gen, Long source) {
        if (connection != null) connection.expectReady(id, gen, source);
    }
    /** 会话关闭只关闭当前连接；断连清理不会反向关闭会话。 */
    @Override public synchronized void flushAndClose() {
        if (connection != null) connection.flushAndClose();
    }
}
