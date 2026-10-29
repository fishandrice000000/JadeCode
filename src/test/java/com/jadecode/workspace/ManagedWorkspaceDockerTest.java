package com.jadecode.workspace;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.config.JadePaths;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.safety.*;
import com.jadecode.safety.approval.*;
import com.jadecode.sandbox.*;
import com.jadecode.tools.*;
import com.jadecode.util.*;

/** 使用真实 Docker 的完整输入→环境→成果路径；显式 opt-in，无真实模型或远端仓库访问。 */
class ManagedWorkspaceDockerTest {
    @TempDir Path root;

    @Test void snapshotEnvironmentAndImmutableArtifactWorkAcrossContainerRecreation() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("jade.sandbox.integration"));
        var store=new ManagedWorkspaceStore(new JadePaths(root.resolve("data"))); var workspace=store.create();
        var permissions=PermissionState.defaults(); permissions.select(PermissionProfile.WORKSPACE);
        var context=ExecutionContext.create(ExecutionContext.Source.USER,request->ApprovalAnswer.ALLOW_ONCE);
        var gate=new PermissionGate(new PathResolver(workspace.path()),permissions,request->ApprovalAnswer.ALLOW_ONCE);
        gate.sandboxed(true); gate.managedWorkspace(true);
        try (var docker=new DockerRuntime(SandboxSettings.defaults(),root.resolve("cache"),System.err::println)) {
            workspace.bind(docker,permissions);
            var sandbox=new SandboxSession("leader",workspace.path(),root.resolve("data"),permissions,gate,new ApprovalCoordinator(null),docker);
            Tool bash=sandbox.wrap(new BashTool(workspace.path(),sandbox.shellRunner()));
            Map<String,Tool> tools=new HashMap<>(); WorkspaceTools.forWorkspace(workspace,gate,permissions,bash).forEach(tool->tools.put(tool.name(),tool));
            Path source=Files.createDirectory(root.resolve("source")); Files.writeString(source.resolve("note.txt"),"before\n");
            Files.writeString(source.resolve("requirements.txt"),""); Files.write(source.resolve("binary"),new byte[]{0,1,2});
            Path zip=root.resolve("input.zip"); WorkspaceFiles.zip(source,zip,(file,name)->{});
            var begin=Json.mapper().createObjectNode().put("action","upload.begin").put("workspaceId",workspace.id())
                    .put("size",Files.size(zip)).put("sha256",WorkspaceFiles.sha256(zip));
            String resource=store.command(begin).path("id").asText();
            store.command(Json.mapper().createObjectNode().put("action","upload.chunk").put("workspaceId",workspace.id()).put("resourceId",resource)
                    .put("offset",0).put("data",Base64.getEncoder().encodeToString(Files.readAllBytes(zip))));
            store.command(Json.mapper().createObjectNode().put("action","upload.finish").put("workspaceId",workspace.id()).put("resourceId",resource));
            var input=Json.mapper().createObjectNode().put("destination","project"); input.putObject("source").put("kind","snapshot").put("resourceId",resource);
            assertFalse(call(tools.get("import_resource"),input,gate,context).isError());
            var prepared=call(tools.get("prepare_environment"),Json.mapper().createObjectNode().put("project","project").put("requirements","requirements.txt").put("network_access",false),gate,context);
            assertFalse(prepared.isError(),prepared.content()); assertTrue(workspace.status().path("environment").path("verified").asBoolean());
            docker.endActor("leader"); // 主动删除容器，验证 .venv 没有随容器消失。
            var execute=Json.mapper().createObjectNode().put("command","project/.venv/bin/python -c 'print(123)' && printf 'after\n' > project/note.txt");
            assertNull(gate.check("bash",execute,context)); var executed=bash.executeOutcome(execute,context); assertFalse(executed.isError(),executed.content());
            assertFalse(workspace.status().path("environment").path("verified").asBoolean(),"arbitrary shell invalidates the previous verification claim");
            Files.writeString(workspace.path().resolve("project/new.txt"),"new\n"); Files.delete(workspace.path().resolve("project/binary"));
            var publication=Json.mapper().createObjectNode().put("summary","fixture changes"); publication.putArray("paths").add("project");
            var outcome=call(tools.get("publish_result"),publication,gate,context); assertFalse(outcome.isError(),outcome.content());
            var manifest=Json.mapper().readTree(outcome.content()); String id=manifest.path("artifactId").asText();
            assertTrue(manifest.path("deleted").toString().contains("project/binary"));
            assertFalse(manifest.path("files").toString().contains(".venv")); assertTrue(manifest.path("executions").size()>=2);
            String digest=WorkspaceFiles.sha256(workspace.artifacts().bundle(id));
            Files.writeString(workspace.path().resolve("project/note.txt"),"later\n");
            assertEquals(digest,WorkspaceFiles.sha256(workspace.artifacts().bundle(id)));
            Path extracted=WorkspaceFiles.directory(root.resolve("artifact")); WorkspaceFiles.unzip(workspace.artifacts().bundle(id),extracted,()->false);
            assertEquals("after\n",Files.readString(extracted.resolve("files/project/note.txt")));
            assertTrue(Files.size(extracted.resolve("changes.patch"))>0);
            assertEquals("project",workspace.status().path("inputs").get(0).path("destination").asText());
            // 新权限必须同时约束基线与当前文件，不能通过 publish_result 绕过 read_file deny。
            var deny=new com.jadecode.safety.rules.PermissionRule("fixture.deny",com.jadecode.safety.rules.PermissionRule.Effect.DENY,
                    com.jadecode.safety.rules.PermissionRule.Kind.PATH,"read_file",workspace.path().toString(),"READ",
                    workspace.path().resolve("project/note.txt").toString(),false,List.of());
            var restricted=new PermissionState(PermissionProfile.WORKSPACE,List.of(deny),Set.of(),null);
            var restrictedGate=new PermissionGate(new PathResolver(workspace.path()),restricted,request->ApprovalAnswer.ALLOW_ONCE);
            Tool publisher=WorkspaceTools.forWorkspace(workspace,restrictedGate,restricted,bash).stream().filter(tool->tool.name().equals("publish_result")).findFirst().orElseThrow();
            assertNull(restrictedGate.check("publish_result",publication,context));
            assertTrue(publisher.executeOutcome(publication,context).isError()); assertEquals(1,workspace.artifacts().list().path("artifacts").size());
        }
        assertTrue(Files.exists(workspace.artifacts().bundle(workspace.artifacts().list().path("artifacts").get(0).path("artifactId").asText())));
    }
    private ToolOutcome call(Tool tool,ObjectNode input,PermissionGate gate,ExecutionContext context) {
        assertNull(gate.check(tool.name(),input,context)); return tool.executeOutcome(input,context);
    }
}
