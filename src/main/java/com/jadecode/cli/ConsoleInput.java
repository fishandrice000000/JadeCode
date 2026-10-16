package com.jadecode.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.safety.PermissionGate;

/**
 * CLI 唯一输入读取者：主线程读任务与审批，执行线程只提交审批请求并等待答复。
 *
 * <p>审批使用 /approve ID 或 /deny ID 显式关联，而非把下一行任意输入当作 y/N。
 * 因此用户排队的新提示不会意外批准另一个命令。普通输入仍可排队，审批答复不进任务队列。
 * 本类不执行安全规则，不让模型设置审批 ID，也不创建另一线程竞争 stdin。</p>
 *
 * <p>REPL 的 UI 线程调用 readQuery：普通行返回上层排队，审批行直接回答当前审批。
 * 单次 run 的 UI 线程调用 await：不接收新目标，只在执行线程提出审批时读取输入。
 * 安全门通过 ask 在执行线程等待同一份答复。两个方向共享一条 Pending 通道，但只有
 * 构造本对象的线程读 reader；这样不会出现两个 reader 抢走彼此输入的问题。</p>
 */
public final class ConsoleInput implements PermissionGate.Approval, AutoCloseable {
    /** 只能由 inputOwner 读取；执行线程不得调用 readLine。 */
    private final BufferedReader reader;
    /** 审批说明/诊断通道；普通输入提示仍直接输出到 System.out。 */
    private final Consumer<String> output;
    /** 防止竞争读取的 UI 所有权，不用于判断任务是否有审批权限。 */
    private final Thread inputOwner = Thread.currentThread();
    /** 锁保护的一次未答复审批；当前不支持同时挂起多份审批。 */
    private Pending pending;
    /** 本终端内递增的审批序号，用来拒绝错配/过期答复。 */
    private long counter;
    /** 审批通道关闭标记，不代表本类拥有整个会话或能取消全部工具。 */
    private boolean closed;

