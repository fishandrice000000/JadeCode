package com.jadecode.cli.tui;

import java.util.List;
import java.util.function.Consumer;
import com.jadecode.cli.tui.bridge.ApprovalBroker;
import com.jadecode.session.SessionRuntime;
import com.jadecode.tui.components.SelectList;
import com.jadecode.tui.core.InlineRenderer;
import com.jadecode.tui.terminal.InputDecoder.Input;

/**
 * 在界面线程处理输入与显示事件。只通过现有 submitUser／promoteUserBuffer 发出用户意图，
 * 不执行工具、不改模型历史。磁盘偏好保存和关闭业务资源都交给其它线程。
 */
public final class TuiController {
    private final TuiState state;
    private final SessionRuntime runtime;
    private final ApprovalBroker approvals;
    private final InlineRenderer renderer;
    private final TuiPreferences preferences;
    private final Consumer<UiEvent> events;
    private final Runnable shutdown;
    private long ctrlC;
    private String originalTheme;
    public TuiController(TuiState state, SessionRuntime runtime, ApprovalBroker approvals,
            InlineRenderer renderer, TuiPreferences preferences, Consumer<UiEvent> events, Runnable shutdown) {
        this.state = state; this.runtime = runtime; this.approvals = approvals; this.renderer = renderer;
        this.preferences = preferences; this.events = events; this.shutdown = shutdown;
    }

    /** 应用事件；返回 false 表示清理完成，可以结束界面循环。 */
    public boolean accept(UiEvent event) {
        switch (event) {
            case UiEvent.Input input -> input(input.input());
            case UiEvent.Execution execution -> state.execution(execution.notice());
            case UiEvent.Delta delta -> state.delta(delta);
            case UiEvent.StreamEnd end -> state.end(end);
            case UiEvent.Message message -> state.add(message.actor(),
                    message.label().equals("Error") || message.label().equals("Rejected") ? "Error" : message.label(), message.label(), message.text());
            case UiEvent.ApprovalRequested request -> {
                if (state.closing) approvals.answer(request.id(), false);
                else { if (state.approvals.isEmpty()) state.approvalChoice = -1; state.approvals.addLast(request); }
            }
            case UiEvent.ApprovalRemoved removed -> removeApproval(removed.id());
            case UiEvent.Stopped stopped -> { return false; }
        }
        return true;
    }

    /**
     * 处理一个已解码的输入。先处理整会话退出，再只让当前可操作的区域接收按键。
     * 同一个 Enter 不能既批准操作又提交草稿；PASTE 是一整块正文，不拆成许多 Enter。
     * 本方法只修改界面状态或调用现有 Session 方法，不等待模型和工具返回。
     */
    private void input(Input input) {
        String key = input.key();
        if (state.closing) return;
        if (key.equals("EOF")) { close(); return; }
        if (key.equals("CTRL_C")) {
            long now = System.nanoTime();
            if (ctrlC != 0 && now - ctrlC < 500_000_000L) { close(); return; }
            ctrlC = now;
            if (state.approvals.isEmpty() && state.selector == null) state.editor.clear();
            state.hint = "再次 Ctrl+C（500ms 内）退出整个会话；Esc 不取消 Agent"; return;
        }
        // 审批盖住选择器，选择器盖住编辑器：被盖住的草稿仍保存，但不会收到这一按键。
        if (!state.approvals.isEmpty()) { approval(input); return; }
        if (state.selector != null) { selector(input); return; }
        switch (key) {
            case "TEXT" -> state.editor.insert(input.text(), false);
            case "PASTE" -> state.editor.insert(input.text(), true);
            case "ENTER" -> submit();
            case "NEWLINE" -> state.editor.insert("\n", false);
            case "BACKSPACE" -> state.editor.delete(true);
            case "DELETE" -> state.editor.delete(false);
            case "CTRL_D" -> { if (state.editor.empty()) close(); else state.editor.delete(false); }
            case "LEFT" -> state.editor.move(-1);
            case "RIGHT" -> state.editor.move(1);
            case "UP" -> state.editor.vertical(-1);
            case "DOWN" -> state.editor.vertical(1);
            case "HOME" -> state.editor.home();
            case "END" -> state.editor.end();
            case "CLEAR" -> state.editor.clear();
            case "KILL_END" -> state.editor.killEnd();
            case "DELETE_WORD" -> state.editor.deleteWord();
            case "UNDO" -> state.editor.undo();
            case "CTRL_O" -> {
                state.toolsExpanded = !state.toolsExpanded;
                state.tools.values().forEach(e -> { e.expanded = state.toolsExpanded; e.version++; });
            }
            case "CTRL_T" -> { state.thinkingVisible = !state.thinkingVisible; state.entries.stream().filter(e -> e.kind.equals("Thinking")).forEach(e -> e.expanded = false); }
            case "CLICK" -> click(renderer.hit(input.x(), input.y()));
            case "TAB" -> { if (state.editor.text().startsWith("/")) { state.selectorKind = "Commands"; state.selector = new SelectList(List.of("/help", "/theme")); state.selector.filter(state.editor.text()); } }
            case "ESC" -> state.hint = "Esc 仅关闭选择界面；本版不支持单轮取消";
            default -> { }
        }
    }

