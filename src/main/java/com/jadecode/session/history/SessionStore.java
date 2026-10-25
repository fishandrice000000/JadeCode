package com.jadecode.session.history;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.jadecode.config.WorkspacePaths;
import com.jadecode.util.Json;

/**
 * 当前工作区的会话目录：负责发现、文件身份和独占写锁，不请求模型、不修改工作区业务文件。
 * 清单用于快速列举，只有选定一个会话才解析完整日志。主会话才调用此存储，队友不列在选择器中。
 */
public final class SessionStore {
    /**
     * 在打开锁文件之前先做 JVM 内排他。某些系统关闭同文件的另一个通道也会释放进程原有锁，
     * 所以不能只依赖 tryLock 的 OverlappingFileLockException，再关闭第二个通道。
     */
    private static final java.util.Set<Path> OPEN = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final WorkspacePaths paths;
    private final Consumer<String> warnings;

    /** 一个打开的会话及其写者；采用到运行时后由运行时负责关闭 journal。 */
    public record Opened(SessionJournal journal, RestoredSession restored) implements AutoCloseable {
        @Override public void close() throws IOException { journal.close(); }
    }

    public SessionStore(WorkspacePaths paths, Consumer<String> warnings) { this.paths = paths; this.warnings = warnings; }

    /**
     * 名称仅用于查清单，不作为路径。优先精确名称；没有精确项时允许按部分标题筛选。
     * 重名全部返回，调用方必须让用户选择；这里不偷偷按最近更新时间取第一项。
     */
    public List<SessionMetadata> find(String name) throws IOException {
        String query = SessionMetadata.validTitle(name).toLowerCase(java.util.Locale.ROOT);
        var all = list();
        var exact = all.stream().filter(item -> item.title().toLowerCase(java.util.Locale.ROOT).equals(query)).toList();
        return exact.isEmpty() ? all.stream().filter(item -> item.title().toLowerCase(java.util.Locale.ROOT).contains(query)).toList() : exact;
    }

    /** 列表只读取轻量清单；坏清单报出文件位置，不把原始对话/凭据打印出来。 */
    public List<SessionMetadata> list() throws IOException {
        paths.directory(paths.sessions(), false);
        if (Files.notExists(paths.sessions())) return List.of();
        List<SessionMetadata> result = new ArrayList<>();
        try (var directories = Files.list(paths.sessions())) {
            for (Path directory : directories.toList()) {
                try {
                    SessionMetadata.requireId(directory.getFileName().toString());
                    SessionMetadata metadata = readMetadata(directory);
                    Path events = directory.resolve("events.jsonl"); regular(events, false);
                    if (Files.size(events) > 0) result.add(metadata);
                } catch (IOException | RuntimeException bad) { warnings.accept("Ignoring unreadable session manifest: " + directory.getFileName()); }
            }
        }
        result.sort(Comparator.comparing((SessionMetadata item) -> Instant.parse(item.updatedAt())).reversed());
        return List.copyOf(result);
    }

    /** 创建一个全新会话；这里只存模型名称，不保存 AppConfig 或认证。 */
    public Opened create(String model) throws IOException {
        String time = Instant.now().toString();
        var metadata = new SessionMetadata(SessionMetadata.VERSION, UUID.randomUUID().toString(), paths.workspace().toString(),
                "New session", time, time, model);
        Path directory = paths.sessions().resolve(metadata.id());
        paths.directory(paths.sessions(), true);
        Files.createDirectory(directory);
        // 本地会话含私有对话。当前支持 Linux，使用私有权限；不支持 POSIX 时保留平台默认。
        try { Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { }
        SessionJournal.saveMetadata(directory, metadata);
        Files.createFile(directory.resolve("events.jsonl"));
        return open(metadata.id());
    }

    /**
     * 锁定并加载原会话；只有校验通过才处理半行和写入修复记录。
     * 半行修复前保存完整原日志副本，继续追加绝不会接在残缺 JSON 后。
     * @throws IOException 被其它进程使用、目录/文件非法或历史损坏
     */
    public Opened open(String id) throws IOException {
        SessionMetadata.requireId(id);
        Path directory = paths.sessions().resolve(id);
        paths.directory(directory, false);
        SessionMetadata metadata = readMetadata(directory);
        Path lockFile = directory.resolve("session.lock"), eventFile = directory.resolve("events.jsonl");
        regular(lockFile, true); regular(eventFile, false);
        Path identity = directory.toRealPath();
        if (!OPEN.add(identity)) throw new IOException("This session is already open in this process");
        FileChannel lockChannel = null;
        FileLock lock = null;
        FileChannel events = null;
        SessionJournal journal = null;
        try {
            lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("This session is in use by another process");
            // 内存解析设清晰上限，避免坏文件撑爆 UI；更大的日志将来可改为流式读取。
            if (Files.size(eventFile) > 256_000_000) throw new IOException("Session journal exceeds 256 MB and cannot be loaded yet");
            RestoredSession restored = SessionLoader.load(metadata, Files.readAllBytes(eventFile));
            events = FileChannel.open(eventFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            if (restored.hasPartialTail()) {
                Files.copy(eventFile, directory.resolve("recovery-" + UUID.randomUUID() + ".jsonl"));
                events.truncate(restored.validBytes()); events.force(true);
            }
            events.position(events.size());
            journal = new SessionJournal(directory, restored.metadata(), restored.sequence(), events, lockChannel, lock, warnings,
                    () -> OPEN.remove(identity));
            if (restored.repairedHistory()) journal.append(JournalEntry.Kind.REPLACE_HISTORY, restored.history(), restored.todos(), "recovery");
            if (!metadata.equals(restored.metadata())) {
                try { SessionJournal.saveMetadata(directory, journal.metadata()); }
                catch (IOException error) { warnings.accept("Cannot update the index after rebuilding the manifest; resume by UUID is still possible."); }
            }
            return new Opened(journal, restored);
        } catch (IOException | RuntimeException error) {
            // 清理异常不能盖住首次损坏/锁定错误；每个资源仍须独立尝试关闭。
            if (journal != null) {
                // 已移交给 Journal 后由它关闭 IO 所有者/通道/锁；不能再移除可能已被新写者取得的 OPEN 项。
                try { journal.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            } else {
                if (events != null) try { events.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
                if (lock != null) try { lock.release(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
                if (lockChannel != null) try { lockChannel.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
                OPEN.remove(identity);
            }
            throw new IOException("Cannot resume session " + id + ": " + error.getMessage(), error);
        }
    }

    /** 清单必须属于当前工作区和对应 UUID 目录，不支持跨目录猜测恢复。 */
    private SessionMetadata readMetadata(Path directory) throws IOException {
        paths.directory(directory, false);
        Path file = directory.resolve("session.json"); regular(file, false);
        if (Files.size(file) > 16_384) throw new IOException("Session manifest is too large");
        SessionMetadata metadata = Json.mapper().readerFor(SessionMetadata.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue(Files.readAllBytes(file));
        if (!metadata.id().equals(directory.getFileName().toString()) || !metadata.workspace().equals(paths.workspace().toString()))
            throw new IOException("Session ID or workspace does not match the directory");
        return metadata;
    }

    /** 日志、清单和锁不跟随链接；缺锁文件可以新建，其它文件缺失就是错误。 */
    private static void regular(Path file, boolean mayBeMissing) throws IOException {
        if (mayBeMissing && Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Session file is not a regular non-symlink file: " + file.getFileName());
    }
}
