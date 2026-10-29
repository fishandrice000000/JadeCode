package com.jadecode.workspace;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.safety.*;
import com.jadecode.safety.approval.*;
import com.jadecode.safety.rules.PermissionRule;
import com.jadecode.util.*;

/** 新工具必须声明权限并继续使用原审批/版本许可，不能成为绕过原 Gate 的旁路。 */
class WorkspacePermissionTest {
    @TempDir Path root;
    private ObjectNode git() {
        var input=Json.mapper().createObjectNode().put("destination","project");
        input.putObject("source").put("kind","git").put("url","https://example.test/repo.git").put("revision","main"); return input;
    }
    @Test void networkImportAsksWithExactSourceAndHasNoMisleadingPathOnlyPersistentGrant() {
        var seen=new AtomicReference<ApprovalRequest>(); var state=PermissionState.defaults();
        var context=ExecutionContext.create(ExecutionContext.Source.USER,request->{seen.set(request);return ApprovalAnswer.ALLOW_ONCE;});
        var gate=new PermissionGate(new PathResolver(root),state,(Approval)null); gate.managedWorkspace(true);
        var input=git(); assertNull(gate.check("import_resource",input,context));
        assertEquals(input,seen.get().input()); assertNull(seen.get().suggestion());
        assertTrue(gate.consume("import_resource",input,context).explicitlyApproved());
        assertThrows(IllegalStateException.class,()->gate.consume("import_resource",input,context));
    }
    @Test void traversalCredentialsAndNetworkWithoutHumanChannelNeverReceivePermit() {
        var count=new AtomicInteger(); var context=ExecutionContext.create(ExecutionContext.Source.USER,request->{count.incrementAndGet();return ApprovalAnswer.ALLOW_ONCE;});
        var gate=new PermissionGate(new PathResolver(root),PermissionState.defaults(),(Approval)null);
        assertNotNull(gate.check("import_resource",git().put("destination","../outside"),context));
        var credential=git(); ((ObjectNode)credential.get("source")).put("url","https://fixture@example.test/repo.git");
        assertNotNull(gate.check("import_resource",credential,context)); assertEquals(0,count.get());
        assertNotNull(gate.check("import_resource",git(),ExecutionContext.create(ExecutionContext.Source.USER,null)));
    }
    @Test void fullAccessCannotBypassManagedCoordinationButOrdinarySessionsKeepTheirBehavior() {
        var state=PermissionState.defaults(); state.select(PermissionProfile.FULL_ACCESS);
        var gate=new PermissionGate(new PathResolver(root),state,(Approval)null); gate.managedWorkspace(true);
        assertNotNull(gate.check("bash",Json.mapper().createObjectNode().put("command","echo fixture")));
        gate.managedWorkspace(false);
        assertNull(gate.check("bash",Json.mapper().createObjectNode().put("command","echo fixture")));
    }
    @Test void explicitDenyOverridesManagedFileDeclarationAndChangedParametersInvalidatePermit() {
        var rule=new PermissionRule("fixture.deny",PermissionRule.Effect.DENY,PermissionRule.Kind.PATH,"publish_result",root.toString(),"READ",root.resolve("secret").toString(),true,List.of());
        var state=new PermissionState(PermissionProfile.WORKSPACE,List.of(rule),Set.of(),null);
        var gate=new PermissionGate(new PathResolver(root),state,(Approval)null);
        var publication=Json.mapper().createObjectNode().put("summary","fixture"); publication.putArray("paths").add("secret");
        assertNotNull(gate.check("publish_result",publication));
        var input=Json.mapper().createObjectNode(); var context=ExecutionContext.create(ExecutionContext.Source.USER,null);
        assertNull(gate.check("workspace_status",input,context)); input.put("changed",true);
        assertThrows(IllegalStateException.class,()->gate.consume("workspace_status",input,context));
    }
}
