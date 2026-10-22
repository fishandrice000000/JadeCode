package com.jadecode.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.LlmClient;
import com.jadecode.llm.ModelResponse;
import com.jadecode.messages.Message;
import com.jadecode.messages.TextBlock;
import com.jadecode.safety.approval.ApprovalRequest;
import com.jadecode.safety.approval.ModelApprovalReviewer;
import com.jadecode.util.Json;

/**
 * S84：内置提示词的英文审计证据。
 *
 * <p>检查真实构造出来的 system/辅助请求：程序指令为英文，但保留角色、JSON 字段与
 * “允许中文用户回复”的原始语义；这里不调用网络，也不断言模型质量。</p>
 */
class PromptLanguageTest {
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    @Test void mainAndSubagentPromptsAreEnglishAndKeepRoleAndSkills() {
        String main = JadeCodeFactory.systemPrompt(Path.of("/work"), "- pdf: read PDFs");
        assertFalse(CJK.matcher(main).find(), main);
        assertTrue(main.contains("You are Jade Code"));
        assertTrue(main.contains("Skills available:"));
        assertTrue(main.contains("- pdf: read PDFs"));
        assertTrue(main.contains("Never bypass a denial."));
        String sub = JadeCodeFactory.subSystemPrompt(Path.of("/work"));
        assertFalse(CJK.matcher(sub).find(), sub);
        assertTrue(sub.contains("Complete the given task"));
    }

    @Test void builtInAuxiliaryPromptsAreEnglishAndKeepMachineFields() throws Exception {
        assertPrompt("com.jadecode.context.HistoryCompactor", "SUMMARY_SYSTEM",
                List.of("factual state", "Preserve the current goal"));
        assertPrompt("com.jadecode.memory.MemoryExtractionService", "EXTRACTION_SYSTEM",
                List.of("Return only a JSON array", "name", "type", "description", "body"));
        assertPrompt("com.jadecode.memory.MemoryRecallService", "SELECTION_SYSTEM",
                List.of("memory"));
        assertPrompt("com.jadecode.memory.MemoryConsolidationService", "CONSOLIDATION_SYSTEM",
                List.of("Consolidate", "JSON"));
    }

    @Test void approvalReviewerBuildsEnglishPromptAndKeepsJsonContract() {
        var captured = new CapturingClient();
        var request = new ApprovalRequest("id", "leader", "run", "bash",
                Json.mapper().createObjectNode().put("command", "rm x"),
                Path.of("/work"), "needs confirmation", 1, null);
        var result = new ModelApprovalReviewer(captured).review(request, "clean the workspace");
        assertEquals(ModelApprovalReviewer.Result.ESCALATE, result);
        String system = captured.last.system();
        assertNotNull(system);
        assertFalse(CJK.matcher(system).find(), system);
        assertTrue(system.contains("Review one exact proposed coding-agent operation"));
        assertTrue(system.contains("Return exactly {\"decision\":\"allow|deny|escalate\""));
        assertTrue(system.contains("Never execute tools."));
        String body = captured.last.messages().getFirst().extractText();
        assertTrue(body.contains("\"actor\"") && body.contains("\"tool\""), body);
    }

    private static void assertPrompt(String className, String field, List<String> fragments) throws Exception {
        Class<?> type = Class.forName(className);
        var value = type.getDeclaredField(field);
        value.setAccessible(true);
        String prompt = (String) value.get(null);
        assertNotNull(prompt, className + "." + field);
        assertFalse(CJK.matcher(prompt).find(), className + "." + field + " must be English");
        for (String fragment : fragments) assertTrue(prompt.contains(fragment),
                className + "." + field + " missing " + fragment + ": " + prompt);
    }

    private static final class CapturingClient implements LlmClient {
        private CompleteRequest last;
        @Override public ModelResponse complete(CompleteRequest request) {
            last = request;
            return new ModelResponse(Message.assistant(List.of(new TextBlock(
                    "{\"decision\":\"escalate\",\"reason\":\"test\"}"))),
                    new ModelResponse.Usage(1, 1), "end_turn");
        }
    }
}
