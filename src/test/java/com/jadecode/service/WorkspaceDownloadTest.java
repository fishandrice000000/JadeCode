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
import com.jadecode.execution.ExecutionContext;
import com.jadecode.workspace.ManagedWorkspaceStore;
import com.jadecode.workspace.WorkspaceTools;
import com.jadecode.safety.PermissionGate;
import com.jadecode.safety.PermissionState;
import com.jadecode.tools.BashTool;
import com.jadecode.util.PathResolver;
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
            // 用真实发布服务准备原始二进制文件，验证传输不再隐式包装为 ZIP。
            var stored=new ManagedWorkspaceStore(paths).open(workspace);
            byte[] content=new byte[180_000]; new Random(19).nextBytes(content);
            Files.write(stored.path().resolve("presentation.pptx"),content);
            var publication=Json.mapper().createObjectNode().put("summary","fixture PPT");
            publication.putArray("paths").add("presentation.pptx");
            var state=PermissionState.defaults();
            var gate=new PermissionGate(new PathResolver(stored.path()),state,null);
            var publisher=WorkspaceTools.forWorkspace(stored,gate,state,new BashTool(stored.path())).stream()
                    .filter(tool->tool.name().equals("publish_result")).findFirst().orElseThrow();
            var context=ExecutionContext.create(ExecutionContext.Source.USER,null);
            assertNull(gate.check("publish_result",publication,context));
            var outcome=publisher.executeOutcome(publication,context);
            assertFalse(outcome.isError(),outcome.content());
            String artifact=Json.mapper().readTree(outcome.content()).path("artifacts").get(0).path("artifactId").asText();
            Path socket=root.resolve("download.sock"); server.bind(UnixDomainSocketAddress.of(socket));
            var serving=readers.submit(()->{
                try (var accepted=server.accept()) {
                    ServiceDaemon.serve(service,accepted,()->{},timer.schedule(()->{},10,TimeUnit.SECONDS));
                } catch (Exception failure) { throw new CompletionException(failure); }
            });
            try (var channel=SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(UnixDomainSocketAddress.of(socket));
                try (var peer=new ServiceClient.Peer(ChannelStreams.input(channel),ChannelStreams.output(channel),channel)) {
                    byte[] bytes=content; Path output=root.resolve("result.pptx");
                    Path part=output.resolveSibling(output.getFileName()+".jade-"+artifact+".part");
                    Files.write(part,Arrays.copyOf(bytes,23_000));
                    WorkspaceClient.download(peer,workspace,artifact,output);
                    assertArrayEquals(content,Files.readAllBytes(output)); assertFalse(Files.exists(part));
                    assertThrows(java.io.IOException.class,()->WorkspaceClient.download(peer,workspace,artifact,output));
                    Path corrupt=root.resolve("corrupt.pptx"); Path broken=corrupt.resolveSibling(corrupt.getFileName()+".jade-"+artifact+".part");
                    byte[] prefix=Arrays.copyOf(bytes,23_000); prefix[100]^=1; Files.write(broken,prefix);
                    assertThrows(java.io.IOException.class,()->WorkspaceClient.download(peer,workspace,artifact,corrupt));
                    assertFalse(Files.exists(corrupt)); assertFalse(Files.exists(broken));
                    WorkspaceClient.download(peer,workspace,artifact,corrupt);
                    assertArrayEquals(content,Files.readAllBytes(corrupt));
                }
            }
            serving.get(5,TimeUnit.SECONDS); Files.delete(socket);
        } finally { service.close(); readers.shutdownNow(); timer.shutdownNow(); }
    }
}
