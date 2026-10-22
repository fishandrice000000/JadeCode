package com.jadecode.tui.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlocking;
import org.jline.utils.NonBlockingInputStream;

/**
 * 唯一的 JLine 终端适配器与原始字节读写边界，不创建 LineReader/Status/第二输出者。
 *
 * <p>生命周期分为 NEW → ACTIVE ⇄ HANDOFF → CLOSING → CLOSED：HANDOFF 用于外部编辑器或
 * Ctrl+Z 暂退，只恢复终端属性/停止读取，不关闭 Host；CLOSED 之后禁止重新激活。所有原始
 * 输入只由一条读取线程读取，并发布带序号/epoch 的 {@link TerminalEvent}；解码、组件状态和
 * 写屏都留在 UI 线程。这样不会出现两个 reader 争抢输入，也不让信号处理器并发写屏。</p>
 *
 * <p>raw 模式关闭 ISIG/IEXTEN/IXON，使 Ctrl+C 字节进入 UI，由应用实现“清草稿/再次退出”
 * 语义，而不是被系统直接杀进程。暂停/关闭时恢复启动前保存的完整 Attributes；终端 writer
 * 是 renderer 唯一输出通道，{@code System.out/err} 的兜底路由不能截获它。</p>
 */
public final class TerminalSession implements AutoCloseable {
    /** 终端资源状态。 */
    public enum State { NEW, ACTIVE, HANDOFF, CLOSING, CLOSED }

    private final Terminal terminal;
    private final Attributes original;
    private final PrintWriter writer;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private volatile State state = State.NEW;
    private volatile boolean running;
    private volatile Consumer<TerminalEvent> sink = ignored -> { };
    private NonBlockingInputStream input;
    private Thread reader;
    private Object previousTerm, previousHup;

