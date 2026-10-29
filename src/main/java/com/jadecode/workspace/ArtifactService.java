package com.jadecode.workspace;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.util.Json;

/**
 * 从受管工作区制作不可变成果。复制期间独占 Harness 执行屏障，文件清单来自真实快照，
 * Agent 的说明与实际命令结果分开保存；发布不关闭会话，也不推送任何远端仓库。
 */
public final class ArtifactService {
    private final ManagedWorkspace workspace;
    ArtifactService(ManagedWorkspace workspace) { this.workspace=workspace; }

    /** 列表只包含完整发布的版本，不列暂存目录。 */
    public ObjectNode list() throws IOException {
        var result=Json.mapper().createObjectNode(); var rows=result.putArray("artifacts");
        for (Path file:workspace.records("artifacts")) {
            var manifest=ManagedWorkspaceStore.read(file); var row=Json.mapper().createObjectNode();
            for (String field:List.of("artifactId","workspaceId","created","bundleSize","bundleSha256")) row.set(field,manifest.get(field));
            String summary=manifest.path("agentSummary").asText(); row.put("summary",summary.substring(0,Math.min(512,summary.length())))
                    .put("summaryTruncated",summary.length()>512).put("fileCount",manifest.path("files").size()).put("changedCount",manifest.path("changed").size());
            rows.add(row);
        }
        return result;
    }
    public ObjectNode manifest(String id) throws IOException {
        ManagedWorkspaceStore.id(id); return ManagedWorkspaceStore.read(workspace.metadata().resolve("artifacts").resolve(id+".json"));
    }
    /** 根据服务登记的 ID 取得 ZIP，调用者不能提交服务器任意文件路径。 */
    public Path bundle(String id) throws IOException {
        manifest(id); Path file=workspace.metadata().resolve("artifacts").resolve(id).resolve("bundle.zip");
        if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Artifact bundle is unavailable"); return file;
    }

