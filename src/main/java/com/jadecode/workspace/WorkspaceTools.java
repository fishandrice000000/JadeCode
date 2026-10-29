package com.jadecode.workspace;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.safety.*;
import com.jadecode.tools.*;
import com.jadecode.util.Json;

/**
 * 独立工作区的模型工具适配器。外层 AgentExecutor 已调用 Gate；本层消费确切许可，
 * 实际文件逐个复核，环境准备再走原受限 bash，不能由新工具绕过联网和路径审批。
 */
public final class WorkspaceTools {
    private WorkspaceTools() { }
    /** 为当前 actor 绑定自己的 Gate/bash，共享的只有工作区数据与执行屏障。 */
    public static List<Tool> forWorkspace(ManagedWorkspace workspace,PermissionGate gate,PermissionState permissions,Tool bash) {
        return List.of(tool("workspace_status","List this managed workspace's snapshot IDs, imported sources, operations and environment state.","{}",workspace,gate,permissions,bash),
                tool("import_resource","Import a Git HTTPS repository at a commit/ref or an uploaded snapshot into a NEW relative directory. Does not install or execute project code.",
                        "\"source\":{\"type\":\"object\",\"properties\":{\"kind\":{\"enum\":[\"git\",\"snapshot\"]},\"url\":{\"type\":\"string\"},\"revision\":{\"type\":\"string\"},\"resourceId\":{\"type\":\"string\"}},\"required\":[\"kind\"],\"additionalProperties\":false},\"destination\":{\"type\":\"string\"},\"retryOperationId\":{\"type\":\"string\",\"description\":\"For a failed import, reuse its source/destination; Git revision must be its resolvedCommit from workspace_status.\"}",workspace,gate,permissions,bash),
                tool("publish_result","Publish immutable selected files, binary patch and actual execution records; keep this conversation open. Patch applies with git apply -p2. Returns artifactId for user download.",
                        "\"paths\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"summary\":{\"type\":\"string\"}",workspace,gate,permissions,bash),
                tool("prepare_environment","Create/update a project's .venv, install a local requirements file, then run pip check/list. Installation is serialized with other workspace commands. Network requires existing bash approval. Failure can leave partial packages.",
                        "\"project\":{\"type\":\"string\"},\"requirements\":{\"type\":\"string\"},\"network_access\":{\"type\":\"boolean\"}",workspace,gate,permissions,bash));
    }

