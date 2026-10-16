package com.jadecode.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.PrintAboveWriter;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.WriterOutputStream;

import com.jadecode.util.Ansi;

/**
 * 管理 CLI 的输入行和屏幕输出，让后台日志不会插进用户正在写的任务里。
 *
 * <p>例如用户只输入了“读取 README”，后台恰好打印一条 hook 日志。本类让 JLine 把日志
 * 显示到输入行上方，再画回提示符和“读取 README”，用户可以接着写，不会丢字或把日志当成输入。
 * REPL 仍能在模型工作时接受下一项任务；提示符表示可以输入，不表示模型已经做完。</p>
 *
 * <p>项目现有 hook、SDK 提示和记忆提示都使用 System.out/err。只在 CLI 会话运行期间，
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
    private final LineReader lines;
    /** 没有终端时直接读行，不向日志文件打印输入提示符。 */
    private final BufferedReader plainInput;
    /** hook 和其它模块仍使用 System.out/err，由这里转成输入行上方的输出。 */
    private final PrintStream routedOut;
    private final PrintStream routedErr;
    /** 同一会话资源可能被多次 close，第一次才恢复流和终端。 */
    private boolean closed;

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
        if (System.console() == null) {
            terminal = null;
            lines = null;
            plainInput = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            routedOut = null;
            routedErr = null;
            return;
        }
        terminal = TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).dumb(false).build();
        try {
            // 不因引号没配对就擅自多读一行，也不把用户的 ! 替换成历史命令。
            // 粘贴时的换行保留在缓冲区；历史只存在内存，不设置历史文件。
            DefaultParser parser = new DefaultParser().escapeChars(new char[0]).quoteChars(new char[0])
                    .eofOnEscapedNewLine(false);
            lines = LineReaderBuilder.builder().terminal(terminal).appName("JadeCode").parser(parser)
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    .option(LineReader.Option.BRACKETED_PASTE, true).build();
            // 终端粘贴会带开始/结束标记，JLine 不把其中的换行当作提交。
            // Alt+Enter 通常编码成 ESC 后跟回车；只往输入缓冲区加换行，不执行 accept-line。
            lines.getWidgets().put("jade-insert-newline", () -> {
                lines.getBuffer().write('\n');
                return true;
            });
            lines.getKeyMaps().get(LineReader.MAIN).bind(new Reference("jade-insert-newline"), "\033\r", "\033\n");
            plainInput = null;
            routedOut = aboveStream(lines);
            routedErr = aboveStream(lines);
            System.setOut(routedOut);
            System.setErr(routedErr);
        } catch (RuntimeException failure) {
            // 创建一半失败也要还原终端设置，不能让返回 shell 后的键盘保持错误模式。
            System.setOut(originalOut);
            System.setErr(originalErr);
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
            return lines.readLine(Ansi.cyan(prompt));
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
     * 在任务和命令都已停止后，恢复 System.out/err 及终端设置，重复调用也可以。
     * 不关闭全局 System.in；JLine 的终端负责恢复用户原来的回显和按键设置。
     * @throws IOException 终端设置恢复失败
     */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (terminal == null) return;
        try {
            // 项目正常日志以换行结束；退出时再补换行，让未结束的零散打印也能被 Writer 输出。
            routedOut.println();
            routedErr.println();
            routedOut.flush();
            routedErr.flush();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            terminal.close();
        }
    }
}
