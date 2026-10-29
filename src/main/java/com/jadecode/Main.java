package com.jadecode;


/**
 * JadeCode 根入口：解析根语法并分派到交互界面或无界面任务。
 *
 * <p>没有 run/repl 子命令了：位置参数就是首轮任务。{@code --help}/{@code --version} 只打印
 * 信息，不加载 Node、前端资产、配置或模型客户端；headless 也不碰前端。</p>
 *
 * <p>退出码：0 正常；2 参数错误/环境不支持/缺 Node 或成套前端；1 执行或清理失败。
 * SIGINT/SIGTERM 由 JVM 默认处理（130/143），本类不吞信号。</p>
 */
public final class Main {
    private Main() { }

    /** 进程入口。 */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * 可测试的分派入口。
     *
     * @param args 原始命令行参数
     * @return 进程退出码
     */
    public static int run(String[] args) {
        return com.jadecode.cli.ServiceCli.run(args);
    }
}
