package com.jadecode.cli.tui;

import com.jadecode.execution.ExecutionNotice;
import com.jadecode.tui.terminal.InputDecoder;

/** 工作线程交给界面线程的不可变资料；事件不能直接持有组件、history 或执行工具。 */
public sealed interface UiEvent {
    record Input(InputDecoder.Input input) implements UiEvent { }
    record Execution(ExecutionNotice notice) implements UiEvent { }
    record Delta(String request, String kind, String text) implements UiEvent { }
    record StreamEnd(String request, boolean failed) implements UiEvent { }
    record Message(String actor, String label, String text, boolean error) implements UiEvent { }
    record ApprovalRequested(String id, String actor, String tool, String arguments, String reason) implements UiEvent { }
    record ApprovalRemoved(String id) implements UiEvent { }
    record Stopped() implements UiEvent { }
}
