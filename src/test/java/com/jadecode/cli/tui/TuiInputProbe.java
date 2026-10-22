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
            for (InputEvent event : decoder.decode(chunk)) report(event, queries, decoder);
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
        try {
            queries.start();
            System.err.println("TuiInputProbe ready: press Enter / Shift+Enter / Alt+Enter / arrows (15s)");
            Thread.sleep(15_000);
        } finally {
            // 异常/中断/正常结束都恢复终端，避免把备用屏或键盘 flags 留给 shell。
            terminal.close();
            ticker.interrupt();
        }
    }

    /**
     * 与生产同顺序处理终端回复：先让 TerminalQueries 消费协商结果，再同步 decoder 的
     * Kitty 兼容状态。只测 setter 会漏掉这条接线，正是旧探针误报的根因。
     * @return TerminalQueries 是否报告能力变化
     */
    static boolean applyReply(TerminalQueries queries, InputDecoder decoder, InputEvent event) {
        boolean changed = queries.accept(event.reply(), event.terminalEpoch());
        decoder.setKittyActive(queries.kittyActive());
        return changed;
    }

    private static void report(InputEvent event, TerminalQueries queries, InputDecoder decoder) {
        if (event.kind() == InputEvent.Kind.REPLY) {
            boolean changed = applyReply(queries, decoder, event);
            if (changed) System.err.println("REPLY kind=" + event.reply().kind() + " payload=" + event.reply().payload()
                    + " keyboard=" + queries.snapshot().keyboard() + " queriesKitty=" + queries.kittyActive()
                    + " decoderKitty=" + decoder.kittyActive());
            return;
        }
        if (event.kind() != InputEvent.Kind.KEY || event.key() == null) return;
        if (!WATCHED.contains(event.key().key())) return;
        // 两个状态必须同时打印；生产一致时应相等，便于现场区分协商/同步问题。
        System.err.println("KEY " + event.key().display() + " phase=" + event.key().phase()
                + " queriesKitty=" + queries.kittyActive() + " decoderKitty=" + decoder.kittyActive());
    }
}
