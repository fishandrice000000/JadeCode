package com.jadecode.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import com.jadecode.cli.backend.BackendApplication;
import com.jadecode.cli.backend.BackendSessionService;
import com.jadecode.cli.backend.transport.LocalControlServer;
import com.jadecode.config.JadeConfigFile;

/**
 * 交互入口：一个 Java 后端 + 一个 Node 界面，Node 独占终端。
 *
 * <p>启动顺序：校验成套产物与 TTY → 创建私有 UDS 端点与一次性凭据 → 启动 Node 子进程
 * （继承真实 stdin/stdout/stderr，但只转发终端/编辑器等必要环境）→ 后端接受唯一连接并
 * 完成 hello/快照/ready → 首轮任务入队 → 等待关闭。Java 是父进程且不读终端。</p>
 *
 * <p>退出语义：Node 有序退出或 EOF 时，后端拒绝审批、保存设置并关闭会话；本入口再核对
 * 子进程退出码。启动失败（缺 Node/缺成套前端/身份不匹配/参数错误）返回 2；协议/IO/清理
 * 失败返回 1；正常退出 0。信号（SIGINT/SIGTERM）由 JVM 默认处理，退出码为 130/143。</p>
 */
public final class InteractiveLauncher {
    // 根 CLI 由 ServiceCli 连接常驻服务；run 保留进程内兼容装配，spawn/finish 供新入口共用。
    /** 启动 Node 后最多等待其退出的时间（正常关闭会更快）。 */
    private static final long NODE_EXIT_GRACE_MILLIS = 5_000;

    /**
     * 运行交互模式；返回进程退出码。
     *
     * @param options 已解析的根参数（不含 headless）
     */
    public int run(LaunchOptions options) {
        FrontendDistribution distribution;
        try {
            distribution = FrontendDistribution.locate();
            distribution.nodeExecutable();
        } catch (FrontendDistribution.Missing missing) {
            System.err.println("Cannot start the interactive UI:");
            System.err.println(missing.getMessage());
            return 2;
        }
        if (System.console() == null) {
            System.err.println("The interactive UI needs a real terminal. Use --headless <prompt> for automation.");
            return 2;
        }
        LocalControlServer server = null;
        Process node = null;
        try {
            server = LocalControlServer.open();
            String credential = newCredential();
            node = spawn(distribution, server.socketPath(), credential);
            BackendSessionService.Startup startup = startup(options);
            BackendApplication.Options backendOptions = new BackendApplication.Options(
                    JadeConfigFile.defaultPath(), options.permission(), startup, options.modelConfig());
            int code;
            String backendFailure;
            try (LocalControlServer endpoint = server;
                    BackendApplication application = new BackendApplication(backendOptions, endpoint, credential,
                            distribution.buildId())) {
                code = application.run();
                backendFailure = application.failureMessage();
            }
            int exit = finish(code, node);
            // 单独发给 Node 的信号会先关闭 UDS；随后 broken pipe 是退出结果，不覆盖信号身份。
            if (code != 0 && exit != 129 && exit != 130 && exit != 143 && backendFailure != null && !backendFailure.isBlank())
                System.err.println("Backend stopped: " + backendFailure);
            return exit;
        } catch (IOException | RuntimeException failure) {
            System.err.println("Could not start the interactive UI: " + failure.getMessage());
            if (node != null) node.destroy();
            return 1;
        } finally {
            if (node != null && node.isAlive()) node.destroy();
            if (server != null) server.close();
        }
    }

    /**
     * 有界等待界面退出；Node 的 SIGHUP/SIGINT/SIGTERM 身份优先于其断连引出的后端 IO 失败。
     * 其它非零退出仍为故障 1，等待超时强退子进程，不把崩溃冒充正常退出。
     */
    static int finish(int backendCode, Process node) {
        try {
            if (!node.waitFor(NODE_EXIT_GRACE_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                System.err.println("The Node frontend did not exit after the backend stopped; terminating it.");
                node.destroyForcibly();
                return backendCode == 0 ? 1 : backendCode;
            }
            int exit = node.exitValue();
            if (exit == 129 || exit == 130 || exit == 143) return exit;
            if (backendCode != 0) return backendCode;
            if (exit != 0) {
                System.err.println("The Node frontend exited with code " + exit + ".");
                return 1;
            }
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    /** 依据根参数得到启动意图；首轮 prompt 只由 Java 持有并按代次提交一次。 */
    static BackendSessionService.Startup startup(LaunchOptions options) {
        if (options.resume() != null && options.resume().isEmpty())
            return new BackendSessionService.Startup("picker", "", "", false);
        if (options.resume() != null)
            return new BackendSessionService.Startup("resume", "", options.resume(), false);
        if (options.hasPrompt())
            return new BackendSessionService.Startup("prompt", options.initialPrompt(), "", true);
        return new BackendSessionService.Startup("new", "", "", false);
    }

    /** 32 字节随机一次性凭据，只经子进程环境传递。 */
    static String newCredential() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 启动 Node 前端。
     *
     * <p>环境变量按白名单转发；认证相关的模型/MCP 变量不会出现在 Node 进程里。桥接变量
     * （socket/凭据/构建身份）只属于本次启动。</p>
     */
    Process spawn(FrontendDistribution distribution, Path socketPath, String credential) throws IOException {
        String nodeExecutable;
        try {
            nodeExecutable = distribution.nodeExecutable();
        } catch (FrontendDistribution.Missing missing) {
            throw new IOException(missing.getMessage(), missing);
        }
        ProcessBuilder builder = new ProcessBuilder(nodeExecutable, distribution.entry().toString());
        builder.directory(Path.of("").toAbsolutePath().toFile());
        builder.inheritIO();
        var environment = builder.environment();
        var forwarded = FrontendDistribution.forwardedEnvironment(System.getenv());
        environment.clear();
        for (String name : forwarded) environment.put(name, System.getenv(name));
        environment.put("JADE_BRIDGE_SOCKET", socketPath.toString());
        environment.put("JADE_BRIDGE_CREDENTIAL", credential);
        environment.put("JADE_BRIDGE_BUILD_ID", distribution.buildId());
        environment.put("JADE_BRIDGE_PROTOCOL_VERSION", Integer.toString(distribution.protocolVersion()));
        environment.put("JADE_PROJECT_VERSION", distribution.projectVersion());
        // 正常启动保持终端安静；只有真实启动/退出失败才由调用方输出诊断。
        return builder.start();
    }

    /** @return 打印版本信息（不加载 Node/配置/模型）。 */
    public static String version() {
        FrontendDistribution.BuildInfo info = FrontendDistribution.BuildInfo.load();
        return "jadecode " + info.version();
    }

    /** 供诊断：UTF-8 字节长度，不读取内容。 */
    static int credentialLength(String credential) {
        return credential.getBytes(StandardCharsets.UTF_8).length;
    }
}
