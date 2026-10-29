package com.jadecode.service;

import java.io.IOException;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 服务私有审计日志；与模型 history 分开记录提交/审批/工具边界，供崩溃后辨识未知结果。
 * 每条完整写入并 force，失败抛给调用者，不把未落盘的提交报告成已接受。
 * 不记录配置或认证。恢复只展示记录，永不执行日志里的命令。
 */
final class ServiceJournal implements AutoCloseable {
    private final Path path;
    private final java.util.concurrent.ExecutorService ioOwner = java.util.concurrent.Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("jade-service-journal").factory());
    private boolean failed;
    ServiceJournal(Path path) { this.path = path; }
    synchronized void append(String kind, ObjectNode payload) {
        if (failed) throw new IllegalStateException("Service journal is no longer writable");
        ObjectNode exact=payload.deepCopy();
        var completion=ioOwner.submit(()->write(kind,exact));
        // 工具取消不能中断写者并留下半行，再让下一条记录接到半行后面。
        // 与 SessionJournal 同样，等待者保留取消标记，写者只负责完成已提交的事实。
        boolean interrupted = Thread.interrupted();
        try {
            while(true) try { completion.get(); return; }
            catch(InterruptedException cancelled) { interrupted=true; }
            catch(java.util.concurrent.ExecutionException failure) {
                failed=true; throw new IllegalStateException("Service journal persistence failed",failure.getCause());
            }
        } finally { if(interrupted)Thread.currentThread().interrupt(); }
    }
    /** 专属 IO 线程完整追加并 force；失败之后账本锁定，不继续追加到未知的日志尾。 */
    private void write(String kind,ObjectNode payload) {
        try {
            var record = Json.mapper().createObjectNode().put("time",Instant.now().toString()).put("kind",kind);
            record.set("payload", payload.deepCopy());
            try (var file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,LinkOption.NOFOLLOW_LINKS)) {
                // 父目录由 JadePaths 创建为 0700，日志显式 0600，内容不可暴露给沙箱。
                Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                ByteBuffer bytes = java.nio.charset.StandardCharsets.UTF_8.encode(record + "\n");
                if (file.size()+bytes.remaining()>256L*1024*1024)
                    throw new IOException("Service audit limit reached");
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
        } catch (IOException failure) { throw new IllegalStateException("Service journal persistence failed", failure); }
    }
    /** 所属运行时停止后关闭写者；超时继续保留工作区占用，不声称清理完成。 */
    @Override public synchronized void close() throws IOException {
        ioOwner.shutdown();
        try {
            if(!ioOwner.awaitTermination(2,java.util.concurrent.TimeUnit.SECONDS))
                throw new IOException("Service audit writer has not stopped");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}
    }
}