    /** 生产终端；构造只包装 stdin，不读取输入、不启动线程。 */
    public ConsoleInput() {
        this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out::println);
    }

    /**
     * 绑定输入流及输出回调，供生产包装和离线检查复用。
     * 构造线程成为唯一读取者，构造本身不读取流、不创建线程。
     * @param reader 非 null 的行输入流；close 不关闭它或全局 System.in
     * @param output 非 null 的审批/诊断接收方，可能由执行线程调用，应快速且不抛异常
     */
    public ConsoleInput(BufferedReader reader, Consumer<String> output) {
        this.reader = reader;
        this.output = output;
    }

    /**
     * 执行线程提出审批，只等待自己的答复；关闭、并发审批或中断都拒绝。
     * 真正是否允许询问由 PermissionGate 按可信任务来源决定。
     *
     * <p>锁内创建 Pending 并显示带 ID 的命令，锁外等 future，给 UI 留出回答所需的锁。
     * 此处绝不读取 stdin，也不因拿到一行普通任务输入就批准。finally 只清理自己的申请，
     * 不清除后来可能已建立的另一份申请；中断时恢复中断标记并按拒绝返回。</p>
     * @param toolName 待执行工具名
     * @param input 只读展示的工具参数，不在此处执行或改写
     * @param reason 安全规则要求询问的原因
     * @return 仅匹配当前 ID 的显式批准为 true；拒绝、关闭、已有并发审批或等待失败为 false
     */
    @Override
    public boolean ask(String toolName, ObjectNode input, String reason) {
        Pending request;
        synchronized (this) {
            // 1. 只容纳一次审批：无法明确关联的并发申请按拒绝处理，不偷偷覆盖旧申请。
            if (closed || pending != null) return false;
            request = new Pending("approval_" + ++counter, new CompletableFuture<>());
            pending = request;
            output.accept("[Permission " + request.id() + "] " + reason + "\n  " + toolName + "(" + input + ")"
                    + "\n  /approve " + request.id() + " 或 /deny " + request.id());
            notifyAll();
        }
        // 2. 执行线程停在这里，UI 仍可读取用户后续输入；只有 answer/close 完成此 future。
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
     * REPL 入口：只有此方法与 await 读取输入，必须由构造本对象的 UI 线程调用。
     * 行尾单个反斜杠续行；审批命令仅作为独立行识别，任务正文不被截成审批指令。
     *
     * @return 去除首尾空白的完整任务；初始行为空白、q/exit 或 EOF 时返回 null 并关闭审批通道。
     *         若 EOF 发生在续行中，保留并返回已经输入的非空正文
     * @throws IOException 读取输入失败，由 CLI 负责关闭整个会话
     * @throws IllegalStateException 不是构造本对象的 UI 线程调用
     */
    public String readQuery() throws IOException {
        requireInputOwner();
        while (true) {
            System.out.print("JadeCode >> ");
            System.out.flush();
            String line = reader.readLine();
            if (line == null || isExit(line)) { close(); return null; }
            // 审批命令被本地消费，绝不送给模型。普通行即使有待审批申请，也仍可作为新任务排队。
            if (answer(line)) continue;
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
     * 单次 run 不收新任务：UI 等待完成或审批请求，有审批时才读一行。
     * 不启动输入线程，也不在无审批时因 stdin 的 EOF 提前终止正在执行的任务。
     *
     * <p>任务完成回调与 ask 都会唤醒 UI。无审批时只等待监视器信号，不碰输入流；有审批
     * 才读行并关联 ID。EOF 关闭审批通道、拒绝未答审批后继续等任务收尾；显式退出则报告取消，
     * 由 RunCommand 的资源关闭流程结束会话。</p>
     * @param task SessionRuntime.submit 返回的结果 future，不由本方法创建或重试任务
     * @return 任务最终答复；运行器已经负责正常终端输出，调用方无需重复打印
     * @throws IOException 有审批时读取输入失败
     * @throws InterruptedException UI 等待或读取 future 结果时被中断
     * @throws ExecutionException 任务失败，原始原因保留在 cause 中
     * @throws IllegalStateException 非 UI 线程调用，或用户显式退出取消
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
            else if (isExit(line)) { close(); throw new IllegalStateException("Execution cancelled by user"); }
            else if (!answer(line)) output.accept("请使用上方带 ID 的 /approve 或 /deny，不接受新任务");
        }
        return task.get();
    }

    /**
     * 尝试把独立输入行当作审批答复消费，不读流、不提交任务。
     * @param line 非 null 的一行原文
     * @return 是否属于 /approve 或 /deny 命令；即使命令缺 ID、ID 过期或不匹配也返回 true，
     *         但只提示错误而不批准，且不让这类控制输入变成模型新目标。普通文本返回 false
     */
    private boolean answer(String line) {
        String[] parts = line.strip().split("\\s+", 2);
        boolean allow = parts[0].equals("/approve");
        if (!allow && !parts[0].equals("/deny")) return false;
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
     * 检查输入读取归属，不是按线程赋予权限；任务权限仍由可信 ExecutionContext 判断。
     * @throws IllegalStateException 非构造线程试图读取同一终端
     */
    private void requireInputOwner() {
        if (Thread.currentThread() != inputOwner) throw new IllegalStateException("Only the UI input owner may read stdin");
    }

    /**
     * @param line 非 null 输入行
     * @return 行尾恰为单个反斜杠时为 true；尾部两个反斜杠保留为普通正文
     */
    private static boolean softBreak(String line) { return line.endsWith("\\") && !line.endsWith("\\\\"); }

    /**
     * 判断本地退出控制，不排在可能长期等待的任务之后。
     * @param line 非 null 输入行，比较 q/exit 时忽略大小写和首尾空白
     * @return q、exit 或纯空白行时为 true；多行任务正文内部不调用此判断
     */
    private static boolean isExit(String line) {
        return line.strip().equalsIgnoreCase("q") || line.strip().equalsIgnoreCase("exit") || line.isBlank();
    }

    /** 唤醒单次 run 的 UI 等待者；不读取输入、不替代 task.isDone/pending 条件复核。 */
    private synchronized void signal() { notifyAll(); }

    /**
     * 一次审批的关联标识与答复通道，不暴露为模型可写参数。
     * @param id 此终端内生成的 approval_ 序号
     * @param answer 由匹配答复或 close 完成的布尔 future
     */
    private record Pending(String id, CompletableFuture<Boolean> answer) { }

    /**
     * 幂等关闭审批通道并唤醒等待者，未答复申请按拒绝完成，避免执行线程永久等用户。
     * 不关闭 reader/System.in，不直接终止模型或命令；CLI 随后的 SessionRuntime.close
     * 才关闭整个会话。本方法不会强制打断 UI 已经阻塞的 reader.readLine。
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (pending != null) pending.answer().complete(false);
        pending = null;
        notifyAll();
    }
}
