package com.jadecode.cli.tui.bridge;

import java.util.function.Consumer;
import com.jadecode.cli.tui.UiEvent;
import com.jadecode.execution.ExecutionNotice;
import com.jadecode.execution.ExecutionObserver;

/** 将真实运行观察转成 UI 事件；旧整条输出只解析程序前缀一次，不解析回复内部标签。 */
public final class ExecutionUiBridge implements ExecutionObserver {
    private final Consumer<UiEvent> sink;
    public ExecutionUiBridge(Consumer<UiEvent> sink) { this.sink = sink; }
    @Override public void accept(ExecutionNotice notice) { sink.accept(new UiEvent.Execution(notice)); }
    /** 工厂现有输出适配。仅这里认识旧标签，新的工具生命周期不靠它猜测。 */
    public void message(String value, boolean error) {
        String actor = "System", body = value, label = error ? "Error" : "Notice";
        if (body.startsWith("[Leader] ")) { actor = "leader"; body = body.substring(9); }
        else if (body.startsWith("[Leader]")) { actor = "leader"; body = body.substring(8); }
        else if (body.startsWith("[Teammate ")) {
            int end = body.indexOf("] ");
            if (end > 0) { actor = body.substring(10, end); body = body.substring(end + 2); }
        }
        if (body.startsWith("[")) {
            int end = body.indexOf(']');
            if (end > 0) { label = body.substring(1, end); body = body.substring(end + 1); if (body.startsWith(" ")) body = body.substring(1); }
        }
        // 最终非流式回复由 Session 输出，没有 Leader 前缀；它仍属于 Leader，不能显示成 System。
        if (actor.equals("System") && label.equals("Reply")) actor = "leader";
        sink.accept(new UiEvent.Message(actor, label, body, error));
    }
}
