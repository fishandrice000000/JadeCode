package com.jadecode.tui.terminal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.text.Color;

/** 终端协商：键盘 flags/DA 回退/OSC10/11 两段解析/退出退栈。 */
class TuiTerminalQueriesTest {
    private final List<String> output = new ArrayList<>();
    private final TerminalQueries queries = new TerminalQueries(output::add);

    private static InputEvent.TerminalReply reply(String kind, String payload) {
        return new InputEvent.TerminalReply(kind, payload);
    }

    @Test void startPushesDesiredFlagsBeforeQuerying() {
        queries.start();
        String sent = String.join("", output);
        assertTrue(sent.contains("\u001b[>7u"), sent);
        assertTrue(sent.contains("\u001b[?u"));
        assertTrue(sent.contains("\u001b[c"));
        assertTrue(sent.contains("\u001b]10;?"));
    }

    @Test void normalizedKeyboardFlagsActivateKitty() {
        queries.start();
        assertTrue(queries.accept(reply("KEYBOARD_FLAGS", "3"), 0));
        assertTrue(queries.kittyActive());
        assertEquals(TerminalCapabilities.KeyboardSupport.KITTY, queries.snapshot().keyboard());
    }

    @Test void deviceAttributesWithoutKittyEnablesModifyOtherKeysFallback() {
        queries.start();
        queries.accept(reply("DA", "?1;2c"), 0);
        assertEquals(TerminalCapabilities.KeyboardSupport.MODIFY_OTHER_KEYS, queries.snapshot().keyboard());
        assertTrue(String.join("", output).contains("\u001b[>4;2m"));
    }

    @Test void lateKittyFlagsOverrideFallbackAndDisableRestoresBoth() {
        queries.start();
        queries.accept(reply("DA", "?1;2c"), 0);
        queries.accept(reply("KEYBOARD_FLAGS", "3"), 0);
        assertTrue(queries.kittyActive());
        output.clear();
        queries.disable();
        String sent = String.join("", output);
        assertTrue(sent.contains("\u001b[<u"), sent);
        assertTrue(sent.contains("\u001b[>4;0m"), sent);
        assertFalse(queries.kittyActive());
    }

    @Test void oscForegroundAndBackgroundUseTwoFieldPayloads() {
        queries.start();
        assertTrue(queries.accept(reply("COLOR", "11;rgb:0000/0000/0000"), 0));
        assertTrue(queries.accept(reply("COLOR", "10;#ffffff"), 0));
        assertEquals(Color.rgb(0, 0, 0), queries.snapshot().background());
        assertEquals(Color.rgb(255, 255, 255), queries.snapshot().foreground());
        assertTrue(queries.snapshot().colorReplyComplete());
        assertEquals(Boolean.TRUE, queries.snapshot().darkBackground());
    }

    @Test void oscPaletteUsesThreeFields() {
        queries.start();
        assertTrue(queries.accept(reply("COLOR", "4;5;rgb:ffff/0000/ffff"), 0));
        assertEquals(Color.rgb(255, 0, 255), queries.snapshot().ansi().get(5));
    }
}
