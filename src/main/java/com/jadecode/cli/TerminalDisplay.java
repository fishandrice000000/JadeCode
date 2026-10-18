package com.jadecode.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.PrintAboveWriter;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.WriterOutputStream;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.AttributedString;
import org.jline.utils.Status;

import com.jadecode.util.Ansi;

/**
 * 管理 CLI 的输入行和屏幕输出，让后台日志不会插进用户正在写的任务里。
 *
 * <p>例如用户只输入了“读取 README”，后台恰好打印一条 hook 日志。本类让 JLine 把日志
 * 显示到输入行上方，再画回提示符和“读取 README”，用户可以接着写，不会丢字或把日志当成输入。
 * REPL 仍能在模型工作时接受下一项任务；提示符表示可以输入，不表示模型已经做完。</p>
 *
 * <p>流式半行显示在底部临时区域，完整行由 CliOutput 写入记录；双方与输入共用 JLine 的锁。
 * 不支持该区域的终端只显示完整行，不能把一个片段强行打印成一条永久日志。</p>
 *
 * <p>项目现有 hook、SDK 提示和记忆提示最终使用 System.out/err。只在 CLI 会话运行期间，
 * 将这两条输出转给同一个 JLine 读取器，避免逐个模块修改打印代码。退出时先恢复输出，
 * 再恢复终端设置；不修改任何 Agent 任务或权限规则。不在真实终端中运行时，保留普通流，
 * 不显示输入提示符或终端控制字符。</p>
 */
public final class TerminalDisplay implements AutoCloseable {
    /** 创建时的输出对象，JLine 建好后仍直接用原终端输出，避免自己截获自己。 */
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    /** 真实终端用 JLine，文件/管道输入时这两个字段都为 null。 */
    private final Terminal terminal;
    private final ScreenReader lines;
    /** 支持保存光标与滚动区域的终端才有底部预览，管道和简单终端为 null。 */
    private final Status preview;
    /** 没有终端时直接读行，不向日志文件打印输入提示符。 */
    private final BufferedReader plainInput;
    /** hook 和其它模块仍使用 System.out/err，由这里转成输入行上方的输出。 */
    private final PrintStream routed;
    /** 颜色只在本次 CLI 中按真实终端设置，退出时恢复原来的显示开关。 */
    private final boolean previousColors;
    /** 同一会话资源可能被多次 close，第一次才恢复流和终端。 */
    private volatile boolean closed;

    /**
     * 为一次 CLI 运行准备终端；此时不读取输入，也不启动模型或任务线程。
     *
     * @return 调用者必须最后关闭的屏幕对象，在它之前先关闭任务和批准申请
     * @throws IOException 真实终端无法初始化，不能假装已支持安全重画输入
     */
    public static TerminalDisplay open() throws IOException {
        return new TerminalDisplay();
    }

