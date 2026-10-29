package com.jadecode.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** SSH 的 stdin 与真实 Process 管道一样带缓冲；活连接的小握手必须在 EOF 前可见。 */
class ServiceCliForwardingTest {
    @Test void smallHandshakeAndNextFrameArriveBeforeFrontendClosesItsConnection() throws Exception {
        try(var frontend=new PipedInputStream();var source=new PipedOutputStream(frontend);
            var server=new PipedInputStream();var wire=new PipedOutputStream(server);
            var buffered=new BufferedOutputStream(wire,8192);
            var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            var forwarding=threads.submit(()->{ServiceCli.forwardClientInput(frontend,buffered);return null;});
            var frames=new byte[][]{"client.hello\n".getBytes(StandardCharsets.UTF_8),"session.ready\n".getBytes(StandardCharsets.UTF_8)};
            var replies=java.util.List.of(new CompletableFuture<byte[]>(),new CompletableFuture<byte[]>());
            // PipedInputStream 检查读线程是否存活；一个接收者持续读完整连接，避免夹具把线程退出误当断线。
            var receiver=threads.submit(()->{
                for(int index=0;index<frames.length;index++)replies.get(index).complete(server.readNBytes(frames[index].length));
                return server.read();
            });
            try {
                // 模拟小于缓冲区的两帧；发送者保持连接打开，服务需要先收到 hello 才会回答。
                for(int index=0;index<frames.length;index++) {
                    byte[] expected=frames[index];
                    source.write(expected);source.flush();
                    assertArrayEquals(expected,replies.get(index).get(3,TimeUnit.SECONDS));
                    assertFalse(forwarding.isDone(),"A live frontend must not need EOF to flush a frame");
                }
                source.close();forwarding.get(3,TimeUnit.SECONDS);
                // 转发结束不接管流的所有权；关闭连接仍由 interactive/Peer 的生命周期负责。
                buffered.write('x');buffered.flush();assertEquals('x',receiver.get(3,TimeUnit.SECONDS));
            } finally {
                source.close();frontend.close();server.close();threads.shutdownNow();
            }
        }
    }
}
