package com.jadecode.cli.tui;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.jadecode.bootstrap.JadeCodeFactory;
import com.jadecode.cli.SessionHost;
import com.jadecode.cli.tui.bridge.ApprovalBroker;
import com.jadecode.cli.tui.bridge.ExecutionUiBridge;
import com.jadecode.cli.tui.bridge.RoutingPrintStream;
import com.jadecode.cli.tui.bridge.StreamingAdapter;
import com.jadecode.cli.tui.view.ChatViewport;
import com.jadecode.cli.tui.view.ConversationView;
import com.jadecode.cli.tui.view.DockView;
import com.jadecode.config.JadeConfigFile;
import com.jadecode.config.JadeSettings;
import com.jadecode.config.WorkspacePaths;
import com.jadecode.execution.ExecutionObserver;
import com.jadecode.llm.ResponseStream;
import com.jadecode.session.SessionRuntime;
import com.jadecode.tui.components.Editor;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.PresentedFrame;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.core.RenderScheduler;
import com.jadecode.tui.geometry.Rect;
import com.jadecode.tui.input.InputDecoder;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.render.FullscreenRenderer;
import com.jadecode.tui.render.RegularRenderer;
import com.jadecode.tui.render.ScreenRenderer;
import com.jadecode.tui.terminal.AnsiEncoder;
import com.jadecode.tui.media.ImageClipboard;
import com.jadecode.tui.terminal.ClipboardService;
import com.jadecode.tui.terminal.PosixJobControl;
import com.jadecode.tui.terminal.TerminalCapabilities;
import com.jadecode.tui.terminal.TerminalEvent;
import com.jadecode.tui.terminal.TerminalHandoff;
import com.jadecode.tui.terminal.TerminalQueries;
import com.jadecode.tui.terminal.TerminalSession;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.ThemeManager;

/**
 * TUI 根装配：终端/配置/会话/Host/组件/单 UI 循环/双屏切换与有序关闭。
 *
 * <p>不承载业务循环或组件算法。读取线程只发原始事件；StdinBuffer/InputDecoder 组装按键；
 * 所有状态/组件只在 UI 线程更新。每帧先布局 ChatViewport 与 Dock，再由唯一 renderer 写出；
 * PresentedFrame 只在 flush/错误检查成功后发布。切换模式不重启 Host、不清草稿；关闭时先拒
 * 审批、停输入、等待本地写入，再按原顺序关闭 Host/终端，最后恢复全局流并输出一次告别。</p>
 */
public final class TuiApplication {
    /** 装配用接收者；可注入假会话进行 PTY 验证，不读取真实凭据或调用付费模型。 */
    public record Wiring(Consumer<String> output, Consumer<String> errors, Supplier<ResponseStream> streams,
            ExecutionObserver observer, JadeSettings settings, com.jadecode.safety.PermissionState permissions) { }
    @FunctionalInterface public interface SessionFactory { SessionRuntime create(Wiring wiring); }

    /** 生产入口；创建客户端不等于调用模型，实际提示词仍由用户提交触发。 */
    public int run() { return run((String) null); }

    /** 启动入口与会话内 /resume 共用同一个 Host 和加载器；空串表示打开选择器。 */
    public int run(String resume) { return run(resume, null); }

    /** 显式命令行档位只影响本次 CLI，不修改用户启动默认。 */
    public int run(String resume, com.jadecode.safety.PermissionProfile override) {
        return run(w -> JadeCodeFactory.create((hooks, cwd, output) -> { }, w.output(), w.errors(),
                        w.streams(), w.observer(), w.settings(), w.permissions()),
                JadeConfigFile.defaultPath(), resume, override);
    }

    public int run(SessionFactory factory, Path prefs) { return run(factory, prefs, null); }

    public int run(SessionFactory factory, Path prefs, String resume) { return run(factory, prefs, resume, null); }

