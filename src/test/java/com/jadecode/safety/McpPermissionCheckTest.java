package com.jadecode.safety;

import com.jadecode.execution.ToolIdentity;
import com.jadecode.util.Json;
import com.jadecode.util.PathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 权限依据宿主身份，模型自填只读字段或服务名字都不能替代授权。 */
class McpPermissionCheckTest {
    @TempDir Path workspace;

    /** 已登记公网读取可用，未知服务/危险 URL/超大分页仍被拒绝；没有人工通道时不假批准。 */
    @Test void trustedIdentityControlsReadPermission() {
        var state = new PermissionState(PermissionProfile.READ_ONLY, List.of(), Set.of(), null);
        var gate = new PermissionGate(new PathResolver(workspace), state, (com.jadecode.safety.approval.Approval) null);
        var input = Json.mapper().createObjectNode().put("url", "https://example.com").put("readOnly", true);
        var known = new ToolIdentity(ToolIdentity.Kind.MCP, "fetch", "fetch", true);
        var unknown = new ToolIdentity(ToolIdentity.Kind.MCP, "fetch", "fetch", false);
        assertNull(gate.check("mcp__fetch__fetch", input, null, known));
        assertNotNull(gate.check("mcp__fetch__fetch", input, null, unknown));
        assertNotNull(gate.check("mcp__fetch__fetch", input.deepCopy().put("url", "file:///etc/passwd"), null, known));
        assertNotNull(gate.check("mcp__fetch__fetch", input.deepCopy().put("max_length", 10001), null, known));
    }
}
