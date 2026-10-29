package com.jadecode.safety.checks;

import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.PathResolver;
import com.jadecode.workspace.*;

/** 新工作区工具的静态权限声明，网络来源和工作区路径都在审批快照中，不执行 IO。 */
public final class WorkspacePermissionCheck implements ToolPermissionCheck {
    private final PathResolver resolver;
    public WorkspacePermissionCheck(PathResolver resolver) { this.resolver=resolver; }
    @Override public Analysis analyze(String tool,ObjectNode input) {
        var resources=new ArrayList<Resource>(); boolean write=false, network=false;
        try {
            switch(tool) {
                case "import_resource" -> {
                    var relative=WorkspaceFiles.relative(input.path("destination").asText());
                    if (WorkspaceFiles.excluded(relative)) throw new IllegalArgumentException("Protected import target");
                    resources.add(new Resource(resolver.resolve(relative),"WRITE")); write=true;
                    if (input.path("source").path("kind").asText().equals("git")) {
                        GitWorker.validate(input.path("source").path("url").asText(),input.path("source").path("revision").asText()); network=true;
                    }
                }
                case "publish_result" -> {
                    if (!input.path("paths").isArray() || input.path("paths").isEmpty()) throw new IllegalArgumentException("Missing artifact paths");
                    for (var path:input.path("paths")) resources.add(new Resource(resolver.resolve(WorkspaceFiles.relative(path.asText())),"READ"));
                }
                case "prepare_environment" -> {
                    if (input.has("network_access") && !input.path("network_access").isBoolean()) throw new IllegalArgumentException("network_access must be boolean");
                    resources.add(new Resource(resolver.resolve(WorkspaceFiles.relative(input.path("project").asText())),"WRITE")); write=true;
                }
                case "workspace_status" -> { }
                default -> throw new IllegalArgumentException("Unknown workspace tool");
            }
            if (resources.stream().anyMatch(r->!r.path().startsWith(resolver.workspaceReal()))) throw new IllegalArgumentException("Path escapes workspace");
            return new Analysis(tool,resolver.workspaceReal(),resources,List.of(),write,!write,network,"",network?"managed.network":"");
        } catch (Exception invalid) {
            return new Analysis(tool,resolver.workspaceReal(),List.of(),List.of(),false,false,false,"Invalid managed operation: "+invalid.getMessage());
        }
    }
}
