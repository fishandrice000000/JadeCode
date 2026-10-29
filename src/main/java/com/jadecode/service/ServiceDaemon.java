package com.jadecode.service;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.FrontendDistribution;
import com.jadecode.cli.backend.protocol.*;
import com.jadecode.config.*;
import com.jadecode.util.Json;

/**
 * 每个用户数据根唯一的常驻进程入口。启动锁保护注册表，私有 UDS 支持多会话和顺序重连；
 * 控制连接 EOF 仅移除订阅，只有明确的管理命令才关闭会话或服务。
 */
public final class ServiceDaemon {
    private ServiceDaemon() { }
    /** 阻塞运行服务；全部配置/凭据留在本执行机器，不写入端点发现文件。 */
    public static int run() {
        JadePaths paths=JadePaths.defaults(); Path root=paths.root().resolve("service");
        Path socketDirectory=null;
        try {
            paths.directory(root,true);
            // 服务目录在工作区之外；只有取得 OS 锁的进程能发布端点或修改会话清单。
            if(Files.exists(root.resolve("daemon.lock"),LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(root.resolve("daemon.lock"),LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Invalid daemon lock path");
            try (FileChannel lockFile=FileChannel.open(root.resolve("daemon.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                 FileLock lock=lockFile.tryLock()) {
                if(lock==null) return 0;
                cleanupOldEndpoint(root);
                String build=FrontendDistribution.backendBuildId();
                socketDirectory=Files.createTempDirectory("jade-service-",PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
                Path socket=socketDirectory.resolve("agent.sock");
                try (var server=ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                     var service=new AgentService(paths,build,null)) {
                    server.bind(UnixDomainSocketAddress.of(socket));
                    Files.setPosixFilePermissions(socket,PosixFilePermissions.fromString("rw-------"));
                    JadePaths.atomicWrite(root.resolve("endpoint.json"),Json.mapper().createObjectNode()
                            .put("socket",socket.toString()).put("pid",ProcessHandle.current().pid())
                            .put("started",ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli())
                            .put("buildId",build).toString());
                    Thread shutdown=new Thread(()->{service.close();try{server.close();}catch(IOException ignored){}});
                    Runtime.getRuntime().addShutdownHook(shutdown);
                    // 有界连接名额和首帧期限防止未握手客户端无限持有 reader。
                    var readers=Executors.newVirtualThreadPerTaskExecutor();
                    var timer=Executors.newSingleThreadScheduledExecutor();
                    var slots=new Semaphore(32);
                    try {
                        while(server.isOpen()) {
                            SocketChannel peer=server.accept();
                            if(!slots.tryAcquire()){peer.close();continue;}
                            readers.submit(()->{
                                var deadline=timer.schedule(()->{try{peer.close();}catch(IOException ignored){}},10,TimeUnit.SECONDS);
                                try(peer) { serve(service,peer,()->{try{server.close();}catch(IOException ignored){}},deadline); }
                                catch(Exception failure) { /* 连接错误不会终止其他会话；协议答复在 serve 内发送。 */ }
                                finally { deadline.cancel(false); slots.release(); }
                            });
                        }
                    } catch(AsynchronousCloseException stopped) { /* 明确 service.stop 唤醒 accept。 */ }
                    finally {
                        service.close(); timer.shutdownNow(); readers.shutdownNow();
                        Runtime.getRuntime().removeShutdownHook(shutdown);
                        Files.deleteIfExists(root.resolve("endpoint.json"));
                    }
                }
            }
            return 0;
        } catch(Exception failure) {
            System.err.println("[Service] Startup/cleanup failed: "+failure.getMessage()); return 1;
        } finally {
            if(socketDirectory!=null) try {
                Files.deleteIfExists(socketDirectory.resolve("agent.sock"));Files.deleteIfExists(socketDirectory);
            } catch(IOException failure){System.err.println("[Service] Socket cleanup incomplete");}
        }
    }
    /** 第一帧选择会话，之后直接沿用成套 TUI 协议；第一帧不触发模型执行。 */
    static void serve(AgentService service,SocketChannel peer,Runnable stop,ScheduledFuture<?> deadline) throws Exception {
        InputStream in=ChannelStreams.input(peer);OutputStream out=ChannelStreams.output(peer);
        WireMessage request=WireCodec.readFrame(in);
        if(request==null) return;
        try {
            switch(request.type()) {
                case "service.list" -> { reply(out,service.list()); return; }
                case "service.cancel" -> { service.stopSession(request.payload().path("id").asText());reply(out,Json.mapper().createObjectNode().put("status","stopping"));return; }
                case "service.close" -> { service.closeSession(request.payload().path("id").asText());reply(out,Json.mapper().createObjectNode());return; }
                case "service.stop" -> {
                    try {
                        service.close();
                        if(!service.list().path("sessions").isEmpty())
                            throw new IOException("Service cleanup is incomplete; original registry was preserved");
                        reply(out,Json.mapper().createObjectNode().put("status","stopped"));
                    } finally { stop.run(); }
                    return;
                }
                case "service.open" -> { }
                default -> throw new IOException("Expected service.open or management command");
            }
            // 创建会话只准备运行时；模型和 cron 要等客户端安装快照并通过 ready 屏障。
            AgentService.Managed managed=service.open(request.payload());
            String credential=Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
            try(ProtocolSession protocol=managed.protocol(out,credential,()->{try{peer.close();}catch(IOException ignored){}},request.payload())) {
                reply(out,Json.mapper().createObjectNode().put("id",managed.id).put("credential",credential));
                deadline.cancel(false);
                WireMessage message;
                while((message=WireCodec.readFrame(in))!=null) try { protocol.receive(message); }
                catch(ProtocolException rejected) {
                    protocol.sendError(message.commandId(),rejected.error(),rejected.getMessage(),message.sessionId(),message.generation());
                    if(!rejected.error().recoverable()) break;
                }
            }
        } catch(Exception failure) {
            reply(out,Json.mapper().createObjectNode().put("error",failure.getMessage()==null?"Service operation failed":failure.getMessage()));
        }
    }
    private static byte[] randomBytes(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return bytes;}
    /** 只清理私有发现文件登记的旧 UDS；不遍历或 prune 系统临时目录。 */
    private static void cleanupOldEndpoint(Path root)throws IOException{
        Path metadata=root.resolve("endpoint.json");
        if(!Files.exists(metadata,LinkOption.NOFOLLOW_LINKS))return;
        if(!Files.isRegularFile(metadata,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid endpoint metadata");
        var previous=Json.mapper().readTree(Files.readString(metadata));
        var owner=ProcessHandle.of(previous.path("pid").asLong());
        if(owner.filter(ProcessHandle::isAlive).map(p->p.info().startInstant()
                .map(t->t.toEpochMilli()==previous.path("started").asLong()).orElse(true)).orElse(false))
            throw new IOException("Previous endpoint owner is still alive");
        Path socket=Path.of(previous.path("socket").asText()).toAbsolutePath().normalize();
        Path parent=socket.getParent(),temporary=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if(parent!=null && parent.getParent().equals(temporary) && parent.getFileName().toString().startsWith("jade-service-")
                && socket.getFileName().toString().equals("agent.sock") && Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS)){
            Files.deleteIfExists(socket);
            Files.deleteIfExists(parent);
        }
        Files.delete(metadata);
    }
    private static void reply(OutputStream out,ObjectNode p)throws Exception {
        out.write(WireCodec.encode(WireMessage.event(1,"","service.result",null,null,p)));out.flush();
    }
}
