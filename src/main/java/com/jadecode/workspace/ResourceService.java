package com.jadecode.workspace;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import com.fasterxml.jackson.databind.node.*;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.util.Json;

/**
 * 将经授权的来源发布到不存在的新目录。暂存和输入基线在工作区外，失败不覆盖已有项目。
 * 来源记录先落盘再发布，崩溃时保留中断证据，不凭目录存在就重放获取。
 */
public final class ResourceService {
    private final ManagedWorkspace workspace;
    ResourceService(ManagedWorkspace workspace) throws IOException {
        this.workspace=workspace; WorkspaceFiles.directory(workspace.metadata().resolve("inputs"));
    }

    /** 模型只看该工作区 READY 输入；未完成记录仍在私有目录供诊断。 */
    public ArrayNode inputs() throws IOException {
        var result=Json.mapper().createArrayNode();
        for (Path file:workspace.records("inputs")) {
            var record=ManagedWorkspaceStore.read(file);
            if (record.path("state").asText().equals("READY")) result.add(record);
        }
        var sorted=Json.mapper().createArrayNode();
        var records=new ArrayList<com.fasterxml.jackson.databind.JsonNode>(); result.forEach(records::add);
        records.sort(Comparator.comparing(node->node.path("created").asText())); records.forEach(sorted::add); return sorted;
    }

    /**
     * source 为 git 或已经上传到本工作区的 snapshot；check 核验每个将发布的普通文件。
     * 只在取消/权限仍有效且目标仍不存在时原子改名，不执行仓库安装脚本。
     */
    public ObjectNode importResource(ObjectNode input,ExecutionContext context,BooleanSupplier invalid,WorkspaceFiles.Check check) throws Exception {
        try (var lease=workspace.exclusive()) {
            if (inputs().size()>=64) throw new IOException("Imported input limit reached (64)");
            Path record=workspace.operation("import",context); String id=record.getFileName().toString().replace(".json","");
            Path scratch=workspace.scratch(id);
            try {
                var operation=ManagedWorkspaceStore.read(record); operation.set("input",input.deepCopy()); ManagedWorkspaceStore.write(record,operation);
                if (input.hasNonNull("retryOperationId")) {
                    String previousId=input.path("retryOperationId").asText(); ManagedWorkspaceStore.id(previousId);
                    var previous=ManagedWorkspaceStore.read(workspace.metadata().resolve("operations").resolve(previousId+".json"));
                    var previousInput=previous.path("input");
                    if (!previous.path("kind").asText().equals("import") || previous.path("state").asText().equals("READY")
                            || !previousInput.path("destination").equals(input.path("destination"))) throw new IOException("Retry does not match a failed import");
                    if (input.path("source").path("kind").asText().equals("git")) {
                        String pinned=previous.path("resolvedCommit").asText(); if (pinned.isEmpty()) pinned=workspace.pinnedCommit(previousId);
                        if (pinned.isEmpty() || !input.path("source").path("url").equals(previousInput.path("source").path("url"))
                                || !input.path("source").path("revision").asText().equals(pinned))
                            throw new IOException("Retry must explicitly name the previously resolved commit");
                    } else if (!input.path("source").equals(previousInput.path("source"))) throw new IOException("Retry snapshot changed");
                }
                String destination=input.path("destination").asText(); Path target=WorkspaceFiles.resolve(workspace.path(),destination);
                if (target.equals(workspace.path()) || WorkspaceFiles.excluded(WorkspaceFiles.relative(destination)) || Files.exists(target,LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Import requires a new, unprotected directory");
                var source=input.path("source"); String kind=source.path("kind").asText(); Path prepared=scratch.resolve("repository");
                var provenance=Json.mapper().createObjectNode().put("id",id).put("destination",destination).put("state","PREPARING")
                        .put("created",java.time.Instant.now().toString());
                if (kind.equals("git")) {
                    String url=source.path("url").asText(), revision=source.path("revision").asText(); GitWorker.validate(url,revision);
                    if (invalid.getAsBoolean()) throw new IOException("Import was cancelled or authorization changed");
                    var args=Json.mapper().createObjectNode().put("operation","fetch").put("url",url).put("revision",revision);
                    String commit=workspace.run("managed.git",scratch,args,true,context,invalid).content().strip();
                    if (!commit.matches("[a-f0-9]{40,64}")) throw new IOException("Git did not return a pinned commit");
                    provenance.put("kind","git").put("url",url).put("requestedRevision",revision).put("commit",commit);
                    operation.put("resolvedCommit",commit); ManagedWorkspaceStore.write(record,operation);
                } else if (kind.equals("snapshot")) {
                    String resource=source.path("resourceId").asText(); ManagedWorkspaceStore.id(resource);
                    Path uploads=workspace.metadata().resolve("uploads"); var uploaded=ManagedWorkspaceStore.read(uploads.resolve(resource+".json"));
                    Path archive=uploads.resolve(resource+".part");
                    if (!uploaded.path("state").asText().equals("READY") || !WorkspaceFiles.sha256(archive).equals(uploaded.path("sha256").asText()))
                        throw new IOException("Snapshot is not ready or its bytes changed");
                    WorkspaceFiles.directory(prepared); WorkspaceFiles.unzip(archive,prepared,invalid);
                    provenance.put("kind","snapshot").put("resourceId",resource).put("sha256",uploaded.path("sha256").asText());
                } else throw new IOException("Unsupported resource source");
                // 读取前检查权限；保存原始普通文件基线，后续成果可包含未跟踪文件和删除。
                WorkspaceFiles.Check before=(path,name)-> {
                    if (invalid.getAsBoolean()) throw new IOException("Import cancelled or authorization changed");
                    check.accept(path,destination+"/"+name);
                };
                Path baseline=workspace.metadata().resolve("inputs").resolve(id+".zip");
                WorkspaceFiles.zip(prepared,baseline,before); provenance.put("baselineSha256",WorkspaceFiles.sha256(baseline));
                Path saved=workspace.metadata().resolve("inputs").resolve(id+".json"); ManagedWorkspaceStore.write(saved,provenance);
                if (invalid.getAsBoolean() || Files.exists(target,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Import target or authorization changed before publication");
                Files.createDirectories(target.getParent()); Files.move(prepared,target,StandardCopyOption.ATOMIC_MOVE);
                provenance.put("state","READY"); ManagedWorkspaceStore.write(saved,provenance); workspace.finish(record,"READY",id);
                return provenance;
            } catch (Exception failure) {
                String pinned=workspace.pinnedCommit(id);
                if (!pinned.isEmpty()) { var operation=ManagedWorkspaceStore.read(record).put("resolvedCommit",pinned); ManagedWorkspaceStore.write(record,operation); }
                workspace.finish(record,context.isCancelled()?"CANCELLED":"FAILED",failure.getMessage()==null?"Import failed":failure.getMessage());
                throw new IOException("Import operation "+id+": "+failure.getMessage(),failure);
            } finally { WorkspaceFiles.delete(scratch); }
        }
    }
}
