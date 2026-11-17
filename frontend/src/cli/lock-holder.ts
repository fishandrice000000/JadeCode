/**
 * flock 的专用持锁子进程：就绪后只等父进程关闭管道，不读取业务文件或连接服务。
 * 父进程异常退出也会产生 EOF，让内核释放锁；锁文件保留，避免不同 inode 上出现双重锁。
 */
process.stdout.write("LOCKED\n");
process.stdin.resume();
