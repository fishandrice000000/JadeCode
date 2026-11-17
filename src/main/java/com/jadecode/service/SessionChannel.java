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
    private ProtocolSession connection;
    private String connectionId;
    private long sequence;
    private long generation = -1;
    /** 新代次可能先收到装配提示；它不等于该代次的持久历史已安装。 */
    private long baselineGeneration = -1;
    private boolean syncing;
    private java.util.function.BiConsumer<String, ObjectNode> audit = (kind, payload) -> {
    };

    /**
     * 服务安装私有日志出口；只记录业务边界，不保存所有 token 增量。
     *
     * @param value 需要记录业务边界时调用的审计接收者
     */
    public synchronized void audit(java.util.function.BiConsumer<String, ObjectNode> value) {
        audit = value;
    }

    /**
     * 独占领取控制连接；返回 false 表示另一个客户端仍持有控制权。
     *
     * @param candidate 本次尝试绑定或解除的确切控制连接
     * @param id 事件携带的持久会话 ID；attach 时表示控制连接 ID，expectReady 时表示快照 ID
     * @return 当前没有控制连接且成功绑定 candidate 时为 true
     */
    public synchronized boolean attach(ProtocolSession candidate, String id) {
        if (connection != null)
            return false;
        connection = candidate;
        connectionId = id;
        syncing = true;
        return true;
    }

    /**
     * 只允许确切的旧连接释放自身，迟到 EOF 不能摘掉新连接。
     *
     * @param candidate 本次尝试绑定或解除的确切控制连接
     * @return candidate 恰好是当前控制连接并成功解除时为 true
     */
    public synchronized boolean detach(ProtocolSession candidate) {
        if (connection != candidate)
            return false;
        connection = null;
        syncing = false;
        return true;
    }

    /**
     * 当前是否有控制客户端；只供管理和闲置回收，不授予执行权限。
     *
     * @return 当前存在控制连接时为 true
     */
    public synchronized boolean connected() {
        return connection != null;
    }

    /**
     * 快照期间与事件使用同一把锁；基线仅在新会话代次安装一次。
     *
     * @param nextGeneration 即将安装完整历史基线的会话代次
     * @param baseline 提示词与材料、笔记共同计算的整理基线哈希
     * @return 当前代次的完整展示投影条目
     */
    public synchronized List<Entry> snapshot(long nextGeneration, List<Entry> baseline) {
        if (baselineGeneration != nextGeneration) {
            var entries = new ArrayList<>(baseline);
            // 装配期间的新提示保留在已保存历史之后；上一代显示不能带进来。
            if (generation == nextGeneration)
                entries.addAll(display.entries());
            generation = nextGeneration;
            baselineGeneration = nextGeneration;
            display.install(entries);
        }
        return display.entries();
    }

    /**
     * 快照已完整排入新连接后恢复增量；调用者必须在本监视器内同步发送整份快照。
     */
    public synchronized void synchronizedDisplay() {
        syncing = false;
    }

    /**
     * 在会话通道锁内采用事件到展示投影，再按当前同步状态交给控制连接，供普通业务事件使用。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param id 事件携带的持久会话 ID；attach 时表示控制连接 ID，expectReady 时表示快照 ID
     * @param gen 事件所属会话代次，供旧代次过滤和快照同步使用
     */
    @Override
    public synchronized void sendEvent(String type, ObjectNode payload, String id, Long gen) {
        adoptAndSend(type, payload, id, gen, null);
    }

    /**
     * 同一锁内先采用完整资料，再向连接投递；streamKey 只控制传输合并，不影响投影。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param id 事件携带的持久会话 ID；attach 时表示控制连接 ID，expectReady 时表示快照 ID
     * @param gen 事件所属会话代次，供旧代次过滤和快照同步使用
     * @param streamKey 传给连接出口的相邻增量合并键
     */
    private void adoptAndSend(String type, ObjectNode payload, String id, Long gen, String streamKey) {
        // 上一代的迟到通知不能清空当前显示或发给新连接。
        if (gen != null && gen < generation)
            return;
        sequence++;
        if (Set.of("task.submitted", "task.result", "task.failed", "approval.requested", "approval.removed")
                .contains(type)
                || type.equals("execution.notice")
                        && Set.of("TOOL_START", "TOOL_END").contains(payload.path("kind").asText()))
            audit.accept(type, payload);
        if (gen != null && generation != gen && !type.startsWith("session.snapshot")) {
            // 首次 open 的展示基线由 snapshot 安装；不能让上一代文字污染新会话。
            generation = gen;
            display.install(List.of());
        }
        // 投影直接采用完整业务资料；断连时也无需先分块、再组装自己的输出。
        display.accept(type, payload);
        // 同步阶段的运行事件已进入投影；它们不越过快照直接发到尚未 ready 的界面。
        if (connection != null && (!syncing || control(type))) {
            if (streamKey == null)
                connection.sendEvent(type, payload, id, gen);
            else
                connection.sendStreamEvent(type, payload, id, gen, streamKey);
        }
    }

    /**
     * 判断事件是否属于历史快照或状态快照，供同步期间选择立即发送的控制事件。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @return 类型属于完整历史快照或状态快照时为 true
     */
    private boolean control(String type) {
        return type.startsWith("session.snapshot") || type.equals("state.snapshot");
    }

    /**
     * 先将完整增量采用到服务展示投影，再把合并键交给当前连接的发送队列，供正文和工具参数增量传输使用。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @param p 本次事件或命令回执的完整 JSON 载荷
     * @param id 事件携带的持久会话 ID；attach 时表示控制连接 ID，expectReady 时表示快照 ID
     * @param gen 事件所属会话代次，供旧代次过滤和快照同步使用
     * @param key 当前请求与通道的增量合并键
     */
    @Override
    public synchronized void sendStreamEvent(String type, ObjectNode p, String id, Long gen, String key) {
        adoptAndSend(type, p, id, gen, key);
    }

    /**
     * 把命令结果交给当前控制连接。带连接前缀的命令 ID 先核对归属并剥去前缀；连接缺失或结果属于旧连接时直接返回。
     *
     * @param commandId 客户端命令 ID，供命令回执和重复请求查找使用
     * @param type 当前协议事件或命令结果的类型名
     * @param p 本次事件或命令回执的完整 JSON 载荷
     * @param id 事件携带的持久会话 ID；attach 时表示控制连接 ID，expectReady 时表示快照 ID
     * @param gen 事件所属会话代次，供旧代次过滤和快照同步使用
     */
    @Override
    public synchronized void sendResult(String commandId, String type, ObjectNode p, String id, Long gen) {
        if (connection == null)
            return;
        if (commandId != null && commandId.startsWith("@")) {
            String prefix = "@" + connectionId + "/";
            if (!commandId.startsWith(prefix))
                return;
            commandId = commandId.substring(prefix.length());
        }
        connection.sendResult(commandId, type, p, id, gen);
    }

    /**
     * 将成功载荷以 command.result 类型交给当前连接，沿用原命令 ID、会话和代次。
     *
     * @param commandId 客户端命令 ID，供命令回执和重复请求查找使用
     * @param p 本次事件或命令回执的完整 JSON 载荷
     * @param id 事件携带的持久会话 ID；attach 时表示控制连接 ID，expectReady 时表示快照 ID
     * @param gen 事件所属会话代次，供旧代次过滤和快照同步使用
     */
    @Override
    public void sendOk(String commandId, ObjectNode p, String id, Long gen) {
        sendResult(commandId, "command.result", p, id, gen);
    }

    /**
     * 将错误码、说明和可重试状态编码为 command.error，交给命令结果通道返回。
     *
     * @param commandId 客户端命令 ID，供命令回执和重复请求查找使用
     * @param error 本次协议错误类别，决定错误码和可重试标记
     * @param message 需要返回给调用者的结果或错误说明
     * @param id 事件携带的持久会话 ID；attach 时表示控制连接 ID，expectReady 时表示快照 ID
     * @param gen 事件所属会话代次，供旧代次过滤和快照同步使用
     */
    @Override
    public void sendError(String commandId, ProtocolError error, String message, String id, Long gen) {
        sendResult(commandId, "command.error", Json.mapper().createObjectNode().put("status", "error")
                .put("code", error.name()).put("message", message).put("retryable", error.recoverable()), id, gen);
    }

    /**
     * 返回会话通道已采用事件的来源序号，供快照记录展示投影对应的游标。
     *
     * @return 该会话通道已经采用的事件来源序号
     */
    @Override
    public synchronized long sentSeq() {
        return sequence;
    }

    /**
     * 当前存在控制连接时，登记该连接正在等待的快照 ID、代次和来源序号，供后续 ready 确认核对。
     *
     * @param id 事件携带的持久会话 ID；attach 时表示控制连接 ID，expectReady 时表示快照 ID
     * @param gen 事件所属会话代次，供旧代次过滤和快照同步使用
     * @param source 快照 begin 固定的展示来源序号
     */
    @Override
    public synchronized void expectReady(String id, Long gen, Long source) {
        if (connection != null)
            connection.expectReady(id, gen, source);
    }

    /**
     * 会话关闭只关闭当前连接；断连清理不会反向关闭会话。
     */
    @Override
    public synchronized void flushAndClose() {
        if (connection != null)
            connection.flushAndClose();
    }
}
