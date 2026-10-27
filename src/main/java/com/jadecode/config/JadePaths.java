package com.jadecode.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * JadeCode 用户数据根，与命令工作目录分开。生产使用 ~/.jadecode，测试显式注入临时根。
 * 路径访问不创建文件；写入只使用受检查目录及原子替换，不访问模型或读取认证。
 */
public record JadePaths(Path root) {
    public JadePaths { root = root.toAbsolutePath().normalize(); }
    public static JadePaths defaults() { return new JadePaths(Path.of(System.getProperty("user.home"), ".jadecode")); }
    public Path config() { return root.resolve("config"); }
    public Path cache() { return root.resolve("cache"); }
    public Path memory() { return root.resolve("memory"); }
    public Path workspaces() { return root.resolve("workspaces"); }
    public Path fetchEnvironment() { return root.resolve("mcp/fetch"); }

    /** 真实项目路径的 UTF-8 哈希用于隔离 checkout/worktree，不表示跨设备项目身份。 */
    public static String identity(Path workspace) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(workspace.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /**
     * 从文件系统根逐级检查，拒绝任何符号链接或同名文件。create=false 不创建缺失部分。
     * 新目录使用私有权限；这是应用内约束，不是对恶意并发替换目录的系统沙箱。
     */
    public void directory(Path directory, boolean create) throws IOException {
        // 先消除 .. 等词法跳转，再限定到调用方注入的用户根。
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(root)) throw new IOException("Data directory is outside the JadeCode root");
        // 从文件系统根开始检查，避免最外层用户数据目录本身就是链接。
        Path cursor = target.getRoot();
        for (Path part : target) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Data directory contains a symlink or file");
            } else if (create) {
                try {
                    try { Files.createDirectory(cursor, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
                    catch (UnsupportedOperationException unsupported) { Files.createDirectory(cursor); }
                } catch (FileAlreadyExistsException raced) {
                    if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw raced;
                }
            }
        }
    }

    /** 写完整临时文件、force 后原子替换，再刷新目录；调用方负责锁与冲突策略。 */
    public static void atomicWrite(Path target, String text) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Data target is not a regular file");
        // 同目录临时文件保证最终 rename 不跨文件系统；读者只会看到完整旧版或新版。
        Path temporary = Files.createTempFile(target.getParent(), ".jade-write-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var data = StandardCharsets.UTF_8.encode(text);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            // 正文先 force，再发布文件名并刷新目录项；失败不返回虚假的成功。
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
