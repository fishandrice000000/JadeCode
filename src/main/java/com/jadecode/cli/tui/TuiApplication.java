package com.jadecode.cli.tui;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.jadecode.bootstrap.JadeCodeFactory;
import com.jadecode.cli.SessionHost;
import com.jadecode.config.WorkspacePaths;
import com.jadecode.config.JadeConfigFile;
import com.jadecode.config.JadeSettings;
import com.jadecode.cli.tui.bridge.*;
import com.jadecode.execution.ExecutionObserver;
import com.jadecode.llm.ResponseStream;
import com.jadecode.session.SessionRuntime;
import com.jadecode.tui.core.*;
import com.jadecode.tui.terminal.TerminalSession;
import com.jadecode.tui.theme.Theme;

/**
 * 新 REPL 唯一的前端生命周期和界面循环。主线程只处理界面，Session 照常在自己的线程运行。
 * 先拒绝审批并停业务，最后恢复终端；任何初始化失败也走 finally，不留下原始模式。
 */
public final class TuiApplication {
    /** 装配用接收者；可注入假会话进行 PTY 验证，不必读取真实凭据或调用付费模型。 */
    public record Wiring(Consumer<String> output, Consumer<String> errors, Supplier<ResponseStream> streams,
            ExecutionObserver observer, JadeSettings settings, com.jadecode.safety.PermissionState permissions) { }
    @FunctionalInterface public interface SessionFactory { SessionRuntime create(Wiring wiring); }

    /** 生产入口。创建客户端不等于调用模型；实际提示词仍由用户提交触发。 */
    public int run() {
        return run((String) null);
    }

    /** 启动入口与会话内 /resume 共用同一个 Host 和加载器；空串表示打开选择器。 */
    public int run(String resume) {
        return run(resume, null);
    }

    /** 显式命令行档位只影响本次 CLI，不修改用户启动默认。 */
    public int run(String resume, com.jadecode.safety.PermissionProfile override) {
        return run(w -> JadeCodeFactory.create((hooks, cwd, output) -> { }, w.output(), w.errors(), w.streams(), w.observer(), w.settings(), w.permissions()),
                JadeConfigFile.defaultPath(), resume, override);
    }

    /**
     * 建立终端和会话，处理显示事件，直到用户退出或界面发生不可恢复的错误。
     *
     * <p>本线程只更新屏幕；模型请求、工具和关闭会话都在其它线程完成。factory 可以提供
     * 假模型，因此终端检查不必调用真实 API；prefs 也由调用者指定，检查时不会写真实偏好。
     * 方法会临时接管 System.out/err，最终恢复它们和终端属性。返回 0 仅表示界面正常退出，
     * 不表示各项业务任务成功；初始化或显示失败返回 1。</p>
     */
    public int run(SessionFactory factory, Path prefs) {
        return run(factory, prefs, null);
    }

    /** 可注入入口；factory 决定如何装配客户端，离线验证可提供假客户端和临时偏好路径。 */
    public int run(SessionFactory factory, Path prefs, String resume) {
        return run(factory, prefs, resume, null);
    }

