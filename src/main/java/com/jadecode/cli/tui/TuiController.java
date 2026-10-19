package com.jadecode.cli.tui;

import java.util.List;
import java.util.function.Consumer;
import com.jadecode.cli.tui.bridge.ApprovalBroker;
import com.jadecode.cli.SessionHost;
import com.jadecode.tui.components.SelectList;
import com.jadecode.tui.core.InlineRenderer;
import com.jadecode.tui.terminal.InputDecoder.Input;

/**
 * 在界面线程处理输入与显示事件。只通过现有 submitUser／promoteUserBuffer 发出用户意图，
 * 不执行工具、不改模型历史。磁盘偏好保存和关闭业务资源都交给其它线程。
 */
public final class TuiController {
    private final TuiState state;
    private final SessionHost host;
    private final ApprovalBroker approvals;
    private final InlineRenderer renderer;
    private final TuiPreferences preferences;
    private final Consumer<UiEvent> events;
    private final Runnable shutdown;
    private long ctrlC;
    private String originalTheme;
    /** 新运行时装配通知可能先于 SessionOpened 到达，暂存而不是丢掉模型参数通知。 */
    private final java.util.Map<Long, List<UiEvent>> awaiting = new java.util.LinkedHashMap<>();
    public TuiController(TuiState state, SessionHost host, ApprovalBroker approvals,
            InlineRenderer renderer, TuiPreferences preferences, Consumer<UiEvent> events, Runnable shutdown) {
        this.state = state; this.host = host; this.approvals = approvals; this.renderer = renderer;
        this.preferences = preferences; this.events = events; this.shutdown = shutdown;
    }

