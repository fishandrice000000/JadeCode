package com.jadecode.cli.plain;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.Approval;

/**
 * run 仅在需要时询问 y/n，不接收下一项任务，不创建编辑器或全局输出重定向。
 * 执行线程等待 future，调用 await 的线程独占输入；多份申请排队，EOF 和关闭均拒绝。
 */
public final class PlainApproval implements Approval, AutoCloseable {
    private record Request(String tool, String args, String reason, CompletableFuture<Boolean> result) { }
    private final ArrayDeque<Request> waiting = new ArrayDeque<>();
    private boolean closed;
    /** 由工具线程排入一项申请并等待对应答复；不在工具线程读取 stdin，避免多个 Agent 抢输入。 */
    @Override public boolean ask(String tool, ObjectNode input, String reason) {
        var request = new Request(tool, input.deepCopy().toString(), reason, new CompletableFuture<>());
        synchronized (this) { if (closed) return false; waiting.addLast(request); notifyAll(); }
        try { return request.result().get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (ExecutionException failure) { return false; }
        finally { synchronized (this) { waiting.remove(request); notifyAll(); } }
    }
    /**
     * 由 run 的主线程等待这项任务，并逐份处理 y/n 审批。没有申请时不读取 stdin，
     * 因而重定向到 /dev/null 也不会提前结束正在请求模型的任务。
     * 确实需要答复却遇到 EOF 时拒绝所有申请；任务异常在最后的 get 原样传播给命令入口。
     */
    public void await(CompletableFuture<String> task) throws Exception {
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        task.whenComplete((value, error) -> { synchronized (this) { notifyAll(); } });
        while (!task.isDone()) {
            Request request;
            synchronized (this) { while (!task.isDone() && waiting.isEmpty()) wait(); if (task.isDone()) break; request = waiting.peekFirst(); }
            System.err.println("[Approval] " + request.reason() + "\n" + request.tool() + "(" + request.args() + ")\n输入 y 批准 / n 拒绝");
            String answer = reader.readLine();
            if (answer == null) close();
            else if (answer.strip().equalsIgnoreCase("y") || answer.strip().equalsIgnoreCase("n")) request.result().complete(answer.strip().equalsIgnoreCase("y"));
            // 先移除已答申请，防止工具线程尚未醒来时再次展示同一项。
            synchronized (this) { if (request.result().isDone()) waiting.remove(request); }
        }
        task.get();
    }
    @Override public synchronized void close() { closed = true; waiting.forEach(r -> r.result().complete(false)); waiting.clear(); notifyAll(); }
}