    /** 可注入配置与启动档位；验证使用临时配置和假模型，不触碰真实用户配置。 */
    public int run(SessionFactory factory, Path prefs, String resume, com.jadecode.safety.PermissionProfile override) {
        PrintStream originalOut = System.out, originalErr = System.err;
        var queue = new LinkedBlockingQueue<UiEvent>(); var accepting = new AtomicBoolean(true);
        Consumer<UiEvent> events = event -> { if (accepting.get()) queue.offer(event); };
        var broker = new ApprovalBroker(events);
        SessionHost host = null;
        TerminalSession terminal = null;
        InlineRenderer renderer = null;
        Thread shutdownHook = null;
        String failureMessage = null;
        var farewellPrinted = new AtomicBoolean();
        try {
            // 1. 先确认是真实终端，再读取偏好、装配业务；非 TTY 不会启动一份不可操作的会话。
            terminal = new TerminalSession();
            var state = new TuiState(); state.directory = Path.of("").toAbsolutePath().normalize().toString();
            var preferences = new TuiPreferences(prefs);
            // 用户显式写错压缩开关时不能只提示“主题读取失败”再偷偷按另一种管线运行。
            JadeSettings settings = preferences.loadSettings();
            var permissions = settings.permissions().state(new JadeConfigFile(prefs));
            if (override != null) permissions.select(override);
            state.permissionProfile = permissions.snapshot().profile().label();
            state.theme = settings.tui().theme(); state.thinkingVisible = settings.tui().thinkingVisible();
            state.toolsExpanded = settings.tui().toolsExpanded(); state.commandSuggestions = settings.tui().commandSuggestions();
            // 捕获旧输出必须在 Terminal 建立之后，否则 renderer 会截获自己形成递归。
            var normalRoute = new RoutingPrintStream(events, false); var errorRoute = new RoutingPrintStream(events, true);
            System.setOut(normalRoute); System.setErr(errorRoute);
            // 每份运行时的所有显示通知带同一代次。装配通知先暂存，恢复卡片后再采用。
            host = new SessionHost(new WorkspacePaths(Path.of("").toRealPath()), generation -> {
                Consumer<UiEvent> scoped = event -> events.accept(new UiEvent.Scoped(generation, event));
                var bridge = new ExecutionUiBridge(scoped);
                return factory.create(new Wiring(text -> bridge.message(text, false), text -> bridge.message(text, true),
                        () -> new StreamingAdapter(scoped), bridge, settings, permissions));
            }, warning -> events.accept(new UiEvent.Message("System", "Warning", warning, true)));
            SessionHost sessions = host;
            TerminalSession screen = terminal;
            shutdownHook = new Thread(() -> {
                broker.close();
                try { sessions.close(); }
                finally {
                    accepting.set(false); System.setOut(originalOut); System.setErr(originalErr);
                    try { screen.close(); } catch (java.io.IOException ignored) { }
                    farewell(originalOut, sessions, farewellPrinted);
                }
            }, "jade-tui-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            var closing = new AtomicBoolean();
            Runnable shutdown = () -> {
                if (!closing.compareAndSet(false, true)) return;
                // close 可能逐个等待队友，不持 UI 锁等待；清理期间仍处理显示通知。
                Thread.ofVirtual().name("jade-tui-close").start(() -> {
                    try { sessions.close(); }
                    finally {
                        normalRoute.finish(); errorRoute.finish();
                        try { preferences.awaitWrites(); }
                        catch (java.io.IOException failed) { events.accept(new UiEvent.Message("System", "Warning", failed.getMessage(), true)); }
                        events.accept(new UiEvent.Stopped());
                    }
                });
            };
            // 2. 组件和控制器只交给本线程使用。业务线程只能往 queue 放不可变的显示资料。
            renderer = new InlineRenderer(terminal.writer()); var view = new ConversationView();
            var controller = new TuiController(state, sessions, broker, renderer, preferences, events, shutdown);
            var scheduler = new RenderScheduler();
            terminal.start(input -> events.accept(new UiEvent.Input(input)));
            controller.resume(resume);
            if (state.hint.isEmpty()) state.hint = "Enter 提交 · Alt+Enter 换行 · /help · 窗口重排可能清除终端滚动历史";
            loadBranch(events, state.directory);
            boolean running = true; long animated = 0;
            while (running) {
                // 3. 一次取有限数量的事件，再给屏幕刷新机会。积攒片段不会丢掉任何正文；
                //    输入需要立即显示，纯流式更新则可合并到下一帧，避免逐字刷屏。
                UiEvent event = queue.poll(16, TimeUnit.MILLISECONDS);
                boolean input = false;
                // 有限批处理防止高频模型增量让屏幕永远没有机会刷新。
                for (int count = 0; event != null && count < 128; count++) {
                    input |= event instanceof UiEvent.Input;
                    if (event instanceof UiEvent.Message m && m.label().equals("GitBranch")) state.branch = m.text();
                    else running = controller.accept(event);
                    scheduler.request();
                    if (!running || count == 127) break;
                    event = queue.poll();
                }
                if (System.nanoTime() - animated >= 160_000_000L && state.actors.containsValue("RUNNING")) {
                    scheduler.request(); animated = System.nanoTime();
                }
                // 仅读取不可变内存快照，不读日志/网络。只有统计或设置真的变化才要求重绘。
                if (controller.refreshSession()) scheduler.request();
                if (scheduler.ready(input || !running)) {
                    // 最后一列留给终端，避免写满后自动折行，使光标和点击坐标整体偏移。
                    var context = new RenderContext(Math.max(1, terminal.columns() - 1), terminal.rows(), new Theme(state.theme, terminal.colors()));
                    renderer.render(view.render(state, context), context); scheduler.rendered();
                }
            }
            return 0;
        } catch (Exception failure) {
            failureMessage = "TUI 未完成：" + failure.getMessage(); return 1;
        } finally {
            // 不关闭全局 System.in。正常路径的 Stopped 已表示业务清理结束；异常路径在此补清理。
            broker.close();
            try { if (host != null) host.close(); }
            finally {
                accepting.set(false);
                try { if (renderer != null) renderer.finish(); }
                finally {
                    System.setOut(originalOut); System.setErr(originalErr);
                    try { if (terminal != null) terminal.close(); }
                    catch (java.io.IOException cleanup) { originalErr.println("终端恢复失败：" + cleanup.getMessage()); }
                    farewell(originalOut, host, farewellPrinted);
                    if (failureMessage != null) originalErr.println(failureMessage);
                    if (shutdownHook != null) {
                        try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                        catch (IllegalStateException exiting) { /* JVM 已在执行关闭钩子。 */ }
                    }
                }
            }
        }
    }

    /** 终端恢复后输出一次身份；启动失败且没有打开会话时不打印无效 ID。 */
    private static void farewell(PrintStream output, SessionHost host, AtomicBoolean printed) {
        var session = host == null ? null : host.display();
        if (session == null || session.id().isBlank() || !printed.compareAndSet(false, true)) return;
        output.println("\nSession: " + com.jadecode.tui.text.DisplayWidth.clean(session.title()).replace('\n', ' '));
        output.println("ID: " + session.id());
        output.println("Resume: ./jade repl --resume " + session.id());
    }

    /** 页脚的 Git 元数据异步读取，不走模型 bash 工具，也不阻塞键盘。非仓库不报错。 */
    private void loadBranch(Consumer<UiEvent> events, String directory) {
        Thread.ofVirtual().name("jade-tui-git").start(() -> {
            try {
                var process = new ProcessBuilder("git", "-C", directory, "symbolic-ref", "--quiet", "--short", "HEAD")
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); return; }
                if (process.exitValue() == 0) events.accept(new UiEvent.Message("System", "GitBranch",
                        new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip(), false));
            } catch (Exception unavailable) { /* Git 不存在或非仓库不影响 Agent 使用。 */ }
        });
    }
}
