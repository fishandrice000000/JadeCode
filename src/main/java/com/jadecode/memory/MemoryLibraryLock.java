package com.jadecode.memory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 同一个文件库的短期读写互斥，不把“模型正在思考”也锁住。
 * JVM 内先使用普通锁，避免两个线程争抢 FileLock 时抛 OverlappingFileLockException；
 * 随后持有文件锁，保证多个 JadeCode 进程的扫描、记录写入和索引发布不会互相穿插。
 * 不限制外部编辑器或任意 shell，也不声称提供操作系统沙箱。
 */
final class MemoryLibraryLock {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL = new ConcurrentHashMap<>();
    @FunctionalInterface interface Operation<T> { T run() throws IOException; }

    /**
     * 在已经核验的真实库目录内执行一次操作，异常原样传播，锁始终释放。
     * 锁文件不截断、不删除且不跟随符号链接，避免不同进程锁住不同 inode。
     * 调用方不得在锁内等待模型；本方法只用于文件库本身的短期操作。
     */
    static <T> T run(Path root, Operation<T> operation) throws IOException {
        ReentrantLock local = LOCAL.computeIfAbsent(root, ignored -> new ReentrantLock());
        try { local.lockInterruptibly(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("等待记忆库操作已中断");
        }
        try (var channel = FileChannel.open(root.resolve(".store.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.lock()) {
            return operation.run();
        } finally { local.unlock(); }
    }
    private MemoryLibraryLock() { }
}
