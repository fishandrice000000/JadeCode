package com.jadecode.cli;

import java.io.PrintStream;
import java.util.Set;

import com.jadecode.util.Ansi;
import com.jadecode.llm.ResponseStream;

/**
 * 把会话交来的文字排成易读的终端输出，不改变模型实际收到或返回的消息。
 *
 * <p>例如队友的整条回复原来是“[Teammate ID] [Reply] 正文”。这里把身份和类型放在
 * 独立标题行，正文另起一行；工具与通知仍是短日志。只装饰程序添加的标签，不解析正文中的
 * Markdown，所以代码块、缩进和用户需要复制的命令保持原样。文件和管道中不使用颜色。</p>
 *
 * <p>一份完整回复只打印一次。正常输出和错误输出共用锁，避免两个 Agent 的标题和正文
 * 在两次打印之间互相穿插。已有 SDK 等模块的直接输出不经此类，不在这里过滤其警告。</p>
 */
public final class CliOutput {
    /** 同时保护 stdout/stderr 的整段输出；不阻止 Agent 本身并行工作。 */
    private static final Object OUTPUT_LOCK = new Object();
    /** 只装饰程序已经使用的标签；未知文字不会因排版而被改写或丢弃。 */
    private static final Set<String> LABELS = Set.of("[Reply]", "[Thinking]", "[Incoming]", "[Background]", "[Cron]",
            "[Approval]", "[Warning]", "[Plan]", "[Error]", "[Rejected]", "[Blocked]",
            "[Request]", "[Tool]", "[Turn]", "[Memory]");

    /** 所有方法都是展示工具，不需要创建对象。 */
    private CliOutput() { }

    /**
     * 显示正常事件或回复，使用调用时的 System.out，兼容 JLine 对全局输出的接管。
     * @param text 带可选 Agent 身份和事件标签的整条文字；不修改原字符串
     */
    public static void print(String text) {
        write(System.out, text);
    }

    /**
     * 显示错误或警告，重定向运行时仍保留 stderr 与 stdout 的区别。
     * @param text 完整错误说明；本方法不判断业务是否成功，也不抑制错误
     */
    public static void error(String text) {
        write(System.err, text);
    }

    /**
     * 在同一把锁内排版和打印，确保整条多行回复不会被另一条展示输出拆开。
     * @param stream 当前终端或重定向流，不关闭它
     * @param text 完整展示文字
     */
    private static void write(PrintStream stream, String text) {
        synchronized (OUTPUT_LOCK) {
            stream.println(format(text));
        }
    }

    /**
     * 分离开头的 Agent 身份与事件类型，只给程序生成的标题上色。
     *
     * <p>回复、计划和审批前留空行，标题与正文分开；工具、通知、计数则紧凑显示。
     * 未知标签或没有标签的文字原样保留，不能因新增事件而丢失输出。解析只看开头，
     * 不会把模型正文里的 “[Error]” 或 Markdown 链接误当成另一个系统事件。</p>
     * @param text 整条展示文字，不应为 null
     * @return 带可选色码和分段换行的显示副本，原文与消息历史均不变
     */
    static String format(String text) {
        String source = "";
        String rest = text;
        // 身份是工厂添加的固定前缀。先取出它，才能识别后面的 Reply/Tool 等类型。
        if (rest.startsWith("[Leader] ") || rest.startsWith("[Teammate ")) {
            int end = rest.indexOf("] ");
            if (end >= 0) {
                String label = rest.substring(0, end + 1);
                source = (label.equals("[Leader]") ? Ansi.cyan(label) : Ansi.magenta(label)) + " ";
                rest = rest.substring(end + 2);
            }
        }
        if (!rest.startsWith("[")) return source + rest;
        int end = rest.indexOf(']');
        if (end < 0) return source + rest;
        String label = rest.substring(0, end + 1);
        // 只识别已有的程序标签。未知开头可能是普通链接等正文，必须原样保留。
        if (!LABELS.contains(label) && !label.startsWith("[Subagent ")) return source + rest;
        // 只去掉标签和正文之间程序加的一处分隔，不 strip 正文，避免破坏代码缩进。
        String body = rest.substring(end + 1);
        if (body.startsWith(" ") || body.startsWith("\n")) body = body.substring(1);
        String heading = source + style(label);
        boolean separate = label.equals("[Reply]") || label.equals("[Thinking]")
                || label.equals("[Plan]") || label.equals("[Approval]");
        if (separate) return "\n" + heading + (body.isEmpty() ? "" : "\n" + body) + "\n";
        return heading + (body.isEmpty() ? "" : " " + body);
    }