    /**
     * paths 选中工作区相对路径，summary 为模型陈述；check 核验当前文件及基线中的删除文件。
     * 等待屏障可中断；成功只在 ZIP 与摘要全部落盘后登记，之后修改工作区不改变该版本。
     */
    public ObjectNode publish(ObjectNode input,ExecutionContext context,BooleanSupplier invalid,WorkspaceFiles.Check check) throws Exception {
        List<String> selected=new ArrayList<>();
        if (!input.path("paths").isArray() || input.path("paths").isEmpty() || input.path("paths").size()>64)
            throw new IOException("Publish requires 1..64 relative paths");
        for (var item:input.path("paths")) {
            if (!item.isTextual()) throw new IOException("Artifact paths must be strings");
            String normalized=WorkspaceFiles.relative(item.asText()).toString(); selected.add(normalized.isEmpty()?".":normalized);
        }
        if (input.path("summary").asText().length()>8192) throw new IOException("Artifact summary limit exceeded");
        try (var lease=workspace.exclusive()) {
            if (workspace.records("artifacts").size()>=64) throw new IOException("Artifact version limit reached (64)");
            Path operation=workspace.operation("publish",context); String id=operation.getFileName().toString().replace(".json","");
            Path scratch=workspace.scratch(id);
            try {
                WorkspaceFiles.Check before=(path,name)-> {
                    if (invalid.getAsBoolean()) throw new IOException("Publication cancelled or authorization changed");
                    check.accept(path,name);
                };
                Path original=WorkspaceFiles.directory(scratch.resolve("original")), result=WorkspaceFiles.directory(scratch.resolve("result"));
                var inputs=workspace.resources().inputs();
                for (var source:inputs) {
                    Path baseline=workspace.metadata().resolve("inputs").resolve(source.path("id").asText()+".zip");
                    if (!WorkspaceFiles.sha256(baseline).equals(source.path("baselineSha256").asText())) throw new IOException("Input baseline digest mismatch");
                    Path destination=WorkspaceFiles.resolve(original,source.path("destination").asText());
                    // 后来的导入可以替换已删除的旧输入目录；基线按来源时间采用最新版本。
                    if (Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) WorkspaceFiles.delete(destination);
                    WorkspaceFiles.directory(destination);
                    String prefix=source.path("destination").asText()+"/";
                    WorkspaceFiles.unzip(baseline,destination,invalid,name->selected(prefix+name,selected),
                            (file,name)->before.accept(file,prefix+name));
                }
                // 只比较用户选中的范围，未选中的仓库文件不能被误记为删除。
                var allOriginal=WorkspaceFiles.inspect(original,List.of("."),(file,name)->{});
                for (String name:allOriginal.keySet()) if (!selected(name,selected)) Files.delete(original.resolve(name));
                var baseline=WorkspaceFiles.inspect(original,selected,before);
                var current=WorkspaceFiles.inspect(workspace.path(),selected,before);
                WorkspaceFiles.copy(workspace.path(),result,current.keySet(),before);
                if (!current.equals(WorkspaceFiles.inspect(result,List.of("."),(file,name)->{})))
                    throw new IOException("Workspace bytes changed during snapshot copy");
                // Git 只在隔离工作者中比较两份私有文件树，既有仓库钩子和配置不参与。
                workspace.run("managed.git",scratch,Json.mapper().createObjectNode().put("operation","patch"),false,context,invalid);
                var manifest=Json.mapper().createObjectNode().put("artifactId",id).put("workspaceId",workspace.id())
                        .put("created",Instant.now().toString()).put("agentSummary",input.path("summary").asText())
                        .put("patchStrip",2).put("patchBase","managed-workspace-root")
                        .put("validationMeaning","Command exit facts, not a certification that tests are sufficient");
                manifest.set("inputs",inputs); manifest.set("files",Json.mapper().valueToTree(current));
                var deleted=manifest.putArray("deleted"); var changed=manifest.putArray("changed");
                baseline.keySet().stream().filter(name->!current.containsKey(name)).forEach(deleted::add);
                current.forEach((name,info)-> { if (!info.equals(baseline.get(name))) changed.add(name); });
                var executions=manifest.putArray("executions"); var records=workspace.records("executions");
                for (Path file:records.subList(Math.max(0,records.size()-128),records.size())) executions.add(ManagedWorkspaceStore.read(file));
                Path payload=WorkspaceFiles.directory(scratch.resolve("payload")); Files.move(result,payload.resolve("files"));
                Files.move(scratch.resolve("changes.patch"),payload.resolve("changes.patch"));
                ManagedWorkspaceStore.write(payload.resolve("manifest.json"),manifest);
                Path artifacts=WorkspaceFiles.directory(workspace.metadata().resolve("artifacts"));
                Path ready=WorkspaceFiles.directory(artifacts.resolve(".pending-"+id));
                WorkspaceFiles.zip(payload,ready.resolve("bundle.zip"),(file,name)-> { if (invalid.getAsBoolean()) throw new IOException("Publication cancelled"); });
                manifest.put("bundleSize",Files.size(ready.resolve("bundle.zip"))).put("bundleSha256",WorkspaceFiles.sha256(ready.resolve("bundle.zip")));
                if (invalid.getAsBoolean()) throw new IOException("Authorization changed before artifact registration");
                Files.move(ready,artifacts.resolve(id),StandardCopyOption.ATOMIC_MOVE);
                ManagedWorkspaceStore.write(artifacts.resolve(id+".json"),manifest); workspace.finish(operation,"READY",id);
                return manifest;
            } catch (Exception failure) {
                workspace.finish(operation,context.isCancelled()?"CANCELLED":"FAILED",failure.getMessage()==null?"Publication failed":failure.getMessage()); throw failure;
            } finally {
                WorkspaceFiles.delete(scratch);
                WorkspaceFiles.delete(workspace.metadata().resolve("artifacts").resolve(".pending-"+id));
            }
        }
    }
    private static boolean selected(String name,List<String> roots) {
        for (String root:roots) if (root.isEmpty() || root.equals(".") || name.equals(root) || name.startsWith(root+"/")) return true;
        return false;
    }
}
