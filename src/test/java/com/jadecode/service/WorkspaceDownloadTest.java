package com.jadecode.service;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.cli.WorkspaceClient;
import com.jadecode.config.JadePaths;
import com.jadecode.util.Json;
import com.jadecode.workspace.WorkspaceFiles;

/** 真实 UDS/管理协议验证成果断点下载、摘要与拒绝覆盖；不启动模型或 Docker。 */
class WorkspaceDownloadTest {
    @TempDir Path root;
    @Test void resumesPublishedBytesAndRejectsCorruptPrefixOrExistingDestination() throws Exception {
        var paths=new JadePaths(root.resolve("data")); paths.directory(paths.config(),true);
        Files.writeString(paths.config().resolve("config.json"),"{\"memory\":{\"enabled\":false}}");
        var service=new AgentService(paths,"fixture",(workspace,permissions,configuration)->{throw new AssertionError("Download must not create an Agent");});
        var readers=Executors.newVirtualThreadPerTaskExecutor(); var timer=Executors.newSingleThreadScheduledExecutor();
        try (var server=ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            String workspace=service.workspaceCommand(Json.mapper().createObjectNode().put("action","create")).path("workspaceId").asText();
            String artifact=UUID.randomUUID().toString();
            Path source=Files.createDirectory(root.resolve("source")); byte[] content=new byte[180_000]; new Random(19).nextBytes(content); Files.write(source.resolve("binary"),content);
            Path directory=Files.createDirectories(paths.root().resolve("managed").resolve(workspace).resolve("artifacts").resolve(artifact));
            Path bundle=directory.resolve("bundle.zip"); WorkspaceFiles.zip(source,bundle,(file,name)->{});
            JadePaths.atomicWrite(directory.resolveSibling(artifact+".json"),Json.mapper().createObjectNode().put("artifactId",artifact)
                    .put("bundleSha256",WorkspaceFiles.sha256(bundle)).put("bundleSize",Files.size(bundle)).toString());
            Path socket=root.resolve("download.sock"); server.bind(UnixDomainSocketAddress.of(socket));
            var serving=readers.submit(()->{
                try (var accepted=server.accept()) {
                    ServiceDaemon.serve(service,accepted,()->{},timer.schedule(()->{},10,TimeUnit.SECONDS));
                } catch (Exception failure) { throw new CompletionException(failure); }
            });
            try (var channel=SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(UnixDomainSocketAddress.of(socket));
                try (var peer=new ServiceClient.Peer(ChannelStreams.input(channel),ChannelStreams.output(channel),channel)) {
                    byte[] bytes=Files.readAllBytes(bundle); Path output=root.resolve("result.zip");
                    Path part=output.resolveSibling(output.getFileName()+".jade-"+artifact+".part");
                    Files.write(part,Arrays.copyOf(bytes,23_000));
                    WorkspaceClient.download(peer,workspace,artifact,output);
                    assertEquals(WorkspaceFiles.sha256(bundle),WorkspaceFiles.sha256(output)); assertFalse(Files.exists(part));
                    assertThrows(java.io.IOException.class,()->WorkspaceClient.download(peer,workspace,artifact,output));
                    Path corrupt=root.resolve("corrupt.zip"); Path broken=corrupt.resolveSibling(corrupt.getFileName()+".jade-"+artifact+".part");
                    byte[] prefix=Arrays.copyOf(bytes,23_000); prefix[100]^=1; Files.write(broken,prefix);
                    assertThrows(java.io.IOException.class,()->WorkspaceClient.download(peer,workspace,artifact,corrupt));
                    assertFalse(Files.exists(corrupt)); assertFalse(Files.exists(broken));
                    WorkspaceClient.download(peer,workspace,artifact,corrupt);
                    assertEquals(WorkspaceFiles.sha256(bundle),WorkspaceFiles.sha256(corrupt));
                }
            }
            serving.get(5,TimeUnit.SECONDS); Files.delete(socket);
        } finally { service.close(); readers.shutdownNow(); timer.shutdownNow(); }
    }
}