    /** 一个工具一次消费许可；预期失败作为 is_error 返回，让模型决定修复、等待或请求用户。 */
    private static Tool tool(String name,String description,String properties,ManagedWorkspace workspace,PermissionGate gate,PermissionState permissions,Tool bash) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return description; }
            public Map<String,String> properties() { return Map.of(); }
            public List<String> required() { return switch(name) { case "import_resource" -> List.of("source","destination"); case "publish_result" -> List.of("paths","summary"); case "prepare_environment" -> List.of("project","requirements"); default -> List.of(); }; }
            public ObjectNode inputSchema() {
                try {
                    var schema=Json.mapper().createObjectNode().put("type","object").put("additionalProperties",false);
                    schema.set("properties",Json.mapper().readTree(properties.equals("{}")?properties:"{"+properties+"}"));
                    schema.set("required",Json.mapper().valueToTree(required())); return schema;
                } catch (IOException invalid) { throw new IllegalStateException("Invalid managed tool schema",invalid); }
            }
            public String execute(ObjectNode input) { return "Error: Managed tools require an execution context"; }
            @Override public ToolOutcome executeOutcome(ObjectNode input,ExecutionContext context) {
                try {
                    var checked=gate.consume(name,input,context); long revision=checked.revision();
                    java.util.function.BooleanSupplier invalid=()->context.isCancelled() || permissions.snapshot().revision()!=revision;
                    WorkspaceFiles.Check reads=(path,relative)->authorize(gate,"read_file",workspace.path().resolve(relative),context);
                    ObjectNode result=switch(name) {
                        case "workspace_status" -> workspace.status();
                        case "import_resource" -> workspace.resources().importResource(checked.input(),context,invalid,
                                (path,relative)->authorize(gate,"write_file",workspace.path().resolve(relative),context));
                        case "publish_result" -> workspace.artifacts().publish(checked.input(),context,invalid,reads);
                        case "prepare_environment" -> prepare(workspace,gate,bash,checked.input(),context,invalid);
                        default -> throw new IOException("Unsupported managed tool");
                    };
                    return new ToolOutcome(result.toString(),false);
                } catch (Exception failure) { return new ToolOutcome("Error: "+(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage()),true); }
            }
        };
    }

    /** 文件权限按其最终工作区路径判断，包括成果中已经删除但仍在原始基线中的文件。 */
    private static void authorize(PermissionGate gate,String tool,Path target,ExecutionContext context) throws IOException {
        var input=Json.mapper().createObjectNode().put("path",target.toString());
        if (tool.equals("write_file")) input.put("content",""); // 仅解释写入路径，不执行空写入。
        String denied=gate.check(tool,input,context);
        if (denied!=null) throw new IOException(denied); gate.consume(tool,input,context);
    }

    /**
     * 使用明确 .venv 解释器，不依赖跨工具调用保存 activate。要求本地普通 requirements 文件，
     * 不重写锁文件；联网、嵌套 requirements 和构建代码仍受原 bash 的容器视图限制。
     */
    private static ObjectNode prepare(ManagedWorkspace workspace,PermissionGate gate,Tool bash,ObjectNode input,ExecutionContext context,
            java.util.function.BooleanSupplier invalid) throws Exception {
        try (var lease=workspace.exclusive()) {
            Path project=WorkspaceFiles.resolve(workspace.path(),input.path("project").asText());
            Path requirements=WorkspaceFiles.resolve(project,input.path("requirements").asText());
            if (!Files.isDirectory(project) || !Files.isRegularFile(requirements,LinkOption.NOFOLLOW_LINKS)
                    || WorkspaceFiles.excluded(project.relativize(requirements))) throw new IOException("Expected a local project requirements file");
            authorize(gate,"read_file",requirements,context);
            if (invalid.getAsBoolean()) throw new IOException("Environment preparation cancelled");
            Path environment=WorkspaceFiles.resolve(project,".venv");
            String image=workspace.imageIdentity(); var previous=workspace.status().path("environment");
            if (Files.exists(environment,LinkOption.NOFOLLOW_LINKS) && previous.path("project").asText().equals(workspace.path().relativize(project).toString())
                    && previous.hasNonNull("image") && !previous.path("image").asText().equals(image))
                throw new IOException("Environment image changed; recreate this project's .venv before installing dependencies");
            var record=Json.mapper().createObjectNode().put("project",workspace.path().relativize(project).toString())
                    .put("requirements",input.path("requirements").asText()).put("requirementsSha256",WorkspaceFiles.sha256(requirements))
                    .put("state","INSTALLING").put("verified",false).put("updated",Instant.now().toString());
            Path operation=workspace.operation("environment",context);
            workspace.environment(record);
            try {
                if (invalid.getAsBoolean()) throw new IOException("Environment preparation cancelled");
                String command="cd "+quote(project.toString())+" && python3 -m venv .venv && .venv/bin/python --version && .venv/bin/python -m pip --disable-pip-version-check install -r "+quote(requirements.toString())
                        +" && .venv/bin/python -m pip --disable-pip-version-check check && .venv/bin/python -m pip --disable-pip-version-check list --format=json";
                var shell=Json.mapper().createObjectNode().put("command",command).put("network_access",input.path("network_access").asBoolean());
                String denied=gate.check("bash",shell,context); if (denied!=null) throw new IOException(denied);
                ToolOutcome result=bash.executeOutcome(shell,context);
                if (result.isError() || invalid.getAsBoolean()) throw new IOException(result.content());
                if (!WorkspaceFiles.sha256(requirements).equals(record.path("requirementsSha256").asText())) throw new IOException("Requirements changed during installation");
                record.put("state","READY").put("verified",true).put("result",result.content());
                workspace.environment(record); workspace.finish(operation,"READY","Python environment checked"); return record;
            } catch (Exception failure) {
                record.put("state",context.isCancelled()?"CANCELLED":"FAILED").put("verified",false);
                workspace.environment(record); workspace.finish(operation,record.path("state").asText(),"Environment may contain partial packages"); throw failure;
            }
        }
    }
    private static String quote(String value) { return "'"+value.replace("'","'\"'\"'")+"'"; }
}
