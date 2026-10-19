package com.jadecode.cli.tui;

import com.jadecode.execution.ExecutionNotice;
import com.jadecode.tui.terminal.InputDecoder;

/** 工作线程交给界面线程的显示资料和恢复快照；不交出组件或运行时历史的写入权，不执行工具。 */
public sealed interface UiEvent {
    /** generation 是递增的运行时编号，用来丢弃旧运行时的迟到显示，不是权限或持久会话 ID。 */
    record Scoped(long generation, UiEvent event) implements UiEvent { }
    record SessionOpened(com.jadecode.cli.SessionHost.Opened opened) implements UiEvent { }
    record SessionList(java.util.List<com.jadecode.session.history.SessionMetadata> sessions) implements UiEvent { }
    record ResumeFailed(String message) implements UiEvent { }
    record Input(InputDecoder.Input input) implements UiEvent { }
    record Execution(ExecutionNotice notice) implements UiEvent { }
    record Delta(String request, String kind, String text) implements UiEvent { }
    record StreamEnd(String request, boolean failed) implements UiEvent { }
    record Message(String actor, String label, String text, boolean error) implements UiEvent { }
    record ApprovalRequested(String id, String actor, String tool, String arguments, String reason) implements UiEvent { }
    record ApprovalRemoved(String id) implements UiEvent { }
    record Stopped() implements UiEvent { }
}
