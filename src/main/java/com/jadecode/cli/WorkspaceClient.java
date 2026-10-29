package com.jadecode.cli;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.service.ServiceClient;
import com.jadecode.sandbox.PathProtection;
import com.jadecode.util.Json;
import com.jadecode.workspace.WorkspaceFiles;

/**
 * 用户显式上传/下载的客户端 IO。路径始终在客户端解释，远程只收到资源块和工作区 ID。
 * 不让模型填写客户端落盘位置；有界传输不把 ZIP 全部读入内存，失败保留可续传前缀。
 */
public final class WorkspaceClient {
    private WorkspaceClient() { }
    public static ObjectNode request(ServiceClient.Peer peer,String action,String workspace) throws IOException {
        return ServiceClient.request(peer,"service.workspace",command(action,workspace));
    }
    private static ObjectNode command(String action,String workspace) {
        var value=Json.mapper().createObjectNode().put("action",action);
        if (workspace!=null) value.put("workspaceId",workspace); return value;
    }

    /** 用户选中的项目普通文件包含未跟踪文件；凭据/元数据/依赖环境由打包器排除。 */
    public static ObjectNode upload(ServiceClient.Peer peer,String workspace,Path source) throws IOException {
        source=source.toRealPath();
        if (!Files.isDirectory(source) || PathProtection.sensitive(source) || source.getNameCount()<2
                || source.equals(Path.of(System.getProperty("user.home")))) throw new IOException("Choose a narrow ordinary project directory");
        Path temporary=Files.createTempDirectory("jade-snapshot-");
        try {
            Path archive=temporary.resolve("snapshot.zip"); WorkspaceFiles.zip(source,archive,(file,name)->{});
            var begin=command("upload.begin",workspace).put("size",Files.size(archive)).put("sha256",WorkspaceFiles.sha256(archive));
            ObjectNode state=ServiceClient.request(peer,"service.workspace",begin);
            String id=state.path("id").asText(); long offset=state.path("offset").asLong();
            if (state.path("state").asText().equals("READY")) return state;
            try (var file=FileChannel.open(archive,StandardOpenOption.READ)) {
                file.position(offset); ByteBuffer chunk=ByteBuffer.allocate(WorkspaceFiles.CHUNK);
                while (file.read(chunk)>=0) {
                    if (chunk.position()==0) break;
                    byte[] bytes=Arrays.copyOf(chunk.array(),chunk.position());
                    state=ServiceClient.request(peer,"service.workspace",command("upload.chunk",workspace).put("resourceId",id)
                            .put("offset",offset).put("data",Base64.getEncoder().encodeToString(bytes)));
                    offset+=bytes.length;
                    if (state.path("offset").asLong()!=offset) throw new IOException("Server snapshot offset mismatch");
                    chunk.clear();
                }
            }
            return ServiceClient.request(peer,"service.workspace",command("upload.finish",workspace).put("resourceId",id));
        } finally { WorkspaceFiles.delete(temporary); }
    }

    /**
     * 下载到同目录私有 .part 文件，重试从长度继续；最终长度与 SHA-256 全部匹配才发布。
     * 利用同文件系统的硬链接原子创建新名字，不覆盖已存在用户文件，随后移除临时名字。
     */
    public static void download(ServiceClient.Peer peer,String workspace,String artifact,Path destination) throws IOException {
        com.jadecode.workspace.ManagedWorkspaceStore.id(artifact);
        Path target=destination.toAbsolutePath().normalize(); Files.createDirectories(target.getParent());
        if (Files.exists(target,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Download destination already exists");
        Path partial=target.resolveSibling(target.getFileName()+".jade-"+artifact+".part");
        if (Files.exists(partial,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(partial,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid partial download path");
        try (var file=FileChannel.open(partial,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
             var lock=file.tryLock()) {
            if (lock==null) throw new IOException("Another client is downloading this artifact");
            Files.setPosixFilePermissions(partial,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            long offset=file.size(); file.position(offset); String sha=null; long expected=-1;
            while (true) {
                var part=ServiceClient.request(peer,"service.workspace",command("artifact.read",workspace).put("artifactId",artifact).put("offset",offset));
                if (expected<0) { expected=part.path("size").asLong(-1); sha=part.path("sha256").asText(); }
                if (part.path("offset").asLong()!=offset || part.path("size").asLong()!=expected || !part.path("sha256").asText().equals(sha)
                        || expected<0 || expected>WorkspaceFiles.MAX_BYTES) throw new IOException("Artifact metadata changed");
                byte[] bytes=Base64.getDecoder().decode(part.path("data").asText());
                if (bytes.length>WorkspaceFiles.CHUNK || offset+bytes.length>expected || bytes.length==0 && offset<expected)
                    throw new IOException("Invalid artifact chunk");
                ByteBuffer buffer=ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer); offset+=bytes.length;
                if (part.path("eof").asBoolean()) break;
            }
            file.force(true);
            if (offset!=expected || !WorkspaceFiles.sha256(partial).equals(sha)) {
                // 损坏的前缀不能靠追加修复；只删除本次保留的临时名字，下次从零重试。
                Files.delete(partial); throw new IOException("Artifact digest or length mismatch; corrupt temporary data removed");
            }
            Files.createLink(target,partial); Files.delete(partial);
        } catch (Exception failure) {
            String recovery=Files.exists(partial,LinkOption.NOFOLLOW_LINKS)?"; partial file retained: "+partial:"; no output file was published";
            throw new IOException("Artifact download failed: "+failure.getMessage()+recovery,failure);
        }
    }
}
