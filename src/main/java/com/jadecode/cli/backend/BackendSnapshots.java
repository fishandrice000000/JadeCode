package com.jadecode.cli.backend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 把完整显示快照分块发到当前出口。旧本地后端和常驻服务共用哈希/ready 契约；
 * 常驻服务注入投影函数，读取当前显示，不执行日志中的任何工具。
 */
public final class BackendSnapshots implements BackendSessionService.SnapshotSink {
    private final WireContext wire;
    private final LongSupplier revision;
    private final UnaryOperator<List<BackendHistoryProjector.Entry>> projection;
    public BackendSnapshots(WireContext wire, LongSupplier revision,
            UnaryOperator<List<BackendHistoryProjector.Entry>> projection) {
        this.wire = wire; this.revision = revision; this.projection = projection;
    }
    /** 整份快照与运行事件共用出口锁，游标之后的事件不会插入块之间。 */
    @Override public void full(String id, long generation, long ignored,
            List<BackendHistoryProjector.Entry> baseline, BackendSnapshot snapshot) {
        synchronized (wire.session()) {
            var entries = projection.apply(baseline);
            long cursor = wire.session().sentSeq();
            wire.session().expectReady(id, generation, cursor);
            wire.session().sendEvent("session.snapshot.begin", Json.mapper().createObjectNode()
                    .put("snapshotId", id).put("sessionId",wire.sessionId()).put("generation",Long.toString(generation))
                    .put("entryCount",entries.size()).put("configRevision",Long.toString(revision.getAsLong()))
                    .put("sourceSeq",Long.toString(cursor)), wire.sessionId(), generation);
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                int index = 0;
                for (var entry : entries) {
                    // 单卡可以超过帧上限；大卡正文仍用 blob 承载，完整资料不截断。
                    String encoded = Json.mapper().valueToTree(List.of(entry)).toString();
                    digest.update(encoded.getBytes(StandardCharsets.UTF_8)); digest.update((byte) '\n');
                    var chunk = Json.mapper().createObjectNode().put("snapshotId",id).put("chunkIndex",index++);
                    if (encoded.getBytes(StandardCharsets.UTF_8).length > 128*1024)
                        chunk.put("entriesBlobRef",wire.payloads().sendText("snapshot-entries",encoded,wire.sessionId(),generation,"entries"));
                    else chunk.put("encodedEntries",encoded);
                    wire.session().sendEvent("session.snapshot.chunk",chunk,wire.sessionId(),generation);
                }
                var end = Json.mapper().createObjectNode().put("snapshotId",id).put("totalChunks",index)
                        .put("hash",HexFormat.of().formatHex(digest.digest()));
                put(end,snapshot); wire.session().sendEvent("session.snapshot.end",end,wire.sessionId(),generation);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    private void put(ObjectNode payload, BackendSnapshot snapshot) {
        var value = snapshot.toJson(); String text = value.toString();
        if (text.length()>32_000) payload.put("snapshotBlobRef",wire.payloads().sendText("state-snapshot",text,wire.sessionId(),wire.generation(),"state"));
        else payload.set("snapshot",value);
    }
    @Override public void state(BackendSnapshot snapshot) {
        var payload=Json.mapper().createObjectNode(); put(payload,snapshot);
        wire.session().sendEvent("state.snapshot",payload,wire.sessionId(),wire.generation());
    }
}
