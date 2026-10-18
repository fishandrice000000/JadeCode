package com.jadecode.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.Approval;
import com.jadecode.safety.PermissionGate;
import com.jadecode.util.Ansi;

/**
 * 统一读取用户在终端输入的任务和批准/拒绝命令，避免两个线程争着读同一个 stdin。
 *
 * <p>处理任务的线程发现某操作需要用户批准时，调用 ask，显示操作内容并等待。
 * 只有创建本对象的终端线程去 readLine；有申请在等待时，输入 y 才同意，n 才拒绝。
 * 多份申请排队，一次只展示一份，不要求输入申请 ID。空行不回答审批，
 * REPL 可以用它升级用户缓冲，不能把下一项任务误当成“同意执行上一条命令”。</p>
 *
 * <p>REPL 调 readQuery：普通文字返回给 ReplCommand 排队，y/n 审批答复在这里处理。
 * 单次 run 调 await：只在需要用户批准时读输入，不接收第二项任务。
 * 本类不判断哪些操作危险，那是 PermissionGate 的工作，也不调用模型或执行命令。</p>
 */
public final class ConsoleInput implements Approval, AutoCloseable {
    /** 只由 inputOwner 读；执行工具的线程不能自己调用 readLine。 */
    private final BufferedReader reader;
    /** CLI 传入的终端读取器；离线检查传 BufferedReader 时为 null。 */
    private final TerminalDisplay display;
    /** 显示申请内容和错误的函数；CLI 的输入提示另由 JLine 处理。 */
    private final Consumer<String> output;
    /** 创建本对象的线程，只有它可以读输入；不能用它判断用户或定时任务的权限。 */
    private final Thread inputOwner = Thread.currentThread();
    /** 尚未回答的申请，读写要拿锁；一次只支持一份。 */
    private CompletableFuture<Boolean> pending;
    /** 多个队友同时申请时排队；一次只显示队首，答复不会串到其它申请。 */
    private final java.util.ArrayDeque<ApprovalRequest> approvals = new java.util.ArrayDeque<>();

    /** 参数复制后展示，防止等待期间调用方修改原 JSON 而批准另一条命令。 */
    private record ApprovalRequest(String tool, ObjectNode input, String reason, CompletableFuture<Boolean> result) { }
    /** 是否已停止接受用户批准，不表示本类能停止整个会话的所有工具。 */
    private boolean closed;

