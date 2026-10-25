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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 *
 * <p>模型/Workflow 线程可能被取消。NIO 会因调用线程中断而关闭 FileChannel，不能让这些线程
 * 直接操作共享通道。因此文件操作交给本会话独占的 I/O 线程，调用者同步等到提交结束；
 * 等待期间的中断在返回时恢复，不取消已提交的日志事实。此线程不执行模型或业务任务。</p>
 */
public final class SessionJournal implements AutoCloseable {
    private final Path directory;
    private final FileChannel events, lockChannel;
    private final FileLock lock;
    private final Consumer<String> warnings;
    private final Runnable releaseLocal;
    /** 公共方法持本对象锁直到 IO 完成，故至多一个文件操作在途，不积压另一份业务队列。 */
    private final ExecutorService ioOwner = Executors.newSingleThreadExecutor(Thread.ofPlatform()
            .name("jade-session-journal-io").daemon(true).inheritInheritableThreadLocals(false).factory());
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

    /** 目标状态只作为会话元数据持久化，不放进模型历史、不覆盖 Todo 或保存审批能力。 */
    public synchronized void goal(com.jadecode.goal.GoalState value) throws IOException {
        write(new JournalEntry(SessionMetadata.VERSION, metadata.id(), sequence + 1, Instant.now().toString(),
                JournalEntry.Kind.GOAL, List.of(), null, "goal", null, null, null, value));
    }

    /**
     * 所有生产者持同一把锁同步提交。调用线程中断不会触碰文件通道；磁盘/通道真实失败仍封住后续写入。
     * 清单告警回到调用线程处理，不能让 IO 线程执行可能回调会话的显示函数而形成跨线程锁等待。
     */
    private void write(JournalEntry record) throws IOException {
        if (closed || failed) throw new IOException("Session journal is closed or a write has failed");
        boolean warn;
        try { warn = onIo(() -> writeRecord(record)); }
        catch (IOException failure) { failed = true; throw failure; }
        if (warn) warnings.accept("Session list index was not updated, but the journal was saved; resume by UUID.");
    }

    /** 只由 ioOwner 操作通道、序号和清单；完成 future 后变更才对等待调用者可见。 */
    private boolean writeRecord(JournalEntry record) throws IOException {
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
            boolean warn = !indexWarning;
            indexWarning = true;
            return warn;
        }
        return false;
    }

    /**
     * 把一段文件提交交给不受分支取消影响的线程，并等待确定结果。
     * InterruptedException 只中断了等待，没有撤销已经发生的 IO；不能取消 future 或提前放开日志锁。
     * 无论成功/失败，都恢复调用者中断标志，让其后续执行边界照常响应取消。
     */
    private <T> T onIo(Callable<T> operation) throws IOException {
        Future<T> pending;
        try { pending = ioOwner.submit(operation); }
        catch (java.util.concurrent.RejectedExecutionException unavailable) { throw new IOException("Session journal IO is unavailable", unavailable); }
        boolean interrupted = false;
        try {
            while (true) {
                try { return pending.get(); }
                catch (InterruptedException cancelled) { interrupted = true; }
                catch (ExecutionException failure) {
                    if (failure.getCause() instanceof IOException io) throw io;
                    throw new IOException("Session journal IO failed", failure.getCause());
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
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
            // close 与写入同样由 IO 所有者执行；不能 shutdownNow 打断最后一次 force 或锁释放。
            onIo(() -> {
                try { events.close(); }
                finally { try { lock.release(); } finally { lockChannel.close(); } }
                return null;
            });
        } finally { ioOwner.shutdown(); releaseLocal.run(); }
    }
}
