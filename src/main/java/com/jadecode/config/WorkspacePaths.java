package com.jadecode.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 描述一个工作区内 JadeCode 自己的数据目录，不改变工具执行命令时的工作目录。
 *
 * <p>工厂与各存储共用这份布局，避免每个模块重新拼接隐藏目录。构造和路径访问方法
 * 不创建文件；只有显式调用 {@link #directory(Path, boolean)} 才可能创建目录。
 * 机器级配置仍在用户 HOME 下，不属于这里。</p>
 */
public record WorkspacePaths(Path workspace) {
    /** 只规范化路径，不读配置、不访问模型，也不在构造时迁移数据。 */
    public WorkspacePaths { workspace = workspace.toAbsolutePath().normalize(); }

    public Path root() { return workspace.resolve(".jadecode"); }
    public Path sessions() { return root().resolve("sessions"); }
    public Path tasks() { return root().resolve("tasks"); }
    public Path memory() { return root().resolve("memory"); }
    public Path transcripts() { return root().resolve("transcripts"); }
    public Path toolResults() { return root().resolve("tool-results"); }
    public Path teams() { return root().resolve("teams"); }
    /** Workflow 的快照、成功步骤和输出归当前工作区，不使用机器级配置目录。 */
    public Path workflows() { return root().resolve("workflows"); }
    public Path approvalBackups() { return root().resolve("approvals/backups"); }
    public Path cronFile() { return root().resolve("cron/jobs.json"); }

    /**
     * 验证内部目录的每一级父目录，必要时逐级创建；拒绝软链和同名普通文件。
     * 新增 .jadecode 一层后只检查最内层目录是不够的，否则父目录链接仍可能逃出工作区。
     * 此检查不是对恶意并发替换路径的文件系统沙箱。
     *
     * @param directory 必须位于本布局 root 下的目录
     * @param create false 只检查已经存在的部分，缺目录不创建
     * @throws IOException 目录位置非法、存在链接/非目录或创建失败
     */
    public void directory(Path directory, boolean create) throws IOException {
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(root())) throw new IOException("Data directory is outside .jadecode: " + target);
        Path cursor = workspace;
        for (Path part : workspace.relativize(target)) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Data directory cannot be a symlink or regular file: " + cursor);
            } else if (create) {
                // 逐级创建，不让 createDirectories 偷偷跟随某个未检查过的父链接。
                try { Files.createDirectory(cursor); }
                catch (java.nio.file.FileAlreadyExistsException raced) {
                    if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw raced;
                }
            }
        }
    }

    /** 旧目录到新目录的固定映射；先列子目录，再列父目录，供迁移及只读旧引用兼容共用。 */
    public Map<Path, Path> legacyLocations() {
        Map<Path, Path> paths = new LinkedHashMap<>();
        paths.put(workspace.resolve(".team/approval-backups"), approvalBackups());
        paths.put(workspace.resolve(".tasks"), tasks());
        paths.put(workspace.resolve(".memory"), memory());
        paths.put(workspace.resolve(".team"), teams());
        paths.put(workspace.resolve(".transcripts"), transcripts());
        paths.put(workspace.resolve(".task_outputs/tool-results"), toolResults());
        paths.put(workspace.resolve(".scheduled_tasks.json"), cronFile());
        return paths;
    }

    /**
     * 只为已迁移数据提供旧路径读取兼容，不改变历史正文，也不把写入重定向到另一个文件。
     * 原位置仍有对象时不替换；新位置的父目录和最终真实位置均须留在内部目录中。
     * @return 原路径，或确实存在且通过检查的新路径；无 IO 副作用
     */
    public Path legacyRead(Path original) throws IOException {
        Path absolute = original.toAbsolutePath().normalize();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return original;
        for (var entry : legacyLocations().entrySet()) {
            if (!absolute.startsWith(entry.getKey())) continue;
            Path target = entry.getValue().resolve(entry.getKey().relativize(absolute));
            directory(target.getParent(), false);
            if (Files.exists(target)) {
                Path real = target.toRealPath();
                if (!real.startsWith(root().toRealPath())) throw new IOException("Migration reference escapes the data directory");
                return real;
            }
            break;
        }
        return original;
    }
}
