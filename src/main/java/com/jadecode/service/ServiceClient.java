package com.jadecode.service;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.backend.protocol.*;
import com.jadecode.config.*;
import com.jadecode.util.Json;

/**
 * 本地 UDS 或 SSH 的窄连接器。远端命令固定为 jade --service-relay，工作区等参数只走 JSON，
 * 不拼接到远端 shell。SSH 保留主机密钥校验，首版使用已配置的非交互认证。
 */
public final class ServiceClient {
    private ServiceClient() { }
    /** 一条连接拥有自身 IO 和 SSH 子进程；close 不发停止会话命令。 */
    public static final class Peer implements AutoCloseable {
        public final InputStream input; public final OutputStream output;
        private final AutoCloseable resource;
        Peer(InputStream input,OutputStream output,AutoCloseable resource){this.input=input;this.output=output;this.resource=resource;}
        @Override public void close() throws IOException {
            try{resource.close();}catch(Exception failure){throw new IOException("Service connection close failed",failure);}
        }
    }
    /** 本机连接按需启动 daemon；远端 relay 自己启动其本机服务。 */
    public static Peer connect(String remote) throws IOException {
        if(remote!=null) {
            if(!remote.matches("[A-Za-z0-9_][A-Za-z0-9_.@:\\-]*")) throw new IOException("Invalid SSH target");
            var process=new ProcessBuilder("ssh","-T","-o","BatchMode=yes","-o","ConnectTimeout=10","-o","ServerAliveInterval=15","-o","ServerAliveCountMax=2","--",remote,"jade --service-relay")
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            return new Peer(process.getInputStream(),process.getOutputStream(),()->{
                process.getOutputStream().close();process.getInputStream().close();
                if(process.isAlive()) process.destroy();
            });
        }
        return local(true);
    }
    /** 私有发现文件只含 socket 与进程身份；不读取任何模型认证。 */
    private static Peer local(boolean launch) throws IOException {
        JadePaths paths=JadePaths.defaults();Path root=paths.root().resolve("service");
        paths.directory(root,true);
        try {
            Path endpoint=root.resolve("endpoint.json");
            if(!Files.isRegularFile(endpoint,LinkOption.NOFOLLOW_LINKS)) throw new IOException("No service endpoint");
            var value=Json.mapper().readTree(Files.readString(endpoint));
            long pid=value.path("pid").asLong(),start=value.path("started").asLong();
            var process=ProcessHandle.of(pid);
            if(process.isEmpty() || !process.get().isAlive() || process.get().info().startInstant()
                    .map(t->t.toEpochMilli()!=start).orElse(true)) throw new IOException("Service endpoint is stale");
            SocketChannel channel=SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                channel.connect(UnixDomainSocketAddress.of(value.path("socket").asText()));
                return new Peer(ChannelStreams.input(channel),ChannelStreams.output(channel),channel);
            }catch(IOException failure){channel.close();throw failure;}
        } catch(IOException failure) {
            if(!launch) throw failure;
            start(root);
            long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(System.nanoTime()<end) {
                try{return local(false);}catch(IOException notReady){
                    try{Thread.sleep(40);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}
                }
            }
            throw new IOException("Agent service did not start; see "+root.resolve("daemon.log"));
        }
    }
    /** 独立进程不继承终端输入；setsid 使终端关闭不会向 daemon 发送挂断信号。 */
    private static void start(Path root)throws IOException {
        Path log=root.resolve("daemon.log");
        if(Files.exists(log,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(log,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid service log path");
        if(!Files.exists(log)) Files.createFile(log,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        var args=new ArrayList<>(List.of("/usr/bin/setsid","--",Path.of(System.getProperty("java.home"),"bin/java").toString(),
                "-Duser.home="+System.getProperty("user.home"),"-cp",absoluteClasspath(),"com.jadecode.Main","--service-daemon"));
        new ProcessBuilder(args).redirectInput(new File("/dev/null")).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
    }
    private static String absoluteClasspath() {
        return Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(part->Path.of(part).toAbsolutePath().normalize().toString())
                .reduce((a,b)->a+java.io.File.pathSeparator+b).orElseThrow();
    }
    /** 管理前导帧与正常 TUI 帧使用相同大小/UTF-8 验证，失败不进入协议握手。 */
    public static ObjectNode request(Peer peer,String type,ObjectNode payload)throws IOException {
        try {
            peer.output.write(WireCodec.encode(WireMessage.event(1,"",type,null,null,payload)));peer.output.flush();
            var reply=WireCodec.readFrame(peer.input);
            if(reply==null) throw new IOException("Service connection ended before its reply");
            if(reply.payload().has("error")) throw new IOException(reply.payload().path("error").asText());
            return reply.payload();
        }catch(ProtocolException invalid){throw new IOException("Invalid service reply",invalid);}
    }
    /** SSH 远端固定入口；只转发字节，不渲染，不加载模型，EOF 不停止 daemon。 */
    public static int relay() {
        var inputEnded = new java.util.concurrent.atomic.AtomicBoolean();
        try(Peer peer=local(true)) {
            var writer=Thread.ofVirtual().start(()->{
                try{System.in.transferTo(peer.output);inputEnded.set(true);}catch(IOException ignored){}
                finally{try{peer.close();}catch(IOException ignored){}}
            });
            peer.input.transferTo(System.out);System.out.flush();writer.interrupt();return 0;
        }catch(IOException failure){if(inputEnded.get())return 0;System.err.println("Service relay failed: "+failure.getMessage());return 1;}
    }
}
