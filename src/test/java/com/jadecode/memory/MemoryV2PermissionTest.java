package com.jadecode.memory;

import com.jadecode.safety.*;
import com.jadecode.safety.rules.PermissionRule;
import com.jadecode.util.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 验证记忆例外是窄只读授权，不能越过用户显式规则或覆盖正式库。 */
class MemoryV2PermissionTest {
    @TempDir Path temporary;

    @Test void explicitDenyStillWinsAndFullAccessDoesNotEnableDirectMemoryWrites() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("project")), root = temporary.resolve("memory");
        var store = new MemoryV2Store(root, workspace, ignored -> { }); store.initialize();
        String path = root.resolve("MEMORY.md").toString();
        var deny = new PermissionRule("deny.memory", PermissionRule.Effect.DENY, PermissionRule.Kind.TOOL,
                "read_file", "", "", "", false, List.of());
        var state = new PermissionState(PermissionProfile.FULL_ACCESS, List.of(deny), Set.of(), null);
        var gate = new PermissionGate(new PathResolver(workspace), state, (com.jadecode.safety.approval.Approval) null);
        gate.memoryAccess(root, store::readable);
        assertTrue(gate.check("read_file", Json.mapper().createObjectNode().put("path", path)).contains("deny.memory"));
        assertTrue(gate.check("write_file", Json.mapper().createObjectNode().put("path", path).put("content", "bad")).contains("read-only"));
        assertTrue(gate.check("bash", Json.mapper().createObjectNode().put("command", "rm -rf " + root)).contains("read-only"));
        assertFalse(store.readable(root.resolve("state/maintenance.json")));
    }
}