    /**
     * 按类型而非“业务成功”选择颜色，Reply 的绿色不表示测试或任务已经成功。
     * @param label 开头的完整方括号标签
     * @return 未知类型原样返回；无颜色终端中所有分支也都是普通文字
     */
    private static String style(String label) {
        return switch (label) {
            case "[Reply]" -> Ansi.bold(Ansi.green(label));
            case "[Thinking]" -> Ansi.gray(label);
            case "[Incoming]", "[Background]", "[Cron]" -> Ansi.cyan(label);
            case "[Approval]", "[Warning]", "[Plan]" -> Ansi.yellow(label);
            case "[Error]", "[Rejected]", "[Blocked]" -> Ansi.red(label);
            case "[Request]", "[Tool]", "[Turn]", "[Memory]" -> Ansi.gray(label);
            default -> label.startsWith("[Subagent ") ? Ansi.magenta(label) : label;
        };
    }

    /**
     * 显示启动说明，让用户知道提示符表示可继续输入，不是所有 Agent 已经完成。
     * 不显示模型、密钥或配置内容，也不启动任何任务。
     */
    public static void welcome() {
        print(Ansi.bold(Ansi.cyan("JadeCode")) + "  /  coding agent\n"
                + "  Enter 提交 · Alt+Enter 换行 · 支持多行粘贴\n"
                + "  工作中也可继续输入；空输入 Enter 将待处理内容插入当前轮次\n"
                + "  审批用 y/n；q/exit 或 Ctrl+D 退出\n");
    }

    /**
     * 为 Leader 的一次正式请求创建独立显示对象；队友与辅助调用不安装它。
     * @param display 当前 CLI 的终端对象，不由此返回值关闭
     * @return 新的片段缓冲，complete/failed 会清理预览；不拥有模型历史或网络流
     */
    public static ResponseStream stream(TerminalDisplay display) {
        return new Streaming(display);
    }

    /**
     * 按行保存生成中的文字，半行放底部预览，完成行只打印一次。
     *
     * <p>这不是 SDK 的完整消息缓冲，只是当前显示半行。签名和工具 JSON 从不传入。
     * 所有操作与完整事件共用 OUTPUT_LOCK，避免队友日志插进半条永久输出；JLine 再负责
     * 光标和输入。管道没有预览，按完整行及响应结束时输出，保证不混入屏幕控制符。</p>
     */
    private static final class Streaming implements ResponseStream {
        private final TerminalDisplay display;
        private final StringBuilder pending = new StringBuilder();
        private String kind;
        private long refreshed;
        private boolean ended;

        /** 只保存引用，不打印标题；模型没返回可读片段就不创建空回复。 */
        private Streaming(TerminalDisplay display) { this.display = java.util.Objects.requireNonNull(display); }

        /** 接收正文增量，完整正文仍由 SDK 保存在最终消息中。 */
        @Override public void text(String text) { append("Reply", text); }

        /** 只接收接口明确返回的可读思考，不推测、补造或展开加密内容。 */
        @Override public void thinking(String text) { append("Thinking", text); }

        /**
         * 先处理内容类型切换，再提交完整行，最后限频更新半行预览。
         * @param next 程序选定的 Reply/Thinking 类型
         * @param text 原始增量，空片段不创建标题；原文不 strip，以保留代码缩进
         */
        private void append(String next, String text) {
            synchronized (OUTPUT_LOCK) {
                if (ended || display.isClosed() || text.isEmpty()) return;
                if (!next.equals(kind)) {
                    flush();
                    kind = next;
                    print("[Leader] [" + next + "]");
                }
                pending.append(text);
                int newline;
                while ((newline = pending.indexOf("\n")) >= 0) {
                    display.clearPreview();
                    // 直接写正文，不再调用 format；正文里的标签或 Markdown 不是系统事件。
                    System.out.println(pending.substring(0, newline));
                    pending.delete(0, newline + 1);
                }
                long now = System.nanoTime();
                if (refreshed == 0 || now - refreshed >= 80_000_000L) {
                    display.preview(kind, pending.toString());
                    refreshed = now;
                }
            }
        }

        /** 清除临时区，把尚未换行的原文完整提交一次；调用者持有 OUTPUT_LOCK。 */
        private void flush() {
            display.clearPreview();
            if (!pending.isEmpty()) {
                System.out.println(pending);
                pending.setLength(0);
            }
        }

        /** 完整响应已校验通过，提交末行并结束预览；不表示业务任务完成。 */
        @Override public void complete() { end(false); }

        /** 保留用户已看到的部分文字，但明确标为中断，不把它当作完整回答。 */
        @Override public void failed() { end(true); }

        /**
         * 一次性收尾，退出后的晚到请求不再写屏幕。
         * @param failed true 时说明预览不完整；没有任何片段时由 Session 直接报告原错误
         */
        private void end(boolean failed) {
            synchronized (OUTPUT_LOCK) {
                if (ended) return;
                ended = true;
                if (display.isClosed()) { pending.setLength(0); return; }
                flush();
                if (failed && kind != null) error("[Warning] Leader 输出中断，以上片段不是完整响应，未作为完整消息采用");
            }
        }
    }
}
