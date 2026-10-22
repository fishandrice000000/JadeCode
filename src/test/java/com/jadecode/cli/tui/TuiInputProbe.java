package com.jadecode.cli.tui;

import java.util.List;
import java.util.Set;
import com.jadecode.tui.input.InputDecoder;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.StdinBuffer;
import com.jadecode.tui.terminal.TerminalEvent;
import com.jadecode.tui.terminal.TerminalQueries;
import com.jadecode.tui.terminal.TerminalSession;

/**
 * 受控输入诊断（测试作用域，不进入生产 jar）：只打印指定测试键的协商状态与标准化按键，
 * 不记录普通正文/凭据。用法：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes:&lt;deps&gt; com.jadecode.cli.tui.TuiInputProbe
 * 然后在目标终端按 Enter、Shift+Enter、Alt+Enter、方向键，15 秒后自动退出。
 */
public final class TuiInputProbe {
    private static final Set<String> WATCHED = Set.of("enter", "up", "down", "left", "right", "escape", "tab");

    public static void main(String[] args) throws Exception {
        var terminal = new TerminalSession();
        var queries = new TerminalQueries(text -> terminal.write(text));
        var decoder = new InputDecoder(terminal.epoch());
        var buffer = new StdinBuffer(100, chunk -> {
            for (InputEvent event : decoder.decode(chunk)) report(event, queries);
        }, System::nanoTime);
        terminal.start(event -> {
            if (event instanceof TerminalEvent.InputChunk chunk) {
                decoder.setEpoch(event.terminalEpoch());
                buffer.feed(chunk.bytes());
            } else if (event instanceof TerminalEvent.Resize resize) {
                terminal.bumpEpoch();
            }
        });
        var ticker = Thread.ofVirtual().name("probe-tick").start(() -> {
            while (terminal.state() == TerminalSession.State.ACTIVE) {
                buffer.tick();
                try { Thread.sleep(10); } catch (InterruptedException stop) { return; }
            }
        });
        queries.start();
        System.err.println("TuiInputProbe ready: press Enter / Shift+Enter / Alt+Enter / arrows (15s)");
        Thread.sleep(15_000);
        terminal.close();
        ticker.interrupt();
    }

    private static void report(InputEvent event, TerminalQueries queries) {
        if (event.kind() == InputEvent.Kind.REPLY) {
            boolean changed = queries.accept(event.reply(), event.terminalEpoch());
            if (changed) System.err.println("REPLY kind=" + event.reply().kind() + " payload=" + event.reply().payload()
                    + " keyboard=" + queries.snapshot().keyboard() + " kitty=" + queries.kittyActive());
            return;
        }
        if (event.kind() != InputEvent.Kind.KEY || event.key() == null) return;
        if (!WATCHED.contains(event.key().key())) return;
        System.err.println("KEY " + event.key().display() + " phase=" + event.key().phase()
                + " kitty=" + queries.kittyActive());
    }
}
