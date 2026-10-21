package com.jadecode.cli.plain;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import com.jadecode.safety.approval.*;

/**
 * run 仅在需要时提供三项选择，不接收下一项任务，不创建编辑器或全局输出重定向。
 * 执行线程等待 future，调用 await 的线程独占输入；多份申请排队，EOF 和关闭均拒绝。
 */
public final class PlainApproval implements Approval, AutoCloseable {
    private record Request(ApprovalRequest data, CompletableFuture<ApprovalAnswer> result) { }
    private final ArrayDeque<Request> waiting = new ArrayDeque<>();
    private boolean closed;
    private long revision;
    /** 由工具线程排入一项申请并等待对应答复；不在工具线程读取 stdin，避免多个 Agent 抢输入。 */
    @Override public ApprovalAnswer ask(ApprovalRequest data) {
        var request = new Request(data, new CompletableFuture<>());
        synchronized (this) {
            if (closed) return ApprovalAnswer.DENY;
            if (data.revision() < revision) return ApprovalAnswer.RECHECK;
            waiting.addLast(request); notifyAll();
        }
        try { return request.result().get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return ApprovalAnswer.DENY; }
        catch (ExecutionException failure) { return ApprovalAnswer.DENY; }
        finally { synchronized (this) { waiting.remove(request); notifyAll(); } }
    }
    /**
     * 由 run 的主线程等待任务，并逐份提供 1/2/3 三项审批；y/n 是仅本次/拒绝的简写。
     * 空行不作答，无可靠规则建议时不接受 2。没有申请时不读取 stdin，
     * 因而重定向到 /dev/null 也不会提前结束正在请求模型的任务。
     * 确实需要答复却遇到 EOF 时拒绝所有申请；任务异常在最后的 get 原样传播给命令入口。
     */
    public void await(CompletableFuture<String> task) throws Exception {
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        task.whenComplete((value, error) -> { synchronized (this) { notifyAll(); } });
        while (!task.isDone()) {
            Request request;
            synchronized (this) { while (!task.isDone() && waiting.isEmpty()) wait(); if (task.isDone()) break; request = waiting.peekFirst(); }
            var data = request.data();
            // 路径和工具参数可能包含控制字符；文本 run 也不能让它们改写审批画面。
            System.err.println(com.jadecode.tui.text.TextSanitizer.display("[Approval] " + data.actor() + " · " + data.reason() + "\n" + data.tool() + "(" + data.input() + ")"));
            System.err.println(com.jadecode.tui.text.TextSanitizer.display(data.suggestion() == null ? "[1] 仅本次  [2] 允许此类命令（不可用）  [3] 拒绝"
                    : "保存范围：" + data.suggestion().description() + "\n[1] 仅本次  [2] 允许此类命令  [3] 拒绝"));
            String answer = reader.readLine();
            if (answer == null) close();
            else {
                ApprovalAnswer choice = switch (answer.strip().toLowerCase(java.util.Locale.ROOT)) {
                    case "1", "y" -> ApprovalAnswer.ALLOW_ONCE;
                    case "2" -> data.suggestion() == null ? null : ApprovalAnswer.ALLOW_SIMILAR;
                    case "3", "n" -> ApprovalAnswer.DENY;
                    default -> null;
                };
                if (choice != null) request.result().complete(choice);
            }
            // 先移除已答申请，防止工具线程尚未醒来时再次展示同一项。
            synchronized (this) { if (request.result().isDone()) waiting.remove(request); }
        }
        task.get();
    }
    @Override public synchronized void reconsider(long value) {
        revision = Math.max(revision, value);
        waiting.removeIf(p -> { if (p.data().revision() >= revision) return false; p.result().complete(ApprovalAnswer.RECHECK); return true; });
        notifyAll();
    }
    @Override public synchronized void close() { closed = true; waiting.forEach(r -> r.result().complete(ApprovalAnswer.DENY)); waiting.clear(); notifyAll(); }
}
