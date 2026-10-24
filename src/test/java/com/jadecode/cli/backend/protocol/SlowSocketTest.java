package com.jadecode.cli.backend.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jadecode.util.Json;

/** 真实 UDS 的慢读/过载验证；没有用会立即成功的内存输出流代替内核背压。 */
class SlowSocketTest {
    @TempDir Path directory;

    @Test
    void pausedReaderDrainsEveryFrameInOrder() throws Exception {
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(directory.resolve("s")));
            try (SocketChannel reader = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                reader.connect(server.getLocalAddress());
                try (SocketChannel writer = server.accept();
                        ProtocolSession session = protocol(writer)) {
                    writer.setOption(StandardSocketOptions.SO_SNDBUF, 1024);
                    // 先不读：发送容量远大于 socket 缓冲但低于 outbox 预算的结构事件。
                    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                        for (int i = 0; i < 100; i++) {
                            var payload = Json.mapper().createObjectNode().put("index", i).put("text", "x".repeat(8192));
                            session.sendEvent("message", payload, "s", 1L);
                        }
                    });
                    CompletableFuture<List<Integer>> received = new CompletableFuture<>();
                    Thread.ofVirtual().start(() -> {
                        try {
                            var input = Channels.newInputStream(reader);
                            List<Integer> indexes = new ArrayList<>();
                            for (int i = 0; i < 100; i++) {
                                WireMessage frame = WireCodec.readFrame(input);
                                assertNotNull(frame);
                                assertEquals(i + 1, frame.seq());
                                indexes.add(frame.payload().path("index").asInt());
                            }
                            received.complete(indexes);
                        } catch (Throwable failure) { received.completeExceptionally(failure); }
                    });
                    assertEquals(java.util.stream.IntStream.range(0, 100).boxed().toList(), received.get(5, TimeUnit.SECONDS));
                    assertNull(session.failureReason());
                }
            }
        }
    }

    @Test
    void unreadSocketOverloadDoesNotBlockTheProducer() throws Exception {
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(directory.resolve("s")));
            try (SocketChannel reader = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                reader.connect(server.getLocalAddress());
                try (SocketChannel writer = server.accept(); ProtocolSession session = protocol(writer)) {
                    writer.setOption(StandardSocketOptions.SO_SNDBUF, 1024);
                    // 不启动 reader。旧实现会在 offer 的过载回调里同步等待 close 五秒。
                    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                        for (int i = 0; i < 800; i++) {
                            session.sendEvent("message", Json.mapper().createObjectNode().put("text", "x".repeat(16384)), "s", 1L);
                        }
                    });
                    assertNotNull(session.failureReason(), "overload must remain a real failure, never a silent drop");
                    writer.close(); // 唤醒内核阻塞写，使关闭线程可以收尾。
                }
            }
        }
    }

    /** 构造真实写通道；这些测试只检查事件出口，不伪造已握手的业务命令。 */
    private ProtocolSession protocol(SocketChannel writer) {
        return new ProtocolSession(Channels.newOutputStream(writer), "fixture", "conn", hello -> null,
                command -> {}, () -> {});
    }
}
