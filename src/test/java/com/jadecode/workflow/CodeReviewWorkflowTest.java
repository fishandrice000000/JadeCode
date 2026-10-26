package com.jadecode.workflow;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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

/** 真运行时/缓存，假模型；只在临时工作区记录流程，不读取路径标签指向的文件。 */
@Timeout(15)
class CodeReviewWorkflowTest {
    @TempDir Path workspace;
    /** 默认源码使用 CRLF，证明资料编号/引用检查对常见换行一致，不丢行号。 */
    private static ObjectNode args() {
        ObjectNode args = Json.mapper().createObjectNode().put("context", "Callers may pass a null name.");
        args.putArray("files").addObject().put("path", "Service.java").put("content", "class Service {\r\n  return name.trim();\r\n}");
        return args;
    }
    /** 一条完整候选，其位置/触发/影响在不同测试中按需替换，不靠不完整 schema 凑通过。 */
    private static ObjectNode finding(String title, String severity) {
        return Json.mapper().createObjectNode().put("title", title).put("severity", severity).put("path", "Service.java")
                .put("start_line", 2).put("end_line", 2).put("evidence", "return name.trim();")
                .put("trigger", "name is null").put("impact", "call throws before returning").put("suggestion", "handle null according to contract");
    }
    /** 审查与复核的响应使用严格 JSON，真实用量用于检查步骤数和恢复零重调。 */
    private static ModelResponse reply(JsonNode value) {
        return new ModelResponse(Message.assistant(List.of(new TextBlock(value.toString()))), new ModelResponse.Usage(2, 3), "end_turn");
    }
    /** 复核结构明确表示确认/否定/缺上下文，空 needed_context 才能接受确定结论。 */
    private static ObjectNode verdict(String decision, String needed) {
        return Json.mapper().createObjectNode().put("verdict", decision).put("reason", "checked supplied callers").put("needed_context", needed);
    }
    /** 审查模板允许零问题/零疑点，避免把空报告误包装成整体通过。 */
    private static ObjectNode audit() {
        ObjectNode out = Json.mapper().createObjectNode(); out.putArray("findings"); out.putArray("questions"); return out;
    }
    /** 用原 runner 接口装配新预设；所有输出仍走原 JSON/schema、journal 和取消围栏。 */
    private WorkflowRuntime runtime(LlmClient client) {
        return new WorkflowRuntime(workspace, WorkflowDefinition.registry(List.of(CodeReviewWorkflow.definition())),
                () -> new WorkflowAgentRunner(client, "offline", 2000, ThinkingSettings.DEFAULT), WorkflowRuntime.Limits.defaults(), ignored -> { });
    }
    /** 每维返回调用方提供的结构；复核请求可断言看见完整源资料与候选，统计真实步骤。 */
    private static LlmClient model(Function<String, ObjectNode> audit, Function<String, ObjectNode> verify, AtomicInteger calls) {
        return request -> {
            calls.incrementAndGet(); assertTrue(request.tools().isEmpty()); assertEquals(1, request.messages().size());
            String prompt = request.messages().getFirst().extractText();
            assertTrue(prompt.contains("2:   return name.trim();"));
            return reply(prompt.contains("\nReview supplied code for ") ? audit.apply(prompt) : verify.apply(prompt));
        };
    }

