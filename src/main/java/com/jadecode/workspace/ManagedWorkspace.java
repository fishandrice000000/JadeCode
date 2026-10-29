package com.jadecode.workspace;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.safety.PermissionState;
import com.jadecode.sandbox.*;
import com.jadecode.util.Json;

/**
 * 一份持久独立工作区的运行协调器。会话、Team 和后台共用同一把屏障；普通文件操作可并行，
 * Shell 与导入/环境更新/快照复制独占，防止把半安装环境或混合版本文件当成就绪成果。
 * 屏障只协调 Harness 发起的操作；不暂停模型思考，不拦截同 UID 的外部编辑器。
 */
public final class ManagedWorkspace implements DockerRuntime.Operations {
    /** 只释放内存锁，不执行 IO；关闭不额外抛 checked exception。 */
    @FunctionalInterface public interface Lease extends AutoCloseable { @Override void close(); }
    private final String id;
    private final Path storage, metadata, path;
    private final ReentrantReadWriteLock barrier = new ReentrantReadWriteLock(true);
    private final ResourceService resources;
    private final ArtifactService artifacts;
    private DockerRuntime docker;
    private PermissionState permissions;
    private final ThreadLocal<Path> executionRecord = new ThreadLocal<>();

    ManagedWorkspace(String id,Path storage,Path metadata) throws IOException {
        this.id=id; this.storage=storage; this.metadata=metadata; path=storage.resolve("files");
        resources=new ResourceService(this); artifacts=new ArtifactService(this);
        WorkspaceFiles.directory(metadata.resolve("executions"));
        // 记录只恢复事实；旧进程中的 Future/审批/工具不会重建或自动运行。
        for (Path record : records("operations")) {
            var saved=ManagedWorkspaceStore.read(record);
            if (saved.path("state").asText().equals("PREPARING")) {
                saved.put("state","INTERRUPTED"); ManagedWorkspaceStore.write(record,saved);
            }
        }
        for (Path record : records("executions")) {
            var saved=ManagedWorkspaceStore.read(record);
            if (saved.path("state").asText().equals("RUNNING")) {
                saved.put("state","INTERRUPTED"); ManagedWorkspaceStore.write(record,saved);
            }
        }
    }
    public String id() { return id; }
    public Path path() { return path; }
    Path metadata() { return metadata; }
    ResourceService resources() { return resources; }
    ArtifactService artifacts() { return artifacts; }
    /** 仅由已授权的环境准备调用，可能检查本地 Docker，不下载或构建镜像。 */
    synchronized String imageIdentity() throws IOException {
        if (docker==null) throw new IOException("No managed Docker runtime is attached"); return docker.imageIdentity();
    }

    /** 只接受受限 Docker 执行器；关闭旧会话后可以重新绑定，不把容器生命期当成数据生命期。 */
    public synchronized void bind(DockerRuntime runtime,PermissionState state) throws IOException {
        if (docker!=null && !docker.cleanupConfirmed()) throw new IOException("Previous managed runtime has not stopped");
        docker=Objects.requireNonNull(runtime); permissions=Objects.requireNonNull(state); runtime.operations(this);
        Path environment=metadata.resolve("environment.json");
        if (Files.exists(environment)) {
            var saved=ManagedWorkspaceStore.read(environment).put("verified",false).put("state","UNVERIFIED");
            ManagedWorkspaceStore.write(environment,saved);
        }
    }

