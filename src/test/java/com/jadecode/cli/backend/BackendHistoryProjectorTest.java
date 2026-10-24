package com.jadecode.cli.backend;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.session.history.JournalEntry;
import com.jadecode.session.history.RestoredSession;

/** 恢复投影只重建显示资料；通知不能伪装用户，压缩不能删除已经显示的转录。 */
class BackendHistoryProjectorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ID = "00000000-0000-0000-0000-000000000001";

    private JournalEntry entry(long seq, JournalEntry.Kind kind, String reason, Message... messages) {
        return new JournalEntry(1, ID, seq, "2026-10-25T00:00:00Z", kind,
                List.of(messages), JSON.createArrayNode(), reason);
    }

    private Message message(Role role, String text) {
        return new Message(role, List.of(new TextBlock(text)));
    }

    private List<BackendHistoryProjector.Entry> project(JournalEntry... entries) {
        return BackendHistoryProjector.project(new RestoredSession(null, List.of(), JSON.createArrayNode(),
                List.of(entries), List.of(), entries.length, 0, false, false));
    }

    @Test void notificationDoesNotOverwriteUserOrBecomeUserHistory() {
        var entries = project(entry(1, JournalEntry.Kind.APPEND, "user", message(Role.USER, "my request")),
                entry(2, JournalEntry.Kind.REPLACE_LAST, "notification", message(Role.USER, "my request\nbackground done")));
        assertEquals(List.of("USER", "NOTICE"), entries.stream().map(BackendHistoryProjector.Entry::kind).toList());
        assertEquals("my request", entries.getFirst().body());
        assertEquals("background done", entries.getLast().body());
    }

    @Test void compactionPreservesEarlierDisplayAndRollbackRemovesOnlyCurrentTurn() {
        var entries = project(entry(1, JournalEntry.Kind.APPEND, "user", message(Role.USER, "original")),
                entry(2, JournalEntry.Kind.APPEND, "reply", message(Role.ASSISTANT, "old reply")),
                entry(3, JournalEntry.Kind.REPLACE_HISTORY, "compact", message(Role.USER, "summary")),
                entry(4, JournalEntry.Kind.TURN_STARTED, "turn"),
                entry(5, JournalEntry.Kind.APPEND, "user", message(Role.USER, "rolled back")),
                entry(6, JournalEntry.Kind.REPLACE_HISTORY, "rollback", message(Role.USER, "summary")));
        assertEquals(List.of("original", "old reply", "Model history was compacted; earlier display is preserved."),
                entries.stream().map(BackendHistoryProjector.Entry::body).toList());
        assertTrue(entries.stream().allMatch(BackendHistoryProjector.Entry::historical));
        assertTrue(entries.stream().allMatch(value -> value.elapsedMs() == null));
    }
}
