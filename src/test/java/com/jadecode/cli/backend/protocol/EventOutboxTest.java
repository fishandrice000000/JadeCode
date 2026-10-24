package com.jadecode.cli.backend.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/** 关闭必须排空已入队事件，而不是把 server.stopped 留在队列里。 */
class EventOutboxTest {
    @Test
    void closeWaitsForQueuedEvents() throws Exception {
        List<String> written = new CopyOnWriteArrayList<>();
        CountDownLatch firstWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        EventOutbox outbox = new EventOutbox((type, payload, sessionId, generation, commandId) -> {
            written.add(type);
            if (written.size() == 1) {
                firstWriteEntered.countDown();
                try {
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("interrupted", interrupted);
                }
            }
        }, failure -> { });
        ObjectNode payload = Json.mapper().createObjectNode();
        outbox.offer("message", payload, "", 0L, null);
        outbox.flushNow();
        assertTrue(firstWriteEntered.await(5, TimeUnit.SECONDS));
        outbox.offer("server.stopped", Json.mapper().createObjectNode(), "", 0L, null);
        Thread closer = Thread.ofVirtual().start(outbox::close);
        Thread.sleep(50);
        assertTrue(closer.isAlive(), "close must wait while the first write is still in flight");
        releaseFirst.countDown();
        closer.join(5000);
        assertFalse(closer.isAlive());
        assertEquals(List.of("message", "server.stopped"), written);
        assertNull(outbox.failure());
    }
}