    /** 同线程可重入，供受控工具在独占期间调用 Docker；等待支持线程中断。 */
    public Lease exclusive() throws IOException { return lock(barrier.writeLock()); }
    private static Lease lock(Lock lock) throws IOException {
        try { lock.lockInterruptibly(); return lock::unlock; }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new IOException("Workspace operation interrupted",cancelled); }
    }

    /** 所有实际 Docker 操作都领取屏障，后台不能因工具已返回而提前释放。 */
    @Override public AutoCloseable enter(ExecutionPlan plan) throws IOException {
        AutoCloseable lease=lock(plan.tool().equals("bash") ? barrier.writeLock() : barrier.readLock());
        try {
            if (plan.tool().equals("bash")) {
                if (records("executions").size()>=4096) throw new IOException("Workspace execution record limit reached (4096)");
                Path record=metadata.resolve("executions").resolve(UUID.randomUUID()+".json");
                String command=plan.input().path("command").asText();
                var entry=Json.mapper().createObjectNode().put("id",record.getFileName().toString().replace(".json",""))
                        .put("runId",plan.runId()).put("actor",plan.actor()).put("started",Instant.now().toString())
                        .put("command",command.substring(0,Math.min(8192,command.length()))).put("commandTruncated",command.length()>8192).put("state","RUNNING");
                ManagedWorkspaceStore.write(record,entry); executionRecord.set(record);
            }
            return () -> { executionRecord.remove(); lease.close(); };
        } catch (Exception failure) {
            try { lease.close(); } catch (Exception closing) { failure.addSuppressed(closing); }
            throw failure instanceof IOException io ? io : new IOException(failure);
        }
    }

    /** 只记录实际退出事实；普通 Shell 可能自行改包，不能继续宣称环境已验证。 */
    @Override public void completed(ExecutionPlan plan,DockerRuntime.Outcome outcome) throws IOException {
        Path record=executionRecord.get(); if (record==null) return;
        var entry=ManagedWorkspaceStore.read(record).put("state",outcome.error() ? "FAILED" : "RETURNED")
                .put("ended",Instant.now().toString()).put("truncated",outcome.truncated());
        if (outcome.exitCode()!=null) entry.put("exitCode",outcome.exitCode()); else entry.putNull("exitCode");
        ManagedWorkspaceStore.write(record,entry);
        Path environment=metadata.resolve("environment.json");
        if (Files.exists(environment)) {
            var state=ManagedWorkspaceStore.read(environment).put("verified",false);
            if (!state.path("state").asText().equals("INSTALLING")) state.put("state","UNVERIFIED");
            ManagedWorkspaceStore.write(environment,state);
        }
    }

    /** 只给内部导入/补丁工作者暴露本次新建暂存目录；无宿主回退，不传递模型认证。 */
    DockerRuntime.Outcome run(String tool,Path scratch,ObjectNode input,boolean network,ExecutionContext context,
            java.util.function.BooleanSupplier invalid) throws IOException {
        if (context.isCancelled() || invalid.getAsBoolean()) throw new IOException("Managed execution authorization is stale");
        DockerRuntime runtime; PermissionState state;
        synchronized (this) { runtime=docker; state=permissions; }
        if (runtime==null) throw new IOException("Managed operation requires an attached Docker runtime");
        long revision=state.snapshot().revision();
        var plan=new ExecutionPlan("managed/"+id,context.id(),revision,tool,input,scratch,false,network,false,
                List.of(new ExecutionPlan.Mount(scratch,scratch,true)));
        var result=runtime.execute(plan,300,()->context.isCancelled() || invalid.getAsBoolean() || state.snapshot().revision()!=revision);
        if (result.error()) throw new IOException(result.content());
        return result;
    }

    /** 内部暂存不属于可挂载的工作目录，只有确切本次执行可见。 */
    Path scratch(String id) throws IOException {
        ManagedWorkspaceStore.id(id); return WorkspaceFiles.directory(storage.resolve("staging").resolve(id));
    }
    /** 只读取已登记操作的版本标记；不创建或解释任意外部路径。 */
    String pinnedCommit(String operation) throws IOException {
        ManagedWorkspaceStore.id(operation); Path file=storage.resolve("staging").resolve(operation).resolve("resolved-commit");
        if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>128) return "";
        String commit=Files.readString(file).strip(); return commit.matches("[a-f0-9]{40,64}")?commit:"";
    }

    /** 在危险操作前保存意图；最后以 READY/FAILED/CANCELLED 记录，不用半成品冒充成果。 */
    Path operation(String kind,ExecutionContext context) throws IOException {
        Path records=WorkspaceFiles.directory(metadata.resolve("operations"));
        if (records("operations").size()>=1024) throw new IOException("Workspace operation record limit reached");
        String id=UUID.randomUUID().toString(); Path file=records.resolve(id+".json");
        ManagedWorkspaceStore.write(file,Json.mapper().createObjectNode().put("id",id).put("kind",kind)
                .put("runId",context.id()).put("state","PREPARING").put("started",Instant.now().toString()));
        return file;
    }
    void finish(Path record,String state,String detail) throws IOException {
        String bounded=detail==null?"":detail.substring(0,Math.min(1024,detail.length()));
        var entry=ManagedWorkspaceStore.read(record).put("state",state).put("detail",bounded).put("ended",Instant.now().toString());
        ManagedWorkspaceStore.write(record,entry);
    }
    List<Path> records(String category) throws IOException {
        Path directory=WorkspaceFiles.directory(metadata.resolve(category));
        try (var files=Files.list(directory)) {
            var dated=new ArrayList<Map.Entry<Path,java.nio.file.attribute.FileTime>>();
            for (Path file:files.filter(p->p.toString().endsWith(".json")).toList())
                dated.add(Map.entry(file,Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS)));
            // UUID 的字典顺序不是时间；只读取一次属性，最近窗口按实际落盘顺序选取。
            dated.sort(Map.Entry.comparingByValue()); return dated.stream().map(Map.Entry::getKey).toList();
        }
    }

    /** 向模型和客户端展示资源身份与状态；不返回私有存储路径或配置。 */
    public ObjectNode status() throws IOException {
        var result=Json.mapper().createObjectNode().put("workspaceId",id);
        var uploads=result.putArray("snapshots");
        for (Path file:records("uploads")) uploads.add(ManagedWorkspaceStore.read(file));
        result.set("inputs",resources.inputs());
        var operations=result.putArray("operations");
        var records=records("operations");
        for (Path file:records.subList(Math.max(0,records.size()-64),records.size())) operations.add(ManagedWorkspaceStore.read(file));
        Path environment=metadata.resolve("environment.json");
        if (Files.exists(environment)) result.set("environment",ManagedWorkspaceStore.read(environment));
        return result;
    }
    /** 安装结果由专用入口记录；成功还需 pip check，失败保留未就绪状态。 */
    public synchronized void environment(ObjectNode record) throws IOException {
        ObjectNode exact=record.deepCopy();
        if (docker!=null && docker.resolvedImage()!=null) exact.put("image",docker.resolvedImage());
        ManagedWorkspaceStore.write(metadata.resolve("environment.json"),exact);
    }
}
