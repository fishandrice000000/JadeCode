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
import com.jadecode.safety.PermissionGate;

/**
 * 统一读取用户在终端输入的任务和批准/拒绝命令，避免两个线程争着读同一个 stdin。
 *
 * <p>处理任务的线程发现某操作需要用户批准时，调用 ask，显示操作内容并等待。
 * 只有创建本对象的终端线程去 readLine；看到 /approve ID、/deny ID，或直接输入的 y/yes/n/no
 * （空行算拒绝）后，才回答对应的申请。不能把用户刚输入的下一项任务当成“同意执行上一条命令”。</p>
 *
 * <p>REPL 调 readQuery：普通文字返回给 ReplCommand 排队，批准/拒绝命令在这里处理。
 * 单次 run 调 await：只在需要用户批准时读输入，不接收第二项任务。
 * 本类不判断哪些操作危险，那是 PermissionGate 的工作，也不调用模型或执行命令。</p>
 */
public final class ConsoleInput implements PermissionGate.Approval, AutoCloseable {
    /** 只由 inputOwner 读；执行工具的线程不能自己调用 readLine。 */
    private final BufferedReader reader;
    /** 显示申请内容和错误的函数；普通的输入提示仍直接打印到 System.out。 */
    private final Consumer<String> output;
    /** 创建本对象的线程，只有它可以读输入；不能用它判断用户或定时任务的权限。 */
    private final Thread inputOwner = Thread.currentThread();
    /** 尚未回答的申请，读写要拿锁；一次只支持一份。 */
    private Pending pending;
    /** 本终端的申请序号，避免同意了错误的或已经过期的申请。 */
    private long counter;
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
        this.output = output;
    }

    /**
     * 显示一次“是否允许这个操作”的申请，让执行线程停下来等用户回答。
     *
     * <p>先在 synchronized 内创建 Pending、显示申请 ID，再松开锁等待 answer future。
     * 终端线程才能拿到同一把锁处理用户回答。这里只等结果，绝不自己读取 stdin。
     * 如果已有另一申请、用户已退出或等待被中断，都按不同意处理。
     * finally 只清理自己这份申请，不清掉后来新建的申请。</p>
     * @param toolName 等待批准的工具名
     * @param input 展示给用户的参数，不修改，也不在这里执行
     * @param reason 为什么这个操作要先问用户
     * @return 用户明确批准时为 true（/approve、y、yes 都算）；拒绝、空行、退出、重复申请或等待失败都是 false
     */
    @Override
    public boolean ask(String toolName, ObjectNode input, String reason) {
        Pending request;
        synchronized (this) {
            // 1. 已有申请没回答就拒绝新申请，不能把旧申请直接覆盖掉。
            if (closed || pending != null) return false;
            request = new Pending("approval_" + ++counter, new CompletableFuture<>());
            pending = request;
            output.accept("[Permission " + request.id() + "] " + reason + "\n  " + toolName + "(" + input + ")"
                    + "\n  /approve " + request.id() + " 或 /deny " + request.id()
                    + "（也可直接输入 y 批准 / n 拒绝）");
            notifyAll();
        }
        // 2. 执行线程在这里等，终端线程还能读用户输入；answer 或 close 设置答复后才继续。
        try {
            return request.answer().get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException failure) {
            return false;
        } finally {
            synchronized (this) {
                if (pending == request) pending = null;
                notifyAll();
            }
        }
    }

    /**
     * REPL 每次读一项用户任务时调用，处理批准命令后继续读，直到读到普通任务文字。
     *
     * <p>行尾单个反斜杠表示下一行还属于同一任务。批准命令只在单独输入的一行识别，
     * 不会把多行任务正文里的一段文字截走。有审批在等待时，y/yes 批准、n/no 或空行拒绝；
     * 没有审批时，这些文字仍是普通任务，空行也沿用原来的退出行为。</p>
     * @return 去掉首尾空白的任务文字；q/exit 或输入结束时返回 null，
     *         并拒绝尚未回答的申请；没有审批时的空行也会退出。续行中输入结束则返回已输入的非空正文
     * @throws IOException 读输入失败，由 CLI 负责停止整个会话
     * @throws IllegalStateException 不是创建本对象的线程调用
     */
    public String readQuery() throws IOException {
        requireInputOwner();
        while (true) {
            System.out.print("JadeCode >> ");
            System.out.flush();
            String line = reader.readLine();
            if (line == null) { close(); return null; }
            // 有审批在等时，y/yes/n/no/空行在这里被消化；没有审批时它们仍是普通任务，空行照旧退出。
            if (answer(line)) continue;
            if (isExit(line)) { close(); return null; }
            StringBuilder query = new StringBuilder();
            while (softBreak(line)) {
                query.append(line, 0, line.length() - 1).append('\n');
                System.out.print("> ");
                System.out.flush();
                line = reader.readLine();
                if (line == null) break;
            }
            if (line != null) query.append(line);
            String text = query.toString().strip();
            if (text.isEmpty()) { close(); return null; }
            return text;
        }
    }

    /**
     * 单次 run 等任务做完时调用，只有任务需要用户批准才读取输入，不接受新任务。
     *
     * <p>没申请时，终端线程只在 wait 里等；任务完成或 ask 新建申请时都会叫醒它。
     * 有申请才读一行，按 /approve、/deny 或简写的 y/n 回答。这样普通任务不需要输入时，
     * 不会因为 stdin 已结束就被提前打断。</p>
     *
     * <p>读到 EOF 时拒绝未答申请，继续等执行线程处理后续结果。
     * 有审批在等待时，y/yes 批准、n/no 或空行拒绝；没有审批时 q/exit 或空行才报告取消，
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
            String line = reader.readLine();
            if (line == null) close();
            else if (answer(line)) { /* 已按审批回答处理，继续等任务完成 */ }
            else if (isExit(line)) { close(); throw new IllegalStateException("Execution cancelled by user"); }
            else output.accept("请使用 /approve 或 /deny（也可直接输入 y / n），不接受新任务");
        }
        return task.get();
    }

    /**
     * 检查这一行是否在回答批准申请；不读输入流，也不提交任务。
     * @param line 已读取的一行文字，不可为 null
     * @return /approve 或 /deny 命令返回 true；即使缺 ID、ID 过期或不匹配也只提示错误，
     *         不批准、不发给模型。简写 y/yes/n/no 只在有审批在等待时生效；
     *         其它文字（以及没有审批时的 y/n）返回 false，留给上层当作任务
     */
    private boolean answer(String line) {
        String[] parts = line.strip().split("\\s+", 2);
        boolean allow = parts[0].equals("/approve");
        boolean deny = parts[0].equals("/deny");
        if (!allow && !deny) return answerYesNo(line);
        synchronized (this) {
            if (parts.length != 2 || pending == null || !pending.id().equals(parts[1])) {
                output.accept("[Permission] No matching pending approval");
            } else {
                pending.answer().complete(allow);
                pending = null;
                notifyAll();
            }
        }
        return true;
    }

    /**
     * 处理简写的审批回答。有审批在等待时，y/yes 批准，n/no 或空行拒绝，并消耗这一行；
     * 没有审批在等待时返回 false，让上层把 y/n 当普通任务、空行按退出处理。
     */
    private boolean answerYesNo(String line) {
        String word = line.strip().toLowerCase(Locale.ROOT);
        boolean yes = word.equals("y") || word.equals("yes");
        boolean no = word.equals("n") || word.equals("no") || word.isEmpty();
        if (!yes && !no) return false;
        synchronized (this) {
            if (pending == null) return false;
            pending.answer().complete(yes);
            pending = null;
            notifyAll();
        }
        return true;
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
     * 判断这行是否还没写完，要继续读下一行。
     * @param line 非 null 的输入行
     * @return 行尾是单个反斜杠时为 true；尾部两个反斜杠保留为正文
     */
    private static boolean softBreak(String line) { return line.endsWith("\\") && !line.endsWith("\\\\"); }

    /**
     * 判断用户是否要求退出，不能把退出动作排在一个长任务后面等着。
     * @param line 非 null 输入行，q/exit 忽略大小写和首尾空白
     * @return q、exit 或纯空白行为 true；多行任务中间不调用它判断退出
     */
    private static boolean isExit(String line) {
        return line.strip().equalsIgnoreCase("q") || line.strip().equalsIgnoreCase("exit") || line.isBlank();
    }

    /**
     * 用 notifyAll 叫醒单次 run 正在 wait 的终端线程。
     * 它醒来后仍检查 task 是否完成或 pending 是否有申请；这里不读输入。
     */
    private synchronized void signal() { notifyAll(); }

    /**
     * 一次还没回答的批准申请，不交给模型填写。
     * @param id 程序生成的 approval_ 序号
     * @param answer 对应的批准/拒绝 future，由用户回答或 close 设置结果
     */
    private record Pending(String id, CompletableFuture<Boolean> answer) { }

    /**
     * 退出询问功能，未回答的申请都按拒绝处理，并叫醒等待线程；重复调用也可以。
     *
     * <p>这样执行线程不会一直等用户，但不直接停止模型或命令，那由 SessionRuntime.close
     * 接着处理。这里不关闭 reader/System.in，也不能强行打断终端已经开始的 readLine。</p>
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (pending != null) pending.answer().complete(false);
        pending = null;
        notifyAll();
    }
}
