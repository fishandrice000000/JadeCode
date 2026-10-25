package com.jadecode.workflow;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.llm.ModelResponse;
import com.jadecode.util.Json;

/**
 * 同步完成一次可信流程，内部通过虚拟线程并发执行步骤。主会话只得到最终工具结果，不接收中间对话。
 * Runtime 由 Session 拥有并关闭；每次运行各有锁、journal、计数和取消标志，不与 Team 任务图混用。
 * 存储或模型失败保留成功步骤；resume 重新执行可信代码，命中稳定 key 时才复用已写盘结果。
 */
public final class WorkflowRuntime implements AutoCloseable {
    /** 少量工程限额，所有重试与一层嵌套共享；测试可注入更小值验证边界。 */
    public record Limits(int concurrency, int maxCalls) {
        public Limits {
            if (concurrency < 1 || concurrency > 32 || maxCalls < 1 || maxCalls > 1000)
                throw new IllegalArgumentException("Invalid workflow limits");
        }
        /** 课程并发上限为 8、请求上限为 1000，不改变用户模型配置。 */
        public static Limits defaults() { return new Limits(8, 1000); }
    }

    /** 只读生命周期/进度，不包含可执行代码、模型输入正文或审批能力。 */
    public record Progress(String runId, String event, String label, String status) { }

    private final WorkflowStore store;
    private final Map<String, WorkflowDefinition> registry;
    private final Supplier<WorkflowAgentRunner> runners;
    private final Consumer<Progress> observer;
    private final Limits limits;
    private final Set<Run> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /** 绑定已有依赖，不启动线程、不建目录、不请求模型。registry 的代码全部来自宿主。 */
    public WorkflowRuntime(Path workspace, Map<String, WorkflowDefinition> registry, Supplier<WorkflowAgentRunner> runners,
            Limits limits, Consumer<Progress> observer) {
        store = new WorkflowStore(workspace); this.registry = Map.copyOf(registry);
        this.runners = Objects.requireNonNull(runners); this.limits = Objects.requireNonNull(limits);
        this.observer = Objects.requireNonNull(observer);
        registry.forEach((key, value) -> { if (!key.equals(value.name())) throw new IllegalArgumentException("Workflow registry name mismatch"); });
    }

    /** 返回可信目录描述供工具提示使用，不执行流程或暴露 script。 */
    public String catalog() {
        return registry.values().stream().sorted(Comparator.comparing(WorkflowDefinition::name))
                .map(d -> d.name() + ": " + d.description()).collect(java.util.stream.Collectors.joining("; "));
    }

    /**
     * 运行或恢复指定流程。参数错误/坏存储在执行前抛出；流程中失败则返回带 run_id 的 failed 结果。
     * null args 在新运行时等价空对象，在 resume 时表示沿用原输入；输入与输出均复制以隔离可变 JSON。
     * 本方法不写主会话历史、不创建守护任务、不重新批准权限；工具入口应先经过原 Gate。
     */
    public ObjectNode run(String name, ObjectNode args, String resume, ExecutionContext context) throws IOException {
        if (closed || context == null || context.isCancelled()) throw new IOException("Workflow session is closed");
        var definition = registry.get(name);
        if (definition == null) throw new IllegalArgumentException("Unknown workflow: " + name);
        ObjectNode input = args == null ? null : args.deepCopy();
        if (resume == null) {
            if (input == null) input = Json.mapper().createObjectNode();
            WorkflowSchema.validate(input, definition.argsSchema());
        }
        WorkflowAgentRunner runner = Objects.requireNonNull(runners.get());
        try (var session = store.open(definition, input, resume, runner.identity())) {
            Run run = new Run(session, context, runner, registry, limits, observer);
            synchronized (active) {
                if (closed || context.isCancelled()) throw new IOException("Workflow session is closed");
                active.add(run);
            }
            try {
                // 已完成 run 的恢复先保留原成功产物。缓存语义校验发生在脚本重放时，不能一开始
                // 就把旧 completed 改成 running，后来发现坏缓存又用错误正文覆盖成功证据。
                boolean preserveSuccess = resume != null && session.header.path("status").asText().equals("completed");
                if (!preserveSuccess) session.snapshot("running", run.usage());
                run.event("task_started", definition.name(), "running");
                JsonNode result;
                String status;
                try {
                    var flow = new WorkflowContext(run, definition.name() + "@" + definition.version(), 0);
                    result = Objects.requireNonNull(definition.script().run(flow, (ObjectNode) session.header.get("args").deepCopy()),
                            "Workflow script returned Java null");
                    run.check(); status = "completed";
                } catch (Exception failure) {
                    status = run.cancelled || context.isCancelled() || Thread.currentThread().isInterrupted() ? "cancelled" : "failed";
                    // 不回显模型原文或 API 异常正文。详细输入在用户工作区，工具错误只给确定的错误类别。
                    result = Json.mapper().createObjectNode().put("error", safeReason(failure));
                }
                // 与步骤写 journal 使用同一把锁：先停止新写入，再发布最终产物，迟到结果不能污染已结束运行。
                synchronized (run) {
                    if (status.equals("completed") && (closed || context.isCancelled() || run.cancelled)) {
                        status = "cancelled"; result = Json.mapper().createObjectNode().put("error", "Workflow cancelled");
                    }
                    run.finished = true;
                    if (!preserveSuccess || status.equals("completed")) {
                        session.output(result);
                        session.snapshot(status, run.usage());
                    }
                }
                run.event("task_notification", definition.name(), status);
                ObjectNode response = Json.mapper().createObjectNode().put("run_id", session.header.get("run_id").asText())
                        .put("workflow", name).put("status", status).put("output_file", session.directory.resolve("output.json").toString());
                response.set("result", result.deepCopy()); response.set("usage", run.usage());
                response.put("output_preserved", preserveSuccess && !status.equals("completed"));
                return response;
            } finally {
                run.cancel(); active.remove(run);
            }
        }
    }

