package com.jadecode.cli.backend.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 协议状态机回归：握手顺序、构建/凭据、连接身份、ready 屏障与重复 ready。
 *
 * <p>这些用例使用真实 {@link WireCodec} 解码状态机写出的字节，不用 mock 发送方法。</p>
 */
class ProtocolSessionTest {
    private static final String CREDENTIAL = "test-credential";
    private static final String BUILD = "build-1";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final AtomicBoolean helloOpenedSession = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final List<String> commands = new ArrayList<>();
    private final AtomicReference<ProtocolSession> holder = new AtomicReference<>();

    private ProtocolSession session() {
        ProtocolSession session = new ProtocolSession(out, CREDENTIAL, "conn-1",
                hello -> greeting(),
                command -> commands.add(command.type()),
                () -> { },
                () -> ready.set(true),
                () -> {
                    helloOpenedSession.set(true);
                    // 模拟 BackendSessionService.openInitial 的快照事件：它必须排在 bootstrap 之后。
                    ObjectNode snapshot = Json.mapper().createObjectNode();
                    snapshot.put("snapshotId", "snap-1");
                    holder.get().sendEvent("session.snapshot.begin", snapshot, "session-1", 1L);
                });
        holder.set(session);
        return session;
    }

    private ProtocolSession.Greeting greeting() {
        ObjectNode hello = Json.mapper().createObjectNode();
        hello.put("connectionId", "conn-1");
        hello.put("protocolVersion", WireCodec.PROTOCOL_VERSION);
        hello.put("buildId", BUILD);
        ObjectNode bootstrap = Json.mapper().createObjectNode();
        bootstrap.put("buildId", BUILD);
        bootstrap.put("protocolVersion", WireCodec.PROTOCOL_VERSION);
        return new ProtocolSession.Greeting(hello, bootstrap);
    }

    private List<String> typesWritten() {
        List<String> types = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            try { types.add(WireCodec.parse(line).type()); }
            catch (ProtocolException failure) { throw new AssertionError(failure); }
        }
        return types;
    }

    private static WireMessage message(long seq, String connectionId, String type, String commandId,
            String sessionId, Long generation, ObjectNode payload) {
        return new WireMessage(1, connectionId, seq, type, commandId, sessionId, generation, payload);
    }

    private static WireMessage hello(long seq, String buildId, String credential) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("protocolVersion", 1);
        payload.put("buildId", buildId);
        payload.put("credential", credential);
        payload.putArray("frontendCapabilities");
        return message(seq, "", "client.hello", null, null, null, payload);
    }

    private static WireMessage ready(long seq, String snapshotId, long generation, long lastSnapshotSeq) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("snapshotId", snapshotId);
        payload.put("generation", Long.toString(generation));
        payload.put("lastSnapshotSeq", Long.toString(lastSnapshotSeq));
        return message(seq, "conn-1", "session.ready", null, null, null, payload);
    }

    private static WireMessage submit(long seq, String connectionId) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("text", "hello");
        payload.put("clientSubmissionId", "sub-1");
        return message(seq, connectionId, "session.submit", "cmd-1", "session-1", 1L, payload);
    }

    @Test
    void handshakeOpensSessionOnlyAfterBootstrapAndReadyUsesTheSnapshotBarrier() throws Exception {
        ProtocolSession session = session();
        session.receive(hello(1, BUILD, CREDENTIAL));
        assertTrue(helloOpenedSession.get(), "the session is opened from onHello");
        assertEquals(ProtocolSession.State.HELLO, session.state());
        session.expectReady("snap-1", 1L);
        assertEquals(ProtocolSession.State.SYNC, session.state());
        session.receive(ready(2, "snap-1", 1L, 2L));
        assertEquals(ProtocolSession.State.READY, session.state());
        assertTrue(ready.get());
        // 重复 ready 不重放：回调只触发一次。
        session.receive(ready(3, "snap-1", 1L, 2L));
        assertTrue(ready.get());
        session.close();
        List<String> types = typesWritten();
        int helloIndex = types.indexOf("server.hello");
        int bootstrapIndex = types.indexOf("server.bootstrap");
        int snapshotIndex = types.indexOf("session.snapshot.begin");
        assertTrue(helloIndex >= 0 && bootstrapIndex > helloIndex && snapshotIndex > bootstrapIndex,
                "hello → bootstrap → snapshot must keep their queue order: " + types);
    }

    @Test
    void wrongBuildNeverSendsBootstrapOrOpensASession() {
        ProtocolSession session = session();
        ProtocolException failure = assertThrows(ProtocolException.class,
                () -> session.receive(hello(1, "other-build", CREDENTIAL)));
        assertEquals(ProtocolError.BUILD_MISMATCH, failure.error());
        assertFalse(helloOpenedSession.get());
        assertEquals(List.of(), typesWritten());
        session.close();
    }

    @Test
    void wrongCredentialIsRejectedBeforeAnyEvent() {
        ProtocolSession session = session();
        ProtocolException failure = assertThrows(ProtocolException.class,
                () -> session.receive(hello(1, BUILD, "wrong")));
        assertEquals(ProtocolError.UNAUTHORIZED, failure.error());
        assertEquals(List.of(), typesWritten());
        assertFalse(helloOpenedSession.get());
        session.close();
    }

    @Test
    void framesFromAnotherConnectionAreRejectedAfterHandshake() throws Exception {
        ProtocolSession session = session();
        session.receive(hello(1, BUILD, CREDENTIAL));
        session.expectReady("snap-1", 1L);
        session.receive(ready(2, "snap-1", 1L, 2L));
        ProtocolException failure = assertThrows(ProtocolException.class,
                () -> session.receive(submit(3, "WRONG-CONNECTION")));
        assertEquals(ProtocolError.UNAUTHORIZED, failure.error());
        assertEquals(List.of(), commands);
        session.close();
    }

    @Test
    void readyRequiresGenerationAndAnExistingSnapshotSequence() throws Exception {
        ProtocolSession session = session();
        session.receive(hello(1, BUILD, CREDENTIAL));
        session.expectReady("snap-1", 1L);
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("snapshotId", "snap-1");
        payload.put("lastSnapshotSeq", "2");
        ProtocolException missingGeneration = assertThrows(ProtocolException.class,
                () -> session.receive(message(2, "conn-1", "session.ready", null, null, null, payload)));
        assertEquals(ProtocolError.INVALID_MESSAGE, missingGeneration.error());
        session.close();
    }

    @Test
    void readyMustEchoTheSnapshotSourceSequence() throws Exception {
        ProtocolSession session = session();
        session.receive(hello(1, BUILD, CREDENTIAL));
        session.expectReady("snap-1", 1L, 7L);
        ProtocolException mismatch = assertThrows(ProtocolException.class,
                () -> session.receive(ready(2, "snap-1", 1L, 6L)));
        assertEquals(ProtocolError.INVALID_MESSAGE, mismatch.error());
        session.receive(ready(3, "snap-1", 1L, 7L));
        assertEquals(ProtocolSession.State.READY, session.state());
        session.close();
    }

    @Test
    void repeatedCommandIdIsNotExecutedTwice() throws Exception {
        ProtocolSession session = session();
        session.receive(hello(1, BUILD, CREDENTIAL));
        session.expectReady("snap-1", 1L);
        session.receive(ready(2, "snap-1", 1L, 2L));
        session.receive(submit(3, "conn-1"));
        session.receive(submit(3, "conn-1"));
        assertEquals(List.of("session.submit"), commands);
        session.close();
    }
}
