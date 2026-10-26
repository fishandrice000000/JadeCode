package com.jadecode.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.llm.*;
import com.jadecode.messages.*;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 假模型、真实文件和线程验证编排边界，不读认证、不发网络请求，不靠耗时排名判断并行正确性。 */
@Timeout(15)
class WorkflowRuntimeTest {
    @TempDir Path workspace;

    /** 真实 harness 来源，由程序生成，不能由工具 JSON 自填。 */
    private static ExecutionContext origin() { return ExecutionContext.create(ExecutionContext.Source.USER, null); }

    /** 假响应报告固定真实计数，便于验证格式重试也计费。 */
    private static ModelResponse reply(String text) {
        return new ModelResponse(Message.assistant(List.of(new TextBlock(text))), new ModelResponse.Usage(2, 3), "end_turn");
    }

    /** 测试只构造可信对象；schema 在进入 runtime 前就校验。 */
    private static ObjectNode object(String text) throws IOException { return (ObjectNode) WorkflowSchema.parse(text); }

    /** 通用只读示例的输入允许一个 tag，以便验证 resume 参数身份不被替换。 */
    private static WorkflowDefinition flow(String name, WorkflowDefinition.Script script) throws IOException {
        return new WorkflowDefinition(name, "test flow", "1", List.of("Review"),
                object("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"tag\":{\"type\":\"string\"}}}"), script);
    }

    /** 固定模型身份，换 runtime 仍可复用同一成功 journal；客户端全由用例注入。 */
    private WorkflowRuntime runtime(List<WorkflowDefinition> definitions, LlmClient client) {
        return runtime(definitions, client, new WorkflowRuntime.Limits(2, 100));
    }

    /** 较小限额只影响本次测试，避免开大量线程凑矩阵。 */
    private WorkflowRuntime runtime(List<WorkflowDefinition> definitions, LlmClient client, WorkflowRuntime.Limits limits) {
        return new WorkflowRuntime(workspace, WorkflowDefinition.registry(definitions),
                () -> new WorkflowAgentRunner(client, "offline", 2000, ThinkingSettings.DEFAULT), limits, ignored -> { });
    }