    /**
     * 建立真实终端并保存原属性。失败时按逆序关闭已建立资源。
     * @throws IOException 非真实终端（stdout 不是 TTY）或初始化失败
     */
    public TerminalSession() throws IOException {
        if (System.console() == null) throw new IOException("jade repl requires a real terminal; use jade run for scripts");
        terminal = TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).dumb(false).build();
        original = terminal.getAttributes();
        writer = terminal.writer();
        try {
            enterRaw();
        } catch (IOException | RuntimeException failure) {
            try { terminal.setAttributes(original); terminal.close(); }
            catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** @return 当前终端资源状态。 */
    public State state() { return state; }

    /** @return 终端代次；resize/恢复后递增。 */
    public long epoch() { return epoch.get(); }

    /** 递增 terminalEpoch，使此前生成的鼠标/审批动作失效；由 UI 在 resize/模式切换时调用。 */
    public long bumpEpoch() { return epoch.incrementAndGet(); }

    /** @return 真实列数；0 表示终端未报告，布局使用显式 fallback。 */
    public int columns() { return terminal.getWidth(); }

    /** @return 真实行数；0 表示未知。 */
    public int rows() { return terminal.getHeight(); }

    /** @return 唯一受信输出 writer；只有 renderer/协议查询可以写。 */
    public PrintWriter writer() { return writer; }

    /** @return 最近一次输出是否有错误；renderer 在 flush 后必须检查。 */
    public boolean outputFailed() { return writer.checkError(); }

    /** @return 是否允许颜色（NO_COLOR/dumb 由 TerminalCapabilities 另判）。 */
    public boolean colors() {
        Integer colors = terminal.getNumericCapability(org.jline.utils.InfoCmp.Capability.max_colors);
        return colors != null && colors > 0;
    }

    /** @return 终端名（TERM），仅用于能力探测/诊断。 */
    public String type() { return terminal.getType(); }

    /**
     * 开始读取原始字节。信号只发事件，不写屏；读取循环使用 50ms 超时，退出不依赖关闭 System.in。
     * 重复调用只保留一个 reader。
     */
    public void start(Consumer<TerminalEvent> events) {
        if (state == State.CLOSED) throw new IllegalStateException("终端已经关闭");
        if (state == State.HANDOFF) throw new IllegalStateException("终端暂退中");
        this.sink = events == null ? ignored -> { } : events;
        if (reader != null) return;
        previousTerm = org.jline.utils.Signals.register("TERM", () -> emitEof());
        previousHup = org.jline.utils.Signals.register("HUP", () -> emitEof());
        terminal.handle(Terminal.Signal.WINCH, ignored -> emit(new TerminalEvent.Resize(
                terminal.getWidth(), terminal.getHeight(), sequence.incrementAndGet(), epoch.get())));
        terminal.handle(Terminal.Signal.CONT, ignored -> {
            // 从 shell fg 回来：JLine 已恢复 raw；UI 收到 CONT 后重查尺寸/协议。
            emit(new TerminalEvent.Resize(terminal.getWidth(), terminal.getHeight(),
                    sequence.incrementAndGet(), epoch.get()));
        });
        input = NonBlocking.nonBlocking("jade-stdin", terminal.input());
        running = true;
        state = State.ACTIVE;
        reader = Thread.ofPlatform().daemon().name("jade-tui-reader").start(this::readLoop);
    }

    private void readLoop() {
        byte[] buffer = new byte[4096];
        try {
            while (running) {
                int read = input.readBuffered(buffer, 0, buffer.length, 50);
                if (read == NonBlockingInputStream.READ_EXPIRED) continue;
                if (read == NonBlockingInputStream.EOF) { emitEof(); return; }
                if (read <= 0) continue;
                byte[] chunk = java.util.Arrays.copyOf(buffer, read);
                emit(new TerminalEvent.InputChunk(chunk, sequence.incrementAndGet(), epoch.get()));
            }
        } catch (IOException failure) {
            if (running && !terminated.get()) emit(new TerminalEvent.Failure(failure.getMessage(),
                    sequence.incrementAndGet(), epoch.get()));
            else if (running) emitEof();
        }
    }

    private void emit(TerminalEvent event) {
        try { sink.accept(event); } catch (RuntimeException ignored) { /* UI 队列已关闭时不能再抛出。 */ }
    }

    private void emitEof() {
        if (terminated.compareAndSet(false, true))
            emit(new TerminalEvent.Eof(sequence.incrementAndGet(), epoch.get()));
    }

    /**
     * 暂退到外部编辑器/子进程：停止读取、恢复属性、显示光标并关闭应用协议模式。
     * 不关闭 Terminal/Host；恢复由 {@link #resume()} 完成。失败时保持 HANDOFF 并抛出。
     */
    public synchronized void pauseForHandoff() throws IOException {
        if (state != State.ACTIVE && state != State.NEW) throw new IllegalStateException("当前状态不能暂退: " + state);
        state = State.HANDOFF;
        running = false;
        if (reader != null) {
            try { reader.join(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            reader = null;
        }
        // 同时退出键盘协商模式，避免 shell 收到 Kitty release/modifyOtherKeys 序列。
        writer.print("\u001b[<u\u001b[>4;0m\u001b[?2004l\u001b[?25h\u001b[0m\u001b[m");
        writer.flush();
        terminal.setAttributes(original);
        if (writer.checkError()) throw new IOException("终端在暂退时写入失败");
    }

    /** 从暂退恢复：重新 raw、清屏并重启唯一 reader；epoch 递增使旧动作失效。 */
    public synchronized void resume() throws IOException {
        if (state != State.HANDOFF) throw new IllegalStateException("终端不在暂退状态: " + state);
        enterRaw();
        state = State.ACTIVE;
        epoch.incrementAndGet();
        running = true;
        if (reader == null) reader = Thread.ofPlatform().daemon().name("jade-tui-reader").start(this::readLoop);
    }

    /** 重新进入 raw 并关闭会吞掉 Ctrl+C 的系统处理；暂停/恢复共用。 */
    private void enterRaw() throws IOException {
        terminal.enterRawMode();
        Attributes raw = terminal.getAttributes();
        raw.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        raw.setLocalFlag(Attributes.LocalFlag.IEXTEN, false);
        raw.setInputFlag(Attributes.InputFlag.IXON, false);
        terminal.setAttributes(raw);
        writer.print("\u001b[?1000l\u001b[?1002l\u001b[?1003l\u001b[?1006l\u001b[?2004h\u001b[?25h");
        writer.flush();
        if (writer.checkError()) throw new IOException("终端输出不可用");
    }

    /**
     * 幂等关闭：停止读取（不关闭全局 System.in）、恢复粘贴/鼠标/光标/颜色和原属性，
     * 注销信号处理器。重复调用不重复写恢复序列。
     */
    @Override public synchronized void close() throws IOException {
        if (state == State.CLOSED || state == State.CLOSING) return;
        state = State.CLOSING;
        running = false;
        if (reader != null && reader != Thread.currentThread()) {
            try { reader.join(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        reader = null;
        IOException failure = null;
        try {
            writer.print("\u001b[<u\u001b[>4;0m\u001b[?2026l\u001b[0m\u001b[?25h\u001b[?1000l\u001b[?1002l\u001b[?1003l\u001b[?1006l\u001b[?2004l\r\n");
            writer.flush();
            if (writer.checkError()) throw new IOException("终端在关闭时写入失败");
            terminal.setAttributes(original);
        } catch (IOException error) { failure = error; }
        finally {
            try {
                if (input != null) input.shutdown();
            } catch (RuntimeException ignored) { }
            if (previousTerm != null) org.jline.utils.Signals.unregister("TERM", previousTerm);
            if (previousHup != null) org.jline.utils.Signals.unregister("HUP", previousHup);
            previousTerm = previousHup = null;
            try { terminal.close(); } catch (RuntimeException ignored) { }
            state = State.CLOSED;
        }
        if (failure != null) throw failure;
    }

    /** 测试/宿主可注入的写入入口；仍然只有一个 writer。 */
    public void write(String value) {
        writer.print(value);
        writer.flush();
        if (writer.checkError()) throw new IllegalStateException("终端写入失败，停止交互会话");
    }
}
