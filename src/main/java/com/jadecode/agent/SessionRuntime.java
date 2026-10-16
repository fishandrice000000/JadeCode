package com.jadecode.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import com.jadecode.cron.CronJob;
import com.jadecode.llm.LlmException;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.safety.Hooks;
import com.jadecode.safety.PermissionGate;
import com.jadecode.tools.BackgroundManager;
import com.jadecode.tools.BackgroundManager.Completion;

/**
 * 会话的单消费者：拥有历史和当前目标，集中决定领取、继续、等待和结束。
 *
 * <p>新请求 FIFO；当前任务的工具、hook 与必等后台结果不进入新任务队列。等待后台结果
 * 不占用模型请求，但仍占据任务位置。生产者只提交/发信号，只有本消费者采用历史事件。
 * 本类处理生命周期，模型/工具执行委托 AgentLoop，定时状态委托 CronProducer。</p>
 */
public final class SessionRuntime implements AutoCloseable {
    /** 等待不等于任务结束；CLOSED 后不得再发起新工具。 */
    public enum State { IDLE, RUNNING, WAITING_BACKGROUND, CLOSED }
    private final AgentLoop engine;
    private final BackgroundManager background;
    private final List<Message> history;
    private final SessionInbox inbox = new SessionInbox();
    private final CronProducer cron;
    private final Consumer<String> output;
    private final Consumer<String> errors;
    private volatile State state = State.IDLE;
    private volatile boolean closed;
    private volatile Active active;
    private Thread consumer;

    /** 新会话从空历史开始；构造不启动线程或模型。调用方在退出时 close。 */
    public SessionRuntime(AgentLoop engine, Consumer<String> output, Consumer<String> errors) {
        this(engine, new ArrayList<>(), output, errors);
    }

    /** 兼容入口可借用调用方列表；只有消费者触碰列表，外部不能并发修改。 */
    private SessionRuntime(AgentLoop engine, List<Message> history, Consumer<String> output, Consumer<String> errors) {
        this.engine = java.util.Objects.requireNonNull(engine);
        this.background = engine.background();
        this.history = history;
        this.output = output;
        this.errors = errors;
        this.cron = new CronProducer(engine.cronScheduler(), this::publishCron, errors);
        if (background != null) background.onCompletion(inbox::wake);
    }

    /**
     * 启动唯一消费者；只有 repl 传 true 才生产到期任务。
     * @param enableCron 单次 run 必须 false，不能退出后假装仍有调度服务
     */
    public synchronized void start(boolean enableCron) {
        if (closed) throw new IllegalStateException("Session is closed");
        if (consumer != null) return;
        consumer = new Thread(this::consume, "jade-session-consumer");
        consumer.setDaemon(true);
        consumer.start();
        if (enableCron) cron.start();
    }

    /**
     * 人工生产者的非阻塞入口；身份和审批通道不来自 prompt 或工具 JSON。
     * @return 任务真正结束时完成的 future；排队成功不表示任务完成
     */
    public CompletableFuture<String> submit(String prompt, PermissionGate.Approval approval) {
        CompletableFuture<String> result = new CompletableFuture<>();
        if (prompt == null || prompt.isBlank()) {
            result.completeExceptionally(new IllegalArgumentException("Prompt must not be blank"));
            return result;
        }
        ExecutionContext context = ExecutionContext.create(ExecutionContext.Source.USER, approval, () -> closed);
        SessionEvent.Request request = new SessionEvent.Request(prompt, context, null);
        if (!inbox.offer(new SessionInbox.Entry(request, result))) {
            result.completeExceptionally(new RejectedExecutionException("Session inbox is full or closed"));
        }
        return result;
    }

    /** 只读状态用于 CLI/离线观察，不暴露可变历史。 */
    public State state() { return state; }

    /** 定时适配器只生产；满队列由它归还 lease，不抢占当前任务。 */
    private boolean publishCron(CronJob job) {
        ExecutionContext context = ExecutionContext.create(ExecutionContext.Source.SCHEDULED, null, () -> closed);
        return inbox.offer(new SessionInbox.Entry(new SessionEvent.Request(job.prompt(), context, job.id()),
                new CompletableFuture<>()));
    }

