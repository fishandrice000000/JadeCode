package com.jadecode.memory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 协调同一记忆库的短期文件读写。先取得 JVM 内普通锁，再取得 .store.lock 文件锁，
 * 供同进程线程和多个 JadeCode 进程依次读取快照、写作业或发布知识。
 */
final class MemoryLibraryLock {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL = new ConcurrentHashMap<>();

    /**
     * 一次在记忆库短锁内完成的文件读写操作，可以返回结果并向调用方报告 IOException。
     *
     * @param <T> 本次操作返回值的类型
     */
    @FunctionalInterface
    interface Operation<T> {
        /**
         * 执行本次记忆库读写并返回结果；文件操作失败以 IOException 交给取得库锁的调用方处理。
         *
         * @return 本次库内文件操作取得的结果
         * @throws IOException 本次库内文件操作失败
         */
        T run() throws IOException;
    }

    /**
     * 在指定库根的 JVM 锁和文件锁内执行一次文件操作，返回 operation 的结果，结束时释放两层锁。
     * 等待本地锁被中断时恢复中断标记并抛出 InterruptedIOException；文件或操作异常向上传递。
     * 维护模型请求由外层维护锁协调。
     *
     * @param <T> 本次操作返回值的类型
     * @param root 经过目录核验的记忆库根，用于定位 .store.lock
     * @param operation 在已取得记忆库短锁后执行的本地读写操作
     * @return operation 在两层锁内执行后返回的结果
     * @throws IOException 等待锁被中断、锁文件无法打开或 operation 文件操作失败
     */
    static <T> T run(Path root, Operation<T> operation) throws IOException {
        ReentrantLock local = LOCAL.computeIfAbsent(root, ignored -> new ReentrantLock());
        try {
            local.lockInterruptibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Interrupted while waiting for the memory library operation");
        }
        try (var channel = FileChannel.open(root.resolve(".store.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.lock()) {
            return operation.run();
        } finally {
            local.unlock();
        }
    }

    /**
     * 汇集记忆库短期读写锁的静态执行入口。
     */
    private MemoryLibraryLock() {
    }
}