    /** 应用事件；返回 false 表示清理完成，可以结束界面循环。 */
    public boolean accept(UiEvent event) {
        switch (event) {
            case UiEvent.Scoped scoped -> {
                if (scoped.generation() == state.generation) return accept(scoped.event());
                if (scoped.generation() > state.generation)
                    awaiting.computeIfAbsent(scoped.generation(), ignored -> new java.util.ArrayList<>()).add(scoped.event());
                // 旧代次事件直接丢弃，不能把旧回复、关闭通知或工具卡接到新会话。
            }
            case UiEvent.SessionOpened opened -> {
                var session = opened.opened();
                state.generation = session.generation(); state.loadingResume = false;
                state.selector = null; state.sessionPicker = null;
                state.sessionId = session.restored().metadata().id();
                HistoryViewMapper.restore(state, session.restored());
                for (UiEvent initial : awaiting.getOrDefault(state.generation, List.of())) accept(initial);
                awaiting.clear();
                state.add("System", "Notice", "Session", "会话 ID: " + state.sessionId + "\n恢复历史不自动重跑旧任务。Token 用量从本次打开开始统计。");
                if (!session.restored().metadata().model().equals(session.currentModel()))
                    state.add("System", "Warning", "Model", "原会话模型: " + session.restored().metadata().model()
                            + "；当前使用: " + session.currentModel());
                state.hint = "会话已打开，等待新输入 · /resume 切换 · /help";
                host.start(state.generation);
            }
            case UiEvent.SessionList list -> {
                state.loadingResume = false; state.selectorKind = "Resume";
                state.sessionPicker = new SessionPicker(list.sessions()); state.selector = state.sessionPicker.choices();
                state.hint = list.sessions().isEmpty() ? "当前工作区没有其它可恢复会话；Esc 返回" : "选择会话后恢复；不会重跑旧工具";
            }
            case UiEvent.ResumeFailed failed -> {
                state.loadingResume = false; awaiting.clear();
                state.hint = failed.message(); state.add("System", "Error", "Resume", failed.message());
            }
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
            if (state.approvals.isEmpty() && state.selector == null) {
                state.editor.clear(); state.commands = null; state.commandsDismissed = false;
            }
            state.hint = "再次 Ctrl+C（500ms 内）退出整个会话；Esc 不取消 Agent"; return;
        }
        // 审批盖住选择器，选择器盖住编辑器：被盖住的草稿仍保存，但不会收到这一按键。
        if (!state.approvals.isEmpty()) { approval(input); return; }
        if (state.selector != null) { selector(input); return; }
        // 候选出现时，文字仍输入原草稿；只有选择／补全按键被候选接收。
        if (state.commands != null && commandInput(input)) return;
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
                state.entries.stream().filter(e -> e.kind.equals("Tool"))
                        .forEach(e -> { e.expanded = state.toolsExpanded; e.version++; });
            }
            case "CTRL_T" -> { state.thinkingVisible = !state.thinkingVisible; state.entries.stream().filter(e -> e.kind.equals("Thinking")).forEach(e -> e.expanded = false); }
            case "CLICK" -> click(renderer.hit(input.x(), input.y()));
            case "TAB" -> { }
            case "ESC" -> state.hint = "Esc 仅关闭选择界面；本版不支持单轮取消";
            default -> { }
        }
        if (List.of("TEXT", "PASTE", "BACKSPACE", "DELETE", "CTRL_D", "CLEAR", "KILL_END", "DELETE_WORD", "UNDO").contains(key))
            state.commandsDismissed = false;
        refreshCommands();
    }

    /** 只对单行、无空格的斜杠命令提供候选；普通任务或多行粘贴不会误入命令选择。 */
    private void refreshCommands() {
        String draft = state.editor.text();
        if (!state.commandSuggestions || state.commandsDismissed || !draft.startsWith("/") || draft.codePoints().anyMatch(Character::isWhitespace)) {
            state.commands = null;
            return;
        }
        if (state.commands == null) state.commands = new SelectList(List.of("/help", "/theme", "/model", "/resume"));
        if (!state.commands.filter().equals(draft)) state.commands.filter(draft);
    }

    /**
     * 候选列表不保存另一份正文。Tab 只把选中命令补回编辑器；Enter 才清掉命令草稿并执行。
     * 返回 true 表示本按键已经处理，不再让编辑器浏览历史或把 `/` 发给模型。
     */
    private boolean commandInput(Input input) {
        switch (input.key()) {
            case "UP" -> state.commands.move(-1);
            case "DOWN" -> state.commands.move(1);
            case "ESC" -> { state.commands = null; state.commandsDismissed = true; }
            case "TAB", "ENTER" -> {
                String command = state.commands.selected();
                if (command == null) return false;
                if (input.key().equals("TAB")) {
                    state.editor.clear(); state.editor.insert(command, false); refreshCommands();
                } else {
                    state.editor.submit(); state.commands = null; open(command);
                }
            }
            default -> { return false; }
        }
        return true;
    }

    /**
     * 将编辑器原文交给会话，并立即返回。忙时合并、排队和插入升级由 Session 决定。
     * 空提交只升级同一业务缓冲；前端命令不发给模型；q／exit 则是普通提示词。
     * UI 先保留用户输入供查阅；若业务队列拒收，再另显示拒收原因，不伪称已执行。
     */
    private void submit() {
        if (state.loadingResume) { state.hint = "正在打开会话，草稿已保留，请稍后提交"; return; }
        String prompt = state.editor.submit();
        if (prompt.isBlank()) {
            state.hint = host.hasCurrent() && host.current().promoteUserBuffer() ? "待发送内容已升级为插入式" : "没有可插入的待发送内容"; return;
        }
        if (prompt.equals("/resume") || prompt.startsWith("/resume ") || prompt.startsWith("/resume\n") || prompt.startsWith("/resume\t")) {
            String id = prompt.substring(7).strip();
            if (id.isEmpty()) open("/resume"); else resume(id);
            return;
        }
        if (prompt.equals("/theme") || prompt.equals("/help") || prompt.equals("/model")) { open(prompt); return; }
        if (!host.hasCurrent()) { state.editor.insert(prompt, false); state.hint = "请先 /resume 选择会话，或重启 jade repl 新建会话"; return; }
        state.add("You", "User", "Request", prompt); state.hint = "";
        // future 在业务线程结束，回调仍只投递显示事件，不能在该线程直接改 TuiState。
        long generation = state.generation;
        host.current().submitUser(prompt, approvals).whenComplete((answer, failure) -> {
            if (failure instanceof java.util.concurrent.RejectedExecutionException)
                events.accept(new UiEvent.Scoped(generation, new UiEvent.Message("System", "Rejected", failure.getMessage(), true)));
        });
    }
    /** 前端命令不交给模型：帮助/主题/参数在本地处理，resume 则让 Host 异步装配目标运行时。 */
    private void open(String command) {
        if (state.loadingResume) { state.hint = "正在打开会话，请稍后"; return; }
        if (command.equals("/resume")) {
            state.loadingResume = true; state.hint = "正在读取当前工作区的会话列表…";
            Thread.ofVirtual().name("jade-session-list").start(() -> {
                try { events.accept(new UiEvent.SessionList(host.list())); }
                catch (Exception failure) { events.accept(new UiEvent.ResumeFailed(failure.getMessage())); }
            });
        } else if (command.equals("/theme")) {
            originalTheme = state.theme; state.selectorKind = "Theme"; state.selector = new SelectList(List.of("dark", "light"));
            if (state.theme.equals("light")) state.selector.move(1);
        } else if (command.equals("/model")) {
            // 只展示启动时已取得的快照，不把命令发给模型，也不在 UI 线程重新请求网络。
            state.add("System", "Model", "Model · " + state.model, state.modelInfo);
        } else {
            state.selectorKind = "Help";
            state.selector = new SelectList(List.of("Enter 提交 · Alt+Enter 换行 · 粘贴后仍需 Enter", "忙时空 Enter：插入待发送缓冲",
                    "Ctrl+T 显示思考 · Ctrl+O 展开工具 · 鼠标滚动／复制由终端处理", "Ctrl+C 清草稿，500ms 内再按退出 · 空编辑器 Ctrl+D 退出",
                    "Esc 关闭选择界面，不取消 Agent · q/exit 是普通提示词", "窗口／主题变化可能清空 scrollback 并重绘", "/model", "/theme", "/resume"));
        }
    }
    /**
     * 选择期间，文字只用于筛选，不进入原草稿。主题上下移动只作预览，Esc 恢复原主题；
     * 只有 Enter 确认才排队保存。保存回调发事件回到界面线程，不跨线程改组件。
     */
    private void selector(Input input) {
        switch (input.key()) {
            case "ESC" -> {
                if (state.selectorKind.equals("Theme")) state.theme = originalTheme;
                boolean startupPicker = state.selectorKind.equals("Resume") && !host.hasCurrent();
                state.selector = null; state.sessionPicker = null;
                if (startupPicker) close();
            }
            case "UP", "BACKTAB" -> { state.selector.move(-1); previewTheme(); }
            case "DOWN", "TAB" -> { state.selector.move(1); previewTheme(); }
            case "TEXT" -> state.selector.filter(state.selector.filter() + input.text());
            case "BACKSPACE" -> { String filter = state.selector.filter(); if (!filter.isEmpty()) state.selector.filter(filter.substring(0, filter.offsetByCodePoints(filter.length(), -1))); }
            case "ENTER" -> {
                String choice = state.selector.selected(); if (choice == null) return;
                String kind = state.selectorKind; state.selector = null;
                if (kind.equals("Resume")) {
                    String id = state.sessionPicker.id(choice); state.sessionPicker = null;
                    resume(id);
                } else if (kind.equals("Theme")) {
                    state.theme = choice;
                    preferences.saveLater(choice, failed -> {
                        if (failed == null) events.accept(new UiEvent.Message("System", "Theme", "主题已保存：" + choice, false));
                        else events.accept(new UiEvent.Message("System", "Warning", "本次主题已生效，但保存失败：" + failed.getMessage(), true));
                    });
                } else if (choice.startsWith("/")) open(choice);
            }
            default -> { }
        }
    }
    private void previewTheme() { if (state.selectorKind.equals("Theme") && state.selector.selected() != null) state.theme = state.selector.selected(); }

    /**
     * 异步打开会话。加载与关闭不占 UI 线程，只有 SessionOpened 回来后才清理旧卡片并启动新会话。
     * id 为 null 新建，空串打开启动选择器，其他字符串必须通过存储层的 UUID 校验。
     */
    public void resume(String id) {
        if (id != null && id.isEmpty()) { open("/resume"); return; }
        if (state.loadingResume) { state.hint = "会话正在切换"; return; }
        state.loadingResume = true; state.hint = id == null ? "正在创建会话…" : "正在恢复会话…";
        Thread.ofVirtual().name("jade-session-open").start(() -> {
            try { events.accept(new UiEvent.SessionOpened(host.open(id))); }
            catch (Exception failure) { events.accept(new UiEvent.ResumeFailed(failure.getMessage())); }
        });
    }
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
