package com.jadecode;

/**
 * Java 仅提供常驻服务与 SSH 远端适配入口；所有用户 CLI 模式由 Node jade 承担。
 * 不创建前端或解析任务，非法入口在启动服务前返回 2。
 */
public final class Main {
    private Main() {
    }

    /** 服务进程入口；信号由 JVM 正常处理，退出不吞掉清理错误。 */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** 精确分派两个服务入口；不读取模型配置或隐式启动客户端。 */
    public static int run(String[] args) {
        if (args.length == 1 && args[0].equals("--service-daemon"))
            return com.jadecode.service.ServiceDaemon.run();
        if (args.length == 1 && args[0].equals("--service-relay"))
            return com.jadecode.service.ServiceRelay.relay();
        System.err.println("Use jade for client commands; jade-server accepts --service-daemon or --service-relay");
        return 2;
    }
}
