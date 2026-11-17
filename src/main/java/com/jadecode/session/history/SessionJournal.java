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
 * 保存当前会话已经采用的消息、轮次、用量和元数据。
 *
 * <p>独占日志通道及会话文件锁，每条事件按递增序号写入 JSON 行并刷新后返回。
 * 文件写入交给专属 IO 线程，调用线程同步等待，等待中的中断在返回时恢复。
 * 完整日志失败后写者停止接收新记录；会话清单索引更新失败以告警报告。</p>
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

    /**
     * 由 SessionStore 在取得写锁并校验日志后创建，不对外提供无锁写入。
     *
     * @param directory 当前会话已取得独占写者锁的持久目录
     * @param metadata 打开会话时已经核验的元数据
     * @param sequence 当前日志最后一个已经落盘的事件序号
     * @param events 会话 events.jsonl 的写入通道
     * @param lockChannel 持有会话文件锁的通道
     * @param lock 本会话独占的操作系统文件锁
     * @param warnings 接收文件或维护告警的文本出口
     * @param releaseLocal 日志关闭后释放 JVM 内会话占用的回调
     */
    SessionJournal(Path directory, SessionMetadata metadata, long sequence, FileChannel events,
            FileChannel lockChannel, FileLock lock, Consumer<String> warnings, Runnable releaseLocal) {
        this.directory = directory;
        this.metadata = metadata;
        this.sequence = sequence;
        this.events = events;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.warnings = warnings;
        this.releaseLocal = releaseLocal;
    }

    /**
     * 返回当前日志持有的会话元数据，供会话身份、标题和更新时间查询使用。
     *
     * @return 此日志当前已采用的会话元数据
     */
    public SessionMetadata metadata() {
        return metadata;
    }

    /**
     * 返回当前会话的 events.jsonl 路径，供记忆提取按固定事件区间读取已持久化证据。
     *
     * @return 此会话的 events.jsonl 路径
     */
    public Path eventsPath() {
        return directory.resolve("events.jsonl");
    }

    /**
     * 与 append 共用锁，返回已经 force 成功的事件序号，作为任务证据的闭区间终点。
     *
     * @return 最后一个已经写入并刷新成功的事件序号
     */
    public synchronized long sequence() {
        return sequence;
    }

    /**
     * 以当前会话、下一序号和当前时间构造一条正式事件，保存消息、Todo 与原因，并同步等待日志写入刷新。
     * 通道关闭、写者已失败或 IO 错误时抛出 IOException。
     *
     * @param kind 要保存的持久会话事件种类
     * @param messages 本次读取或采用的模型消息列表
     * @param todos 与当前历史一起保存的 Todo 状态
     * @param reason 当前事件来源或失败的业务原因
     * @throws IOException 日志写者不可用或本条事件序列化、写入刷新失败
     */
    public synchronized void append(JournalEntry.Kind kind, List<Message> messages, ArrayNode todos, String reason)
            throws IOException {
        write(new JournalEntry(SessionMetadata.VERSION, metadata.id(), sequence + 1, Instant.now().toString(), kind,
                messages, todos, reason, null, null, null, null, null));
    }

    /**
     * 在正式事件中同时保存 runId，将消息和状态变化关联到真实轮次；构造下一序号后交给统一日志写入刷新流程，
     * IO 失败向上传递。
     *
     * @param kind 要保存的持久会话事件种类
     * @param messages 本次读取或采用的模型消息列表
     * @param todos 与当前历史一起保存的 Todo 状态
     * @param reason 当前事件来源或失败的业务原因
     * @param runId 当前轮次 ID，用于关联取消、后台命令和任务收尾
     * @throws IOException 日志写者不可用或本条事件序列化、写入刷新失败
     */
    public synchronized void append(JournalEntry.Kind kind, List<Message> messages, ArrayNode todos,
            String reason, String runId) throws IOException {
        write(new JournalEntry(SessionMetadata.VERSION, metadata.id(), sequence + 1, Instant.now().toString(),
                kind, messages, todos, reason, null, null, null, null, runId));
    }

    /**
     * 统计线程只写用量事实，不读取或修改会话消费者拥有的 Todo。
     *
     * @param value 本次模型请求的用量事实及用途、Agent 归属
     * @throws IOException 用量事件写入失败
     */
    public synchronized void usage(com.jadecode.llm.UsageRecord value) throws IOException {
        write(new JournalEntry(SessionMetadata.VERSION, metadata.id(), sequence + 1, Instant.now().toString(),
                JournalEntry.Kind.USAGE, List.of(), null, "usage", value, null, null, null, null));
    }

    /**
     * 名称先可靠写入日志，再更新清单；即使索引更新失败，也能在下次恢复时找回名称。
     *
     * @param value 用户选择的新会话标题
     * @throws IOException 会话改名事件写入失败
     */
    public synchronized void rename(String value) throws IOException {
        write(new JournalEntry(SessionMetadata.VERSION, metadata.id(), sequence + 1, Instant.now().toString(),
                JournalEntry.Kind.RENAMED, List.of(), null, "rename", null, value, null, null, null));
    }

    /**
     * 只保存会话选择，不记录密钥，也不将更改伪装成用户提示词。
     *
     * @param value 后续请求应读取的思考档位
     * @throws IOException 思考档位事件写入失败
     */
    public synchronized void thinking(com.jadecode.llm.ThinkingSettings value) throws IOException {
        write(new JournalEntry(SessionMetadata.VERSION, metadata.id(), sequence + 1, Instant.now().toString(),
                JournalEntry.Kind.THINKING, List.of(), null, "thinking", null, null, value, null, null));
    }

    /**
     * 目标状态只作为会话元数据持久化，不放进模型历史、不覆盖 Todo 或保存审批能力。
     *
     * @param value 本次需要持久保存的目标状态
     * @throws IOException 目标状态事件写入失败
     */
    public synchronized void goal(com.jadecode.goal.GoalState value) throws IOException {
        write(new JournalEntry(SessionMetadata.VERSION, metadata.id(), sequence + 1, Instant.now().toString(),
                JournalEntry.Kind.GOAL, List.of(), null, "goal", null, null, null, value, null));
    }

    /**
     * 所有生产者持同一把锁同步提交。调用线程中断不会触碰文件通道；磁盘/通道真实失败仍封住后续写入。
     * 清单告警回到调用线程处理，不能让 IO 线程执行可能回调会话的显示函数而形成跨线程锁等待。
     *
     * @param record 本次需要同步保存的完整会话事件
     * @throws IOException 专属 IO 线程不可用或本次事件保存失败
     */
    private void write(JournalEntry record) throws IOException {
        if (closed || failed)
            throw new IOException("Session journal is closed or a write has failed");
        boolean warn;
        try {
            warn = onIo(() -> writeRecord(record));
        } catch (IOException failure) {
            failed = true;
            throw failure;
        }
        if (warn)
            warnings.accept("Session list index was not updated, but the journal was saved; resume by UUID.");
    }

    /**
     * 在日志专属 IO 线程提交一条正式事件，并更新可重建的会话索引。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>核对日志可写，把完整 JSON 行写入 events.jsonl 并刷新，随后推进 sequence。</li>
     * <li>按 request、USAGE、RENAMED、THINKING 等事件更新本地元数据，正文提交失败时封住写者并抛出 IOException。</li>
     * <li>尝试原子保存 session.json；索引首次失败返回 true，通知调用方发出一次索引告警，后续同类失败返回 false。</li>
     * </ol>
     *
     * <p>返回值用于索引告警，日志正文成功提交与索引是否更新分别记录。</p>
     *
     * @param record 本次需要同步保存的完整会话事件
     * @return 日志正文提交成功后，元数据索引首次保存失败、需要发出一次告警时为 true；其余成功提交为 false
     * @throws IOException 写者关闭或已失败，事件序列化、日志写入或刷新失败
     */
    private boolean writeRecord(JournalEntry record) throws IOException {
        try {
            ByteBuffer bytes = ByteBuffer
                    .wrap((Json.mapper().writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8));
            if (events.position() + bytes.remaining() > 256_000_000)
                throw new IOException("Session journal is approaching the 256 MB recovery limit; start a new session");
            while (bytes.hasRemaining())
                events.write(bytes);
            events.force(true);
            sequence++;
            String prompt = record.reason().equals("request") && !record.messages().isEmpty()
                    ? record.messages().getFirst().extractText()
                    : null;
            SessionMetadata next = metadata.updated(prompt, record.time());
            if (record.kind() == JournalEntry.Kind.USAGE)
                next = next.withUsage(next.usage().add(record.usage().usage()));
            if (record.kind() == JournalEntry.Kind.RENAMED)
                next = next.renamed(record.title());
            if (record.kind() == JournalEntry.Kind.THINKING)
                next = next.withThinking(record.thinking());
            metadata = next;
        } catch (IOException | RuntimeException error) {
            failed = true;
            throw new IOException(
                    "Failed to save the session journal; later operations are stopped. Executed tools are not rolled back",
                    error);
        }
        // 索引失败不否定刚才已可靠写出的日志，不得为了更新标题重试工具或主请求。
        try {
            saveMetadata(directory, metadata);
        } catch (IOException error) {
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
     *
     * @param <T> 本次操作返回值的类型
     * @param operation 本次提交给日志独占 IO 线程的文件操作
     * @return 文件操作在专属 IO 线程完成后取得的结果
     * @throws IOException 专属 IO 线程拒绝任务，或操作以 IO、运行时故障结束
     */
    private <T> T onIo(Callable<T> operation) throws IOException {
        Future<T> pending;
        try {
            pending = ioOwner.submit(operation);
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            throw new IOException("Session journal IO is unavailable", unavailable);
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return pending.get();
                } catch (InterruptedException cancelled) {
                    interrupted = true;
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof IOException io)
                        throw io;
                    throw new IOException("Session journal IO failed", failure.getCause());
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    /**
     * 原子更新可重建的清单，不原地截断旧文件；临时文件也留在会话目录中。
     *
     * @param directory 当前会话已取得独占写者锁的持久目录
     * @param metadata 打开会话时已经核验的元数据
     * @throws IOException 元数据序列化、临时文件写入、原子替换或临时文件清理失败
     */
    static void saveMetadata(Path directory, SessionMetadata metadata) throws IOException {
        Path temporary = Files.createTempFile(directory, "session-", ".tmp");
        IOException original = null;
        try {
            Files.writeString(temporary, Json.mapper().writeValueAsString(metadata), StandardCharsets.UTF_8);
            Files.move(temporary, directory.resolve("session.json"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            original = failure;
            throw failure;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                if (original != null)
                    original.addSuppressed(cleanup);
                else
                    throw cleanup;
            }
        }
    }

    /**
     * 关闭与 append 使用同一锁，最后一次写入结束前不会释放进程锁。
     * SessionRuntime 还会先禁止新的历史采用，避免迟到网络回复写入另一份会话。
     *
     * @throws IOException 日志或锁通道刷新关闭失败
     */
    @Override
    public synchronized void close() throws IOException {
        if (closed)
            return;
        closed = true;
        try {
            // close 与写入同样由 IO 所有者执行；不能 shutdownNow 打断最后一次 force 或锁释放。
            onIo(() -> {
                try {
                    events.close();
                } finally {
                    try {
                        lock.release();
                    } finally {
                        lockChannel.close();
                    }
                }
                return null;
            });
        } finally {
            ioOwner.shutdown();
            releaseLocal.run();
        }
    }
}
