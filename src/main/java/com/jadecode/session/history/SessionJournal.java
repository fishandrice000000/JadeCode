package com.jadecode.session.history;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jadecode.messages.Message;
import com.jadecode.util.Json;

/**
 * 当前会话的唯一日志写者，拥有日志通道和 OS 会话锁，关闭时一并释放。
 *
 * <p>每条记录包含换行，并在允许下一步执行前 force 到本地文件。这样可恢复的事实
 * 不依赖 UI 是否已经显示文本。文件系统/硬件仍可能失效，工具副作用与日志也不是事务。
 * 清单只是列表索引，更新失败会告警；完整日志写入失败则锁定本写者，禁止继续追加。</p>
 */
public final class SessionJournal implements AutoCloseable {
    private final Path directory;
    private final FileChannel events, lockChannel;
    private final FileLock lock;
    private final Consumer<String> warnings;
    private final Runnable releaseLocal;
    private SessionMetadata metadata;
    private long sequence;
    private boolean closed, failed, indexWarning;

    /** 由 SessionStore 在取得写锁并校验日志后创建，不对外提供无锁写入。 */
    SessionJournal(Path directory, SessionMetadata metadata, long sequence, FileChannel events,
            FileChannel lockChannel, FileLock lock, Consumer<String> warnings, Runnable releaseLocal) {
        this.directory = directory; this.metadata = metadata; this.sequence = sequence;
        this.events = events; this.lockChannel = lockChannel; this.lock = lock; this.warnings = warnings;
        this.releaseLocal = releaseLocal;
    }

    public SessionMetadata metadata() { return metadata; }

    /**
     * 同步保存一次正式采用的变化。调用者必须保留已执行工具的结果，写盘失败不能据此重跑工具。
     * 任何写入失败后，本对象不再接受新记录，以免在半行后追加而破坏合法前缀。
     * @throws IOException 通道关闭、已经失败、序列化或落盘失败
     */
    public synchronized void append(JournalEntry.Kind kind, List<Message> messages, ArrayNode todos, String reason) throws IOException {
        if (closed || failed) throw new IOException("会话日志已关闭或发生过写入失败");
        try {
            String time = Instant.now().toString();
            var record = new JournalEntry(SessionMetadata.VERSION, metadata.id(), sequence + 1, time, kind, messages, todos, reason);
            ByteBuffer bytes = ByteBuffer.wrap((Json.mapper().writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8));
            if (events.position() + bytes.remaining() > 256_000_000)
                throw new IOException("会话日志即将超过当前 256 MB 恢复上限，请开启新会话");
            while (bytes.hasRemaining()) events.write(bytes);
            events.force(true);
            sequence++;
            String prompt = reason.equals("request") && !messages.isEmpty() ? messages.getFirst().extractText() : null;
            metadata = metadata.updated(prompt, time);
        } catch (IOException | RuntimeException error) {
            failed = true;
            throw new IOException("会话日志保存失败，后续操作已停止；已执行的工具不会回滚", error);
        }
        // 索引失败不否定刚才已可靠写出的日志，不得为了更新标题重试工具或主请求。
        try { saveMetadata(directory, metadata); }
        catch (IOException error) {
            if (!indexWarning) warnings.accept("会话列表索引未更新，日志仍已保存；可按 UUID 恢复。");
            indexWarning = true;
        }
    }

    /** 原子更新可重建的清单，不原地截断旧文件；临时文件也留在会话目录中。 */
    static void saveMetadata(Path directory, SessionMetadata metadata) throws IOException {
        Path temporary = Files.createTempFile(directory, "session-", ".tmp");
        IOException original = null;
        try {
            Files.writeString(temporary, Json.mapper().writeValueAsString(metadata), StandardCharsets.UTF_8);
            Files.move(temporary, directory.resolve("session.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            original = failure; throw failure;
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) { if (original != null) original.addSuppressed(cleanup); else throw cleanup; }
        }
    }

    /**
     * 关闭与 append 使用同一锁，最后一次写入结束前不会释放进程锁。
     * SessionRuntime 还会先禁止新的历史采用，避免迟到网络回复写入另一份会话。
     */
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            try { events.close(); }
            finally { try { lock.release(); } finally { lockChannel.close(); } }
        } finally { releaseLocal.run(); }
    }
}
