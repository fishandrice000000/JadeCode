package com.jadecode.tui.render;

import java.io.IOException;
import java.util.List;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.PresentedFrame;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.terminal.AnsiEncoder;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * Pi regular 主屏差分渲染：内容自然向下增长，编辑器跟随对话，不贴终端底部。
 *
 * <p>保存旧行、上次宽高、文档 viewportTop、真实硬件光标行和可寻址边界。同尺寸且变更仍在
 * 可寻址区域时，从首个变化行开始重写并清理尾部；宽度变化、高度变化或变更起点已滚出
 * 可寻址区时，按 Pi 规则清 viewport/home/scrollback 后重放。首帧直接输出，不先滚一屏或
 * 清历史。跨过 scrollback 的旧行无法被 ANSI 寻址，因此不能假装“永不清屏”。</p>
 *
 * <p>本类只在 UI 线程使用；不认识消息/工具，也不做布局。所有正文已经由组件净化，编码只
 * 通过 {@link AnsiEncoder} 产生。</p>
 */
public final class RegularRenderer implements ScreenRenderer {
    private final Port port;
    private final boolean trueColor;
    private final boolean hyperlinks;
    private final boolean hardwareCursor;
    private List<StyledLine> previous = List.of();
    private int width, height, viewportTop, hardwareRow, maxLinesRendered;
    private long frameId;
    private PresentedFrame presented;
    private boolean actionsValid = true;
    private boolean started, closed;
    /** 上一次画在屏幕层的 flash；只用于到期/变化时恢复该行，不属于 previous 文档。 */
    private Frame.Flash lastFlash;
    private int lastFlashLine = -1;

    /**
     * @param port 唯一终端输出/尺寸端口
     * @param trueColor 是否允许 24 位颜色
     * @param hyperlinks 是否允许 OSC 8
     * @param hardwareCursor 是否显示真实光标（IME 定位）
     */
    public RegularRenderer(Port port, boolean trueColor, boolean hyperlinks, boolean hardwareCursor) {
        this.port = port; this.trueColor = trueColor; this.hyperlinks = hyperlinks; this.hardwareCursor = hardwareCursor;
    }

    @Override public Mode mode() { return Mode.REGULAR; }

    @Override public void start() throws IOException {
        if (started) return;
        started = true;
        // 主屏不捕获鼠标：保留终端原生拖选/滚轮；关闭可能遗留的备用屏/自动换行覆盖。
        var writer = port.writer();
        writer.print(AnsiEncoder.disableMouse() + AnsiEncoder.disablePaste() + "\u001b[?7h");
        writer.flush();
        if (port.outputFailed()) throw new IOException("终端写入失败，停止交互会话");
    }

    @Override public PresentedFrame present(Frame frame, RenderContext context) throws IOException {
        if (!started) start();
        if (frame.lines().isEmpty()) return presented;
        var writer = port.writer();
        boolean layoutChanged = previous.isEmpty() || width != context.width() || height != context.height();
        boolean existed = !previous.isEmpty();
        int firstChanged = firstChanged(frame.lines(), previous);
        boolean reset = layoutChanged || firstChanged < viewportTop;
        List<StyledLine> painted = frame.lines();
        // 首帧的 height 字段尚未由上一帧赋值；布局/滚动边界必须使用本帧实际高度。
        int frameHeight = Math.max(1, context.height());
        writer.print(AnsiEncoder.syncStart() + AnsiEncoder.hideCursor());
        if (reset) {
            if (existed) {
                // Pi 在宽度/高度/旧行变化时清 viewport + scrollback，再从首行重放。
                writer.print(AnsiEncoder.clearAll());
            }
            // 首帧不清屏：从当前 shell 光标处直接输出；viewportTop 为 0 表示可寻址区从这里开始。
            viewportTop = 0; hardwareRow = 0; maxLinesRendered = 0; firstChanged = 0;
        } else if (lastFlash != null) {
            // flash 不是文档行的一部分；新帧没有/改变它时先恢复旧行，避免残留到历史。
            paintDocumentLine(lastFlashLine, painted, context.width());
        }
        int start = Math.min(firstChanged, painted.size() - 1);
        if (start <= 0) hardwareRow = 0;
        moveTo(start);
        for (int row = start; row < painted.size(); row++) {
            writer.print(AnsiEncoder.clearLine());
            writer.print(AnsiEncoder.encode(painted.get(row), trueColor, port.colorsEnabled(), hyperlinks));
            if (row + 1 < painted.size()) {
                writer.print("\r\n");
                hardwareRow = row + 1;
                if (hardwareRow >= viewportTop + frameHeight) viewportTop = hardwareRow - frameHeight + 1;
            }
        }
        writer.print(AnsiEncoder.clearBelow());
        maxLinesRendered = Math.max(maxLinesRendered, painted.size());
        // flash 画在当前可见文档区顶部右侧；它不进入 previous，因此不会改写文档/scrollback。
        if (frame.flash() != null && !painted.isEmpty()) {
            int flashLine = Math.max(0, Math.min(painted.size() - 1, viewportTop));
            StyledLine overlay = withFlash(painted.get(flashLine), frame.flash(), context.width());
            moveTo(flashLine);
            writer.print(AnsiEncoder.clearLine());
            writer.print(AnsiEncoder.encode(overlay, trueColor, port.colorsEnabled(), hyperlinks));
            lastFlash = frame.flash(); lastFlashLine = flashLine;
        } else {
            lastFlash = null; lastFlashLine = -1;
        }
        // 光标：regular 只显示 Editor/Approval 的真实焦点；超出可见区时夹在可寻址区底部。
        Frame.Cursor cursor = frame.cursor();
        int targetRow = Math.max(viewportTop, Math.min(painted.size() - 1, cursor.row()));
        moveTo(targetRow);
        writer.print("\u001b[" + (Math.max(0, cursor.column()) + 1) + "G");
        writer.print(cursor.visible() && hardwareCursor ? AnsiEncoder.showCursor() : AnsiEncoder.hideCursor());
        writer.print(AnsiEncoder.syncEnd());
        writer.flush();
        if (port.outputFailed()) {
            actionsValid = false; previous = List.of(); presented = null;
            throw new IOException("终端写入失败，停止交互会话");
        }
        previous = painted; width = context.width(); height = context.height(); actionsValid = true;
        presented = new PresentedFrame(++frameId, frame.terminalEpoch(), context.width(),
                Math.min(context.height(), painted.size()), frame.revision(),
                frame.hits().stream().map(Frame.Hit::action).toList());
        return presented;
    }

