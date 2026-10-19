package com.jadecode.cli.plain;

import java.util.function.Consumer;
import com.jadecode.execution.ExecutionObserver;
import com.jadecode.llm.ResponseStream;

/** run 的纯文本显示，不开终端原始模式、不重画输入，不是旧 REPL。 */
public final class PlainOutput implements ExecutionObserver {
    private final Consumer<String> output;
    public PlainOutput(Consumer<String> output) { this.output = output; }
    @Override public void accept(com.jadecode.execution.ExecutionNotice notice) {
        if (notice.kind() == com.jadecode.execution.ExecutionNotice.Kind.TOOL_START && notice.state().equals("RUNNING"))
            output.accept("[" + notice.actor() + "] [Tool] " + notice.name());
    }
    /** 保留已有最终回复与通知，不通过 ANSI 装饰；整条输出使用 PrintStream 的原有同步。 */
    public void message(String text) { output.accept(com.jadecode.tui.text.DisplayWidth.clean(text)); }
    /** run 采用整条最终输出，避免新增一套流式终端控制；模型请求仍使用原完整协议。 */
    public ResponseStream stream() { return ResponseStream.NONE; }
}
