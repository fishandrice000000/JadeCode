package com.jadecode.tui.input;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * 把 {@link StdinBuffer} 的完整块解释成按键、文字、鼠标或终端回复。
 *
 * <p>覆盖 legacy、xterm modifyOtherKeys 和 Kitty CSI-u 三种键盘协议；鼠标使用 SGR
 * (`CSI < b ; x ; y M/m`)。未知 CSI 只产生一个“未知键”事件，不执行任何动作，也不会把
 * 序列尾部当正文。终端回复（颜色、DA、键盘标记、单元格尺寸、Kitty 图形）单独分类，宿主
 * 必须交给 {@code TerminalQueries}/{@code TerminalCapabilities}，不能插入编辑器。</p>
 *
 * <p>释放/重复阶段保留在 {@link KeyChord.Phase} 中：审批与提交只接受 PRESS；legacy
 * 协议无法区分时按 PRESS 处理，由审批的输入水位额外防护。所有坐标从 0 开始。</p>
 */
public final class InputDecoder {
    private static final int SHIFT = 1, ALT = 2, CTRL = 4, SUPER = 8, META = 32;
    private long epoch;

    /** @param terminalEpoch 当前终端代次；暂停/resize 后由宿主更新。 */
    public InputDecoder(long terminalEpoch) { this.epoch = terminalEpoch; }

    /** 更新后续事件使用的终端代次。只能在输入线程/宿主串行点调用。 */
    public void setEpoch(long terminalEpoch) { this.epoch = terminalEpoch; }

    /** 解码一个完整块；未知/畸形序列不会导致异常。 */
    public List<InputEvent> decode(StdinBuffer.Chunk chunk) {
        var events = new ArrayList<InputEvent>();
        switch (chunk.kind()) {
            case TEXT -> decodeText(chunk, events);
            case PASTE -> events.add(InputEvent.paste(chunk.text(), chunk.sequence(), epoch));
            case ESCAPE -> events.add(InputEvent.key(KeyChord.of("escape"), chunk.sequence(), epoch));
            case SEQUENCE -> decodeSequence(chunk, events);
        }
        return events;
    }

