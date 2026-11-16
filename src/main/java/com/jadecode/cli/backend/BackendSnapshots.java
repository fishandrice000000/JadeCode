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

    public BackendSnapshots(WireContext wire, LongSupplier revision,
            UnaryOperator<List<BackendHistoryProjector.Entry>> projection) {
        this.wire = wire;
        this.revision = revision;
        this.projection = projection;
    }

    /** 整份快照与运行事件共用出口锁，游标之后的事件不会插入块之间。 */
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

    private void put(ObjectNode payload, BackendSnapshot snapshot) {
        payload.set("snapshot", snapshot.toJson());
    }

    @Override
    public void state(BackendSnapshot snapshot) {
        var payload = Json.mapper().createObjectNode();
        put(payload, snapshot);
        wire.session().sendEvent("state.snapshot", payload, wire.sessionId(), wire.generation());
    }
}
