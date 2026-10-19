package com.jadecode.tui.terminal;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp.Capability;

/**
 * 唯一的终端适配器：JLine 负责底层，不创建 LineReader、Status 或 PrintAboveWriter。
 * 原始输入在一条读取线程上解码，界面线程独占 writer。关闭时恢复启动前的终端属性。
 */
public final class TerminalSession implements AutoCloseable {
    private final Terminal terminal;
    private final Attributes original;
    private volatile boolean closed;
    private Thread input;
    private Object previousTerm, previousHup;
    public TerminalSession() throws IOException {
        if (System.console() == null) throw new IOException("jade repl 需要真实终端；脚本请使用 jade run \"任务\"");
        terminal = TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).dumb(false).build();
        original = terminal.getAttributes();
        try {
            terminal.enterRawMode();
            // 自定义解码器需要真的收到 Ctrl+C 等字符。保留 ISIG 会把快速两次 Ctrl+C
            // 合并成一个操作系统信号，无法可靠执行“清草稿／再次退出”的区分。
            Attributes raw = terminal.getAttributes();
            raw.setLocalFlag(Attributes.LocalFlag.ISIG, false);
            raw.setLocalFlag(Attributes.LocalFlag.IEXTEN, false);
            raw.setInputFlag(Attributes.InputFlag.IXON, false);
            terminal.setAttributes(raw);
            // 与 Pi 主屏模式一致：只接管键盘和整段粘贴，不捕获鼠标。
            // 开启 1000 会同时截走滚轮和拖选；仅处理点击却不实现滚动／选区，
            // 会让用户既不能查看终端历史，也不能选取文本。启动时清理可能遗留的捕获模式。
            terminal.writer().print("\033[?1000l\033[?1002l\033[?1003l\033[?1006l\033[?2004h"); terminal.flush();
            if (terminal.writer().checkError()) throw new IOException("终端输出不可用");
        } catch (IOException | RuntimeException failure) {
            try { terminal.setAttributes(original); terminal.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    /** 启动唯一输入读取者。50ms 超时让退出不依赖中断 System.in，也不关闭全局输入流。 */
    public void start(Consumer<InputDecoder.Input> events) {
        terminal.handle(Terminal.Signal.WINCH, ignored -> events.accept(InputDecoder.Input.key("RESIZE")));
        terminal.handle(Terminal.Signal.INT, ignored -> events.accept(InputDecoder.Input.key("CTRL_C")));
        // 先走会话清理再离开 JVM，避免 JVM 与 JLine 的关闭钩子并发抢先关掉终端 writer，
        // 导致鼠标／粘贴模式的恢复指令来不及发出。SSH 断开同样需要停掉会话拥有的命令。
        previousTerm = org.jline.utils.Signals.register("TERM", () -> events.accept(InputDecoder.Input.key("EOF")));
        previousHup = org.jline.utils.Signals.register("HUP", () -> events.accept(InputDecoder.Input.key("EOF")));
        input = Thread.ofPlatform().daemon().name("jade-tui-input").start(() -> {
            var decoder = new InputDecoder(events);
            try {
                while (!closed) {
                    int c = terminal.reader().read(50);
                    if (c == -1) { events.accept(InputDecoder.Input.key("EOF")); return; }
                    if (c >= 0) decoder.accept(c); else decoder.tick();
                }
            } catch (IOException failure) { if (!closed) events.accept(InputDecoder.Input.key("EOF")); }
        });
    }
    public int columns() { return Math.max(2, terminal.getWidth()); }
    public int rows() { return Math.max(3, terminal.getHeight()); }
    public PrintWriter writer() { return terminal.writer(); }
    public boolean colors() {
        Integer colors = terminal.getNumericCapability(Capability.max_colors);
        return System.getenv("NO_COLOR") == null && !"dumb".equals(System.getenv("TERM")) && colors != null && colors > 0;
    }
    /** 停止读取后恢复光标、鼠标、粘贴和原始模式；不关闭 System.in。 */
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        if (input != null) {
            try { input.join(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        terminal.writer().print("\033[?2026l" + (colors() ? "\033[0m" : "") + "\033[?25h\033[?1000l\033[?1006l\033[?2004l\r\n");
        try { terminal.flush(); terminal.setAttributes(original); }
        finally {
            terminal.close();
            if (previousTerm != null) org.jline.utils.Signals.unregister("TERM", previousTerm);
            if (previousHup != null) org.jline.utils.Signals.unregister("HUP", previousHup);
        }
    }
}