    /** 空闲领取下一任务，执行中不再调用 take，因此等待期间新请求只能排队。 */
    private void consume() {
        try {
            while (!closed) {
                SessionInbox.Entry entry = inbox.take();
                if (entry == null) return;
                execute(entry, true);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 一项完整任务的生命周期；与 run(messages) 共用，不按来源复制推理循环。
     * append=false 只供既有历史入口，它已包含末条请求，也不重复触发入口 hook。
     */
    private void execute(SessionInbox.Entry entry, boolean append) {
        Active task = new Active(entry, history);
        active = task;
        state = State.RUNNING;
        try {
            ensureOpen();
            if (task.cronId() != null) {
                // 生产后、执行前可能发生取消或退避；未尝试的项归还而不是假确认。
                if (!cron.canDeliver() || !cron.pending(task.cronId())) {
                    cron.release(task.cronId());
                    entry.result().completeExceptionally(new IllegalStateException("Cron delivery deferred or cancelled"));
                    return;
                }
                output.accept("[Cron] Delivering " + task.cronId());
            }
            if (append) {
                if (task.context().source() == ExecutionContext.Source.USER) {
                    engine.hooks().trigger(Hooks.USER_PROMPT_SUBMIT,
                            Map.of(Hooks.PARAM_QUERY, entry.request().prompt(), Hooks.PARAM_CONTEXT, task.context()));
                }
                apply(entry.request());
            }
            // 捕获先于任何通知注入，之后即使换了历史，也始终是这一个目标。
            task.goal = engine.activeRequest(history);
            task.system = engine.taskSystem(task.goal);
            runTask(task);
        } catch (LlmException | IOException | RuntimeException failure) {
            fail(task, failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(task, new IllegalStateException("Session execution interrupted", interrupted));
        } finally {
            active = null;
            if (!closed) state = State.IDLE;
        }
    }

    /**
     * 推进当前目标：先带入已完成结果，再请求模型，随后只处理这份回复的工具或结束意图。
     * 无工具调用只是结束意图；必等结果尚未处理时转为等待，Stop 允许后才真正结束。
     * 该方法不领取其它任务，也不在工具续跑或后台恢复时重新做入口召回。
     */
    private void runTask(Active task) throws LlmException, InterruptedException {
        while (!closed) {
            deliverResults(task, true);
            SessionEvent.ModelReply reply = engine.request(history, task.goal, task.system, this::apply);
            apply(reply);
            // 确认发生在工具执行之前。辅助召回/摘要的回复不会经过这里。
            if (!task.received) {
                task.received = true;
                if (task.cronId() != null) cron.received(task.cronId());
            }
            if (background != null) background.acknowledge(task.receipts);
            task.receipts.clear();

            if (!reply.calls().isEmpty()) {
                SessionEvent.ToolBatch batch = engine.executeTools(reply.calls(), task.context(), task.rounds);
                apply(batch);
                task.rounds = batch.roundsSinceTodo();
                if (batch.failure() != null) throw batch.failure();
                // 到这里整批交换已经闭合；压缩只能在此之后采用。
                engine.compactAfterBatch(batch, history, task.goal, this::apply);
                continue;
            }

            // 请求在途期间可能有结果到达；采用后必须再请求一次，不能没让模型看就宣布完成。
            if (deliverResults(task, false)) continue;
            if (background != null && background.hasRequired(task.context().id())) {
                if (!reply.message().extractText().isBlank()) output.accept("[Progress] " + reply.message().extractText());
                output.accept("[Waiting] " + task.context().id() + " is waiting for required background results");
                state = State.WAITING_BACKGROUND;
                awaitResults(task);
                state = State.RUNNING;
                continue;
            }
            SessionEvent.Continue continuation = engine.stop(history);
            if (continuation != null) {
                apply(continuation);
                continue;
            }
            engine.finish(history);
            ensureOpen();
            String answer = reply.message().extractText();
            output.accept("[Completed] " + answer);
            task.entry.result().complete(answer);
            return;
        }
        throw new IllegalStateException("Session is closed");
    }

    /**
     * 领取事件并统一回灌。等待时 includeDetached=false，不让旧任务通知启动新的模型请求。
     * receipts 保留到正式回复，保证定时首回复前失败可以归还未确认的通知。
     */
    private boolean deliverResults(Active task, boolean includeDetached) {
        if (background == null) return false;
        List<Completion> results = background.collect(task.context().id(), includeDetached);
        if (results.isEmpty()) return false;
        task.receipts.addAll(results);
        apply(new SessionEvent.Notifications(results.stream().map(Completion::notification).toList()));
        return true;
    }

    /** 阻塞在变化信号而不是模型轮询上；版本在扫描前捕获，杜绝检查/等待之间丢唤醒。 */
    private void awaitResults(Active task) throws InterruptedException {
        while (!closed) {
            long observed = inbox.revision();
            if (deliverResults(task, false)) return;
            inbox.awaitChange(observed);
        }
        throw new IllegalStateException("Session is closed");
    }

    /**
     * 唯一历史采用入口。同步事件也走这里，不把队列操作混入每个工具。
     * 通知合并最新 USER 是适配现有位置式压缩保护，不是协议禁止独立通知消息。
     */
    private void apply(SessionEvent event) {
        ensureOpen();
        switch (event) {
            case SessionEvent.Request request -> history.add(Message.userText(
                    request.context().source() == ExecutionContext.Source.SCHEDULED
                            ? "[Scheduled] " + request.prompt() : request.prompt()));
            case SessionEvent.HistoryPrepared prepared -> {
                history.clear();
                history.addAll(prepared.messages());
            }
            case SessionEvent.ModelReply reply -> history.add(reply.message());
            case SessionEvent.ToolBatch batch -> history.add(Message.userBlocks(batch.results()));
            case SessionEvent.Continue continuation -> history.add(Message.userText(continuation.prompt()));
            case SessionEvent.Notifications notifications -> {
                List<ContentBlock> content = new ArrayList<>();
                boolean merge = !history.isEmpty() && history.getLast().role() == Role.USER;
                if (merge) content.addAll(history.getLast().content());
                notifications.texts().forEach(text -> content.add(new TextBlock(text)));
                Message message = Message.userBlocks(List.copyOf(content));
                if (merge) history.set(history.size() - 1, message);
                else history.add(message);
            }
        }
    }

    /**
     * 失败不是全局回滚。仅定时首回复前恢复历史并归还通知；其它失败保留真实进度。
     * 必等命令按任务清理，脱离命令仍存活；不会为补救重放已有工具副作用。
     */
    private void fail(Active task, Exception failure) {
        if (task.cronId() != null && !task.received) {
            // 关闭后不再采用任何新历史；未关闭时恢复也走统一的历史事件入口。
            if (!closed) apply(new SessionEvent.HistoryPrepared(task.snapshot));
            if (background != null) background.restore(task.receipts);
            if (!closed) cron.failed(task.cronId());
            else cron.release(task.cronId());
        } else if (background != null) {
            // 这些通知已保留在失败历史中，不再占据后台容量；当前任务不自动重投。
            background.acknowledge(task.receipts);
        }
        if (background != null) background.cancelRequired(task.context().id());
        task.entry.result().completeExceptionally(failure);
        if (!closed) errors.accept("[Task failed] " + task.context().id() + ": " + failure.getMessage());
    }

    /** 关闭信号与晚到事件之间的边界，所有历史采用前检查。 */
    private void ensureOpen() { if (closed) throw new IllegalStateException("Session is closed"); }

    /** 一次任务跨等待保留的状态；只由消费者修改，不能作为模型可写的工具参数。 */
    private static final class Active {
        final SessionInbox.Entry entry;
        final List<Message> snapshot;
        final List<Completion> receipts = new ArrayList<>();
        String goal;
        String system;
        int rounds;
        boolean received;
        Active(SessionInbox.Entry entry, List<Message> history) {
            this.entry = entry;
            // 只有定时首回复前的交付重试需要恢复，普通任务不额外复制一份长历史。
            snapshot = entry.request().cronId() == null ? List.of() : List.copyOf(history);
        }
        ExecutionContext context() { return entry.request().context(); }
        String cronId() { return entry.request().cronId(); }
    }

    /** 既有 run(messages) 借用同一个执行过程，不启动线程、不复制另一套业务循环。 */
    static void runExisting(AgentLoop engine, List<Message> messages) throws LlmException {
        SessionRuntime runtime = new SessionRuntime(engine, messages, ignored -> { }, ignored -> { });
        ExecutionContext context = ExecutionContext.create(ExecutionContext.Source.USER, null, () -> runtime.closed);
        SessionInbox.Entry entry = new SessionInbox.Entry(new SessionEvent.Request("", context, null), new CompletableFuture<>());
        try {
            runtime.execute(entry, false);
            entry.result().join();
        } catch (CompletionException wrapped) {
            // 保留旧 API 的异常对象与类型，不能用 CompletionException 掩盖真实失败。
            if (wrapped.getCause() instanceof LlmException failure) throw failure;
            if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException(wrapped.getCause());
        } finally {
            runtime.closed = true;
            runtime.inbox.close();
            if (runtime.background != null) runtime.background.onCompletion(() -> { });
        }
    }

    /**
     * 整个会话退出：停止生产和接收、拒绝排队任务、终止所有 shell，不排空业务。
     * HTTP 不响应中断时只限时等收尾；关闭检查保证晚到回复不能启动新工具。
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            state = State.CLOSED;
        }
        cron.close();
        // 先禁用执行器并清理命令，再完成队列 future；用户回调不能延迟禁止新工具的时点。
        engine.close();
        inbox.close();
        Active running = active;
        if (running != null) running.entry.result().completeExceptionally(new IllegalStateException("Session closed"));
        if (consumer != null && consumer != Thread.currentThread()) {
            consumer.interrupt();
            try {
                consumer.join(2000);
                if (consumer.isAlive()) errors.accept("[Session] Model/IO still finishing; new tools are disabled.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