    /** 显示确定的宿主错误，LLM/IO 的原始错误可能含传输详情，因此只报告类别。 */
    private static String safeReason(Exception failure) {
        if (failure instanceof IllegalArgumentException || failure instanceof CancellationException)
            return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (failure instanceof com.jadecode.llm.LlmException) return "Workflow model request failed";
        if (failure instanceof IOException) return "Workflow storage failed";
        return "Workflow step failed: " + failure.getClass().getSimpleName();
    }

    /** 关闭只取消本 Runtime 拥有的运行；已开始的 journal 提交先完成，不保证文件 IO 或网络立即返回。 */
    @Override public void close() {
        synchronized (active) { closed = true; active.forEach(Run::cancel); }
    }

    /** 一次运行的共享状态；嵌套/并行只共享此对象，各阶段的 scope 和 phase 由 Context 隔离。 */
    static final class Run {
        final WorkflowStore.Session store;
        final ExecutionContext origin;
        final WorkflowAgentRunner runner;
        final Map<String, WorkflowDefinition> registry;
        final Limits limits;
        final Semaphore permits;
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final ConcurrentMap<String, CompletableFuture<JsonNode>> inFlight = new ConcurrentHashMap<>();
        private final Consumer<Progress> observer;
        private final long budget;
        volatile boolean cancelled, aborted, finished;
        private int attempts, steps, cached, unknownUsage, inFlightRequests;
        private long tokens;

        /** 每个运行一份计数/线程池，不共享可变模型历史；预算来自已经验证的原输入。 */
        Run(WorkflowStore.Session store, ExecutionContext origin, WorkflowAgentRunner runner,
                Map<String, WorkflowDefinition> registry, Limits limits, Consumer<Progress> observer) {
            this.store = store; this.origin = origin; this.runner = runner; this.registry = registry;
            this.limits = limits; this.observer = observer; permits = new Semaphore(limits.concurrency());
            budget = store.header.path("args").path("budget").asLong(0);
        }

        /** 每个关键边界检查关闭/中断；不能让迟到模型回复继续执行流程或写缓存。 */
        void check() {
            if (cancelled || aborted || finished || origin.isCancelled() || Thread.currentThread().isInterrupted())
                throw new CancellationException("Workflow cancelled");
        }

        /** 计算用量快照；未报告或仍在途的请求都算未知，不能把兄弟分支的迟到费用假装为零。 */
        synchronized ObjectNode usage() {
            return Json.mapper().createObjectNode().put("requests", attempts).put("cached_steps", cached)
                    .put("reported_tokens", tokens).put("unknown_usage", unknownUsage + inFlightRequests);
        }

        /** 缓存也受步骤上限约束，可信脚本写错循环时不能无限复用缓存发进度。 */
        synchronized void step() { check(); if (++steps > limits.maxCalls()) throw new IllegalArgumentException("Workflow step cap reached"); }

        /** 命中 journal 后只更新本次缓存统计，不把原运行的费用再记一次。 */
        synchronized void cached() { check(); cached++; }

        /**
         * 每次真实尝试（含格式重试）都占一个并发名额和请求额度。
         * 预算按返回报告累计；并发中的已发请求可能超额，不能假称精确预扣或绝对费用上限。
         */
        ModelResponse call(String prompt, JsonNode schema) throws Exception {
            check(); permits.acquire();
            try {
                synchronized (this) {
                    check();
                    if (attempts >= limits.maxCalls()) throw new IllegalArgumentException("Workflow request cap reached");
                    if (budget > 0 && (tokens >= budget || unknownUsage > 0)) throw new IllegalArgumentException("Workflow observed token budget exhausted or usage missing");
                    attempts++; inFlightRequests++;
                }
                ModelResponse reply;
                try { reply = runner.call(prompt, schema); }
                catch (Exception failure) { synchronized (this) { inFlightRequests--; unknownUsage++; } throw failure; }
                synchronized (this) {
                    inFlightRequests--;
                    // 用量即使在关闭后才回来也是真实发生过的；只记内存，后面的 check 会禁止写旧 journal。
                    if (reply.usage() == null) unknownUsage++;
                    else tokens = Math.addExact(tokens, Math.addExact(reply.usage().totalInputTokens(), reply.usage().outputTokens()));
                    check();
                    if (budget > 0 && (tokens > budget || unknownUsage > 0)) throw new IllegalArgumentException("Workflow observed token budget exceeded or usage missing");
                }
                return reply;
            } finally { permits.release(); }
        }

        /** 与最终发布互斥：模型结果只有成功落盘才成为可复用缓存。 */
        synchronized void record(String key, JsonNode value) throws IOException { check(); store.record(key, value); }

        /** 快速只读进度；显示故障不能改变已经执行的流程结果。 */
        void event(String type, String label, String status) {
            try { observer.accept(new Progress(store.header.get("run_id").asText(), type, label, status)); }
            catch (RuntimeException ignored) { /* 展示不是执行或批准通道。 */ }
        }

        /** 与 record 共用锁，已开始的落盘先完成再中断工作；迟到线程仍被 check/record 挡住。 */
        synchronized void cancel() { cancelled = true; workers.shutdownNow(); }

        /** 分支失败只停止兄弟工作，不能把原本的业务失败误标成用户取消。 */
        synchronized void abort() { aborted = true; workers.shutdownNow(); }
    }
}
