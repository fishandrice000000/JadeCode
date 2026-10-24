package com.jadecode.cli.backend.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

/** 私有 UDS：单连接、真实字节、退出清理。 */
class LocalControlServerTest {
    @Test
    void acceptsOneConnectionAndCleansItsOwnEndpoint() throws Exception {
        LocalControlServer server = LocalControlServer.open();
        java.nio.file.Path path = server.socketPath();
        assertTrue(Files.exists(path));
        try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            client.connect(UnixDomainSocketAddress.of(path));
            SocketChannel accepted = server.acceptOne(5000);
            assertNotNull(accepted);
            OutputStream out = Channels.newOutputStream(client);
            InputStream in = Channels.newInputStream(accepted);
            out.write("hello\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            byte[] buffer = new byte[6];
            int read = in.read(buffer);
            assertEquals("hello\n", new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
            accepted.close();
        }
        server.close();
        assertFalse(Files.exists(path), "the owned socket must be removed");
    }
}
