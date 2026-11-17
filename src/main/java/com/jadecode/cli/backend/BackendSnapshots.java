package com.jadecode.cli.backend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 把完整显示快照按卡片编码到会话出口，统一维护哈希与 ready 来源游标；
 * 常驻服务注入投影函数，读取当前显示，不执行日志中的任何工具。
 */
public final class BackendSnapshots implements BackendSessionService.SnapshotSink {
    private final WireContext wire;
    private final LongSupplier revision;
    private final UnaryOperator<List<BackendHistoryProjector.Entry>> projection;

    /**
     * 绑定会话事件出口、配置版本来源和历史投影函数，供完整快照统一生成与发送。
     *
     * @param wire 当前会话代次的事件出口与身份上下文
     * @param revision 本次操作依据的版本号，用于读取后修改时核对状态
     * @param projection 将持久基线转换为当前服务展示投影的函数
     */
    public BackendSnapshots(WireContext wire, LongSupplier revision,
            UnaryOperator<List<BackendHistoryProjector.Entry>> projection) {
        this.wire = wire;
        this.revision = revision;
        this.projection = projection;
    }

    /**
     * 在会话出口锁内取得当前展示投影和来源游标，登记 ready 等待，再按 begin、逐条历史 chunk、end 的顺序发送完整快照。
     * 每个条目编码为 JSON 并加入 SHA-256，end 携带总块数、哈希和运行状态，供客户端核验后安装。
     *
     * @param id 本次完整历史快照 ID
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     * @param baseline 从持久日志恢复的展示基线，交给投影函数补入服务实时状态
     * @param snapshot 本次读取或发送的完整状态快照
     * @throws IllegalStateException 当前 Java 运行环境无法提供 SHA-256 算法
     */
    @Override
    public void full(String id, long generation,
            List<BackendHistoryProjector.Entry> baseline, BackendSnapshot snapshot) {
        synchronized (wire.session()) {
            var entries = projection.apply(baseline);
            long cursor = wire.session().sentSeq();
            wire.session().expectReady(id, generation, cursor);
            wire.session().sendEvent("session.snapshot.begin", Json.mapper().createObjectNode()
                    .put("snapshotId", id).put("sessionId", wire.sessionId())
                    .put("generation", Long.toString(generation))
                    .put("entryCount", entries.size()).put("configRevision", Long.toString(revision.getAsLong()))
                    .put("sourceSeq", Long.toString(cursor)), wire.sessionId(), generation);
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                int index = 0;
                for (var entry : entries) {
                    // 先发送完整逻辑卡片；连接出口再按帧上限分块，显示投影无需处理 blob。
                    String encoded = Json.mapper().valueToTree(List.of(entry)).toString();
                    digest.update(encoded.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                    var chunk = Json.mapper().createObjectNode().put("snapshotId", id).put("chunkIndex", index++);
                    chunk.put("encodedEntries", encoded);
                    wire.session().sendEvent("session.snapshot.chunk", chunk, wire.sessionId(), generation);
                }
                var end = Json.mapper().createObjectNode().put("snapshotId", id).put("totalChunks", index)
                        .put("hash", HexFormat.of().formatHex(digest.digest()));
                put(end, snapshot);
                wire.session().sendEvent("session.snapshot.end", end, wire.sessionId(), generation);
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    /**
     * 将运行状态转换为 JSON，写入载荷的 snapshot 字段。
     *
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param snapshot 本次读取或发送的完整状态快照
     */
    private void put(ObjectNode payload, BackendSnapshot snapshot) {
        payload.set("snapshot", snapshot.toJson());
    }

    /**
     * 将当前运行状态放入 state.snapshot 事件，通过当前会话身份发送给前端。
     *
     * @param snapshot 本次读取或发送的完整状态快照
     */
    @Override
    public void state(BackendSnapshot snapshot) {
        var payload = Json.mapper().createObjectNode();
        put(payload, snapshot);
        wire.session().sendEvent("state.snapshot", payload, wire.sessionId(), wire.generation());
    }
}