    /**
     * 先建立终端和读取器，再改 System.out/err，避免 JLine 的输出又回到自己身上。
     * 如果是在文件/管道中执行，不改变输出对象，也不启用行编辑。
     * @throws IOException 无法创建真实终端
     */
    private TerminalDisplay() throws IOException {
        previousColors = Ansi.useTerminal(false);
        if (System.console() == null) {
            terminal = null;
            lines = null;
            preview = null;
            plainInput = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            routed = null;
            return;
        }
        try {
            terminal = TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).dumb(false).build();
        } catch (IOException | RuntimeException failure) {
            Ansi.useTerminal(previousColors);
            throw failure;
        }
        try {
            // 颜色看 JLine 识别出的终端，而不是在类首次加载时永久缓存一次 console 判断。
            // 用户的 NO_COLOR 和 TERM=dumb 仍由 Ansi 尊重；不能为了美化污染重定向输出。
            Integer colors = terminal.getNumericCapability(Capability.max_colors);
            Ansi.useTerminal(colors != null && colors > 0);
            // 不因引号没配对就擅自多读一行，也不把用户的 ! 替换成历史命令。
            // 粘贴时的换行保留在缓冲区；历史只存在内存，不设置历史文件。
            DefaultParser parser = new DefaultParser().escapeChars(new char[0]).quoteChars(new char[0])
                    .eofOnEscapedNewLine(false);
            lines = new ScreenReader(terminal);
            lines.setParser(parser);
            lines.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
            lines.setOpt(LineReader.Option.BRACKETED_PASTE);
            boolean live = terminal.getStringCapability(Capability.change_scroll_region) != null
                    && terminal.getStringCapability(Capability.save_cursor) != null
                    && terminal.getStringCapability(Capability.restore_cursor) != null
                    && terminal.getStringCapability(Capability.cursor_address) != null
                    && terminal.getHeight() >= 5 && terminal.getWidth() > 0;
            preview = live ? Status.getStatus(terminal) : null;
            lines.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, Ansi.gray("       > "));
            // 终端粘贴会带开始/结束标记，JLine 不把其中的换行当作提交。
            // Alt+Enter 通常编码成 ESC 后跟回车；只往输入缓冲区加换行，不执行 accept-line。
            lines.getWidgets().put("jade-insert-newline", () -> {
                lines.getBuffer().write('\n');
                return true;
            });
            lines.getKeyMaps().get(LineReader.MAIN).bind(new Reference("jade-insert-newline"), "\033\r", "\033\n");
            plainInput = null;
            // 交互终端最终只有一块屏幕，用同一个流串行显示 out/err，避免多行回复被另一条日志拆开。
            // 管道分支不替换这两个流，仍可单独重定向错误。
            routed = aboveStream(lines);
            System.setOut(routed);
            System.setErr(routed);
        } catch (IOException | RuntimeException failure) {
            // 创建一半失败也要还原终端设置，不能让返回 shell 后的键盘保持错误模式。
            System.setOut(originalOut);
            System.setErr(originalErr);
            Ansi.useTerminal(previousColors);
            terminal.close();
            throw failure;
        }
    }

    /**
     * 用 JLine 的 Writer 接收原有 println；先正确解码 UTF-8，再在完整行到来时重画屏幕。
     * @param lines 同一个终端读取器，不创建第二个读取 stdin 的对象
     * @return 自动 flush 的输出流；中英文和 ANSI 颜色一起交给 JLine，不直接写进输入行
     */
    private static PrintStream aboveStream(LineReader lines) {
        return new PrintStream(new WriterOutputStream(new PrintAboveWriter(lines), StandardCharsets.UTF_8),
                true, StandardCharsets.UTF_8);
    }

    /**
     * 在创建 ConsoleInput 的终端线程读取一行；后台输出时 JLine 会恢复提示符和已输入的文字。
     * @param prompt REPL、续行或批准询问的提示，真实终端用青色显示
     * @return 用户文字；Ctrl+D、输入结束或 Ctrl+C 返回 null，让 CLI 执行正常的退出清理
     * @throws IOException 文件/管道输入读失败
     * @throws IllegalStateException 本对象已关闭
     */
    public String readLine(String prompt) throws IOException {
        if (closed) throw new IllegalStateException("Terminal is closed");
        if (lines == null) return plainInput.readLine();
        try {
            return lines.readLine(Ansi.bold(Ansi.cyan(prompt)));
        } catch (EndOfFileException | UserInterruptException exit) {
            return null;
        }
    }

    /**
     * 告诉 ConsoleInput 是否由 JLine 完整编辑一条输入。
     * @return true 时换行由粘贴或 Alt+Enter 保留，不再把文本末尾的反斜杠当成必须继续读行
     */
    public boolean editsInput() {
        return lines != null;
    }

    /**
     * 在输入区之外实时显示尚未换行的片段，不把每个 token 当成一条永久日志。
     *
     * <p>底部只显示当前半行末尾，完整内容仍由 CliOutput 写进上方记录。用 JLine 自己的锁
     * 同步预览、普通日志和输入重画，避免直接写 ANSI 光标指令破坏用户正在输入的文字。
     * 终端过小、不支持预览或已退出时不更新；调用方仍会在换行/结束时输出完整正文。</p>
     * @param label Thinking 或 Reply，由程序决定，不来自模型参数
     * @param text 当前未换行片段的完整显示副本；不修改历史
     */
    void preview(String label, String text) {
        if (preview == null || closed) return;
        lines.updateScreen(() -> {
            if (closed) return;
            if (terminal.getHeight() < 5) { preview.update(java.util.List.of()); return; }
            preview.resize(terminal.getSize());
            // 预览只保留屏幕宽度以内的尾部；上方记录不截断。控制字符不作为光标指令执行。
            var line = new AttributedString(text.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " "));
            int width = Math.max(1, terminal.getWidth() - 1);
            int length = line.columnLength();
            preview.update(java.util.List.of(AttributedString.fromAnsi(Ansi.gray("[Leader] [" + label + "] generating…")),
                    line.columnSubSequence(Math.max(0, length - width), length)));
        });
    }

    /** 清掉临时预览；已完成的正文由 CliOutput 保留，不删除任何终端历史。 */
    void clearPreview() {
        if (preview == null || closed) return;
        lines.updateScreen(() -> { if (!closed) preview.update(java.util.List.of()); });
    }

    /** @return 已关闭时展示对象丢弃晚到片段，不在恢复 shell 后继续画预览 */
    boolean isClosed() { return closed; }

    /**
     * 让新增的预览更新使用与 JLine 输入/printAbove 相同的锁。
     * 只扩展屏幕操作，不改变按键、补全或消息调度；避免自己维护第二套终端锁和光标状态。
     */
    private static final class ScreenReader extends LineReaderImpl {
        /** @param terminal 已创建的唯一终端，不另读 stdin */
        private ScreenReader(Terminal terminal) throws IOException { super(terminal, "JadeCode"); }

        /** @param update 持有 JLine 锁时执行的短屏幕更新，不得读文件、请求模型或等用户 */
        private void updateScreen(Runnable update) {
            lock.lock();
            try {
                update.run();
                if (isReading()) redisplay();
                getTerminal().flush();
            } finally { lock.unlock(); }
        }
    }

    /**
     * 在任务和命令都已停止后，恢复 System.out/err 及终端设置，重复调用也可以。
     * 不关闭全局 System.in；JLine 的终端负责恢复用户原来的回显和按键设置。
     * @throws IOException 终端设置恢复失败
     */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (terminal == null) {
            Ansi.useTerminal(previousColors);
            return;
        }
        try {
            if (preview != null) lines.updateScreen(preview::close);
            // 项目正常日志以换行结束；退出时再补换行，让未结束的零散打印也能被 Writer 输出。
            routed.println();
            routed.flush();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            Ansi.useTerminal(previousColors);
            terminal.close();
        }
    }
}
