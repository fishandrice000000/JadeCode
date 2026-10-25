package com.jadecode.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.messages.Role;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.util.Json;

/**
 * 注入可信脚本的少量编排原语。没有文件、Shell、主历史或审批接口；agent 只请求模型。
 * scope 稳定地区分流水线条目/嵌套流程；phase 属当前 Context，不把并发分支的阶段当全局可变状态。
 */
public final class WorkflowContext {
    private final WorkflowRuntime.Run run;
    private final String scope;
    private final int depth;
    private String phase = "";

    /** 只由运行时/原语创建，所有子 Context 沿用同一运行预算和取消信号。 */
    WorkflowContext(WorkflowRuntime.Run run, String scope, int depth) { this.run = run; this.scope = scope; this.depth = depth; }

    /** 标记显示阶段，无 IO，不改变调用依赖；同一阶段对象不要被多个分支同时修改。 */
    public void phase(String title) {
        run.check(); if (title == null || title.isBlank()) throw new IllegalArgumentException("Workflow phase is empty");
        phase = title; run.event("phase", title, "running");
    }

    /** 打印宿主进度，不作为新的用户指令或对话消息。 */
    public void log(String message) { run.check(); run.event("log", Objects.requireNonNull(message), "running"); }

    /**
     * 一次独立步骤：查缓存 → 请求 → 严格解析/校验 → 成功落盘。
     * schema=null 返回 JSON 字符串；结构失败至多修正一次，网络/截断/工具调用不重放。
     * 不修改 prompt/schema，返回缓存副本；相同 key 的并发调用只实际请求一次。
     */
    public JsonNode agent(String prompt, JsonNode schema, String label) throws Exception {
        run.step();
        if (prompt == null || prompt.isBlank() || prompt.length() > 256_000 || label == null || label.isBlank() || label.length() > 512)
            throw new IllegalArgumentException("Workflow prompt or label is invalid/too large");
        if (schema != null) WorkflowSchema.checkSchema(schema);
        JsonNode contract = schema == null ? null : schema.deepCopy();
        String key = key(label, prompt, contract);
        JsonNode cached = run.store.cached(key);
        if (cached != null) {
            if (contract != null) WorkflowSchema.validate(cached, contract);
            else if (!cached.isTextual()) throw new IllegalArgumentException("Cached workflow text has the wrong type");
            run.cached(); run.event("agent", label, "cached"); return cached;
        }
        var owner = new CompletableFuture<JsonNode>();
        var existing = run.inFlight.putIfAbsent(key, owner);
        if (existing != null) {
            // 同一语义步骤并发重复出现时等首个结果；异常不能变成缓存命中。
            try { JsonNode result = existing.get(); run.check(); run.cached(); return result.deepCopy(); }
            catch (ExecutionException error) { throw cause(error); }
        }
        try {
            // 在查缓存和取得 owner 之间，另一线程可能已经写盘并移除了 inFlight；再查一次堵住重复请求。
            JsonNode justSaved = run.store.cached(key);
            if (justSaved != null) {
                if (contract != null) WorkflowSchema.validate(justSaved, contract);
                else if (!justSaved.isTextual()) throw new IllegalArgumentException("Cached workflow text has the wrong type");
                run.cached(); owner.complete(justSaved.deepCopy()); return justSaved;
            }
            run.event("agent", label + (phase.isEmpty() ? "" : " · " + phase), "running");
            JsonNode value = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                var response = run.call(prompt + (attempt == 0 ? "" : "\nYour prior result did not match the required JSON structure. Return valid JSON only."), contract);
                if (response.message().role() != Role.ASSISTANT || !response.message().blocksOfType(ToolUseBlock.class).isEmpty()
                        || response.stopReason().equals("max_tokens") || response.stopReason().equals("refusal"))
                    throw new IllegalArgumentException("Workflow agent returned an incomplete or unsupported action response");
                String text = response.message().extractText();
                if (text.length() > 256_000) throw new IllegalArgumentException("Workflow agent output exceeds size limit");
                try {
                    value = contract == null ? Json.mapper().getNodeFactory().textNode(text) : WorkflowSchema.parse(text);
                    if (contract != null) WorkflowSchema.validate(value, contract);
                    break;
                } catch (java.io.IOException | IllegalArgumentException invalid) {
                    if (attempt == 1 || contract == null) throw new IllegalArgumentException("Workflow agent output failed JSON/schema validation");
                }
            }
            run.record(key, value); owner.complete(value.deepCopy()); run.event("agent", label, "done");
            return value.deepCopy();
        } catch (Exception failure) { owner.completeExceptionally(failure); throw failure; }
        finally { run.inFlight.remove(key, owner); }
    }

    /**
     * 等齐屏障：各 callable 同时开始，返回列表仍按输入顺序，不能把完成顺序当语义身份。
     * 任一失败取消剩余工作并使本轮失败；嵌套并行只在真正模型请求处取 semaphore，避免占满线程互相等锁。
     */
    public <T> List<T> parallel(List<? extends Callable<T>> jobs) throws Exception {
        run.check();
        if (jobs.size() > run.limits.maxCalls()) throw new IllegalArgumentException("Too many workflow parallel items");
        var futures = new ArrayList<Future<T>>();
        try {
            for (var job : List.copyOf(jobs)) futures.add(run.workers.submit(() -> { run.check(); return job.call(); }));
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get());
            run.check(); return Collections.unmodifiableList(results);
        } catch (Exception failure) {
            futures.forEach(future -> future.cancel(true)); run.abort();
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw failure instanceof ExecutionException error ? cause(error) : failure;
        }
    }

    /** 单个条目的阶段，传入独立 Context、上一阶段值、原始条目和稳定下标。 */
    @FunctionalInterface
    public interface Stage { JsonNode apply(WorkflowContext context, JsonNode previous, JsonNode item, int index) throws Exception; }

    /**
     * 每个 item 顺序走完所有 stage，条目之间并行，没有全局阶段屏障。
     * 初始 previous 为 item 副本；输入不被修改，分支 scope 包含下标，返回仍按原条目顺序。
     */
    public List<JsonNode> pipeline(List<JsonNode> items, List<Stage> stages) throws Exception {
        List<Stage> sequence = List.copyOf(stages);
        var jobs = new ArrayList<Callable<JsonNode>>();
        for (int i = 0; i < items.size(); i++) {
            int index = i; JsonNode item = items.get(i).deepCopy();
            jobs.add(() -> {
                var branch = new WorkflowContext(run, scope + "/item:" + index, depth);
                JsonNode previous = item.deepCopy();
                for (Stage stage : sequence) { run.check(); previous = Objects.requireNonNull(stage.apply(branch, previous, item.deepCopy(), index)); }
                return previous;
            });
        }
        return parallel(jobs);
    }

    /**
     * 内联嵌套一层已注册流程；共用 journal、预算、请求上限与取消信号，不创建新 run 或递归工具。
     * 子输入参与 scope，两个同名但输入不同的子流程不会复用彼此的缓存。
     */
    public JsonNode workflow(String name, ObjectNode args) throws Exception {
        run.check();
        if (depth >= 1) throw new IllegalArgumentException("Workflow nesting is limited to one level");
        var definition = run.registry.get(name);
        if (definition == null) throw new IllegalArgumentException("Unknown nested workflow: " + name);
        ObjectNode input = args == null ? Json.mapper().createObjectNode() : args.deepCopy();
        WorkflowSchema.validate(input, definition.argsSchema());
        var child = new WorkflowContext(run, scope + "/workflow:" + name + "@" + definition.version() + ":" + WorkflowSchema.canonical(input), depth + 1);
        return definition.script().run(child, input);
    }

    /** 稳定 SHA-256 使用完整语义输入；schema 排序，数组顺序保留，避免并行计数器或短哈希碰撞。 */
    private String key(String label, String prompt, JsonNode schema) throws Exception {
        ObjectNode basis = Json.mapper().createObjectNode().put("scope", scope).put("runner", run.runner.identity())
                .put("label", label).put("prompt", prompt);
        basis.set("schema", schema == null ? Json.mapper().nullNode() : WorkflowSchema.canonical(schema));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(basis.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** 解包 Future 的真实失败，不让调用方只看到 ExecutionException 丢失失败类别。 */
    private static Exception cause(ExecutionException error) {
        if (error.getCause() instanceof Exception failure) return failure;
        if (error.getCause() instanceof Error fatal) throw fatal;
        return new IllegalStateException("Workflow worker failed", error.getCause());
    }
}
