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
 */
public final class ConsoleInput implements PermissionGate.Approval, AutoCloseable {
    private final BufferedReader reader;
    private final Consumer<String> output;
    private final Thread inputOwner = Thread.currentThread();
    private Pending pending;
    private long counter;
    private boolean closed;

    /** 生产终端；构造只包装 stdin，不读取输入、不启动线程。 */
    public ConsoleInput() {
        this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out::println);
    }

    /** 显式输入流与输出接收方也供无网络离线演示使用。 */
    public ConsoleInput(BufferedReader reader, Consumer<String> output) {
        this.reader = reader;
        this.output = output;
    }

    /**
     * 执行线程提出审批，只等待自己的答复；关闭、并发审批或中断都拒绝。
     * 真正是否允许询问由 PermissionGate 按可信任务来源决定。
     */
    @Override
    public boolean ask(String toolName, ObjectNode input, String reason) {
        Pending request;
        synchronized (this) {
            if (closed || pending != null) return false;
            request = new Pending("approval_" + ++counter, new CompletableFuture<>());
            pending = request;
            output.accept("[Permission " + request.id() + "] " + reason + "\n  " + toolName + "(" + input + ")"
                    + "\n  /approve " + request.id() + " 或 /deny " + request.id());
            notifyAll();
        }
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
     * @return 完整任务；空行、q/exit 或 EOF 返回 null 并关闭审批通道
     */
    public String readQuery() throws IOException {
        requireInputOwner();
        while (true) {
            System.out.print("JadeCode >> ");
            System.out.flush();
            String line = reader.readLine();
            if (line == null || isExit(line)) { close(); return null; }
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

    /** 审批答复必须命中当前 ID，过期或缺失 ID 不批准，也不作为模型新指令执行。 */
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

    /** 输入归属是防止两个 reader 竞争，不是用线程身份判断任务权限。 */
    private void requireInputOwner() {
        if (Thread.currentThread() != inputOwner) throw new IllegalStateException("Only the UI input owner may read stdin");
    }

    /** 单个尾反斜杠才续行，两个反斜杠保留为普通文本。 */
    private static boolean softBreak(String line) { return line.endsWith("\\") && !line.endsWith("\\\\"); }

    /** 退出是 UI 控制操作，不排在可能长期等待的任务后面。 */
    private static boolean isExit(String line) {
        return line.strip().equalsIgnoreCase("q") || line.strip().equalsIgnoreCase("exit") || line.isBlank();
    }

    /** 唤醒正在等待单次任务或审批的 UI 线程。 */
    private synchronized void signal() { notifyAll(); }

    /** 答复通道只属于一次审批，不暴露给模型。 */
    private record Pending(String id, CompletableFuture<Boolean> answer) { }

    /** 关闭立即拒绝尚未答复的审批；不关闭全局 stdin，便于命令结束时资源按顺序清理。 */
    @Override
    public synchronized void close() {
        closed = true;
        if (pending != null) pending.answer().complete(false);
        pending = null;
        notifyAll();
    }
}