    /**
     * 用 UTF-8 包装 System.in，并用 System.out 显示询问。
     * 构造时不读取内容、不启动线程，构造它的线程之后才负责读输入。
     */
    public ConsoleInput() {
        this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out::println);
    }

    /**
     * 记住要读的流和显示询问的函数，便于正式使用和离线检查。
     *
     * <p>不立即读取内容；以后 readQuery/await 必须在构造它的线程里调用。
     * close 不关闭传入的 reader 或全局 System.in。</p>
     * @param reader 用 readLine 读取用户输入的对象，不可为 null
     * @param output 显示申请内容和错误的函数，执行线程也可能调用，应尽快返回且不抛异常
     */
    public ConsoleInput(BufferedReader reader, Consumer<String> output) {
        this.reader = reader;
        this.display = null;
        this.output = output;
    }

    /**
     * CLI 使用同一终端处理输入和后台输出；本类仍决定文字是任务还是批准答复。
     * @param display 已经创建好的终端，不在本类 close 时关闭，要等任务线程先停止
     */
    public ConsoleInput(TerminalDisplay display) {
        this.reader = null;
        this.display = java.util.Objects.requireNonNull(display);
        this.output = CliOutput::print;
    }

    /**
     * 显示一次“是否允许这个操作”的申请，让执行线程停下来等用户回答。
     *
     * <p>先在 synchronized 内创建保存答复的 future、显示待批准操作，再松开锁等用户回答。
     * 终端线程才能拿到同一把锁处理用户回答。这里只等结果，绝不自己读取 stdin。
     * 已有另一申请时排队；用户已退出或等待被中断则按不同意处理。
     * finally 只清理自己这份申请，不清掉后来新建的申请。</p>
     * @param toolName 等待批准的工具名
     * @param input 展示给用户的参数，不修改，也不在这里执行
     * @param reason 为什么这个操作要先问用户
     * @return 输入 y 时为 true；n、退出或等待失败为 false，空行不是审批答复
     */
    @Override
    public boolean ask(String toolName, ObjectNode input, String reason) {
        CompletableFuture<Boolean> request;
        synchronized (this) {
            // 1. 新申请排在队尾；不能覆盖旧申请，也不能同时打印多份让 y/n 对应不清。
            if (closed) return false;
            request = new CompletableFuture<>();
            approvals.addLast(new ApprovalRequest(toolName, input.deepCopy(), reason, request));
            showNextApproval();
            notifyAll();
        }
        // 2. 执行线程在这里等，终端线程还能读用户输入；answer 或 close 设置答复后才继续。
        try {
            return request.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException failure) {
            return false;
        } finally {
            synchronized (this) {
                approvals.removeIf(item -> item.result() == request);
                if (pending == request) { pending = null; showNextApproval(); }
                notifyAll();
            }
        }
    }

    /**
     * REPL 每次读一项用户任务时调用，处理 y/n 答复后继续读，直到读到普通任务文字。
     *
     * <p>真实终端中，Enter 提交，Alt+Enter 换行，粘贴的多行不自动提交。
     * JLine 一次交来完整文字，不再把末尾反斜杠当作续行指令。没有 JLine 的旧流调用
     * 保留反斜杠续行。y/n 必须是整条输入，不会截走多行任务中间的一行。
     * 有审批在等待时，y 批准、n 拒绝；空行留给 REPL 升级缓冲。
     * 没有审批时 y/n 仍是普通任务。q/exit 与输入结束才退出。</p>
     * @return 去掉首尾空白的任务文字；q/exit 或输入结束时返回 null，
     *         并拒绝尚未回答的申请；空行返回空串。续行中输入结束则返回已输入的非空正文
     * @throws IOException 读输入失败，由 CLI 负责停止整个会话
     * @throws IllegalStateException 不是创建本对象的线程调用
     */
    public String readQuery() throws IOException {
        requireInputOwner();
        while (true) {
            String line = readLine("JadeCode >> ");
            if (line == null) { close(); return null; }
            // 只有完整的 y/n 可以回答审批，其它输入继续按任务或空回车处理。
            if (answer(line)) continue;
            if (isExit(line)) { close(); return null; }
            StringBuilder query = new StringBuilder();
            while ((display == null || !display.editsInput()) && softBreak(line)) {
                query.append(line, 0, line.length() - 1).append('\n');
                line = readLine("> ");
                if (line == null) break;
            }
            if (line != null) query.append(line);
            String text = query.toString().strip();
            // 空输入交给 REPL 升级缓冲，既不退出，也不默认拒绝待审批操作。
            return text;
        }
    }

    /**
     * 单次 run 等任务做完时调用，只有任务需要用户批准才读取输入，不接受新任务。
     *
     * <p>没申请时，终端线程只在 wait 里等；任务完成或 ask 新建申请时都会叫醒它。
     * 有申请才读一行，按 y/n 回答。这样普通任务不需要输入时，
     * 不会因为 stdin 已结束就被提前打断。</p>
     *
     * <p>读到 EOF 时拒绝未答申请，继续等执行线程处理后续结果。
     * 有审批在等待时，y 批准、n 拒绝；q/exit 报告取消，空行只提醒继续回答，
     * 由 RunCommand 的 close 停掉会话。</p>
     * @param task submit 返回的任务结果 future，不在这里重新创建或重试任务
     * @return 最终回答；运行器已经显示过，调用者不用重复打印
     * @throws IOException 有申请时读输入失败
     * @throws InterruptedException 等待线程被中断
     * @throws ExecutionException 任务失败，实际异常在 cause 中
     * @throws IllegalStateException 不是终端读取线程调用，或用户要求退出
     */
    public String await(CompletableFuture<String> task) throws IOException, InterruptedException, ExecutionException {
        requireInputOwner();
        task.whenComplete((value, failure) -> signal());
        while (!task.isDone()) {
            synchronized (this) {
                while (!task.isDone() && pending == null) wait();
                if (task.isDone()) break;
            }
            String line = readLine("允许执行？[y/n] >> ");
            if (line == null) close();
            else if (answer(line)) { /* 已按审批回答处理，继续等任务完成 */ }
            else if (isExit(line)) { close(); throw new IllegalStateException("Execution cancelled by user"); }
            else output.accept("请输入 y 批准 / n 拒绝，此时不接受新任务");
        }
        return task.get();
    }

    /**
     * 把整条 y/n 作为当前申请的答复，不读取输入，也不调用模型。
     * 例如粘贴的“y\n继续读取 README”是一条任务，不会拆出第一行批准危险命令。
     * @param line 完整的输入文字，不可为 null；忽略首尾空白与字母大小写
     * @return 有待答申请且收到 y/n 时为 true，表示这条输入已用作答复；
     *         其它情况为 false，让 REPL 继续按普通任务或退出处理
     */
    private boolean answer(String line) {
        String word = line.strip().toLowerCase(Locale.ROOT);
        boolean yes = word.equals("y");
        boolean no = word.equals("n");
        if (!yes && !no) return false;
        synchronized (this) {
            if (pending == null) return false;
            pending.complete(yes);
            pending = null;
            showNextApproval();
            notifyAll();
        }
        return true;
    }

    /**
     * 持有本对象锁时展示下一份审批。只有队首可以接受 y/n，其余只保存，不抢终端。
     * 申请携带调用方/Agent 说明和具体参数；失败或关闭都不会默认批准。
     */
    private void showNextApproval() {
        if (pending != null || approvals.isEmpty() || closed) return;
        ApprovalRequest next = approvals.removeFirst();
        pending = next.result();
        output.accept("[Approval] " + next.reason() + "\n  " + next.tool() + "(" + next.input() + ")"
                + "\n  输入 y 批准 / n 拒绝");
    }

    /**
     * 只允许创建本对象的线程读终端，避免多个 reader 抢走彼此的输入。
     * 这与任务能否申请批准无关，后者看 ExecutionContext.source。
     * @throws IllegalStateException 别的线程试图读取终端
     */
    private void requireInputOwner() {
        if (Thread.currentThread() != inputOwner) throw new IllegalStateException("Only the UI input owner may read stdin");
    }

    /**
     * 只用一个对象读输入：真实 CLI 交给 JLine，注入 BufferedReader 的旧调用者保持直接读取。
     * @param prompt 当前要显示的提示，JLine 会在日志输出后重新画出来
     * @return 一行文字，输入结束为 null
     * @throws IOException 读取失败
     */
    private String readLine(String prompt) throws IOException {
        if (display != null) return display.readLine(prompt);
        System.out.print(Ansi.cyan(prompt));
        System.out.flush();
        return reader.readLine();
    }

    /**
     * 判断这行是否还没写完，要继续读下一行。
     * @param line 非 null 的输入行
     * @return 行尾是单个反斜杠时为 true；尾部两个反斜杠保留为正文
     */
    private static boolean softBreak(String line) { return line.endsWith("\\") && !line.endsWith("\\\\"); }

    /**
     * 判断用户是否要求退出，不能把退出动作排在一个长任务后面等着。
     * @param line 非 null 输入行，q/exit 忽略大小写和首尾空白
     * @return q、exit 为 true；空行不是退出，多行任务中间不调用它判断退出
     */
    private static boolean isExit(String line) {
        return line.strip().equalsIgnoreCase("q") || line.strip().equalsIgnoreCase("exit");
    }

    /**
     * 用 notifyAll 叫醒单次 run 正在 wait 的终端线程。
     * 它醒来后仍检查 task 是否完成或 pending 是否有申请；这里不读输入。
     */
    private synchronized void signal() { notifyAll(); }

    /**
     * 退出询问功能，未回答的申请都按拒绝处理，并叫醒等待线程；重复调用也可以。
     *
     * <p>这样执行线程不会一直等用户，但不直接停止模型或命令，那由 SessionRuntime.close
     * 接着处理。这里不关闭 reader/System.in，也不能强行打断终端已经开始的 readLine。</p>
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (pending != null) pending.complete(false);
        pending = null;
        approvals.forEach(item -> item.result().complete(false));
        approvals.clear();
        notifyAll();
    }
}
