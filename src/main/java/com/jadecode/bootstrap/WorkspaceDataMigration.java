package com.jadecode.bootstrap;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import com.jadecode.config.WorkspacePaths;

/**
 * 在创建任何存储和生产线程前，把旧版工作区数据移到 .jadecode。
 *
 * <p>只移动程序明确拥有的位置；不读 .env，不扫描其它工作区。先检查全部冲突和链接，
 * 再逐项移动，不覆盖目标、不修改历史正文。中途失败会留下已经移动的数据，下一次仅继续
 * 尚未移动的部分。必须先退出旧版 CLI，文件锁只协调使用此迁移器的新版本。</p>
 */
public final class WorkspaceDataMigration {
    private WorkspaceDataMigration() { }

    /**
     * 执行可重复的目录迁移；没有旧数据时不创建 .jadecode。
     * @throws IOException 冲突、链接、锁冲突或移动失败；不启动任何模型或后台任务
     */
    public static synchronized void migrate(WorkspacePaths paths) throws IOException {
        // 同 JVM 只开一条迁移锁通道，避免关闭重复通道时影响原有 OS 锁。
        var moves = new ArrayList<>(paths.legacyLocations().entrySet().stream()
                .filter(e -> Files.exists(e.getKey(), LinkOption.NOFOLLOW_LINKS)).toList());
        // 旧 cron 在异常退出后可能留下临时文件；只识别本程序的固定前缀，不处理其它 dot 文件。
        try (var leftovers = Files.newDirectoryStream(paths.workspace(), ".scheduled-tasks-*.tmp")) {
            for (Path file : leftovers) moves.add(java.util.Map.entry(file,
                    paths.cronFile().getParent().resolve("recovery").resolve(file.getFileName())));
        }
        if (moves.isEmpty()) { paths.directory(paths.root(), false); return; }
        paths.directory(paths.root(), true);
        Path lockPath = paths.root().resolve("migration.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Migration lock cannot be a symlink");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another process is migrating the workspace");
            // 全部检查通过才开始移动，避免一个明显冲突直到迁移一半才被发现。
            for (var move : moves) {
                paths.directory(move.getValue().getParent(), false);
                if (Files.exists(move.getValue(), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Old and new data exist together; resolve them manually and retry: " + move.getKey() + " / " + move.getValue());
                try (var tree = Files.walk(move.getKey())) {
                    if (tree.anyMatch(Files::isSymbolicLink))
                        throw new IOException("Old data contains symlinks and cannot be migrated safely: " + move.getKey());
                }
            }
            for (var move : moves) {
                paths.directory(move.getValue().getParent(), true);
                // 不使用 REPLACE_EXISTING。移动本身保留全部内容，出错不删除任何旧材料。
                Files.move(move.getKey(), move.getValue());
            }
            // 只删迁移后确实为空的旧容器，不递归删除任何未知文件。
            Path oldOutputs = paths.workspace().resolve(".task_outputs");
            if (Files.isDirectory(oldOutputs, LinkOption.NOFOLLOW_LINKS)) {
                try (var remaining = Files.list(oldOutputs)) {
                    if (remaining.findAny().isEmpty()) Files.delete(oldOutputs);
                }
            }
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            throw new IOException("Workspace migration is in progress", busy);
        }
    }
}
