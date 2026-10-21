package com.jadecode.cli.tui.model;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.execution.ToolIdentity;

/** 显示转录/流式块/工具预览关联/历史状态测试；不使用真实模型或工具。 */
class TuiProjectionTest {
    @Test void previewAndExecutionReuseTheSameCardWithoutGuessing() {
        var model = new TranscriptModel();
        model.reset(1);
        var preview = model.previewStarted("req-1", "leader", 0, "call-a", "read_file",
                "{\"path\":\"a", ToolIdentity.builtin("read_file"));
        model.previewDelta("req-1", 0, ".txt\"}");
        model.previewClosed("req-1", 0);
        assertEquals("{\"path\":\"a.txt\"}", preview.arguments());
        model.execution(new ExecutionNotice("leader", ExecutionNotice.Kind.TOOL_START,
                "req-1/call-a", "read_file", "{\"path\":\"a.txt\"}", "RUNNING", 0, 0));
        model.execution(new ExecutionNotice("leader", ExecutionNotice.Kind.TOOL_END,
                "req-1/call-a", "read_file", "hello", "RETURNED", 0, 0, 12_000_000L));
        assertEquals(1, model.tools().size());
        assertEquals(1, model.entries().stream().filter(e -> e.kind() == TranscriptEntry.Kind.TOOL).count());
        var tool = model.tools().getFirst();
        assertEquals(ToolDisplayState.Status.RETURNED, tool.status());
        assertEquals("hello", tool.result());
        assertEquals(12_000_000L, tool.elapsedNanos());
    }

    @Test void sameCallIdFromDifferentActorsDoesNotShareCard() {
        var model = new TranscriptModel();
        model.reset(1);
        model.previewStarted("req-1", "leader", 0, "same", "bash", "{}", null);
        model.previewStarted("req-2", "agent-2", 0, "same", "bash", "{}", null);
        model.execution(new ExecutionNotice("leader", ExecutionNotice.Kind.TOOL_END,
                "req-1/same", "bash", "leader result", "RETURNED", 0, 0));
        model.execution(new ExecutionNotice("agent-2", ExecutionNotice.Kind.TOOL_END,
                "req-2/same", "bash", "agent result", "ERROR", 0, 0));
        assertEquals(2, model.tools().size());
        assertTrue(model.tools().stream().anyMatch(t -> t.actor().equals("leader") && t.result().equals("leader result")));
        assertTrue(model.tools().stream().anyMatch(t -> t.actor().equals("agent-2") && t.status() == ToolDisplayState.Status.ERROR));
    }

    @Test void streamKeepsTextThinkingInterleavingAndMarksIncomplete() {
        var model = new TranscriptModel();
        model.reset(1);
        model.delta("r", "leader", TranscriptEntry.Kind.REPLY, "a");
        model.delta("r", "leader", TranscriptEntry.Kind.THINKING, "b");
        model.delta("r", "leader", TranscriptEntry.Kind.REPLY, "c");
        var kinds = model.entries().stream().map(TranscriptEntry::kind).toList();
        assertEquals(java.util.List.of(TranscriptEntry.Kind.REPLY, TranscriptEntry.Kind.THINKING, TranscriptEntry.Kind.REPLY), kinds);
        model.endStream("r", true, "");
        assertTrue(model.entries().stream().allMatch(e -> e.status().equals("INCOMPLETE")));
        assertEquals("c", model.lastReplyText());
    }

    @Test void historicalToolWithoutResultIsNotReportedAsSuccess() {
        var model = new TranscriptModel();
        model.reset(1);
        var state = model.addHistoricalTool("leader", "old", "bash", "{\"command\":\"x\"}", "", false, false);
        assertEquals(ToolDisplayState.Status.INTERRUPTED, state.status());
        assertTrue(state.result().contains("No result recorded"));
    }

    @Test void truncationStatusComesFromStopReason() {
        var model = new TranscriptModel();
        model.reset(1);
        model.delta("r", "leader", TranscriptEntry.Kind.REPLY, "partial");
        model.endStream("r", false, "max_tokens");
        assertEquals("TRUNCATED", model.entries().getFirst().status());
    }
}