    /** 真实预设走四维 pipeline + 逐条 parallel；再次恢复不增加模型请求，汇总语义保持一致。 */
    @Test void reviewAndResumeReuseAllSuccessfulCalls() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var prompts = new java.util.concurrent.CopyOnWriteArrayList<String>();
        LlmClient client = request -> {
            requests.incrementAndGet(); assertTrue(request.tools().isEmpty()); assertEquals(1, request.messages().size());
            String prompt = request.messages().getFirst().extractText(); prompts.add(prompt);
            return reply(prompt.contains("\nReview supplied changes for ")
                    ? "{\"findings\":[{\"title\":\"check\",\"severity\":\"high\"}]}"
                    : "{\"isReal\":true,\"reason\":\"supported by supplied text\"}");
        };
        ObjectNode first;
        try (var runtime = runtime(List.of(ReviewChangesWorkflow.definition()), client)) {
            first = runtime.run("review-changes", object("{\"changes\":\"example change\"}"), null, origin());
            assertEquals("completed", first.path("status").asText());
            assertEquals(4, first.path("result").path("confirmed").size()); assertEquals(8, requests.get());
            assertEquals(40, first.path("usage").path("reported_tokens").asInt());
        }
        // 复核也复用完整改动前缀，不能在共用文本前插入审查维度或某条发现。
        String audit = prompts.stream().filter(p -> p.contains("\nReview supplied changes for ")).findFirst().orElseThrow();
        String shared = audit.substring(0, audit.indexOf("Only report text-supported issues."));
        assertTrue(shared.contains("example change")); assertEquals(8, prompts.size());
        prompts.forEach(prompt -> assertTrue(prompt.startsWith(shared)));
        // 审查/复核的规则和 schema 不同；同阶段的四个维度应复用到 schema 结束，而非只复用改动资料。
        for (String marker : List.of("\nReview supplied changes for ", "\nAdversarially verify this ")) {
            var phasePrompts = prompts.stream().filter(p -> p.contains(marker)).toList();
            assertEquals(4, phasePrompts.size());
            String prefix = phasePrompts.getFirst().substring(0, phasePrompts.getFirst().indexOf(marker));
            assertTrue(prefix.contains("Return only one JSON value matching this schema:"));
            phasePrompts.forEach(prompt -> assertTrue(prompt.startsWith(prefix + marker)));
        }
        try (var restored = runtime(List.of(ReviewChangesWorkflow.definition()), client)) {
            var second = restored.run("review-changes", null, first.path("run_id").asText(), origin());
            assertEquals(first.get("result"), second.get("result")); assertEquals(8, requests.get());
            assertEquals(8, second.path("usage").path("cached_steps").asInt());
            assertEquals(0, second.path("usage").path("requests").asInt());
        }
    }

    /** 同标签的不同任务仍请求两份结果；只修正坏 JSON，恢复时两个 key 各自命中。 */
    @Test void separatedTasksKeepSchemaPrefixAndDistinctRetryableCacheKeys() throws Exception {
        var schema = object("{\"type\":\"string\"}");
        var definition = flow("separated", (ctx, args) -> Json.mapper().valueToTree(ctx.parallel(List.of(
                () -> ctx.agent("Common reference and fixed rules.", "TASK_A", schema, "same"),
                () -> ctx.agent("Common reference and fixed rules.", "TASK_B", schema, "same")))));
        var prompts = new CopyOnWriteArrayList<String>();
        var firstA = new AtomicBoolean(true);
        ObjectNode result;
        try (var runtime = runtime(List.of(definition), request -> {
            String prompt = request.messages().getFirst().extractText(); prompts.add(prompt);
            if (prompt.contains("\nTASK_A") && firstA.getAndSet(false)) return reply("bad JSON");
            return reply(prompt.contains("\nTASK_A") ? "\"A\"" : "\"B\"");
        })) {
            result = runtime.run("separated", null, null, origin());
            assertEquals("completed", result.path("status").asText(), result.toString());
            assertEquals(Json.mapper().readTree("[\"A\",\"B\"]"), result.path("result"));
            assertEquals(3, result.path("usage").path("requests").asInt());
        }
        String common = "Common reference and fixed rules.\nReturn only one JSON value matching this schema:\n" + schema + "\n";
        assertEquals(3, prompts.size()); prompts.forEach(prompt -> assertTrue(prompt.startsWith(common)));
        assertEquals(1, prompts.stream().filter(p -> p.equals(common + "TASK_A")).count());
        assertEquals(1, prompts.stream().filter(p -> p.equals(common + "TASK_B")).count());
        assertTrue(prompts.stream().anyMatch(p -> p.startsWith(common + "TASK_A\nYour prior result")));
        try (var restored = runtime(List.of(definition), request -> { fail("恢复不得重新请求模型"); return reply("unused"); })) {
            var resumed = restored.run("separated", null, result.path("run_id").asText(), origin());
            assertEquals("completed", resumed.path("status").asText()); assertEquals(result.path("result"), resumed.path("result"));
            assertEquals(2, resumed.path("usage").path("cached_steps").asInt());
            assertEquals(0, resumed.path("usage").path("requests").asInt());
        }
    }

    /** 模型失败前已保存的步骤保留，续跑只补缺失步骤；失败不会自动重放请求。 */
    @Test void failedRunResumesOnlyMissingStep() throws Exception {
        var definition = flow("two", (ctx, args) -> {
            ctx.agent("first", null, "a"); return ctx.agent("second", null, "b");
        });
        AtomicInteger calls = new AtomicInteger(); ObjectNode failed;
        try (var runtime = runtime(List.of(definition), req -> {
            if (calls.incrementAndGet() == 2) throw new LlmException("offline failure"); return reply("ok");
        })) {
            failed = runtime.run("two", null, null, origin()); assertEquals("failed", failed.path("status").asText());
            assertEquals(2, calls.get());
        }
        try (var restored = runtime(List.of(definition), req -> { calls.incrementAndGet(); return reply("second done"); })) {
            var done = restored.run("two", null, failed.path("run_id").asText(), origin());
            assertEquals("completed", done.path("status").asText()); assertEquals(3, calls.get());
            assertEquals(1, done.path("usage").path("cached_steps").asInt());
        }
    }

    /** 结构错误只修正一次，修正也计入用量；重复错误不能变成成功缓存。 */
    @Test void schemaRetryCountsBothAttemptsAndRejectsMalformedResults() throws Exception {
        ObjectNode schema = object("{\"type\":\"object\",\"required\":[\"ok\"],\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"additionalProperties\":false}");
        var definition = flow("json", (ctx, args) -> ctx.agent("structured", schema, "json"));
        AtomicInteger calls = new AtomicInteger();
        try (var runtime = runtime(List.of(definition), req -> reply(calls.incrementAndGet() == 1 ? "{\"ok\":\"wrong type\"}" : "{\"ok\":true}"))) {
            var out = runtime.run("json", null, null, origin());
            assertEquals("completed", out.path("status").asText()); assertEquals(2, calls.get());
            assertEquals(10, out.path("usage").path("reported_tokens").asInt());
        }
        try (var runtime = runtime(List.of(definition), req -> reply("{\"ok\":true} trailing text"))) {
            var out = runtime.run("json", null, null, origin());
            assertEquals("failed", out.path("status").asText()); assertEquals(2, out.path("usage").path("requests").asInt());
            Path journal = Path.of(out.path("output_file").asText()).resolveSibling("journal.jsonl");
            assertEquals("", Files.readString(journal));
        }
    }

    /** 截断即使正文是合法 JSON，也不能进入结构修正或成功 journal。 */
    @Test void truncatedAgentIsNotRetriedOrCached() throws Exception {
        var definition = flow("cut", (ctx, args) -> ctx.agent("step", null, "one"));
        try (var runtime = runtime(List.of(definition), req -> new ModelResponse(reply("partial").message(), new ModelResponse.Usage(2, 3), "max_tokens"))) {
            var out = runtime.run("cut", null, null, origin());
            assertEquals("failed", out.path("status").asText()); assertEquals(1, out.path("usage").path("requests").asInt());
            assertTrue(out.path("result").path("error").asText().contains("max_tokens (limit=2000)"));
            assertTrue(out.path("result").path("error").asText().contains("not cached"));
            assertEquals(5, out.path("usage").path("reported_tokens").asInt());
            assertEquals("", Files.readString(Path.of(out.path("output_file").asText()).resolveSibling("journal.jsonl")));
        }
    }

    /** 预算按已报告用量检查；不足和缺失报告都停止后续步骤，不把未知费用当零。 */
    @Test void observedBudgetAndMissingUsageStopFurtherRequests() throws Exception {
        ObjectNode args = object("{\"changes\":\"example\",\"budget\":1}");
        try (var runtime = runtime(List.of(ReviewChangesWorkflow.definition()), req -> reply("{\"findings\":[]}"))) {
            var out = runtime.run("review-changes", args, null, origin());
            assertEquals("failed", out.path("status").asText());
            assertTrue(out.path("usage").path("reported_tokens").asInt() >= 5);
            assertTrue(out.path("usage").path("requests").asInt() <= 2, "已超额后不能启动等待名额的请求");
        }
        try (var runtime = runtime(List.of(ReviewChangesWorkflow.definition()), req -> new ModelResponse(reply("{\"findings\":[]}").message(), null))) {
            var out = runtime.run("review-changes", args, null, origin());
            assertEquals("failed", out.path("status").asText());
            assertTrue(out.path("usage").path("unknown_usage").asInt() > 0);
        }
    }

    /** 用门闩证明快条目能先进入第二阶段；如果错误地增加全局屏障，本用例就无法释放慢条目。 */
    @Test void pipelineHasNoGlobalStageBarrier() throws Exception {
        CountDownLatch fastVerified = new CountDownLatch(1);
        var definition = flow("pipe", (ctx, args) -> {
            var results = ctx.pipeline(List.of(Json.mapper().getNodeFactory().textNode("slow"), Json.mapper().getNodeFactory().textNode("fast")), List.of(
                (branch, previous, item, index) -> {
                    if (index == 0 && !fastVerified.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Unexpected global stage barrier");
                    return item;
                },
                (branch, previous, item, index) -> { if (index == 1) fastVerified.countDown(); return previous; }));
            return Json.mapper().valueToTree(results);
        });
        try (var runtime = runtime(List.of(definition), req -> { fail("No model should be called"); return null; })) {
            var out = runtime.run("pipe", null, null, origin());
            assertEquals("completed", out.path("status").asText());
            assertEquals("slow", out.path("result").get(0).asText());
        }
    }

    /** 嵌套 parallel 仍只在实际模型请求时拿名额，避免所有外层任务占满名额后死锁。 */
    @Test void boundedParallelIncludesRetriesAndPreservesInputOrder() throws Exception {
        AtomicInteger inUse = new AtomicInteger(), max = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(2);
        var definition = flow("bounded", (ctx, args) -> Json.mapper().valueToTree(ctx.parallel(List.of(
                () -> ctx.parallel(List.of(() -> ctx.agent("a", null, "a"))).getFirst(),
                () -> ctx.agent("b", null, "b")))));
        try (var runtime = runtime(List.of(definition), req -> {
            int used = inUse.incrementAndGet(); max.accumulateAndGet(used, Math::max); started.countDown();
            try { if (!started.await(3, TimeUnit.SECONDS)) throw new LlmException("parallel did not start"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new LlmException("interrupted", e); }
            finally { inUse.decrementAndGet(); }
            return reply(req.messages().getFirst().extractText());
        })) {
            var out = runtime.run("bounded", null, null, origin());
            assertEquals("completed", out.path("status").asText()); assertEquals(2, max.get());
            assertEquals("a", out.path("result").get(0).asText()); assertEquals("b", out.path("result").get(1).asText());
        }
    }

    /** 同语义并发步骤去重；JSON null 是已缓存结果，不能用 null 判断把它当缺失再收费。 */
    @Test void duplicateStepsAndJsonNullUseOneRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var definition = flow("null", (ctx, args) -> Json.mapper().valueToTree(ctx.parallel(List.of(
                () -> ctx.agent("same", object("{\"type\":\"null\"}"), "same"),
                () -> ctx.agent("same", object("{\"type\":\"null\"}"), "same")))));
        try (var runtime = runtime(List.of(definition), req -> { calls.incrementAndGet(); return reply("null"); })) {
            var first = runtime.run("null", null, null, origin()); assertEquals("completed", first.path("status").asText());
            assertEquals(1, calls.get());
            var second = runtime.run("null", null, first.path("run_id").asText(), origin());
            assertEquals("completed", second.path("status").asText()); assertEquals(1, calls.get());
        }
    }

    /** 一层嵌套共享实际请求额度，格式重试不能额外绕过；超限是失败而非假成功。 */
    @Test void nestedWorkflowAndCorrectionShareRequestCap() throws Exception {
        var child = flow("child", (ctx, args) -> ctx.agent("json", object("{\"type\":\"boolean\"}"), "one"));
        var parent = flow("parent", (ctx, args) -> ctx.workflow("child", null));
        AtomicInteger calls = new AtomicInteger();
        try (var runtime = runtime(List.of(parent, child), req -> { calls.incrementAndGet(); return reply("not json"); }, new WorkflowRuntime.Limits(1, 1))) {
            var out = runtime.run("parent", null, null, origin());
            assertEquals("failed", out.path("status").asText()); assertEquals(1, calls.get());
            assertTrue(out.path("result").path("error").asText().contains("cap"));
        }
        var recursive = flow("recursive", (ctx, args) -> ctx.workflow("recursive", null));
        try (var runtime = runtime(List.of(recursive), req -> { fail("recursive workflow must fail before model call"); return null; })) {
            assertEquals("failed", runtime.run("recursive", null, null, origin()).path("status").asText());
        }
    }

    /** 恢复身份或 journal 损坏时保留已存在的成功 output，不把错误恢复当新运行覆盖掉。 */
    @Test void resumeRejectsMismatchAndCorruptionWithoutOverwritingOutput() throws Exception {
        var definition = flow("saved", (ctx, args) -> ctx.agent("one", null, "one"));
        try (var runtime = runtime(List.of(definition), req -> reply("ok"))) {
            var out = runtime.run("saved", object("{\"tag\":\"original\"}"), null, origin());
            String id = out.path("run_id").asText(); Path output = Path.of(out.path("output_file").asText());
            String original = Files.readString(output);
            assertThrows(IOException.class, () -> runtime.run("saved", object("{\"tag\":\"changed\"}"), id, origin()));
            var changedVersion = new WorkflowDefinition(definition.name(), definition.description(), "2", definition.phases(), definition.argsSchema(), definition.script());
            try (var changed = runtime(List.of(changedVersion), req -> reply("must not run"))) {
                assertThrows(IOException.class, () -> changed.run("saved", null, id, origin()));
            }
            try (var changedModel = new WorkflowRuntime(workspace, WorkflowDefinition.registry(List.of(definition)),
                    () -> new WorkflowAgentRunner(req -> reply("must not run"), "other-model", 2000, ThinkingSettings.DEFAULT),
                    WorkflowRuntime.Limits.defaults(), ignored -> { })) {
                assertThrows(IOException.class, () -> changedModel.run("saved", null, id, origin()));
            }
            Path journal = output.resolveSibling("journal.jsonl");
            ObjectNode entry = object(Files.readString(journal)); entry.put("value", false);
            Files.writeString(journal, entry + "\n");
            var invalidCache = runtime.run("saved", null, id, origin());
            assertEquals("failed", invalidCache.path("status").asText());
            assertTrue(invalidCache.path("output_preserved").asBoolean());
            assertEquals(original, Files.readString(output), "坏缓存失败不能覆盖旧成功产物");
            Files.writeString(output.resolveSibling("journal.jsonl"), "broken", StandardOpenOption.APPEND);
            assertThrows(IOException.class, () -> runtime.run("saved", null, id, origin()));
            assertEquals(original, Files.readString(output));
        }
    }

    /** 两个 Runtime 无法同时续跑同 run；关闭后到达的模型回复不能落入 journal。 */
    @Test void runLockAndCloseFenceLateResponses() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<String> id = new AtomicReference<>();
        var definition = flow("late", (ctx, args) -> ctx.agent("one", null, "one"));
        WorkflowRuntime runtime = new WorkflowRuntime(workspace, WorkflowDefinition.registry(List.of(definition)),
                () -> new WorkflowAgentRunner(req -> {
                    entered.countDown();
                    try { if (!release.await(4, TimeUnit.SECONDS)) throw new LlmException("test release missing"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new LlmException("interrupted", error); }
                    return reply("late");
                }, "offline", 2000, ThinkingSettings.DEFAULT), WorkflowRuntime.Limits.defaults(), p -> id.compareAndSet(null, p.runId()));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor(); var competitor = runtime(List.of(definition), req -> reply("fresh"))) {
            Future<ObjectNode> future = pool.submit(() -> runtime.run("late", null, null, origin()));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> competitor.run("late", null, id.get(), origin()));
            runtime.close(); release.countDown();
            var cancelled = future.get(3, TimeUnit.SECONDS);
            assertEquals("cancelled", cancelled.path("status").asText());
            assertEquals("", Files.readString(Path.of(cancelled.path("output_file").asText()).resolveSibling("journal.jsonl")));
            assertEquals("completed", competitor.run("late", null, id.get(), origin()).path("status").asText());
        } finally { release.countDown(); runtime.close(); }
    }

    /** 不支持的 schema/未知参数/目录逃逸在调用前拒绝；软链不能把运行文件写到外部。 */
    @Test void validatesRegistrationInputAndWorkspacePaths() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> WorkflowSchema.checkSchema(object("{\"type\":\"object\",\"$ref\":\"remote\"}")));
        assertThrows(IllegalArgumentException.class, () -> WorkflowSchema.validate(Json.mapper().getNodeFactory().numberNode(1.5), object("{\"type\":\"integer\"}")));
        assertThrows(IOException.class, () -> WorkflowSchema.parse("{\"a\":1,\"a\":2}"));
        var definition = flow("safe", (ctx, args) -> ctx.agent("one", null, "one"));
        try (var runtime = runtime(List.of(definition), req -> { fail("invalid inputs must not request model"); return null; })) {
            assertThrows(IllegalArgumentException.class, () -> runtime.run("missing", null, null, origin()));
            assertThrows(IllegalArgumentException.class, () -> runtime.run("safe", object("{\"unexpected\":true}"), null, origin()));
            assertThrows(IOException.class, () -> runtime.run("safe", null, "../../outside", origin()));
            Path internal = Files.createDirectories(workspace.resolve(".jadecode"));
            Files.createSymbolicLink(internal.resolve("workflows"), Files.createDirectory(workspace.resolve("outside")));
            assertThrows(IOException.class, () -> runtime.run("safe", null, null, origin()));
        }
    }
}