    /**
     * 将编辑器原文交给会话，并立即返回。忙时合并、排队和插入升级由 Session 决定。
     * 空提交只升级同一业务缓冲；前端命令不发给模型；q／exit 则是普通提示词。
     * UI 先保留用户输入供查阅；若业务队列拒收，再另显示拒收原因，不伪称已执行。
     */
    private void submit() {
        String prompt = state.editor.submit();
        if (prompt.isBlank()) {
            state.hint = runtime.promoteUserBuffer() ? "待发送内容已升级为插入式" : "没有可插入的待发送内容"; return;
        }
        if (prompt.equals("/theme") || prompt.equals("/help")) { open(prompt); return; }
        state.add("You", "User", "Request", prompt); state.hint = "";
        // future 在业务线程结束，回调仍只投递显示事件，不能在该线程直接改 TuiState。
        runtime.submitUser(prompt, approvals).whenComplete((answer, failure) -> {
            if (failure instanceof java.util.concurrent.RejectedExecutionException)
                events.accept(new UiEvent.Message("System", "Rejected", failure.getMessage(), true));
        });
    }
    /** 前端命令只切换输入区域，不把 /help 或 /theme 发给模型，也不清掉原草稿。 */
    private void open(String command) {
        if (command.equals("/theme")) {
            originalTheme = state.theme; state.selectorKind = "Theme"; state.selector = new SelectList(List.of("dark", "light"));
            if (state.theme.equals("light")) state.selector.move(1);
        } else {
            state.selectorKind = "Help";
            state.selector = new SelectList(List.of("Enter 提交 · Alt+Enter 换行 · 粘贴后仍需 Enter", "忙时空 Enter：插入待发送缓冲",
                    "Ctrl+T 显示思考 · Ctrl+O 展开工具 · 左键切换单卡", "Ctrl+C 清草稿，500ms 内再按退出 · 空编辑器 Ctrl+D 退出",
                    "Esc 关闭选择界面，不取消 Agent · q/exit 是普通提示词", "窗口／主题变化可能清空 scrollback 并重绘", "/theme"));
        }
    }
    /**
     * 选择期间，文字只用于筛选，不进入原草稿。主题上下移动只作预览，Esc 恢复原主题；
     * 只有 Enter 确认才排队保存。保存回调发事件回到界面线程，不跨线程改组件。
     */
    private void selector(Input input) {
        switch (input.key()) {
            case "ESC" -> { if (state.selectorKind.equals("Theme")) state.theme = originalTheme; state.selector = null; }
            case "UP", "BACKTAB" -> { state.selector.move(-1); previewTheme(); }
            case "DOWN", "TAB" -> { state.selector.move(1); previewTheme(); }
            case "TEXT" -> state.selector.filter(state.selector.filter() + input.text());
            case "BACKSPACE" -> { String filter = state.selector.filter(); if (!filter.isEmpty()) state.selector.filter(filter.substring(0, filter.offsetByCodePoints(filter.length(), -1))); }
            case "ENTER" -> {
                String choice = state.selector.selected(); if (choice == null) return;
                String kind = state.selectorKind; state.selector = null;
                if (kind.equals("Theme")) {
                    state.theme = choice;
                    preferences.saveLater(choice, failed -> {
                        if (failed == null) events.accept(new UiEvent.Message("System", "Theme", "主题已保存：" + choice, false));
                        else events.accept(new UiEvent.Message("System", "Warning", "本次主题已生效，但保存失败：" + failed.getMessage(), true));
                    });
                } else if (choice.startsWith("/")) { if (kind.equals("Commands")) state.editor.submit(); open(choice); }
            }
            default -> { }
        }
    }
    private void previewTheme() { if (state.selectorKind.equals("Theme") && state.selector.selected() != null) state.theme = state.selector.selected(); }
    /** 审批独占输入焦点，按钮选择与草稿分开；排队中的另一份申请不能继承选择。 */
    private void approval(Input input) {
        // 新队首必须先真正显示，再允许选择。仅重置 choice 不足以防住上一份申请的连按。
        if (!renderer.hasAction("approve:" + state.approvals.getFirst().id())) return;
        switch (input.key()) {
            case "LEFT" -> state.approvalChoice = 0;
            case "RIGHT" -> state.approvalChoice = 1;
            case "TAB", "BACKTAB" -> state.approvalChoice = state.approvalChoice < 0 ? 0 : 1 - state.approvalChoice;
            case "ENTER" -> { if (state.approvalChoice >= 0) answer(state.approvals.getFirst().id(), state.approvalChoice == 0); }
            case "CLICK" -> click(renderer.hit(input.x(), input.y()));
            default -> { /* 无默认选择，普通文字、粘贴、空 Enter 和 Esc 都不能批准或拒绝。 */ }
        }
    }
    /** 点击动作来自最后显示帧；不是模型正文提供的命令，仍需核对当前审批编号。 */
    private void click(String action) {
        if (action == null) return;
        if (action.startsWith("approve:")) answer(action.substring(8), true);
        else if (action.startsWith("deny:")) answer(action.substring(5), false);
        else if (action.startsWith("entry:") && state.approvals.isEmpty()) state.entries.stream()
                .filter(e -> e.id.equals(action.substring(6))).findFirst().ifPresent(e -> { e.expanded = !e.expanded; e.version++; });
    }
    private void answer(String id, boolean allowed) {
        if (state.approvals.isEmpty() || !state.approvals.getFirst().id().equals(id)) return;
        approvals.answer(id, allowed); removeApproval(id);
    }
    private void removeApproval(String id) {
        boolean first = !state.approvals.isEmpty() && state.approvals.getFirst().id().equals(id);
        state.approvals.removeIf(request -> request.id().equals(id)); if (first) state.approvalChoice = -1;
    }
    private void close() { state.closing = true; approvals.close(); shutdown.run(); }
}