    /**
     * 用当前文档内容重画某一行（清除旧 flash）。只画仍在可见区的行；文档已缩短时跳过，
     * 由本帧的 clearBelow/diff 负责清理。
     */
    private void paintDocumentLine(int row, List<StyledLine> painted, int width) {
        if (row < viewportTop || row >= viewportTop + Math.max(1, height) || row >= painted.size()) return;
        moveTo(row);
        port.writer().print(AnsiEncoder.clearLine());
        port.writer().print(AnsiEncoder.encode(painted.get(row).crop(Math.max(0, width)),
                trueColor, port.colorsEnabled(), hyperlinks));
    }

    /** 把短提示右对齐叠加到一行副本；不修改传入行，也不改文档缓存。 */
    private static StyledLine withFlash(StyledLine line, Frame.Flash flash, int width) {
        int columns = Math.max(1, width);
        String text = flash.text();
        int textWidth = DisplayWidth.width(text);
        if (textWidth == 0) return line;
        if (textWidth >= columns) return StyledLine.of(DisplayWidth.take(text, columns), flash.style());
        int keep = columns - textWidth - 1;
        StyledLine body = line.crop(keep);
        var spans = new java.util.ArrayList<>(body.spans());
        int used = body.width();
        if (used < keep) spans.add(new Span(" ".repeat(keep - used), Style.PLAIN, null));
        spans.add(new Span(" ", Style.PLAIN, null));
        spans.add(new Span(DisplayWidth.take(text, textWidth), flash.style(), null));
        return new StyledLine(spans, body.annotation());
    }

    /** 移动硬件光标到文档行；只使用相对移动，避免假设可见区顶部。 */
    private void moveTo(int row) {
        if (row == hardwareRow) { port.writer().print('\r'); return; }
        int delta = row - hardwareRow;
        var writer = port.writer();
        writer.print('\r');
        if (delta > 0) writer.print("\u001b[" + delta + "B");
        else writer.print("\u001b[" + (-delta) + "A");
        hardwareRow = row;
    }

    private static int firstChanged(List<StyledLine> next, List<StyledLine> old) {
        int limit = Math.min(next.size(), old.size());
        for (int i = 0; i < limit; i++) if (!next.get(i).equals(old.get(i))) return i;
        return limit;
    }

    @Override public void invalidate() {
        actionsValid = false;
        // 主屏无法擦掉 shell 已写入的历史；丢弃旧帧只保证下次重新输出当前内容。
        previous = List.of();
        lastFlash = null; lastFlashLine = -1;
    }

    @Override public PresentedFrame presented() { return actionsValid ? presented : null; }

    @Override public void finish() throws IOException {
        if (closed) return;
        closed = true; actionsValid = false; presented = null;
        var writer = port.writer();
        // 让 shell 提示符出现在最后一行内容之后，不覆盖编辑器/页脚。
        writer.print(AnsiEncoder.showCursor() + "\r\n");
        writer.flush();
        if (port.outputFailed()) throw new IOException("终端在收尾时写入失败");
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true; actionsValid = false; presented = null;
        var writer = port.writer();
        writer.print(AnsiEncoder.showCursor() + AnsiEncoder.disablePaste() + AnsiEncoder.disableMouse());
        writer.flush();
        if (port.outputFailed()) throw new IOException("终端在关闭时写入失败");
    }
}
