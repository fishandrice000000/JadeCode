package com.jadecode.cli.backend;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.jadecode.config.JadeSettings;
import com.jadecode.llm.ModelMetadata;
import com.jadecode.llm.ModelResponse;
import com.jadecode.llm.ThinkingSettings;
import com.jadecode.llm.UsageRecord;
import com.jadecode.session.SessionRuntime;
import com.jadecode.session.SessionUsage;

/** 用真实去重账本经过 wire 投影，防止 Node 分类资料与累计总数脱节或重复相加。 */
class BackendSnapshotTest {
    @Test void serializesRealUsageGroupsAndMissingReportsWithoutDoubleCounting() {
        SessionUsage ledger = new SessionUsage(ignored -> { });
        var formal = new UsageRecord("r1", "fixture", "leader", UsageRecord.Purpose.FORMAL, new ModelResponse.Usage(20, 10));
        ledger.record(formal); ledger.record(formal);
        ledger.record(new UsageRecord("r2", "fixture", "agent_1", UsageRecord.Purpose.SUBAGENT, null));
        var display = new SessionRuntime.Display("session", "title", "fixture",
                new ModelMetadata(ModelMetadata.Limits.unknown(), "fixture"), ThinkingSettings.DEFAULT,
                "default", List.of(), ledger.snapshot(), null);
        var json = BackendSnapshot.capture(1, display, null, JadeSettings.defaults(), 0, List.of(), Map.of(),
                "", "", Map.of(), Map.of()).toJson().path("usage");
        assertEquals(20, json.path("inputTokens").asLong());
        assertEquals(1, json.path("missingRequests").asLong());
        assertEquals(20, json.path("byActor").path("leader").path("inputTokens").asLong());
        assertEquals(1, json.path("byActor").path("agent_1").path("missingRequests").asLong());
        assertEquals(1, json.path("byPurpose").path("SUBAGENT").path("missingRequests").asLong());
        assertEquals(1, json.path("byModel").path("fixture").path("reportedRequests").asLong());
    }
}