    /**
     * 普通文字块可能包含 legacy 控制字节（Enter=\r、Ctrl+C=0x03、Tab=\t、Backspace=0x7f）。
     * 这些必须转成 Key 事件，不能作为正文插入编辑器；其余连续可打印文字合并成 TEXT。
     */
    private void decodeText(StdinBuffer.Chunk chunk, List<InputEvent> events) {
        String text = chunk.text();
        var printable = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int code = text.codePointAt(i);
            int width = Character.charCount(code);
            if (code < 0x20 || code == 0x7f) {
                if (!printable.isEmpty()) {
                    events.add(InputEvent.text(printable.toString(), chunk.sequence(), epoch));
                    printable.setLength(0);
                }
                events.add(InputEvent.key(chordForCharacter((char) code, EnumSet.noneOf(KeyChord.Modifier.class)),
                        chunk.sequence(), epoch));
            } else printable.appendCodePoint(code);
            i += width;
        }
        if (!printable.isEmpty()) events.add(InputEvent.text(printable.toString(), chunk.sequence(), epoch));
    }

    private void decodeSequence(StdinBuffer.Chunk chunk, List<InputEvent> events) {
        String data = chunk.text();
        long sequence = chunk.sequence();
        if (data.length() == 1 && data.charAt(0) == 0x1b) {
            events.add(InputEvent.key(KeyChord.of("escape"), sequence, epoch));
            return;
        }
        if (data.startsWith("\u001b[")) {
            decodeCsi(data, sequence, events);
            return;
        }
        if (data.startsWith("\u001b]")) {
            decodeOsc(data, sequence, events);
            return;
        }
        if (data.startsWith("\u001bP")) {
            events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("DCS",
                    stripTerminator(data.substring(2))), sequence, epoch));
            return;
        }
        if (data.startsWith("\u001b_")) {
            events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("APC",
                    stripTerminator(data.substring(2))), sequence, epoch));
            return;
        }
        if (data.startsWith("\u001bO") && data.length() >= 3) {
            String key = switch (data.charAt(2)) {
                case 'A' -> "up"; case 'B' -> "down"; case 'C' -> "right"; case 'D' -> "left";
                case 'H' -> "home"; case 'F' -> "end"; case 'P' -> "f1"; case 'Q' -> "f2";
                case 'R' -> "f3"; case 'S' -> "f4"; default -> "";
            };
            if (!key.isEmpty()) events.add(InputEvent.key(KeyChord.of(key), sequence, epoch));
            return;
        }
        // ESC + 单个字符：Alt/Meta 组合。legacy 终端无法提供 base-layout，保留该字符。
        if (data.length() == 2) {
            char c = data.charAt(1);
            events.add(InputEvent.key(chordForCharacter(c, EnumSet.of(KeyChord.Modifier.ALT)), sequence, epoch));
            return;
        }
        events.add(InputEvent.key(KeyChord.of("unknown"), sequence, epoch));
    }

    private void decodeCsi(String data, long sequence, List<InputEvent> events) {
        int finalIndex = -1;
        for (int i = 2; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c >= 0x40 && c <= 0x7e) { finalIndex = i; break; }
        }
        if (finalIndex < 0) { events.add(InputEvent.key(KeyChord.of("unknown"), sequence, epoch)); return; }
        char finalChar = data.charAt(finalIndex);
        String body = data.substring(2, finalIndex);
        if (body.startsWith("?")) {
            String payload = body.substring(1);
            if (finalChar == 'u') {
                events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("KEYBOARD_FLAGS", payload), sequence, epoch));
            } else if (finalChar == 'c') {
                events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("DA", payload), sequence, epoch));
            } else if (finalChar == 'n' && payload.startsWith("997;")) {
                events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("COLOR_SCHEME", payload), sequence, epoch));
            } else {
                events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("CSI", data), sequence, epoch));
            }
            return;
        }
        if (body.startsWith("<") && (finalChar == 'M' || finalChar == 'm')) {
            events.add(mouse(body.substring(1), finalChar == 'M', sequence));
            return;
        }
        if (finalChar == 'u') { decodeCsiU(body, sequence, events); return; }
        if (finalChar == '~') { decodeTilde(body, sequence, events); return; }
        if (finalChar == 't') {
            // CSI 6 ; height ; width t —— 终端单元格像素尺寸回复。
            String[] parts = body.split(";");
            if (parts.length >= 3 && parts[0].equals("6")) {
                events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("CELL_SIZE",
                        parts[1] + ";" + parts[2]), sequence, epoch));
            } else events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("CSI", data), sequence, epoch));
            return;
        }
        if (finalChar == 'c') {
            events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("DA", body), sequence, epoch));
            return;
        }
        // 方向/Home/End/Backtab，可带修饰参数，如 1;5A。
        String key = switch (finalChar) {
            case 'A' -> "up"; case 'B' -> "down"; case 'C' -> "right"; case 'D' -> "left";
            case 'H' -> "home"; case 'F' -> "end"; case 'Z' -> "tab"; case 'P' -> "f1";
            case 'Q' -> "f2"; case 'R' -> "f3"; case 'S' -> "f4"; default -> "";
        };
        if (key.isEmpty()) { events.add(InputEvent.key(KeyChord.of("unknown"), sequence, epoch)); return; }
        EnumSet<KeyChord.Modifier> modifiers = finalChar == 'Z'
                ? EnumSet.of(KeyChord.Modifier.SHIFT) : modifiers(parameter(body, 1), false);
        events.add(InputEvent.key(new KeyChord(key, modifiers, KeyChord.Phase.PRESS), sequence, epoch));
    }

    private void decodeTilde(String body, long sequence, List<InputEvent> events) {
        String[] parts = body.split(";");
        if (parts.length == 3 && parts[0].equals("27")) {
            // modifyOtherKeys: CSI 27 ; modifier ; code ~
            int code;
            try { code = Integer.parseInt(parts[2]); } catch (NumberFormatException invalid) { code = -1; }
            EnumSet<KeyChord.Modifier> modifiers = modifiers(parts[1], false);
            events.add(InputEvent.key(chordForCode(code, modifiers), sequence, epoch));
            return;
        }
        int code;
        try { code = Integer.parseInt(parts[0]); } catch (NumberFormatException invalid) { code = -1; }
        EnumSet<KeyChord.Modifier> modifiers = parts.length > 1 ? modifiers(parts[1], false) : EnumSet.noneOf(KeyChord.Modifier.class);
        String key = switch (code) {
            case 1, 7 -> "home"; case 2 -> "insert"; case 3 -> "delete"; case 4, 8 -> "end";
            case 5 -> "pageup"; case 6 -> "pagedown"; case 11, 12, 13, 14 -> "f" + (code - 10);
            case 15 -> "f5"; case 17 -> "f6"; case 18 -> "f7"; case 19 -> "f8"; case 20 -> "f9";
            case 21 -> "f10"; case 23 -> "f11"; case 24 -> "f12";
            case 25 -> "f13"; case 26 -> "f14"; case 28 -> "f15"; case 29 -> "f16";
            default -> "";
        };
        if (key.isEmpty()) { events.add(InputEvent.key(KeyChord.of("unknown"), sequence, epoch)); return; }
        events.add(InputEvent.key(new KeyChord(key, modifiers, KeyChord.Phase.PRESS), sequence, epoch));
    }

    private void decodeCsiU(String body, long sequence, List<InputEvent> events) {
        String[] parts = body.split(";");
        if (parts.length == 0 || parts[0].isBlank()) {
            events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("KEYBOARD_FLAGS", body), sequence, epoch));
            return;
        }
        int code;
        try { code = Integer.parseInt(parts[0]); } catch (NumberFormatException invalid) { code = -1; }
        EnumSet<KeyChord.Modifier> modifiers = parts.length > 1
                ? modifiers(parts[1].split(":")[0], true) : EnumSet.noneOf(KeyChord.Modifier.class);
        KeyChord.Phase phase = KeyChord.Phase.PRESS;
        if (parts.length > 1 && parts[1].contains(":")) {
            String event = parts[1].substring(parts[1].indexOf(':') + 1);
            phase = switch (event) { case "2" -> KeyChord.Phase.REPEAT; case "3" -> KeyChord.Phase.RELEASE; default -> KeyChord.Phase.PRESS; };
        }
        // Kitty 功能键使用私有码点；Printable code 按字符键处理。
        String key = switch (code) {
            case 57344 -> "escape"; case 57345 -> "enter"; case 57346 -> "tab"; case 57347 -> "backspace";
            case 57348 -> "insert"; case 57349 -> "delete"; case 57350 -> "left"; case 57351 -> "right";
            case 57352 -> "up"; case 57353 -> "down"; case 57354 -> "pageup"; case 57355 -> "pagedown";
            case 57356 -> "home"; case 57357 -> "end"; case 57358 -> "capslock"; case 57359 -> "scrolllock";
            case 57360 -> "numlock"; case 57361 -> "printscreen"; case 57362 -> "pause"; case 57363 -> "menu";
            case 57364 -> "f1"; case 57365 -> "f2"; case 57366 -> "f3"; case 57367 -> "f4";
            case 57368 -> "f5"; case 57369 -> "f6"; case 57370 -> "f7"; case 57371 -> "f8";
            case 57372 -> "f9"; case 57373 -> "f10"; case 57374 -> "f11"; case 57375 -> "f12";
            case 57409, 57410 -> "shift"; case 57411, 57412 -> "ctrl"; case 57413, 57414 -> "alt";
            case 57415, 57416 -> "super"; case 57417 -> "hyper"; case 57418 -> "meta";
            default -> code > 0 && code <= 0x10ffff ? new String(Character.toChars(code)).toLowerCase(Locale.ROOT) : "";
        };
        if (key.isEmpty()) { events.add(InputEvent.key(KeyChord.of("unknown"), sequence, epoch)); return; }
        // CSI-u 的 Enter/Tab/Escape/Backspace 码点可能以控制字符返回；规范化为动作名并保留修饰。
        if (key.length() == 1) {
            char c = key.charAt(0);
            if (c < 0x20 || c == 0x7f) {
                KeyChord normalized = chordForCharacter(c, modifiers);
                events.add(InputEvent.key(new KeyChord(normalized.key(), modifiers, phase), sequence, epoch));
                return;
            }
        }
        events.add(InputEvent.key(new KeyChord(key, modifiers, phase), sequence, epoch));
    }

    private InputEvent mouse(String body, boolean press, long sequence) {
        String[] parts = body.split(";");
        int button = 0, x = 0, y = 0;
        try {
            if (parts.length >= 3) { button = Integer.parseInt(parts[0]); x = Integer.parseInt(parts[1]) - 1; y = Integer.parseInt(parts[2]) - 1; }
        } catch (NumberFormatException ignored) { }
        boolean drag = (button & 32) != 0;
        boolean move = (button & 32) != 0 && (button & 3) == 3;
        var action = !press ? InputEvent.Mouse.Action.RELEASE
                : move ? InputEvent.Mouse.Action.MOVE : drag ? InputEvent.Mouse.Action.DRAG : InputEvent.Mouse.Action.PRESS;
        int base = button & ~(4 | 8 | 16 | 32);
        return InputEvent.mouse(new InputEvent.Mouse(base, action, Math.max(0, x), Math.max(0, y),
                (button & 16) != 0, (button & 8) != 0, (button & 4) != 0), sequence, epoch);
    }

    /** OSC 回复：颜色（10/11/4）、剪贴板（52）、程序状态（7501）。 */
    private void decodeOsc(String data, long sequence, List<InputEvent> events) {
        String payload = stripTerminator(data.substring(2));
        if (payload.matches("1[01];.*") || payload.matches("4;\\d{1,3};.*")) {
            events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("COLOR", payload), sequence, epoch));
        } else if (payload.startsWith("52;")) {
            events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("CLIPBOARD", payload), sequence, epoch));
        } else if (payload.startsWith("7501;")) {
            events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("PROGRAM_STATUS", payload), sequence, epoch));
        } else {
            events.add(InputEvent.terminalReply(new InputEvent.TerminalReply("OSC", payload), sequence, epoch));
        }
    }

    private static String stripTerminator(String value) {
        if (value.endsWith("\u0007")) return value.substring(0, value.length() - 1);
        if (value.endsWith("\u001b\\")) return value.substring(0, value.length() - 2);
        return value;
    }

    private static String parameter(String body, int index) {
        String[] parts = body.split(";");
        return index < parts.length ? parts[index].split(":")[0] : "";
    }

    /** xterm 参数 2-8 / Kitty 位掩码 1+N 转换为修饰集合。 */
    private static EnumSet<KeyChord.Modifier> modifiers(String parameter, boolean kitty) {
        var result = EnumSet.noneOf(KeyChord.Modifier.class);
        if (parameter == null || parameter.isBlank()) return result;
        int value;
        try { value = Integer.parseInt(parameter); } catch (NumberFormatException invalid) { return result; }
        int mask = kitty ? Math.max(0, value - 1) : switch (value) {
            case 2 -> SHIFT; case 3 -> ALT; case 4 -> SHIFT | ALT; case 5 -> CTRL;
            case 6 -> SHIFT | CTRL; case 7 -> ALT | CTRL; case 8 -> SHIFT | ALT | CTRL;
            default -> 0;
        };
        if ((mask & SHIFT) != 0) result.add(KeyChord.Modifier.SHIFT);
        if ((mask & ALT) != 0) result.add(KeyChord.Modifier.ALT);
        if ((mask & CTRL) != 0) result.add(KeyChord.Modifier.CTRL);
        if ((mask & SUPER) != 0) result.add(KeyChord.Modifier.SUPER);
        if ((mask & META) != 0) result.add(KeyChord.Modifier.META);
        return result;
    }

    /** 由 Unicode 码点构造普通字符键；控制码点转换成对应 Ctrl+字母。 */
    private static KeyChord chordForCode(int code, EnumSet<KeyChord.Modifier> modifiers) {
        return switch (code) {
            case 13 -> new KeyChord("enter", modifiers, KeyChord.Phase.PRESS);
            case 9 -> new KeyChord("tab", modifiers, KeyChord.Phase.PRESS);
            case 27 -> new KeyChord("escape", modifiers, KeyChord.Phase.PRESS);
            case 127 -> new KeyChord("backspace", modifiers, KeyChord.Phase.PRESS);
            case -1, 0 -> KeyChord.of("unknown");
            default -> code >= 32 && code <= 0x10ffff
                    ? new KeyChord(new String(Character.toChars(code)).toLowerCase(Locale.ROOT), modifiers, KeyChord.Phase.PRESS)
                    : chordForCharacter((char) code, modifiers);
        };
    }

    /** legacy 控制字节到 Ctrl+字母/特殊键。 */
    private static KeyChord chordForCharacter(char c, EnumSet<KeyChord.Modifier> modifiers) {
        return switch (c) {
            // Enter 在终端里是 CR；LF 是 Ctrl+J，必须映射为换行键而不是第二个 Enter。
            case '\r' -> new KeyChord("enter", modifiers, KeyChord.Phase.PRESS);
            case '\n' -> {
                var withCtrl = EnumSet.copyOf(modifiers); withCtrl.add(KeyChord.Modifier.CTRL);
                yield new KeyChord("j", withCtrl, KeyChord.Phase.PRESS);
            }
            case '\t' -> new KeyChord("tab", modifiers, KeyChord.Phase.PRESS);
            case 0x7f, 0x08 -> new KeyChord("backspace", modifiers, KeyChord.Phase.PRESS);
            case 27 -> new KeyChord("escape", modifiers, KeyChord.Phase.PRESS);
            default -> {
                if (c < 0x20) {
                    var withCtrl = EnumSet.copyOf(modifiers); withCtrl.add(KeyChord.Modifier.CTRL);
                    yield new KeyChord(String.valueOf((char) ('a' + c - 1)), withCtrl, KeyChord.Phase.PRESS);
                }
                yield new KeyChord(String.valueOf(c).toLowerCase(Locale.ROOT), modifiers, KeyChord.Phase.PRESS);
            }
        };
    }
}
