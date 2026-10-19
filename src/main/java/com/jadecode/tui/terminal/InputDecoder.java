package com.jadecode.tui.terminal;

import java.util.function.Consumer;

/**
 * 将可能拆包的终端字符重组为按键、整段粘贴和 SGR 鼠标点击。
 * 只由输入线程使用；不接触编辑器。粘贴里的回车始终属于正文，不能触发提交或审批。
 */
public final class InputDecoder {
    /** x/y 为从零开始的屏幕列／行；其它事件使用 -1。 */
    public record Input(String key, String text, int x, int y) {
        public static Input key(String key) { return new Input(key, "", -1, -1); }
        public static Input text(String text) { return new Input("TEXT", text, -1, -1); }
    }
    private final Consumer<Input> sink;
    private final StringBuilder sequence = new StringBuilder();
    private StringBuilder paste;
    private long escaped;
    private char high;
    public InputDecoder(Consumer<Input> sink) { this.sink = sink; }

    /** 收到一个 UTF-16 单位。转义序列未完整时继续缓存，绝不将其尾部误作用户文字。 */
    public void accept(int value) {
        char c = (char) value;
        if (paste != null) {
            paste.append(c);
            // 只检查固定长度尾部，不能每个粘贴字符都复制整段正文造成平方级耗时。
            if (paste.length() >= 6 && paste.substring(paste.length() - 6).equals("\033[201~")) {
                sink.accept(new Input("PASTE", paste.substring(0, paste.length() - 6), -1, -1)); paste = null;
            }
            return;
        }
        if (!sequence.isEmpty()) {
            sequence.append(c);
            String s = sequence.toString();
            if (s.equals("\033\r") || s.equals("\033\n")) { emit("NEWLINE"); return; }
            if (s.equals("\033[200~")) { sequence.setLength(0); paste = new StringBuilder(); return; }
            if (s.startsWith("\033[") && s.length() > 2 && c >= '@' && c <= '~') {
                String key = switch (s) {
                    case "\033[A" -> "UP"; case "\033[B" -> "DOWN";
                    case "\033[C" -> "RIGHT"; case "\033[D" -> "LEFT";
                    case "\033[H", "\033[1~" -> "HOME"; case "\033[F", "\033[4~" -> "END";
                    case "\033[3~" -> "DELETE"; case "\033[Z" -> "BACKTAB";
                    default -> "IGNORE";
                };
                if (s.matches("\\x1B\\[<\\d+;\\d+;\\d+M")) {
                    String[] parts = s.substring(3, s.length() - 1).split(";");
                    try {
                        if (Integer.parseInt(parts[0]) == 0) sink.accept(new Input("CLICK", "",
                                Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]) - 1));
                    } catch (NumberFormatException invalid) { /* 损坏的终端回复不能杀死唯一输入线程。 */ }
                } else if (!key.equals("IGNORE")) sink.accept(Input.key(key));
                sequence.setLength(0);
            } else if (s.length() == 2 && c != '[' && c != 'O') { sequence.setLength(0); }
            else if (s.startsWith("\033O") && s.length() == 3) {
                String key = switch (c) { case 'H' -> "HOME"; case 'F' -> "END"; default -> "IGNORE"; };
                if (!key.equals("IGNORE")) sink.accept(Input.key(key)); sequence.setLength(0);
            }
            return;
        }
        if (c == 27) { sequence.append(c); escaped = System.nanoTime(); return; }
        String key = switch (c) {
            case '\r', '\n' -> "ENTER"; case '\t' -> "TAB"; case 127, 8 -> "BACKSPACE";
            case 3 -> "CTRL_C"; case 4 -> "CTRL_D"; case 15 -> "CTRL_O"; case 20 -> "CTRL_T";
            case 1 -> "HOME"; case 5 -> "END"; case 2 -> "LEFT"; case 6 -> "RIGHT";
            case 21 -> "CLEAR"; case 11 -> "KILL_END"; case 23 -> "DELETE_WORD";
            case 26, 31 -> "UNDO"; default -> null;
        };
        if (key != null) { sink.accept(Input.key(key)); return; }
        if (Character.isHighSurrogate(c)) { high = c; return; }
        if (Character.isLowSurrogate(c) && high != 0) { sink.accept(Input.text("" + high + c)); high = 0; }
        else if (c >= 32) { high = 0; sink.accept(Input.text(String.valueOf(c))); }
    }

    /** 独立 Esc 等待一小段时间，以区分真实 Esc 与 SSH 拆开的方向键；不超时丢弃粘贴正文。 */
    public void tick() {
        long elapsed = System.nanoTime() - escaped;
        if (sequence.length() == 1 && elapsed > 120_000_000L) emit("ESC");
        else if (sequence.length() > 1 && elapsed > 2_000_000_000L) sequence.setLength(0);
    }
    private void emit(String key) { sequence.setLength(0); sink.accept(Input.key(key)); }
}
