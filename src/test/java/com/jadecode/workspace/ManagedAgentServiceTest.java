package com.jadecode.workspace;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.backend.RuntimeFactory;
import com.jadecode.config.*;
import com.jadecode.llm.*;
import com.jadecode.messages.*;
import com.jadecode.safety.approval.ApprovalAnswer;
import com.jadecode.service.AgentService;
import com.jadecode.util.Json;

/** 注入离线模型，保留真实服务/工厂/模型循环/Gate/Docker，验证新增工具不是只存在于目录。 */
class ManagedAgentServiceTest {
    @TempDir Path root;
    @Test void agentImportsPublishesThenUserClosesReopensAndDeletesTheWorkspace() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("jade.sandbox.integration"));
        var paths=new JadePaths(root.resolve("data")); paths.directory(paths.config(),true);
        Files.writeString(paths.config().resolve("config.json"),"{\"memory\":{\"enabled\":false},\"modelMetadata\":{\"autoDiscover\":false}}");
        var settings=new JadeConfigFile(paths.config().resolve("config.json")).load();
        var resource=new AtomicReference<String>(); var calls=new AtomicInteger();
        LlmClient model=request->{
            ToolExchangeProtocol.validateHistory(request.messages());
            for (Message message:request.messages()) for (ContentBlock block:message.content())
                if (block instanceof ToolResultBlock result) assertFalse(result.isError(),result.content());
            int step=calls.incrementAndGet();
            if (step==1) assertTrue(request.tools().stream().anyMatch(tool->tool.name().equals("import_resource")));
            String name; ObjectNode input=Json.mapper().createObjectNode();
            switch(step) {
                case 1 -> name="workspace_status";
                case 2 -> { name="import_resource"; input.put("destination","project").putObject("source").put("kind","snapshot").put("resourceId",resource.get()); }
                case 3 -> { name="bash"; input.put("command","printf fixture > project/from-agent.txt"); }
                case 4 -> { name="publish_result"; input.put("summary","fixture delivery").putArray("paths").add("project"); }
                default -> { return new ModelResponse(Message.assistant(List.of(new TextBlock("fixture completed"))),new ModelResponse.Usage(1,1),"end_turn"); }
            }
            return new ModelResponse(Message.assistant(List.of(new ToolUseBlock("managed-fixture-"+step,name,input))),new ModelResponse.Usage(1,1),"tool_use");
        };
        try (var service=new AgentService(paths,"fixture-managed",(workspace,permissions,configuration)->
                new RuntimeFactory(settings,permissions,new AppConfig("fixture","http://127.0.0.1:9","fixture",128),model,paths))) {
            String id=service.workspaceCommand(Json.mapper().createObjectNode().put("action","create")).path("workspaceId").asText();
            Path source=Files.createDirectory(root.resolve("source")); Files.writeString(source.resolve("README.md"),"fixture input\n");
            Path zip=root.resolve("snapshot.zip"); WorkspaceFiles.zip(source,zip,(file,name)->{});
            var base=Json.mapper().createObjectNode().put("workspaceId",id);
            resource.set(service.workspaceCommand(base.deepCopy().put("action","upload.begin").put("size",Files.size(zip)).put("sha256",WorkspaceFiles.sha256(zip))).path("id").asText());
            service.workspaceCommand(base.deepCopy().put("action","upload.chunk").put("resourceId",resource.get()).put("offset",0).put("data",Base64.getEncoder().encodeToString(Files.readAllBytes(zip))));
            service.workspaceCommand(base.deepCopy().put("action","upload.finish").put("resourceId",resource.get()));
            var group=service.open(base); assertEquals(id,group.workspaceId);
            assertThrows(java.io.IOException.class,()->service.workspaceCommand(base.deepCopy().put("action","delete")));
            group.backend.runtime().start(false);
            assertEquals("fixture completed",group.backend.runtime().submitUser("fixture task",request->ApprovalAnswer.ALLOW_ONCE).get(45,TimeUnit.SECONDS));
            assertEquals(5,calls.get()); assertEquals("fixture",Files.readString(group.workspace.resolve("project/from-agent.txt")));
            assertEquals(1,service.workspaceCommand(base.deepCopy().put("action","artifacts")).path("artifacts").size());
            service.closeSession(group.id); assertTrue(Files.exists(group.workspace.resolve("project/from-agent.txt")));
            var again=service.open(base); assertEquals(group.workspace,again.workspace); service.closeSession(again.id);
            service.workspaceCommand(base.deepCopy().put("action","delete")); assertFalse(Files.exists(group.workspace));
        }
    }
}
