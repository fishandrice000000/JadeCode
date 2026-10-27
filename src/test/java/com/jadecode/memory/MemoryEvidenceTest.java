package com.jadecode.memory;

import com.jadecode.config.WorkspacePaths;
import com.jadecode.messages.*;
import com.jadecode.session.history.*;
import com.jadecode.util.Json;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 原始事件与显示历史分别验证，防止压缩后的摘要吞掉早期用户纠正。 */
class MemoryEvidenceTest {
    @TempDir Path workspace;

    @Test void journalKeepsEarlyHumanEvidenceAndExcludesInjectedReferences() throws Exception {
        var sessions = new SessionStore(com.jadecode.support.TestPaths.workspace(workspace), ignored -> { });
        try (var opened = sessions.create("offline")) {
            var journal = opened.journal(); long start = journal.sequence();
            journal.append(JournalEntry.Kind.APPEND, List.of(Message.userText("早期纠正：这是当前任务的要求，不是全局偏好")), Json.mapper().createArrayNode(), "request");
            journal.append(JournalEntry.Kind.APPEND, List.of(Message.memoryReference("OLD_MEMORY_MUST_NOT_REENTER")), Json.mapper().createArrayNode(), "memory_reference");
            journal.append(JournalEntry.Kind.APPEND, List.of(Message.userText("BACKGROUND_NOT_HUMAN")), Json.mapper().createArrayNode(), "notification");
            journal.append(JournalEntry.Kind.REPLACE_HISTORY, List.of(Message.userText("COMPACTED_CONTEXT_ONLY")), Json.mapper().createArrayNode(), "compaction");
            journal.append(JournalEntry.Kind.APPEND, List.of(Message.assistant(List.of(new TextBlock("最终只验证了编译，没有部署")))), Json.mapper().createArrayNode(), "reply");
            var job = Json.mapper().createObjectNode().put("journal", journal.eventsPath().toString())
                    .put("start_sequence", start).put("end_sequence", journal.sequence());
            String evidence = MemoryEvidence.render(job, com.jadecode.support.TestPaths.workspace(workspace), 8000);
            assertTrue(evidence.contains("早期纠正")); assertTrue(evidence.contains("没有部署"));
            assertFalse(evidence.contains("OLD_MEMORY")); assertFalse(evidence.contains("BACKGROUND")); assertFalse(evidence.contains("COMPACTED_CONTEXT"));
            assertTrue(evidence.contains(":event="));
        }
    }

    @Test void fallbackExcludesMemoryToolResultsAndRedactsSecrets() throws Exception {
        var job = Json.mapper().createObjectNode().put("id", "fixture");
        job.set("snapshot", Json.mapper().valueToTree(List.of(Message.userText("api_key=fixture-secret-value This is a request"),
                Message.assistant(List.of(new ToolUseBlock("memory-read", "read_file", Json.mapper().createObjectNode()
                        .put("path", "/home/user/.jadecode/memory/MEMORY.md")))),
                Message.userBlocks(List.of(new ToolResultBlock("memory-read", "PREVIOUS_MEMORY_OUTPUT", false))))));
        String result = MemoryEvidence.render(job, com.jadecode.support.TestPaths.workspace(workspace), 5000);
        assertTrue(result.contains("This is a request")); assertFalse(result.contains("fixture-secret-value"));
        assertFalse(result.contains("PREVIOUS_MEMORY_OUTPUT"));
        assertTrue(result.contains("[REDACTED_SECRET]"));
    }

    @Test void oversizedHumanInputRetainsExplicitlyPartialEvidence() throws Exception {
        var job = Json.mapper().createObjectNode().put("id", "fixture");
        job.set("snapshot", Json.mapper().valueToTree(List.of(Message.userText("真实用户约束" + "x".repeat(10_000)))));
        String result = MemoryEvidence.render(job, com.jadecode.support.TestPaths.workspace(workspace), 1000);
        assertTrue(result.contains("真实用户约束")); assertTrue(result.contains("truncated")); assertTrue(result.length() <= 1000);
    }
}
