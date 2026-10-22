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
    private volatile SessionMetadata metadata;
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
        write(new JournalEntry(SessionMetadata.VERSION, metadata.id(), sequence + 1, Instant.now().toString(), kind, messages, todos, reason));
    }

    /** 统计线程只写用量事实，不读取或修改会话消费者拥有的 Todo。 */
    public synchronized void usage(com.jadecode.llm.UsageRecord value) throws IOException {
        write(new JournalEntry(SessionMetadata.VERSION, metadata.id(), sequence + 1, Instant.now().toString(),
                JournalEntry.Kind.USAGE, List.of(), null, "usage", value, null, null));
    }

    /** 名称先可靠写入日志，再更新清单；即使索引更新失败，也能在下次恢复时找回名称。 */
    public synchronized void rename(String value) throws IOException {
        write(new JournalEntry(SessionMetadata.VERSION, metadata.id(), sequence + 1, Instant.now().toString(),
                JournalEntry.Kind.RENAMED, List.of(), null, "rename", null, value, null));
    }

    /** 只保存会话选择，不记录密钥，也不将更改伪装成用户提示词。 */
    public synchronized void thinking(com.jadecode.llm.ThinkingSettings value) throws IOException {
        write(new JournalEntry(SessionMetadata.VERSION, metadata.id(), sequence + 1, Instant.now().toString(),
                JournalEntry.Kind.THINKING, List.of(), null, "thinking", null, null, value));
    }

    /** 所有生产者经过同一把锁写完整行；只有历史事件才保存 Todo，避免队友覆盖主会话计划。 */
    private void write(JournalEntry record) throws IOException {
        if (closed || failed) throw new IOException("Session journal is closed or a write has failed");
        try {
            ByteBuffer bytes = ByteBuffer.wrap((Json.mapper().writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8));
            if (events.position() + bytes.remaining() > 256_000_000)
                throw new IOException("Session journal is approaching the 256 MB recovery limit; start a new session");
            while (bytes.hasRemaining()) events.write(bytes);
            events.force(true);
            sequence++;
            String prompt = record.reason().equals("request") && !record.messages().isEmpty() ? record.messages().getFirst().extractText() : null;
            SessionMetadata next = metadata.updated(prompt, record.time());
            if (record.kind() == JournalEntry.Kind.USAGE) next = next.withUsage(next.usage().add(record.usage().usage()));
            if (record.kind() == JournalEntry.Kind.RENAMED) next = next.renamed(record.title());
            if (record.kind() == JournalEntry.Kind.THINKING) next = next.withThinking(record.thinking());
            metadata = next;
        } catch (IOException | RuntimeException error) {
            failed = true;
            throw new IOException("Failed to save the session journal; later operations are stopped. Executed tools are not rolled back", error);
        }
        // 索引失败不否定刚才已可靠写出的日志，不得为了更新标题重试工具或主请求。
        try { saveMetadata(directory, metadata); }
        catch (IOException error) {
            if (!indexWarning) warnings.accept("Session list index was not updated, but the journal was saved; resume by UUID.");
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
