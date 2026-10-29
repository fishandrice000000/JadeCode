package com.jadecode.workspace;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.config.JadePaths;
import com.jadecode.util.Json;

/**
 * 服务持有的独立工作区和资源目录。工作文件在 Harness 数据根的相邻私有根中，
 * 元数据不挂给工作容器。关闭会话不删除目录；删除只能由用户管理命令明确提出。
 * 本类协调同服务的对象身份，不是跨用户存储服务或文件系统配额实现。
 */
public final class ManagedWorkspaceStore {
    private final Path metadata, root;
    private final JadePaths paths;
    private final Map<String, ManagedWorkspace> opened = new HashMap<>();

    /** 只建立私有目录，不装配模型、不联网；测试传入专属 JadePaths。 */
    public ManagedWorkspaceStore(JadePaths paths) throws IOException {
        this.paths=paths;
        metadata = WorkspaceFiles.directory(paths.root().resolve("managed"));
        root = WorkspaceFiles.directory(paths.root().resolveSibling(paths.root().getFileName()+"-managed-workspaces"));
    }
    public Path root() { return root; }

    /** 分配空工作区；上限包含关闭后保留的目录，避免无限累积，失败清理本次新目录。 */
    public synchronized ManagedWorkspace create() throws IOException {
        if (list().path("workspaces").size() >= 32) throw new IOException("Retained workspace limit reached (32); delete an unused workspace explicitly");
        String id = UUID.randomUUID().toString();
        Path storage = WorkspaceFiles.directory(root.resolve(id));
        Path data = WorkspaceFiles.directory(metadata.resolve(id));
        try {
            WorkspaceFiles.directory(storage.resolve("files")); WorkspaceFiles.directory(storage.resolve("staging"));
            write(data.resolve("workspace.json"), Json.mapper().createObjectNode().put("id",id).put("created",Instant.now().toString()));
            var workspace = new ManagedWorkspace(id,storage,data); opened.put(id,workspace); return workspace;
        } catch (Exception failure) {
            WorkspaceFiles.delete(storage); WorkspaceFiles.delete(data);
            throw failure instanceof IOException io ? io : new IOException(failure);
        }
    }

    /** 从可信 ID 重开保留的数据，不恢复模型线程、工具调用或待审批。 */
    public synchronized ManagedWorkspace open(String id) throws IOException {
        id(id);
        if (opened.containsKey(id)) return opened.get(id);
        Path data = metadata.resolve(id), storage = root.resolve(id);
        if (!read(data.resolve("workspace.json")).path("id").asText().equals(id)) throw new IOException("Workspace identity mismatch");
        if (!storage.toRealPath().equals(storage) || !storage.resolve("files").toRealPath().equals(storage.resolve("files")))
            throw new IOException("Invalid managed workspace storage");
        var workspace = new ManagedWorkspace(id,storage,data); opened.put(id,workspace); return workspace;
    }

    /** 列出保留工作区；读取安全的目录身份，不枚举用户代码正文。 */
    public synchronized ObjectNode list() throws IOException {
        var result = Json.mapper().createObjectNode(); var rows = result.putArray("workspaces");
        try (var directories = Files.list(metadata)) {
            for (Path directory : directories.sorted().toList()) {
                if (!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) continue;
                var record = read(directory.resolve("workspace.json"));
                id(record.path("id").asText()); rows.add(record);
            }
        }
        return result;
    }

    /** 调用方须确认没有存活会话；领取独占执行屏障后删除这一个明确 ID 的数据。 */
    public synchronized void delete(String id) throws IOException {
        delete(id,com.jadecode.sandbox.SandboxSettings.defaults());
    }
    /** 使用服务当前已有镜像维护私有缓存，不拉镜像；失败保留实际错误，不绕过会话占用。 */
    public synchronized void delete(String id,com.jadecode.sandbox.SandboxSettings settings) throws IOException {
        ManagedWorkspace workspace = open(id);
        try (var exclusive = workspace.exclusive()) {
            // 仅清理此真实工作区分区的依赖缓存，既有会话/审计历史独立保留。
            Path cache=new com.jadecode.config.WorkspacePaths(workspace.path(),paths).root().resolve("sandbox/cache");
            paths.directory(cache,false);
            com.jadecode.sandbox.DockerRuntime.cleanEmptyCacheMountpoints(cache,settings);
            WorkspaceFiles.delete(cache);
            WorkspaceFiles.delete(root.resolve(id)); WorkspaceFiles.delete(metadata.resolve(id)); opened.remove(id);
        }
    }

    /**
     * 管理连接的有界传输接口。每次请求只携带一块 base64，上传偏移与内容一致才能重试。
     * 数据/资源 ID 绑定该工作区；模型工具不暴露这些管理写入口。
     */
    public ObjectNode command(ObjectNode input) throws IOException {
        String action = input.path("action").asText();
        if (action.equals("create")) return info(create());
        if (action.equals("list")) return list();
        ManagedWorkspace workspace = open(input.path("workspaceId").asText());
        return switch (action) {
            case "info" -> info(workspace);
            case "upload.begin" -> begin(workspace,input);
            case "upload.chunk" -> chunk(workspace,input);
            case "upload.finish" -> finish(workspace,input);
            case "artifacts" -> workspace.artifacts().list();
            case "artifact.read" -> readArtifact(workspace,input);
            default -> throw new IOException("Unsupported workspace action");
        };
    }

