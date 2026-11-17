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
 *
 * @param root 用户数据根目录，构造时转为规范化绝对路径
 */
public record JadePaths(Path root) {
    /**
     * 将显式数据根转为规范化的绝对路径，作为配置、缓存、记忆和工作区分区的共同起点。
     *
     * @param root 用户数据根目录，构造时转为规范化绝对路径
     */
    public JadePaths {
        root = root.toAbsolutePath().normalize();
    }

    /**
     * 使用当前用户主目录下的 .jadecode 创建默认数据路径布局，返回路径对象。
     *
     * @return 使用当前用户 .jadecode 数据根的布局
     */
    public static JadePaths defaults() {
        return new JadePaths(Path.of(System.getProperty("user.home"), ".jadecode"));
    }

    /**
     * 返回全局配置目录 root/config，供模型和应用设置定位使用。
     *
     * @return 全局配置目录路径
     */
    public Path config() {
        return root.resolve("config");
    }

    /**
     * 返回共享缓存目录 root/cache，供数据布局使用。
     *
     * @return 用户数据根下的共享缓存目录
     */
    public Path cache() {
        return root.resolve("cache");
    }

    /**
     * 返回全局长期记忆目录 root/memory，提取材料、待处理作业和正式知识统一存放在此。
     *
     * @return 全局正式知识、材料与作业共同使用的记忆根
     */
    public Path memory() {
        return root.resolve("memory");
    }

    /**
     * 返回工作区数据分区的父目录 root/workspaces，各真实工作区通过自己的哈希 ID 选择子目录。
     *
     * @return 所有真实工作区数据分区的父目录
     */
    public Path workspaces() {
        return root.resolve("workspaces");
    }

    /**
     * 返回 Fetch MCP 使用的独立 Python 环境目录 root/mcp/fetch。
     *
     * @return Fetch MCP 的独立 Python 环境目录
     */
    public Path fetchEnvironment() {
        return root.resolve("mcp/fetch");
    }

    /**
     * 对传入真实工作区路径字符串计算 UTF-8 SHA-256，返回稳定分区 ID，供不同 checkout 和 worktree 按路径分别存储数据。
     *
     * @param workspace 会话绑定的真实工作区目录
     * @return 工作区路径字符串的 64 位小写 SHA-256 分区 ID
     * @throws IllegalStateException 当前 Java 运行环境无法提供 SHA-256 算法
     */
    public static String identity(Path workspace) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(workspace.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 将目标规范化并限定在数据根下，从文件系统根逐级核对普通目录；create 为 true 时按私有权限创建缺失级。
     * 目录类型错误或创建失败抛出 IOException，create 为 false 时跳过缺失级的创建。
     *
     * @param directory 需要逐级检查、创建或刷新目录项的目标目录
     * @param create 是否为缺失路径创建目录
     * @throws IOException 目标越出数据根、路径级为链接或非目录，或目录创建失败
     */
    public void directory(Path directory, boolean create) throws IOException {
        // 先消除 .. 等词法跳转，再限定到调用方注入的用户根。
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(root))
            throw new IOException("Data directory is outside the JadeCode root");
        // 从文件系统根开始检查，避免最外层用户数据目录本身就是链接。
        Path cursor = target.getRoot();
        for (Path part : target) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Data directory contains a symlink or file");
            } else if (create) {
                try {
                    try {
                        Files.createDirectory(cursor,
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    } catch (UnsupportedOperationException unsupported) {
                        Files.createDirectory(cursor);
                    }
                } catch (FileAlreadyExistsException raced) {
                    if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
                        throw raced;
                }
            }
        }
    }

    /**
     * 写完整临时文件、force 后原子替换，再刷新目录；调用方负责锁与冲突策略。
     *
     * @param target 本次操作的目标绝对路径
     * @param text 本次处理的完整文本，按该入口执行编码、脱敏、显示或组装
     * @throws IOException 目标类型不合法，或临时文件写入、刷新、原子替换与目录刷新失败
     */
    public static void atomicWrite(Path target, String text) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Data target is not a regular file");
        // 同目录临时文件保证最终 rename 不跨文件系统；读者只会看到完整旧版或新版。
        Path temporary = Files.createTempFile(target.getParent(), ".jade-write-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var data = StandardCharsets.UTF_8.encode(text);
                while (data.hasRemaining())
                    channel.write(data);
                channel.force(true);
            }
            // 正文先 force，再发布文件名并刷新目录项；失败不返回虚假的成功。
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