    /** 全流程与跨 runtime 续跑：合并相同问题的维度，取最高严重度，原输入不变且缓存不重调。 */
    @Test void reviewsCompleteFilesDeduplicatesAndResumes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ObjectNode input = args(); input.withArray("files").addObject().put("path", "Caller.java").put("content", "service(null);");
        ObjectNode unchanged = input.deepCopy();
        var prompts = new java.util.concurrent.CopyOnWriteArrayList<String>();
        LlmClient scripted = model(prompt -> {
            ObjectNode out = audit(); out.withArray("findings").add(finding("null handling", prompt.contains("for security.") ? "high" : "medium"));
            return out;
        }, prompt -> {
            assertTrue(prompt.contains("Caller.java")); assertTrue(prompt.contains("service(null);"));
            assertTrue(prompt.contains("Candidate:")); return verdict("confirmed", "");
        }, calls);
        LlmClient client = request -> {
            prompts.add(request.messages().getFirst().extractText());
            return scripted.complete(request);
        };
        ObjectNode first;
        try (var runtime = runtime(client)) {
            first = runtime.run("code-review", input, null, ExecutionContext.create(ExecutionContext.Source.USER, null));
            assertEquals("completed", first.path("status").asText(), first.toString());
            assertEquals(8, calls.get()); assertEquals(40, first.path("usage").path("reported_tokens").asInt());
            JsonNode report = first.path("result"), confirmed = report.path("confirmed");
            assertEquals(1, confirmed.size()); assertEquals("high", confirmed.get(0).path("severity").asText());
            assertEquals(4, confirmed.get(0).path("dimensions").size()); assertEquals(4, confirmed.get(0).path("verification_reasons").size());
            assertEquals(3, report.path("deduplicated_count").asInt()); assertEquals(2, report.path("reviewed_files").size());
            assertTrue(report.path("analysis_only").asBoolean()); assertFalse(report.has("pass"));
        }
        // 八次请求（四个维度、两种阶段）都应完整复用相同资料，再追加各自指令。
        String auditPrompt = prompts.stream().filter(p -> p.contains("\nReview supplied code for ")).findFirst().orElseThrow();
        String shared = auditPrompt.substring(0, auditPrompt.indexOf("\nReview supplied code for ") + 1);
        assertTrue(shared.contains("Caller.java")); assertTrue(shared.contains("service(null);"));
        assertTrue(shared.contains("2:   return name.trim();"));
        assertEquals(8, prompts.size());
        prompts.forEach(prompt -> assertTrue(prompt.startsWith(shared), "完整源资料必须位于维度/候选变化之前"));
        try (var runtime = runtime(client)) {
            var restored = runtime.run("code-review", null, first.path("run_id").asText(), ExecutionContext.create(ExecutionContext.Source.USER, null));
            assertEquals("completed", restored.path("status").asText()); assertEquals(first.path("result"), restored.path("result"));
            assertEquals(8, calls.get()); assertEquals(8, restored.path("usage").path("cached_steps").asInt());
            assertEquals(0, restored.path("usage").path("requests").asInt());
        }
        assertEquals(unchanged, input);
    }

    /** 同一行可以有多个问题，不能仅按文件/行号去重；排序和合并不依赖完成顺序。 */
    @Test void keepsDifferentIssuesAtSameLocationAndSortsSeverity() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = model(prompt -> {
            ObjectNode out = audit();
            if (prompt.contains("for correctness.")) out.withArray("findings").add(finding("null handling", "low"));
            if (prompt.contains("for security.")) out.withArray("findings").add(finding("another issue", "high").put("trigger", "different condition"));
            return out;
        }, prompt -> verdict("confirmed", ""), calls);
        try (var runtime = runtime(client)) {
            var out = runtime.run("code-review", args(), null, ExecutionContext.create(ExecutionContext.Source.USER, null));
            assertEquals("completed", out.path("status").asText(), out.toString());
            JsonNode findings = out.path("result").path("confirmed");
            assertEquals(2, findings.size()); assertEquals("another issue", findings.get(0).path("title").asText());
            assertEquals(0, out.path("result").path("deduplicated_count").asInt()); assertEquals(6, calls.get());
        }
    }

    /** 未提供文件、倒序/越界行号及错误引文进入未确认区，不浪费复核请求或假称确认。 */
    @Test void invalidAnchorsRemainUnconfirmedWithoutVerificationCalls() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = model(prompt -> {
            ObjectNode out = audit(); ObjectNode candidate = finding("anchor", "high");
            if (prompt.contains("for correctness.")) candidate.put("path", "not-supplied.java");
            else if (prompt.contains("for security.")) candidate.put("start_line", 3).put("end_line", 2);
            else if (prompt.contains("for failure-handling.")) candidate.put("end_line", 99);
            else candidate.put("evidence", "this text is absent");
            out.withArray("findings").add(candidate); return out;
        }, prompt -> { fail("invalid anchors must not request verification"); return null; }, calls);
        try (var runtime = runtime(client)) {
            var out = runtime.run("code-review", args(), null, ExecutionContext.create(ExecutionContext.Source.USER, null));
            assertEquals("completed", out.path("status").asText(), out.toString()); assertEquals(4, calls.get());
            assertTrue(out.path("result").path("confirmed").isEmpty()); assertEquals(4, out.path("result").path("unconfirmed").size());
            out.path("result").path("unconfirmed").forEach(item -> assertEquals("invalid_evidence", item.path("status").asText()));
        }
    }

    /** 审查提问、复核缺资料与矛盾确认均保留为疑点；明确否定只计数，不混进确认列表。 */
    @Test void separatesMissingContextContradictionsAndRejectedCandidates() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = model(prompt -> {
            ObjectNode out = audit();
            out.withArray("findings").add(finding("candidate", "medium"));
            if (prompt.contains("for correctness.")) out.withArray("questions").addObject().put("path", "Caller.java")
                    .put("question", "Can callers pass null?").put("needed_context", "caller contract");
            return out;
        }, prompt -> {
            if (prompt.contains("for correctness.")) return verdict("needs_context", "caller contract");
            if (prompt.contains("for security.")) return verdict("confirmed", "still need caller contract");
            if (prompt.contains("for failure-handling.")) return verdict("rejected", "");
            return verdict("confirmed", "").put("reason", " ");
        }, calls);
        try (var runtime = runtime(client)) {
            var out = runtime.run("code-review", args(), null, ExecutionContext.create(ExecutionContext.Source.USER, null));
            assertEquals("completed", out.path("status").asText(), out.toString()); assertEquals(8, calls.get());
            JsonNode report = out.path("result");
            assertTrue(report.path("confirmed").isEmpty()); assertEquals(4, report.path("unconfirmed").size());
            assertEquals(1, report.path("rejected_count").asInt());
        }
    }

    /** 输入结构、重复标签和总量失败在任何模型请求前拒绝；标签不会被当作真实路径读取。 */
    @Test void rejectsBadAndOversizedInputsBeforeModelCalls() throws Exception {
        try (var runtime = runtime(request -> { fail("invalid input must not call models"); return null; })) {
            var origin = ExecutionContext.create(ExecutionContext.Source.USER, null);
            ObjectNode wrong = Json.mapper().createObjectNode(); wrong.putArray("files");
            assertThrows(IllegalArgumentException.class, () -> runtime.run("code-review", wrong, null, origin));
            wrong.set("files", args().get("files")); wrong.put("script", "not allowed");
            assertThrows(IllegalArgumentException.class, () -> runtime.run("code-review", wrong, null, origin));
            ObjectNode duplicates = args(); duplicates.withArray("files").add(duplicates.path("files").get(0).deepCopy());
            assertEquals("failed", runtime.run("code-review", duplicates, null, origin).path("status").asText());
            ObjectNode invalidPath = args(); ((ObjectNode) invalidPath.path("files").get(0)).put("path", "bad\nlabel");
            assertEquals("failed", runtime.run("code-review", invalidPath, null, origin).path("status").asText());
            ObjectNode huge = Json.mapper().createObjectNode(); var files = huge.putArray("files");
            for (int i = 0; i < 3; i++) files.addObject().put("path", "file-" + i).put("content", "x".repeat(50000));
            assertEquals("failed", runtime.run("code-review", huge, null, origin).path("status").asText());
            ObjectNode manyLines = args(); ((ObjectNode) manyLines.path("files").get(0)).put("content", "x\n".repeat(8001));
            assertEquals("failed", runtime.run("code-review", manyLines, null, origin).path("status").asText());
            ObjectNode encoded = args(); ((ObjectNode) encoded.path("files").get(0)).put("content", "\u0001".repeat(50000));
            assertEquals("failed", runtime.run("code-review", encoded, null, origin).path("status").asText());
        }
    }
}