    /** 上传按内容摘要寻找未完成记录；相同输入可在重新执行客户端命令后从已有偏移续传。 */
    private synchronized ObjectNode begin(ManagedWorkspace workspace,ObjectNode input) throws IOException {
        long size = input.path("size").asLong(-1); String sha = input.path("sha256").asText();
        if (size < 1 || size > WorkspaceFiles.MAX_BYTES || !sha.matches("[a-f0-9]{64}")) throw new IOException("Invalid snapshot size or digest");
        Path uploads = WorkspaceFiles.directory(workspace.metadata().resolve("uploads"));
        try (var files = Files.list(uploads)) {
            var records = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            for (Path file : records) {
                var record = read(file);
                if (record.path("sha256").asText().equals(sha) && record.path("size").asLong() == size) return uploadStatus(uploads,record);
            }
            if (records.size() >= 64) throw new IOException("Snapshot resource limit reached (64)");
        }
        String id = UUID.randomUUID().toString();
        var record = Json.mapper().createObjectNode().put("id",id).put("size",size).put("sha256",sha).put("state","UPLOADING");
        Files.createFile(uploads.resolve(id+".part")); write(uploads.resolve(id+".json"),record);
        return uploadStatus(uploads,record);
    }

    /** 重复块只验证字节，不重复追加；错位或超额立即拒绝，不覆盖旧前缀。 */
    private synchronized ObjectNode chunk(ManagedWorkspace workspace,ObjectNode input) throws IOException {
        String id = input.path("resourceId").asText(); id(id);
        Path uploads = workspace.metadata().resolve("uploads"); var record = read(uploads.resolve(id+".json"));
        if (!record.path("state").asText().equals("UPLOADING")) throw new IOException("Snapshot is already finalized");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(input.path("data").asText()); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid snapshot chunk",invalid); }
        long offset = input.path("offset").asLong(-1); Path part = uploads.resolve(id+".part");
        if (bytes.length < 1 || bytes.length > WorkspaceFiles.CHUNK || offset < 0 || offset + bytes.length > record.path("size").asLong())
            throw new IOException("Invalid snapshot chunk bounds");
        try (var file = FileChannel.open(part,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            if (offset < file.size()) {
                ByteBuffer existing = ByteBuffer.allocate(bytes.length); file.position(offset);
                while (existing.hasRemaining() && file.read(existing) >= 0) { }
                if (existing.hasRemaining() || !Arrays.equals(existing.array(),bytes)) throw new IOException("Repeated chunk differs from stored bytes");
            } else {
                if (offset != file.size()) throw new IOException("Snapshot chunk offset mismatch");
                file.position(offset); ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer); file.force(true);
            }
        }
        return uploadStatus(uploads,record);
    }

    /** 完整性成功才登记 READY；展开检查在导入时再次执行，上传完成不等于已执行项目。 */
    private synchronized ObjectNode finish(ManagedWorkspace workspace,ObjectNode input) throws IOException {
        String id = input.path("resourceId").asText(); id(id);
        Path uploads = workspace.metadata().resolve("uploads"); var record = read(uploads.resolve(id+".json"));
        Path part = uploads.resolve(id+".part");
        if (Files.size(part) != record.path("size").asLong() || !WorkspaceFiles.sha256(part).equals(record.path("sha256").asText()))
            throw new IOException("Snapshot length or digest mismatch");
        record.put("state","READY"); write(uploads.resolve(id+".json"),record); return uploadStatus(uploads,record);
    }

    /** 已发布成果按偏移读取；客户端逐块写入本地临时文件并在摘要校验后改名。 */
    private ObjectNode readArtifact(ManagedWorkspace workspace,ObjectNode input) throws IOException {
        Path file = workspace.artifacts().bundle(input.path("artifactId").asText());
        long offset = input.path("offset").asLong(-1), size = Files.size(file);
        if (offset < 0 || offset > size) throw new IOException("Invalid artifact offset");
        ByteBuffer bytes = ByteBuffer.allocate((int)Math.min(WorkspaceFiles.CHUNK,size-offset));
        try (var channel = FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            channel.position(offset); while (bytes.hasRemaining() && channel.read(bytes) >= 0) { }
        }
        if (bytes.hasRemaining()) throw new IOException("Published artifact ended before its registered size");
        return Json.mapper().createObjectNode().put("offset",offset).put("size",size)
                .put("sha256",workspace.artifacts().manifest(input.path("artifactId").asText()).path("bundleSha256").asText())
                .put("data",Base64.getEncoder().encodeToString(bytes.array())).put("eof",offset+bytes.position()==size);
    }
    private static ObjectNode uploadStatus(Path uploads,ObjectNode record) throws IOException {
        var result = record.deepCopy(); result.put("offset",Files.size(uploads.resolve(record.path("id").asText()+".part"))); return result;
    }
    private static ObjectNode info(ManagedWorkspace workspace) throws IOException {
        return workspace.status().put("workspace",workspace.path().toString());
    }
    /** ID 只由服务产生；不能用相对路径引用另一份记录。 */
    public static void id(String id) throws IOException {
        if (!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IOException("Invalid resource identity");
    }
    /** 元数据始终位于私有根中，拒绝替换成链接或超大文件。 */
    static ObjectNode read(Path path) throws IOException {
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16L*1024*1024) throw new IOException("Invalid workspace record");
        var node = Json.mapper().readTree(Files.readString(path));
        if (!(node instanceof ObjectNode object)) throw new IOException("Invalid workspace record object"); return object;
    }
    static void write(Path path,ObjectNode record) throws IOException {
        // 取消执行后仍保存终态；不让当前线程的中断标记破坏元数据追加/原子替换。
        boolean interrupted=Thread.interrupted();
        try {
            String text=record.toString();
            if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16*1024*1024) throw new IOException("Workspace record size limit exceeded");
            JadePaths.atomicWrite(path,text);
        }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
