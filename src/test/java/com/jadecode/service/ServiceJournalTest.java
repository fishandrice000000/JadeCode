package com.jadecode.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.util.Json;

/** 取消等待线程不应关闭日志写者，也不能在下一条之前留下半行。 */
class ServiceJournalTest {
    @TempDir Path temporary;
    @Test void cancellationPreservesCompleteRecordsAndTheCallersInterrupt()throws Exception {
        Path path=temporary.resolve("audit.jsonl");
        try(var journal=new ServiceJournal(path)){
            var error=new AtomicReference<Throwable>();var retained=new AtomicBoolean();
            Thread caller=Thread.ofVirtual().start(()->{
                Thread.currentThread().interrupt();
                try{journal.append("first",Json.mapper().createObjectNode().put("fixture",true));}
                catch(Throwable failure){error.set(failure);}
                finally{retained.set(Thread.currentThread().isInterrupted());}
            });
            caller.join(2000);assertFalse(caller.isAlive());assertNull(error.get());assertTrue(retained.get());
            journal.append("second",Json.mapper().createObjectNode().put("fixture",false));
        }
        var lines=Files.readAllLines(path);assertEquals(2,lines.size());
        assertEquals("first",Json.mapper().readTree(lines.get(0)).path("kind").asText());
        assertEquals("second",Json.mapper().readTree(lines.get(1)).path("kind").asText());
    }
}
