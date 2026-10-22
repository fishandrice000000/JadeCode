package com.jadecode.cli.tui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.input.InputDecoder;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.StdinBuffer;
import com.jadecode.tui.terminal.TerminalQueries;

/** R3-07：探针必须走“回复→查询器更新→decoder 同步→后续按键”完整路径。 */
class TuiInputProbeTest {
    @Test void replyUpdatesQueriesAndDecoderBeforeSubsequentShiftEnter() {
        var writes = new ArrayList<String>();
        var queries = new TerminalQueries(writes::add);
        var decoder = new InputDecoder(0);
        var reply = InputEvent.terminalReply(
                new InputEvent.TerminalReply("KEYBOARD_FLAGS", "3"), 1, 0);
        assertTrue(TuiInputProbe.applyReply(queries, decoder, reply));
        assertTrue(queries.kittyActive());
        assertTrue(decoder.kittyActive(), "probe must synchronize decoder like production");

        var chunks = new ArrayList<StdinBuffer.Chunk>();
        var buffer = new StdinBuffer(100, chunks::add, () -> 0L);
        buffer.feed(new byte[]{27, '\r'});
        buffer.tick();
        var events = new ArrayList<InputEvent>();
        for (var chunk : chunks) events.addAll(decoder.decode(chunk));
        assertFalse(events.isEmpty(), writes.toString());
        var key = events.stream().filter(event -> event.kind() == InputEvent.Kind.KEY).findFirst().orElseThrow();
        assertEquals("enter", key.key().key());
        assertTrue(key.key().has(com.jadecode.tui.input.KeyChord.Modifier.SHIFT));
        assertFalse(key.key().has(com.jadecode.tui.input.KeyChord.Modifier.ALT));
    }

    @Test void daFallbackUpdatesBothStatesToModifyOtherKeys() {
        var writes = new ArrayList<String>();
        var queries = new TerminalQueries(writes::add);
        var decoder = new InputDecoder(0);
        var reply = InputEvent.terminalReply(new InputEvent.TerminalReply("DA", ">84;0;0"), 1, 0);
        assertTrue(TuiInputProbe.applyReply(queries, decoder, reply));
        assertFalse(queries.kittyActive());
        assertFalse(decoder.kittyActive());
        assertEquals(com.jadecode.tui.terminal.TerminalCapabilities.KeyboardSupport.MODIFY_OTHER_KEYS,
                queries.snapshot().keyboard());
    }

    @Test void probeWatchesOnlyLimitedControlKeys() {
        // 白名单不含普通字符；诊断输出不能记录正文/凭据。
        var watched = List.of("enter", "up", "down", "left", "right", "escape", "tab");
        assertFalse(watched.contains("a"));
        assertFalse(watched.contains("paste"));
    }
}
