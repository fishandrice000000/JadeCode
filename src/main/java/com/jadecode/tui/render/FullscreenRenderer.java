package com.jadecode.tui.render;

import java.io.IOException;
import java.util.List;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.PresentedFrame;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.terminal.AnsiEncoder;
import com.jadecode.tui.text.StyledLine;

/**
 * Pi fullscreen 备用屏渲染：逐行绘制可寻址画布，正文 ChatViewport 与 dock 由上层合成。
 *
 * <p>进入 1049 备用屏、关闭自动换行、按配置捕获鼠标并隐藏光标；退出时恢复自动换行/主屏，
 * 转录重放由应用决定。每帧只重写变化的行（选择/搜索/overlay 也属于帧内容），行尾清空，
 * 防止旧内容残留；整帧由同步输出标记包围，flush 成功后才发布 PresentedFrame。图片等协议
 * 输出由 ImageService 在文字之后补画，本类先提供文字/光标骨架。</p>
 */
public final class FullscreenRenderer implements ScreenRenderer {
    private final Port port;
    private final boolean trueColor;
    private final boolean hyperlinks;
    private final boolean hardwareCursor;
    private final boolean captureMouse;
    private List<StyledLine> previous = List.of();
    private int width, height;
    private long frameId;
    private PresentedFrame presented;
    private boolean actionsValid = true;
    private boolean started, closed;

    /**
     * @param port 唯一终端输出端口
     * @param trueColor 24 位颜色
     * @param hyperlinks OSC 8
     * @param hardwareCursor 显示硬件光标
     * @param captureMouse fullscreen 捕获鼠标（regular 不捕获）
     */
    public FullscreenRenderer(Port port, boolean trueColor, boolean hyperlinks, boolean hardwareCursor, boolean captureMouse) {
        this.port = port; this.trueColor = trueColor; this.hyperlinks = hyperlinks;
        this.hardwareCursor = hardwareCursor; this.captureMouse = captureMouse;
    }

    @Override public Mode mode() { return Mode.FULLSCREEN; }

    @Override public void start() throws IOException {
        if (started) return;
        started = true;
        var writer = port.writer();
        writer.print(AnsiEncoder.enterFullscreen() + AnsiEncoder.hideCursor() + AnsiEncoder.enablePaste()
                + (captureMouse ? AnsiEncoder.enableMouse() : AnsiEncoder.disableMouse()));
        writer.print(AnsiEncoder.clearAll());
        writer.flush();
        if (port.outputFailed()) throw new IOException("Terminal write failed; stopping the interactive session");
    }

    @Override public PresentedFrame present(Frame frame, RenderContext context) throws IOException {
        if (!started) start();
        var writer = port.writer();
        List<StyledLine> lines = frame.lines();
        writer.print(AnsiEncoder.syncStart());
        int rows = Math.min(context.height(), Math.max(lines.size(), 1));
        for (int row = 0; row < rows; row++) {
            StyledLine line = row < lines.size() ? lines.get(row) : StyledLine.empty();
            boolean changed = width != context.width() || row >= previous.size() || !line.equals(previous.get(row));
            if (!changed) continue;
            writer.print(AnsiEncoder.cursorTo(row, 0));
            writer.print(AnsiEncoder.clearLine());
            writer.print(AnsiEncoder.encode(line, trueColor, port.colorsEnabled(), hyperlinks));
        }
        // 文档变短/浮层关闭后清掉旧尾部；备用屏没有 scrollback。
        if (rows < previous.size()) {
            writer.print(AnsiEncoder.cursorTo(rows, 0));
            writer.print("\u001b[0J");
        }
        Frame.Cursor cursor = frame.cursor();
        if (cursor.visible() && cursor.row() < rows) {
            writer.print(AnsiEncoder.cursorTo(cursor.row(), Math.min(cursor.column(), Math.max(0, context.width() - 1))));
            writer.print(hardwareCursor ? AnsiEncoder.showCursor() : AnsiEncoder.hideCursor());
        } else {
            writer.print(AnsiEncoder.cursorTo(rows - 1, 0));
            writer.print(AnsiEncoder.hideCursor());
        }
        writer.print(AnsiEncoder.syncEnd());
        writer.flush();
        if (port.outputFailed()) {
            actionsValid = false; previous = List.of(); presented = null;
            throw new IOException("Terminal write failed; stopping the interactive session");
        }
        previous = lines; width = context.width(); height = context.height(); actionsValid = true;
        presented = new PresentedFrame(++frameId, frame.terminalEpoch(), context.width(), rows, frame.revision(),
                frame.hits().stream().map(Frame.Hit::action).toList());
        return presented;
    }

    @Override public void invalidate() {
        actionsValid = false;
        // 暂退/浮层替换后终端可能被 shell 或其它程序写过；丢弃旧帧，下一次全量重写，
        // 不能只按差分跳过“未变化”的行而留下 shell 输出。
        previous = List.of();
    }

    @Override public PresentedFrame presented() { return actionsValid ? presented : null; }

    @Override public void finish() throws IOException {
        if (closed) return;
        // finish 已完整退出备用屏；若 finally 再 close 会再次发 1049l，把刚重放的主屏
        // transcript 擦回进入备用屏前的旧画面。这里标记 closed，close 变成幂等收尾。
        closed = true; actionsValid = false; presented = null;
        var writer = port.writer();
        writer.print(AnsiEncoder.showCursor() + AnsiEncoder.disableMouse() + AnsiEncoder.disablePaste()
                + AnsiEncoder.exitFullscreen());
        writer.flush();
        if (port.outputFailed()) throw new IOException("Terminal write failed during shutdown");
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true; actionsValid = false; presented = null;
        var writer = port.writer();
        writer.print(AnsiEncoder.showCursor() + AnsiEncoder.disableMouse() + AnsiEncoder.disablePaste()
                + AnsiEncoder.exitFullscreen());
        writer.flush();
        if (port.outputFailed()) throw new IOException("Terminal write failed while closing");
    }
}
