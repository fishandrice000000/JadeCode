package com.jadecode.goal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.jadecode.llm.ModelResponse;
import com.jadecode.messages.*;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 直接验证目标状态、迟到判断、严格输出和证据裁剪；不访问认证或真实服务。 */
class GoalControllerTest {
    /** 测试回复复用项目现有 assistant(blocks) 工厂，不扩展生产消息 API。 */
    private static Message text(String value) { return Message.assistant(List.of(new TextBlock(value))); }
    /** Controller 的状态必须先成功保存；替换/清除能使旧 Ticket 失效。 */
    @Test void transitionsRejectLateDecisionsAndPreserveFailedSaves() {
        var saved = new ArrayList<GoalState>();
        var controller = new GoalController((condition, history) -> null, saved::add, GoalController.Limits.defaults());
        assertNull(controller.ticket()); controller.set(" test exits 0 ");
        var first = controller.ticket(); assertEquals("test exits 0", first.state().condition());
        controller.set("test exits 0");
        assertNotEquals(first.state().id(), controller.state().id());
        assertEquals("stale", controller.complete(first, new GoalEvaluator.Evaluation(true, "old", false), 0).action());
        var second = controller.ticket(); controller.clear();
        assertFalse(controller.suspend(second, "late error"));
        assertEquals("stale", controller.complete(second, new GoalEvaluator.Evaluation(true, "old", false), 0).action());
        assertEquals(GoalState.Status.CLEARED, controller.state().status()); assertEquals(3, saved.size());
        var broken = new GoalController((c, h) -> null, state -> { throw new IllegalStateException("disk failed"); }, GoalController.Limits.defaults());
        assertThrows(IllegalStateException.class, () -> broken.set("test")); assertNull(broken.state());
    }

    /** 未完成、上限、成功与无法完成各有明确状态；恢复不复活终态，也不沿用旧自动轮数。 */
    @Test void limitAndRestoreKeepUnfinishedGoal() {
        var controller = new GoalController((c, h) -> null, state -> { }, new GoalController.Limits(3, 1));
        controller.set("goal");
        assertEquals("block", controller.complete(controller.ticket(), new GoalEvaluator.Evaluation(false, "missing test", false), 0).action());
        assertEquals("limit", controller.complete(controller.ticket(), new GoalEvaluator.Evaluation(false, "still missing", false), 1).action());
        GoalState saved = controller.state(); assertTrue(saved.active()); assertEquals(2, saved.evaluations());
        controller.restore(saved); assertEquals(0, controller.state().evaluations()); assertNotNull(controller.ticket());
        assertEquals("achieved", controller.complete(controller.ticket(), new GoalEvaluator.Evaluation(true, "exit 0", false), 0).action());
        controller.restore(controller.state()); assertNull(controller.ticket());
        controller.set("impossible");
        assertEquals("impossible", controller.complete(controller.ticket(), new GoalEvaluator.Evaluation(false, "required file unavailable", true), 0).action());
        assertFalse(controller.state().active());
    }

    /** 判断器只读近期证据，无 tools，不回填主历史；独立输出额度明确。 */
    @Test void evaluatorUsesSeparateToolFreeRequest() throws Exception {
        var history = new ArrayList<>(List.of(Message.userText("user task"), text("candidate")));
        AtomicInteger calls = new AtomicInteger();
        var evaluator = new PromptGoalEvaluator(request -> {
            calls.incrementAndGet(); assertTrue(request.tools().isEmpty()); assertEquals(512, request.maxTokens());
            assertEquals(PromptGoalEvaluator.SYSTEM, request.system()); assertEquals(1, request.messages().size());
            com.fasterxml.jackson.databind.JsonNode payload;
            try { payload = Json.mapper().readTree(request.messages().getFirst().extractText()); }
            catch (IOException invalid) { throw new AssertionError(invalid); }
            assertEquals("test exits 0", payload.path("completion_condition").asText());
            assertTrue(payload.path("conversation").asText().contains("candidate"));
            return new ModelResponse(text("{\"ok\":false,\"reason\":\"missing exit code\",\"impossible\":false}"), null, "end_turn");
        }, 512);
        assertFalse(evaluator.evaluate("test exits 0", history).ok()); assertEquals(1, calls.get()); assertEquals(2, history.size());
    }

    /** 协议结构不能靠宽松类型转换、重复字段或忽略尾部解释蒙混通过。 */
    @Test void parserRejectsAmbiguousAndMalformedJudgments() throws Exception {
        String valid = "{\"ok\":true,\"reason\":\"exit 0\",\"impossible\":false}";
        assertTrue(PromptGoalEvaluator.parse(valid).ok()); assertTrue(PromptGoalEvaluator.parse("```json\n" + valid + "\n```").ok());
        for (String value : List.of(valid + " trailing", valid + " {}", "[]", valid.replace("true", "\"true\""),
                valid.replace("\"exit 0\"", "\" \""), valid.replace("false", "true"),
                valid.replace("\"ok\":true", "\"ok\":false,\"ok\":true"), valid.replace(",\"impossible\":false", ""),
                valid.replace("\"reason\"", "\"unknown\"")))
            assertThrows(IOException.class, () -> PromptGoalEvaluator.parse(value), value);
    }

    /** 截断、拒绝和工具行动不能被当作有效完成证据，也不会自动多请求一次。 */
    @Test void evaluatorFailsClosedWithoutRetry() {
        for (String stop : List.of("", "max_tokens", "refusal", "tool_use")) {
            AtomicInteger calls = new AtomicInteger();
            var evaluator = new PromptGoalEvaluator(request -> {
                calls.incrementAndGet(); return new ModelResponse(text("{\"ok\":true,\"reason\":\"yes\",\"impossible\":false}"), null, stop);
            }, 512);
            assertThrows(IOException.class, () -> evaluator.evaluate("goal", List.of())); assertEquals(1, calls.get());
        }
        var evaluator = new PromptGoalEvaluator(request -> new ModelResponse(Message.assistant(List.of(new ToolUseBlock("id", "bash", Json.mapper().createObjectNode()))), null, "end_turn"), 512);
        assertThrows(IOException.class, () -> evaluator.evaluate("goal", List.of()));
    }

    /** 保留最近完整消息，单条太长保留首尾；原列表与长字符串均不被修改。 */
    @Test void transcriptRetainsRecentMessagesAndMarksOmission() throws Exception {
        var history = List.of(Message.userText("old".repeat(100)), text("recent"));
        String selected = PromptGoalEvaluator.transcript(history, 150);
        assertFalse(selected.contains("oldold")); assertTrue(selected.contains("recent"));
        // 召回资料迁入历史后仍不是完成证据，也不能挤掉最近的实际回答。
        selected = PromptGoalEvaluator.transcript(List.of(text("actual result"),
                Message.memoryReference("OLD_MEMORY_COMPLETION_CLAIM".repeat(100))), 150);
        assertTrue(selected.contains("actual result")); assertFalse(selected.contains("OLD_MEMORY_COMPLETION_CLAIM"));
        String longText = "HEAD" + "x".repeat(1000) + "TAIL";
        selected = PromptGoalEvaluator.transcript(List.of(Message.userText(longText)), 200);
        assertEquals(200, selected.length()); assertTrue(selected.contains("HEAD")); assertTrue(selected.contains("TAIL"));
        assertTrue(selected.contains("incomplete evidence")); assertEquals(longText, Message.userText(longText).extractText());
    }
}