    /**
     * 可注入入口；factory 决定如何装配客户端，离线验证可提供假客户端和临时偏好路径。
     * 返回 0 只表示 UI 正常退出，不表示业务任务成功。
     */
    public int run(SessionFactory factory, Path prefs, String resume,
            com.jadecode.safety.PermissionProfile override) {
        PrintStream originalOut = System.out, originalErr = System.err;
        var events = new LinkedBlockingQueue<UiEvent>();
        var accepting = new AtomicBoolean(true);
        Consumer<UiEvent> sink = event -> { if (accepting.get()) events.offer(event); };
        var broker = new ApprovalBroker(sink);
        SessionHost host = null;
        TerminalSession terminal = null;
        ScreenRenderer renderer = null;
        ThemeManager themes = null;
        TerminalHandoff handoff = null;
        ClipboardService clipboard = null;
        ImageClipboard imageClipboard = null;
        CommandCatalog catalog = null;
        Thread shutdownHook = null;
        var farewellPrinted = new AtomicBoolean();
        String failureMessage = null;
        var stopping = new AtomicBoolean();
        var closeStarted = new AtomicBoolean();
        var textEngineRef = new AtomicReference<com.jadecode.tui.markdown.JsTextEngine>();
        try {
            // 1. 先确认真实终端，再读取配置/装配业务；非 TTY 不创建不可操作会话。
            terminal = new TerminalSession();
            final TerminalSession term = terminal;
            var preferences = new TuiPreferences(prefs);
            JadeSettings settings = preferences.loadSettings();
            var permissions = settings.permissions().state(new JadeConfigFile(prefs));
            if (override != null) permissions.select(override);
            var caps = TerminalCapabilities.detect(System.getenv());
            boolean colors = caps.colorsEnabled() && terminal.colors();
            themes = new ThemeManager(settings.tui().theme(), prefs.toAbsolutePath().getParent().resolve("themes"),
                    colors, settings.tui().lightTheme(), settings.tui().darkTheme());
            var capabilities = new AtomicReference<>(caps);
            var state = new TuiState();
            state.directory = Path.of("").toAbsolutePath().normalize().toString();
            state.permissionProfile = permissions.snapshot().profile().value();
            state.thinkingVisible = settings.tui().thinkingVisible();
            state.toolsExpanded = settings.tui().toolsExpanded();
            state.generation = 0;
            var keys = new KeyBindings(settings.tui().keybindings());
            catalog = new CommandCatalog(Path.of("").toAbsolutePath());
            var settingsMenu = new SettingsMenu(settings.tui(), prefs.toAbsolutePath().getParent().resolve("themes"));
            var conversation = new ConversationView(textEngineRef::get, () -> sink.accept(new UiEvent.Repaint()));
            var chat = new ChatViewport(conversation,
                    ChatViewport.Scrollbar.valueOf(settings.tui().fullscreenScrollbar().toUpperCase(java.util.Locale.ROOT)),
                    settings.tui().fullscreenCopyOnSelect());
            chat.wheelLines(settings.tui().fullscreenWheelScrollLines() == 0 ? 3 : settings.tui().fullscreenWheelScrollLines());
            var mode = new AtomicReference<>(settings.tui().mode());

            // 2. 兜底路由 System.out/err；必须在 Terminal 建立后，renderer 使用原始 writer 不受截获影响。
            var normalRoute = new RoutingPrintStream(sink, false);
            var errorRoute = new RoutingPrintStream(sink, true);
            System.setOut(normalRoute); System.setErr(errorRoute);

            var handoffRef = new AtomicReference<TerminalHandoff>();
            var clipboardRef = new AtomicReference<ClipboardService>();
            var imageClipboardRef = new AtomicReference<ImageClipboard>();
            var queriesRef = new AtomicReference<TerminalQueries>();
            var controllerRef = new AtomicReference<TuiController>();
            var editorRef = new AtomicReference<Editor>();

            var listener = new Editor.Listener() {
                @Override public boolean submit(String text) {
                    TuiController controller = controllerRef.get();
                    return controller != null && controller.submit(text);
                }
                @Override public void localEscape() {
                    TuiController controller = controllerRef.get();
                    if (controller != null) controller.invalidateActions();
                }
                @Override public void externalEditor() {
                    TerminalHandoff edge = handoffRef.get();
                    Editor editor = editorRef.get();
                    if (edge == null || editor == null) return;
                    edge.edit(editor.expandedText(), settingsMenu.current().externalEditor(),
                            value -> sink.accept(new UiEvent.ExternalEditorResult(value)),
                            error -> sink.accept(new UiEvent.Message("System", "Editor", error, true)));
                }
                @Override public void pasteClipboard() {
                    ClipboardService service = clipboardRef.get();
                    ImageClipboard images = imageClipboardRef.get();
                    if (service == null) return;
                    Thread.ofVirtual().name("jade-clipboard").start(() -> {
                        var text = service.readText();
                        if (text.isPresent()) { sink.accept(new UiEvent.ClipboardText(text.get())); return; }
                        var image = images == null ? java.util.Optional.<java.nio.file.Path>empty()
                                : images.saveFromClipboard();
                        if (image.isPresent()) sink.accept(new UiEvent.ClipboardText(image.get().toString() + " "));
                        else sink.accept(new UiEvent.Message("System", "Clipboard", UiMessages.IMAGE_UNAVAILABLE, true));
                    });
                }
                @Override public void copy() {
                    TuiController controller = controllerRef.get();
                    if (controller != null) controller.copySelectionOrLastReply();
                }
            };
            var editor = new Editor(keys, listener);
            editor.setCompletionProvider(settings.tui().commandSuggestions() ? catalog.completionProvider() : null);
            editor.setCompletionMaxVisible(settings.tui().autocompleteMaxVisible());
            editorRef.set(editor);
            state.editor = editor;

            // 3. 每份运行时的显示通知带同一代次；装配通知先暂存，恢复历史后再采用。
            host = new SessionHost(new WorkspacePaths(Path.of("").toRealPath()), generation -> {
                Consumer<UiEvent> scoped = event -> sink.accept(new UiEvent.Scoped(generation, event));
                var bridge = new ExecutionUiBridge(scoped);
                return factory.create(new Wiring(text -> bridge.message(text, false), text -> bridge.message(text, true),
                        () -> new StreamingAdapter(scoped), bridge, settings, permissions));
            }, warning -> sink.accept(new UiEvent.Message("System", "Warning", warning, true)));
            SessionHost sessions = host;

            Runnable shutdown = () -> {
                if (!closeStarted.compareAndSet(false, true)) return;
                Thread.ofVirtual().name("jade-tui-close").start(() -> {
                    try {
                        sessions.close();
                        try { preferences.awaitWrites(); }
                        catch (IOException saveFailure) {
                            sink.accept(new UiEvent.Message("System", "Warning", saveFailure.getMessage(), true));
                        }
                    } finally { events.offer(new UiEvent.Stopped()); }
                });
            };
            var controller = new TuiController(state, sessions, broker, chat, editor, preferences, keys, catalog,
                    settingsMenu, themes, sink, shutdown, new TuiController.TerminalActions() {
                        @Override public void suspend() {
                            TerminalHandoff edge = handoffRef.get();
                            if (edge == null) return;
                            Thread.ofVirtual().name("jade-suspend").start(() -> {
                                TerminalQueries queries = queriesRef.get();
                                if (queries != null) queries.disable();
                                String error = edge.suspend();
                                if (!error.isEmpty()) sink.accept(new UiEvent.Message("System", "Suspend", error, true));
                            });
                        }
                        @Override public void openExternalEditor(String draft, Consumer<String> success, Consumer<String> failure) {
                            TerminalHandoff edge = handoffRef.get();
                            if (edge == null) { failure.accept("Terminal handoff unavailable"); return; }
                            Thread.ofVirtual().name("jade-external-editor").start(() -> {
                                TerminalQueries queries = queriesRef.get();
                                if (queries != null) queries.disable();
                                edge.edit(draft, settingsMenu.current().externalEditor(), success, failure);
                                if (queries != null) queries.restart();
                                // 编辑器可能改写了屏幕/终端模式；请求一次完整重绘（同 resize 路径）。
                                long epoch = term.bumpEpoch();
                                events.offer(new UiEvent.Input(InputEvent.resize(Long.MAX_VALUE, epoch)));
                            });
                        }
                        @Override public void pasteClipboard(Editor target) {
                            ClipboardService service = clipboardRef.get();
                            ImageClipboard images = imageClipboardRef.get();
                            if (service == null) return;
                            Thread.ofVirtual().name("jade-clipboard").start(() -> {
                                var text = service.readText();
                                if (text.isPresent()) { sink.accept(new UiEvent.ClipboardText(text.get())); return; }
                                var image = images == null ? java.util.Optional.<java.nio.file.Path>empty()
                                        : images.saveFromClipboard();
                                if (image.isPresent()) sink.accept(new UiEvent.ClipboardText(image.get().toString() + " "));
                                else sink.accept(new UiEvent.Message("System", "Clipboard", UiMessages.IMAGE_UNAVAILABLE, true));
                            });
                        }
                        @Override public void copy(String text, boolean explicit) {
                            ClipboardService service = clipboardRef.get();
                            if (service == null) return;
                            Thread.ofVirtual().name("jade-copy").start(() -> {
                                var result = service.copy(text);
                                // 复制结果走右上角临时提示，不进转录；OSC52 只表示请求已发送。
                                switch (result) {
                                    case DESKTOP -> sink.accept(new UiEvent.Flash(UiMessages.COPIED, 1000, false));
                                    case OSC52_SENT -> sink.accept(new UiEvent.Flash(UiMessages.COPY_SENT, 1000, false));
                                    case FAILED -> sink.accept(new UiEvent.Flash(
                                            UiMessages.COPY_FAILED + "no clipboard backend", 5000, true));
                                }
                            });
                        }
                        @Override public void setMode(String value) { mode.set(value); }
                        @Override public void openLink(String uri) {
                            sink.accept(new UiEvent.Message("System", "Link", "Link target: " + uri, false));
                        }
                    });
            controllerRef.set(controller);

            // 4. 终端协议/输入组装/读取线程；查询回复分流，绝不进入编辑器。
            var decoder = new InputDecoder(term.epoch());
            var buffer = new com.jadecode.tui.input.StdinBuffer(caps.escapeTimeoutMillis(),
                    chunk -> { for (InputEvent decoded : decoder.decode(chunk)) events.offer(new UiEvent.Input(decoded)); },
                    System::nanoTime);
            var queries = new TerminalQueries(text -> { term.write(text); });
            queriesRef.set(queries);
            term.start(event -> {
                switch (event) {
                    case TerminalEvent.InputChunk chunk -> { decoder.setEpoch(event.terminalEpoch()); buffer.feed(chunk.bytes()); }
                    case TerminalEvent.Resize resize -> {
                        long epoch = term.bumpEpoch();
                        state.clearFlash();
                        events.offer(new UiEvent.Input(InputEvent.resize(event.sequence(), epoch)));
                    }
                    case TerminalEvent.Eof eof -> events.offer(new UiEvent.Input(InputEvent.eof(event.sequence(), event.terminalEpoch())));
                    case TerminalEvent.Failure failure -> sink.accept(new UiEvent.TerminalFailure(failure.message()));
                }
            });
            // 孤立 Esc 需要定时器；Buffer 方法已同步，读线程/定时器不会交错拆包。
            Thread.ofVirtual().name("jade-esc-timer").start(() -> {
                while (!term.state().equals(TerminalSession.State.CLOSED) && accepting.get()) {
                    try { Thread.sleep(10); } catch (InterruptedException stop) { return; }
                    buffer.tick();
                }
            });

            var port = new TerminalPort(term, colors);
            var initial = createRenderer(mode.get(), port, settings.tui().showHardwareCursor());
            renderer = initial;
            initial.start();

            handoff = new TerminalHandoff(term, new PosixJobControl());
            handoffRef.set(handoff);
            // OSC52 与 OSC8 是不同能力：tmux/VSCode 可能不支持超链接但仍可转发剪贴板请求。
            // 只有 dumb/无色且无链接能力时才禁用远端复制回退。
            clipboard = new ClipboardService(text -> term.write(text), caps.colorCount() > 0 || caps.hyperlinks());
            clipboardRef.set(clipboard);
            imageClipboard = new ImageClipboard(prefs.toAbsolutePath().getParent().resolve("images"));
            imageClipboardRef.set(imageClipboard);
            queries.start();

            // 5. 启动会话（resume=null 创建新会话；空串打开选择器）。
            controller.resume(resume);
            loadBranch(sink);
            // 固定文本引擎异步冷加载：不阻塞首帧；成功后请求一次重排，失败保留 Java 回退并如实报告。
            Thread.ofVirtual().name("jade-text-engine-init").start(() -> {
                try {
                    textEngineRef.set(com.jadecode.tui.markdown.JsTextEngine.create());
                    sink.accept(new UiEvent.Repaint());
                } catch (IOException failure) {
                    sink.accept(new UiEvent.Message("System", "Warning",
                            "Fixed text engine unavailable; showing readable fallback: " + failure.getMessage(), true));
                }
            });

            var scheduler = new RenderScheduler();
            boolean running = true;
            boolean sawStopped = false;
            long revision = 0;
            while (running) {
                UiEvent event = events.poll(16, TimeUnit.MILLISECONDS);
                boolean immediate = false;
                for (int count = 0; event != null && count < 64; count++) {
                    immediate |= event instanceof UiEvent.Input
                            || (event instanceof UiEvent.Scoped scoped && scoped.event() instanceof UiEvent.Input);
                    // 先让 TerminalQueries 消费回复；只有能力/色板变化才重绘。
                    if (event instanceof UiEvent.Repaint) {
                        // 固定引擎 DTO 已缓存；只丢弃组件排版缓存，保留解析结果并重排一次。
                        conversation.invalidateLayout();
                        scheduler.request();
                        event = events.poll();
                        continue;
                    }
                    if (event instanceof UiEvent.Input input && input.input().kind() == InputEvent.Kind.REPLY) {
                        boolean changed = queries.accept(input.input().reply(), input.input().terminalEpoch());
                        if (changed) {
                            applyQueries(queries, themes, capabilities, terminal);
                            // 协商状态只影响兼容序列语义；在同一 UI 线程传给 decoder，避免竞态。
                            decoder.setKittyActive(queries.kittyActive());
                        }
                        scheduler.request();
                    } else {
                        boolean accepted = controller.accept(event);
                        if (!accepted) sawStopped = true;
                        running = accepted;
                        scheduler.request();
                    }
                    // CONT/fg 返回：TerminalSession 会发 Resize；在消费任何输入前恢复终端/epoch。
                    if (event instanceof UiEvent.Input input && input.input().kind() == InputEvent.Kind.RESIZE) {
                        // 尺寸/CONT 后二维码/渲染器坐标全部失效；全屏 renderer 必须全量重写，
                        // 否则 shell/编辑器在暂退期间写入的内容会残留在旧行上。
                        renderer.invalidate();
                        if (handoff.suspended() && handoff.resumeAfterContinue()) {
                            controller.invalidateActions();
                            queries.newEpoch(term.epoch());
                            queries.restart();
                            decoder.setKittyActive(false);
                            scheduler.request();
                        }
                    }
                    if (!running || count == 63) break;
                    event = events.poll();
                }
                // 切换模式：先失效旧证据，关闭旧 renderer，再启动新模式；Host/草稿不动。
                if (!mode.get().equals(renderer.mode() == ScreenRenderer.Mode.FULLSCREEN ? "fullscreen" : "regular")) {
                    controller.invalidateActions();
                    try { renderer.finish(); } catch (IOException ignored) { }
                    try { renderer.close(); } catch (IOException ignored) { }
                    renderer = createRenderer(mode.get(), port, settingsMenu.current().showHardwareCursor());
                    renderer.start();
                    scheduler.request();
                }
                controller.applyPendingExternalEditor();
                if (controller.refreshSession()) scheduler.request();
                if (state.hasActiveTurn()) scheduler.requestAnimation(System.nanoTime() + RenderScheduler.ANIMATION_INTERVAL_NANOS);
                // flash 到期即使空闲也要清除并重绘一次；未到期则按期限安排刷新。
                if (state.clearExpiredFlash()) scheduler.request();
                if (state.flashActive()) scheduler.requestAnimation(state.flashDeadline());
                // auto 滚动条 1 秒后隐藏也要有一次空闲重绘；不能依赖下一次按键。
                if (chat.scrollbarVisible()) scheduler.requestAnimation(chat.scrollbarDeadline());
                if (scheduler.ready(immediate || !running)) {
                    int columns = terminal.columns() > 0 ? terminal.columns() : 80;
                    int rows = terminal.rows() > 0 ? terminal.rows() : 24;
                    var context = new RenderContext(columns, rows, themes.preview(), capabilities.get(),
                            System.nanoTime(), ++revision);
                    var composed = compose(state, context, columns, rows, chat, conversation, editor, settingsMenu,
                            themes, controller, renderer.mode(), terminal);
                    try {
                        PresentedFrame presented = renderer.present(composed.frame(), context);
                        controller.presented(presented, composed.frame());
                        scheduler.rendered();
                    } catch (IOException outputFailure) {
                        failureMessage = "Terminal output failed: " + outputFailure.getMessage();
                        running = false;
                    }
                }
            }
            // 6. 等清理事件（Stopped）完成；即使 pending 也由 finally 兜底关闭。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!sawStopped && accepting.get() && System.nanoTime() < deadline) {
                UiEvent stop = events.poll(20, TimeUnit.MILLISECONDS);
                if (stop instanceof UiEvent.Stopped) break;
                if (stop != null) controller.accept(stop);
                if (state.closing && !events.contains(new UiEvent.Stopped())) { /* 等待关闭线程。 */ }
            }
            // 7. 全屏退出转录重放（可配置 resume-hint）；只在正常 UI 退出时输出一次。
            boolean fullscreen = renderer.mode() == ScreenRenderer.Mode.FULLSCREEN;
            try { renderer.finish(); } catch (IOException failure) { failureMessage = failure.getMessage(); }
            if (fullscreen && settingsMenu.current().fullscreenExitOutput().equals("transcript")) {
                replayTranscript(terminal, conversation.document(), capabilities.get());
            }
            return 0;
        } catch (Exception failure) {
            failureMessage = "TUI did not start: " + failure.getMessage();
            return 1;
        } finally {
            broker.close();
            stopping.set(true);
            try { if (host != null) host.close(); }
            catch (RuntimeException failure) { originalErr.println("Session close failed: " + failure.getMessage()); }
            finally {
                accepting.set(false);
                try { if (renderer != null) renderer.close(); }
                catch (IOException ignored) { }
                try { if (themes != null) themes.close(); } catch (RuntimeException ignored) { }
                try { if (catalog != null) catalog.close(); } catch (RuntimeException ignored) { }
                try { if (handoff != null) handoff.close(); } catch (RuntimeException ignored) { }
                com.jadecode.tui.markdown.JsTextEngine engineToClose = textEngineRef.get();
                if (engineToClose != null) { try { engineToClose.close(); } catch (RuntimeException ignored) { } }
                try { if (imageClipboard != null) imageClipboard.close(); } catch (RuntimeException ignored) { }
                System.setOut(originalOut); System.setErr(originalErr);
                try { if (terminal != null) terminal.close(); }
                catch (IOException cleanup) { originalErr.println("Terminal restore failed: " + cleanup.getMessage()); }
                farewell(originalOut, host, farewellPrinted);
                if (failureMessage != null) originalErr.println(failureMessage);
                if (shutdownHook != null) {
                    try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                    catch (IllegalStateException exiting) { }
                }
            }
        }
    }

    /** 终端 renderer 的最小端口；唯一 writer/尺寸/epoch。 */
    private record TerminalPort(TerminalSession session, boolean colors) implements ScreenRenderer.Port {
        @Override public int columns() { return session.columns(); }
        @Override public int rows() { return session.rows(); }
        @Override public long epoch() { return session.epoch(); }
        @Override public PrintWriter writer() { return session.writer(); }
        @Override public boolean outputFailed() { return session.outputFailed(); }
        @Override public boolean colorsEnabled() { return colors; }
    }

    /** 由配置创建 renderer；两次模式共用唯一 port。 */
    private ScreenRenderer createRenderer(String mode, ScreenRenderer.Port port, boolean hardwareCursor) {
        boolean fullscreen = !"regular".equals(mode);
        boolean trueColor = port.colorsEnabled() && System.getenv().getOrDefault("COLORTERM", "").toLowerCase().contains("true");
        boolean links = port.colorsEnabled() && ("1".equals(System.getenv("JADE_TUI_HYPERLINKS"))
                || System.getenv().getOrDefault("TERM_PROGRAM", "").matches("(?i).*(wezterm|iterm).*")
                || System.getenv().getOrDefault("TERM", "").matches("(?i).*(kitty|wezterm|ghostty).*"));
        return fullscreen
                ? new FullscreenRenderer(port, trueColor, links, hardwareCursor, true)
                : new RegularRenderer(port, trueColor, links, hardwareCursor);
    }

    /** 终端协商结果合入主题/能力；晚回复只影响新帧。 */
    private void applyQueries(TerminalQueries queries, ThemeManager themes, AtomicReference<TerminalCapabilities> caps,
            TerminalSession terminal) {
        var snapshot = queries.snapshot();
        // 缺明暗报告时按当前主题外观推断：light 主题对应浅色终端，dark=false；
        // 旧实现把 isLight() 直接当 dark，方向相反。
        boolean dark = snapshot.darkBackground() == null ? !themes.preview().isLight() : snapshot.darkBackground();
        themes.updateTerminalColors(dark, snapshot.foreground(), snapshot.background(), snapshot.ansi());
        var values = caps.get();
        caps.set(values.withQueries(snapshot.cellWidth(), snapshot.cellHeight(), snapshot.keyboard(), null, null, null, null));
    }

    /** 一次布局：视口与 dock 共用 RenderContext；光标/命中与文本同帧。 */
    private Composed compose(TuiState state, RenderContext context, int columns, int rows,
            ChatViewport chat, ConversationView conversation, Editor editor, SettingsMenu settings,
            ThemeManager themes, TuiController controller, ScreenRenderer.Mode mode, TerminalSession terminal) {
        var dock = new DockView().render(state, context, columns);
        var lines = new ArrayList<StyledLine>();
        int cursorRow = 0, cursorColumn = 0;
        boolean cursorVisible = false;
        var hits = new ArrayList<Frame.Hit>();
        if (mode == ScreenRenderer.Mode.FULLSCREEN) {
            int dockRows = Math.min(dock.lines().size(), Math.max(1, rows - 1));
            int viewportHeight = Math.max(1, rows - dockRows);
            List<String> resources = List.of();
            var viewport = chat.render(state, context.withHeight(viewportHeight), viewportHeight, resources,
                    controller.welcomeExpanded(), true);
            // 搜索 Overlay 覆盖视口，点击不能穿透；关闭后回到原编辑器草稿。
            if (controller.searchOpen()) {
                var overlay = controller.searchOverlay();
                Rect rect = overlay.rect(new Rect(0, 0, columns, viewport.size()));
                var overlayLines = overlay.render(context, rect.width());
                if (!overlayLines.isEmpty()) {
                    int top = rect.y();
                    for (int i = 0; i < overlayLines.size() && top + i < viewport.size(); i++)
                        viewport.set(top + i, overlayLines.get(i).padTo(rect.width(), context.theme().style("text")));
                    cursorRow = top + 1;
                    cursorColumn = Math.min(rect.width() - 1, 2 + overlay.cursorColumn(Math.max(1, rect.width() - 4)));
                    cursorVisible = true;
                }
            }
            lines.addAll(viewport);
            lines.addAll(dock.lines().subList(0, dockRows));
            if (!cursorVisible && dock.cursorVisible() && dock.cursorRow() < dockRows) {
                cursorRow = viewport.size() + dock.cursorRow();
                cursorColumn = dock.cursorColumn();
                cursorVisible = true;
            }
            for (var hit : dock.hits()) {
                if (hit.rect().y() >= dockRows) continue;
                var rect = hit.rect().translate(0, viewport.size());
                hits.add(new Frame.Hit(rect, hit.action()));
            }
        } else {
            var document = conversation.render(state, context, List.of(), controller.welcomeExpanded());
            lines.addAll(document);
            lines.addAll(dock.lines());
            if (dock.cursorVisible()) {
                cursorRow = document.size() + dock.cursorRow(); cursorColumn = dock.cursorColumn(); cursorVisible = true;
            }
            for (var hit : dock.hits()) {
                var rect = hit.rect().translate(0, document.size());
                hits.add(new Frame.Hit(rect, hit.action()));
            }
        }
        if (lines.size() > rows) lines = new ArrayList<>(lines.subList(0, rows));
        List<StyledLine> cropped = lines.stream().map(line -> line.crop(columns)).toList();
        boolean fullscreen = mode == ScreenRenderer.Mode.FULLSCREEN;
        if (fullscreen) cropped = controller.applySelection(cropped);
        cropped = overlayFlash(cropped, state, context, columns);
        int safeRow = Math.max(0, Math.min(cursorRow, Math.max(0, cropped.size() - 1)));
        int safeColumn = Math.max(0, Math.min(cursorColumn, Math.max(0, columns - 1)));
        var cursor = cursorVisible ? new Frame.Cursor(safeRow, safeColumn, true) : Frame.NO_CURSOR;
        var frame = new Frame(++frameCounter, terminal.epoch(), context.revision(), cropped, cursor, hits);
        int dockRows = Math.min(dock.lines().size(), Math.max(1, rows - 1));
        int viewportHeight = fullscreen ? Math.max(1, rows - dockRows) : 0;
        int dockTop = fullscreen ? viewportHeight : Math.max(0, cropped.size() - dock.lines().size());
        controller.layout(columns, rows, 0, viewportHeight, dockTop, fullscreen);
        return new Composed(frame, dock);
    }

    /**
     * 把临时复制提示合成到第一行右上角；不修改转录/历史。宽度不足时只保留能放下的
     * 前缀，仍保证总列数不超过终端宽度。
     */
    private List<StyledLine> overlayFlash(List<StyledLine> lines, TuiState state, RenderContext context, int columns) {
        if (!state.flashActive() || lines.isEmpty()) return lines;
        String text = state.flashText();
        var theme = context.theme();
        Style style = state.flashError() ? theme.style("error") : theme.style("success");
        int textWidth = com.jadecode.tui.text.DisplayWidth.width(text);
        int keep = Math.max(0, columns - textWidth - 1);
        var result = new ArrayList<>(lines);
        StyledLine base = lines.getFirst();
        var spans = new ArrayList<Span>(base.crop(keep).spans());
        int used = 0;
        for (Span span : spans) used += com.jadecode.tui.text.DisplayWidth.width(span.text());
        if (used < keep) spans.add(new Span(" ".repeat(keep - used), Style.PLAIN, null));
        spans.add(new Span(" ", Style.PLAIN, null));
        spans.add(new Span(text, style, null));
        result.set(0, new StyledLine(spans, base.annotation()));
        return result;
    }

    private long frameCounter;
    private record Composed(Frame frame, DockView.Layout dock) { }

    /** 全屏退出后把最终转录重放到主屏；最多按 1MiB 块输出，正文不拆代理对。 */
    private void replayTranscript(TerminalSession terminal, List<StyledLine> document,
            TerminalCapabilities caps) {
        var writer = terminal.writer();
        var chunk = new StringBuilder();
        for (StyledLine line : document) {
            chunk.append(AnsiEncoder.encode(line, caps.trueColor(), caps.colorsEnabled(), caps.hyperlinks()))
                    .append("\r\n");
            if (chunk.length() >= 1_048_576) { writer.print(chunk); writer.flush(); chunk.setLength(0); }
        }
        writer.print(chunk); writer.flush();
        if (terminal.outputFailed()) throw new IllegalStateException("Terminal write failed during transcript replay");
    }

    /** 终端恢复后的唯一身份告别；启动失败/未打开会话不打印无效 ID。 */
    private static void farewell(PrintStream output, SessionHost host, AtomicBoolean printed) {
        var session = host == null ? null : host.display();
        if (session == null || session.id().isBlank() || !printed.compareAndSet(false, true)) return;
        String title = session.title() == null || session.title().isBlank() || session.title().equals("新会话")
                ? "New session" : TextSafe.oneLine(session.title());
        output.println("\nSession: " + DisplayWidth.ellipsize(title, 100));
        output.println("ID: " + session.id());
        output.println("Resume: ./jade repl --resume " + session.id());
    }

    /** 页脚 Git 元数据异步读取；非仓库不报错。 */
    private void loadBranch(Consumer<UiEvent> sink) {
        String directory = Path.of("").toAbsolutePath().normalize().toString();
        Thread.ofVirtual().name("jade-git").start(() -> {
            try {
                Process process = new ProcessBuilder("git", "-C", directory, "symbolic-ref", "--quiet", "--short", "HEAD")
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); return; }
                if (process.exitValue() == 0) sink.accept(new UiEvent.Branch(new String(
                        process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip()));
            } catch (Exception unavailable) { /* Git 不存在或非仓库不影响 Agent 使用。 */ }
        });
    }

    /** 告别标题只显示单行，不删除原标题内容。 */
    private static final class TextSafe {
        static String oneLine(String value) {
            return com.jadecode.tui.text.TextSanitizer.singleLine(value == null ? "" : value);
        }
    }
}
