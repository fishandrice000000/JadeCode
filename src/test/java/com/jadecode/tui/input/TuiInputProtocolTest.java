package com.jadecode.tui.input;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 终端字节组装/键协议/鼠标/回复/粘贴的单元测试；不依赖真实终端。 */
class TuiInputProtocolTest {
    private final AtomicLong clock = new AtomicLong(1);
    private final List<StdinBuffer.Chunk> chunks = new ArrayList<>();
    private final StdinBuffer buffer = new StdinBuffer(10, chunks::add, clock::get);

    private List<InputEvent> decode(String text, int... cuts) {
        chunks.clear();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int start = 0;
        for (int cut : cuts) {
            buffer.feed(java.util.Arrays.copyOfRange(bytes, start, cut));
            start = cut;
        }
        buffer.feed(java.util.Arrays.copyOfRange(bytes, start, bytes.length));
        var decoder = new InputDecoder(7);
        var result = new ArrayList<InputEvent>();
        for (var chunk : chunks) result.addAll(decoder.decode(chunk));
        return result;
    }

    @Test void textIsDecodedOnceEvenWhenUtf8Splits() {
        String text = "a中👩‍👩‍👧‍👦";
        for (int cut = 1; cut < text.getBytes(StandardCharsets.UTF_8).length; cut++) {
            var events = decode(text, cut);
            StringBuilder joined = new StringBuilder();
            for (var event : events) if (event.kind() == InputEvent.Kind.TEXT) joined.append(event.text());
            assertEquals(text, joined.toString(), "cut=" + cut);
        }
    }

    @Test void escapeSequencesAreNeverLeakedAsTextAtAnySplit() {
        String sequence = "\u001b[1;5D";
        for (int cut = 1; cut < sequence.length(); cut++) {
            var events = decode(sequence, cut);
            assertEquals(1, events.size(), "cut=" + cut);
            assertEquals(InputEvent.Kind.KEY, events.getFirst().kind());
            assertEquals("left", events.getFirst().key().key());
            assertTrue(events.getFirst().key().has(KeyChord.Modifier.CTRL));
        }
    }

    @Test void bracketedPasteIsOneBlockAndItsEnterDoesNotSubmit() {
        String paste = "\u001b[200~line1\r\nline2\n\u001b[201~";
        var events = decode(paste);
        assertEquals(1, events.size());
        assertEquals(InputEvent.Kind.PASTE, events.getFirst().kind());
        assertEquals("line1\r\nline2\n", events.getFirst().text());
    }

    @Test void csiUAndModifyOtherKeysCarryModifiersAndPhase() {
        var shiftEnter = decode("\u001b[13;2u").getFirst();
        assertEquals("enter", shiftEnter.key().key());
        assertTrue(shiftEnter.key().has(KeyChord.Modifier.SHIFT));
        var release = decode("\u001b[13;1:3u").getFirst();
        assertEquals(KeyChord.Phase.RELEASE, release.key().phase());
        assertFalse(release.actionableKey());
        var ctrlEnter = decode("\u001b[27;5;13~").getFirst();
        assertEquals("enter", ctrlEnter.key().key());
        assertTrue(ctrlEnter.key().has(KeyChord.Modifier.CTRL));
        var ctrlJ = decode("\n").getFirst();
        assertEquals("j", ctrlJ.key().key());
        assertTrue(ctrlJ.key().has(KeyChord.Modifier.CTRL));
    }

    @Test void sgrMouseWheelAndReleaseAreParsedWithZeroBasedCoordinates() {
        var wheel = decode("\u001b[<64;10;5M").getFirst();
        assertEquals(InputEvent.Kind.MOUSE, wheel.kind());
        assertEquals(9, wheel.mouse().x());
        assertEquals(4, wheel.mouse().y());
        assertEquals(-1, wheel.mouse().wheelDirection());
        var release = decode("\u001b[<0;3;4m").getFirst();
        assertEquals(InputEvent.Mouse.Action.RELEASE, release.mouse().action());
    }

    @Test void terminalRepliesAreNotInsertedAsText() {
        var color = decode("\u001b]11;rgb:0000/1111/2222\u0007").getFirst();
        assertEquals(InputEvent.Kind.REPLY, color.kind());
        assertEquals("COLOR", color.reply().kind());
        var keyboard = decode("\u001b[?3u").getFirst();
        assertEquals("KEYBOARD_FLAGS", keyboard.reply().kind());
        var cell = decode("\u001b[6;18;9t").getFirst();
        assertEquals("CELL_SIZE", cell.reply().kind());
        assertEquals("18;9", cell.reply().payload());
    }

    @Test void isolatedEscapeOnlyFiresAfterTimeout() {
        chunks.clear();
        buffer.feed(new byte[]{27});
        assertTrue(chunks.isEmpty());
        clock.addAndGet(20_000_000L);
        buffer.tick();
        assertEquals(1, chunks.size());
        assertEquals(StdinBuffer.Kind.ESCAPE, chunks.getFirst().kind());
    }

    @Test void pasteMarkerFormatMatchesFixedPi() {
        var editor = new com.jadecode.tui.components.EditorBuffer();
        String big = "x".repeat(1500);
        editor.insert(big, true);
        assertTrue(editor.text().startsWith("[paste #1 1500 chars]"), editor.text());
        assertEquals(big, editor.expandedText());
        assertEquals(big, editor.submit());
    }

    @Test void ctrlAndAltSequencesRemainDistinct() {
        var altB = decode("\u001bb").getFirst();
        assertEquals("b", altB.key().key());
        assertTrue(altB.key().has(KeyChord.Modifier.ALT));
        var ctrlLeft = decode("\u001b[1;5D").getFirst();
        assertTrue(ctrlLeft.key().has(KeyChord.Modifier.CTRL));
        assertEquals("left", ctrlLeft.key().key());
    }
}
