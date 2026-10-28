package com.jadecode.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.jadecode.cli.backend.RuntimeFactory;
import com.jadecode.config.JadeConfigFile;
import com.jadecode.config.JadeSettings;
import com.jadecode.config.WorkspacePaths;
import com.jadecode.execution.ExecutionObserver;
import com.jadecode.llm.ResponseStream;
import com.jadecode.safety.PermissionProfile;
import com.jadecode.safety.PermissionState;
import com.jadecode.session.SessionRuntime;
import com.jadecode.util.TerminalText;
import com.jadecode.safety.PermissionProfile;

/**
 * 无界面任务入口：持久化新会话、执行一次初始任务、只把最终回答写到 stdout。
 *
 * <p>契约（M30–M33/M41）：</p>
 * <ul>
 *   <li>不依赖 TTY、Node 或前端资产，也不创建 socket；</li>
 *   <li>默认使用 {@link PermissionProfile#AUTO_REVIEW}，不读取已保存的默认档位；显式
 *       {@code --permission} 只对本次运行生效，绝不写回配置；</li>
 *   <li>没有人工通道：所有档位遇到“仍需人工”的操作都由现有 Gate 拒绝并交回模型；不读 stdin；</li>
 *   <li>使用与 TUI 相同的会话日志，结束后可被 {@code --resume} 查看；</li>
 *   <li>stdout 只输出最终回答一次；warning/error 走 stderr；初始 Future 包含日志落盘与记忆作业登记，
 *       不等待后台提取/整理；退出后由同一工作区的下一次启动恢复维护，也不等未来 cron 或全部 Team。</li>
 * </ul>
 *
 * <p>退出码：0 = harness 正常走完（不代表业务目标达成）；1 = 执行/持久化/清理失败；
 * 参数错误由调用方返回 2。</p>
 */
public final class HeadlessRunner {
    /** 测试可注入的会话装配；生产实现读取用户配置并安装真实客户端。 */
    @FunctionalInterface
    interface HostFactory {
        SessionHost create(PrintStream err) throws IOException;
    }

    private final PrintStream out;
    private final PrintStream err;

    public HeadlessRunner() { this(System.out, System.err); }

    /** 可注入输出流，便于测试真实 stdout/stderr 语义。 */
    public HeadlessRunner(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * 执行一次无界面任务。
     *
     * @param options 已解析的根参数；必须带非空 prompt
     */
    public int run(LaunchOptions options) {
        if (!options.hasPrompt()) {
            err.println("--headless requires a non-empty task");
            return 2;
        }
        try {
            Path configPath = JadeConfigFile.defaultPath();
            JadeConfigFile file = new JadeConfigFile(configPath);
            JadeSettings settings = file.load();
            PermissionState permissions = settings.permissions().state(file);
            // 默认 auto-review，不读取已保存默认；显式覆盖只改本次内存状态，不落盘。
            permissions.select(headlessProfile(options.permission()));
            RuntimeFactory factory = new RuntimeFactory(settings, permissions, options.modelConfig());
            Path workspace = Path.of("").toAbsolutePath().normalize();
            HostFactory hostFactory = ignored -> new SessionHost(new WorkspacePaths(workspace),
                    generation -> factory.create(noOutput(), ignoredErr -> { }, () -> ResponseStream.NONE,
                            ExecutionObserver.NONE),
                    warning -> ignored.println(warning));
            return execute(options, hostFactory);
        } catch (IOException | RuntimeException failure) {
            err.println("Could not run the headless task: "
                    + (failure.getMessage() == null ? failure.toString() : failure.getMessage()));
            return 1;
        }
    }

    /** @return 无界面本次运行的权限档位：缺省 auto-review；显式值仅本次生效。 */
    static PermissionProfile headlessProfile(PermissionProfile override) {
        return override == null ? PermissionProfile.AUTO_REVIEW : override;
    }

    /** 无界面不打印模型进展；只有真错误走 stderr。 */
    private static java.util.function.Consumer<String> noOutput() {
        return ignored -> { };
    }

    /**
     * 可注入执行路径：stdout 只写最终回答；Future 确认日志和记忆作业已登记后关闭，后台整理可留待下次启动。
     *
     * @param hostFactory 创建未打开会话的宿主；测试用假的 LlmClient，生产用用户配置
     */
    int execute(LaunchOptions options, HostFactory hostFactory) {
        SessionHost host = null;
        try {
            host = hostFactory.create(err);
            SessionHost.Opened opened = host.open(null);
            // approval = null：没有人工通道；enableCron = false：不运行未来定时任务。
            CompletableFuture<String> result = host.startWithInitial(opened.generation(), options.initialPrompt(),
                    null, false);
            String answer = result.get();
            if (answer != null && !answer.isBlank()) out.println(TerminalText.display(answer));
            out.flush();
            return 0;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            err.println("Execution did not finish: " + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            err.println("Execution was interrupted");
            return 1;
        } catch (IOException | RuntimeException failure) {
            err.println("Could not run the headless task: "
                    + (failure.getMessage() == null ? failure.toString() : failure.getMessage()));
            return 1;
        } finally {
            if (host != null) {
                try { host.close(); }
                catch (RuntimeException failure) {
                    err.println("Session cleanup failed: " + failure.getMessage());
                }
            }
        }
    }

    /** 供测试核验“等待初始 Future 后再关闭”的顺序：返回最终答案而不打印。 */
    static String awaitAnswer(CompletableFuture<String> result) throws ExecutionException, InterruptedException {
        return result.get();
    }
}
