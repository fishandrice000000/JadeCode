package com.jadecode.cli.tui;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.jadecode.cli.SessionHost;
import com.jadecode.cli.tui.approval.ApprovalViewState;
import com.jadecode.cli.tui.bridge.ApprovalBroker;
import com.jadecode.cli.tui.model.TranscriptEntry;
import com.jadecode.cli.tui.view.ChatViewport;
import com.jadecode.cli.tui.view.HotkeysView;
import com.jadecode.cli.tui.view.McpStatusView;
import com.jadecode.cli.tui.view.SessionInfoView;
import com.jadecode.safety.PermissionProfile;
import com.jadecode.safety.approval.ApprovalAnswer;
import com.jadecode.tui.components.Editor;
import com.jadecode.tui.components.SelectList;
import com.jadecode.tui.core.ActionToken;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.PresentedFrame;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.input.KeyChord;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.LinkTarget;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.ThemeManager;
import com.jadecode.tui.viewport.SelectionModel;

/**
 * 语义输入与 UI 事件分派器：审批 > 菜单/选择器 > 编辑器/视口；调用真实 Host 操作。
 *
 * <p>本类不手写编辑器键表（使用 KeyBindings），不直接写终端（renderer 负责），不实现
 * 组件算法。用户意图通过既有 SessionHost/ApprovalBroker 进入业务；所有批准需当前队首、
 * revision、成功 PresentedFrame 与输入水位同时成立。未知 slash 返回本地英文错误并保留
 * 草稿，不发给模型；不提供 Agent 取消/树/模型热切换等无后端动作。</p>
 */
public final class TuiController {
    /** 终端生命周期/剪贴板/外部编辑器端口；由 Application 实现，测试可注入空实现。 */
    public interface TerminalActions {
        default void suspend() { }
        default void openExternalEditor(String draft, Consumer<String> success, Consumer<String> failure) { }
        default void pasteClipboard(Editor editor) { }
        default void copy(String text, boolean explicit) { }
        default void setMode(String mode) { }
        default void openLink(String uri) { }
    }

    private final TuiState state;
    private final SessionHost host;
    private final ApprovalBroker approvals;
    private final ChatViewport chat;
    private final Editor editor;
    private final TuiPreferences preferences;
    private final KeyBindings keys;
    private final CommandCatalog catalog;
    private final SettingsMenu settings;
    private final ThemeManager themes;
    private final Consumer<UiEvent> events;
    private final Runnable shutdown;
    private final TerminalActions terminal;
    private final SessionInfoView sessionInfo = new SessionInfoView();
    private final HotkeysView hotkeys = new HotkeysView();
    private final McpStatusView mcpView = new McpStatusView();

    private long ctrlC;
    private String originalTheme;
    private boolean savePermissionDefault;
    private java.util.concurrent.CompletableFuture<Void> localWrites =
            java.util.concurrent.CompletableFuture.completedFuture(null);
    private final java.util.Map<Long, List<UiEvent>> awaiting = new java.util.LinkedHashMap<>();
    private int viewportTop, viewportHeight, dockTop, columns, rows;
    private boolean fullscreen;
    /** 全屏屏幕级选区：聊天和编辑器文本都能拖选/复制，与 Pi 一致。 */
    private final SelectionModel screenSelection = new SelectionModel();
    private boolean selectionDragged;
    private boolean selectionPressActive;
    private long lastClickNanos;
    private int clickCount;
    private boolean searchOpen;
    private boolean welcomeExpanded;
    /** 最近成功呈现的原始帧，仅用于把点击坐标映射到同一布局的动作矩形。 */
    private Frame lastFrame;

    public TuiController(TuiState state, SessionHost host, ApprovalBroker approvals, ChatViewport chat,
            Editor editor, TuiPreferences preferences, KeyBindings keys, CommandCatalog catalog,
            SettingsMenu settings, ThemeManager themes, Consumer<UiEvent> events, Runnable shutdown,
            TerminalActions terminal) {
        this.state = state; this.host = host; this.approvals = approvals; this.chat = chat;
        this.editor = editor; this.preferences = preferences; this.keys = keys; this.catalog = catalog;
        this.settings = settings; this.themes = themes; this.events = events; this.shutdown = shutdown;
        this.terminal = terminal == null ? new TerminalActions() { } : terminal;
        state.editor = editor;
    }

    /** 每帧布局后由 Application 调用，供鼠标点击/滚轮坐标换算。 */
    public void layout(int columns, int rows, int viewportTop, int viewportHeight, int dockTop, boolean fullscreen) {
        this.columns = columns; this.rows = rows; this.viewportTop = viewportTop;
        this.viewportHeight = viewportHeight; this.dockTop = dockTop; this.fullscreen = fullscreen;
    }

    /** @return 当前是否有屏幕级选区。 */
    public boolean selectionActive() { return fullscreen && screenSelection.active(); }

    /** @return 当前屏幕级选区纯文字；无选区/无帧为空。 */
    public String selectedText() {
        if (!selectionActive() || lastFrame == null) return "";
        return screenSelection.text(lastFrame.lines());
    }

    /**
     * 把屏幕级选区高亮合并进帧行；只有全屏模式有效，regular 交给终端原生选择。
     * 文本、命中与选区使用同一帧坐标，resize 后由 Application 失效重建。
     */
    public List<StyledLine> applySelection(List<StyledLine> lines) {
        if (!selectionActive()) return lines;
        var result = new java.util.ArrayList<StyledLine>(lines.size());
        for (int row = 0; row < lines.size(); row++) {
            StyledLine line = lines.get(row);
            var range = screenSelection.rangeFor(row, line.text());
            if (range.empty()) { result.add(line); continue; }
            var spans = new java.util.ArrayList<Span>();
            int column = 0;
            for (Span span : line.spans()) {
                for (String cluster : com.jadecode.tui.text.DisplayWidth.clusters(span.text())) {
                    int cells = com.jadecode.tui.text.DisplayWidth.clusterWidth(cluster);
                    boolean selected = column >= range.fromColumn() && column < range.toColumn();
                    spans.add(new Span(cluster, selected
                            ? span.style().withBg(themes.preview().color("selectedBg")) : span.style(), span.link()));
                    column += cells;
                }
            }
            result.add(new StyledLine(spans, line.annotation()));
        }
        return result;
    }

    /** @return 搜索 Overlay 是否打开。 */
    public boolean searchOpen() { return searchOpen; }

    /** @return 欢迎资源区是否由 Ctrl+O 展开。 */
    public boolean welcomeExpanded() { return welcomeExpanded; }

    /** Application 成功呈现后调用，建立动作证据并让审批水位对齐。 */
    public void presented(PresentedFrame frame, Frame rawFrame) {
        state.presented = frame;
        lastFrame = rawFrame;
        if (state.approval.active()) state.approval.presented(frame, state.terminalEpoch, state.inputSequence);
    }

    /** 模式切换/resize 立即失效旧动作。 */
    public void invalidateActions() { state.presented = null; lastFrame = null; state.approval.invalidate(); }

    /** 应用事件；返回 false 表示清理完成，可以结束 UI 循环。 */
    public boolean accept(UiEvent uiEvent) {
        switch (uiEvent) {
            case UiEvent.Scoped scoped -> {
                if (scoped.generation() == state.generation) return accept(scoped.event());
                if (scoped.generation() > state.generation)
                    awaiting.computeIfAbsent(scoped.generation(), ignored -> new java.util.ArrayList<>()).add(scoped.event());
                // 旧代次迟到事件直接丢弃，不能把旧回复/工具卡接到新会话。
            }
            case UiEvent.SessionOpened opened -> {
                var session = opened.opened();
                state.installGeneration(session.generation());
                state.sessionId = session.restored().metadata().id();
                HistoryViewMapper.restore(state, session.restored());
                editor.setGeneration(state.generation);
                for (UiEvent initial : awaiting.getOrDefault(state.generation, List.of())) accept(initial);
                awaiting.clear();
                refreshSession();
                state.hint = "Enter submit · / for commands · Esc closes local UI";
                host.start(state.generation);
            }
            case UiEvent.SessionList list -> {
                state.loadingResume = false; state.selectorKind = "Resume";
                state.sessionPicker = new SessionPicker(list.sessions());
                state.selector = state.sessionPicker.choices();
                state.hint = list.sessions().isEmpty() ? UiMessages.NO_SESSIONS : "Select a session · Enter to resume";
            }
            case UiEvent.ResumeFailed failed -> {
                state.loadingResume = false; awaiting.clear();
                state.hint = failed.message();
                state.transcript.addNotice("System", "Resume", failed.message(), true);
            }
            case UiEvent.Input input -> {
                state.inputSequence = Math.max(state.inputSequence, input.input().sequence());
                state.terminalEpoch = input.input().terminalEpoch();
                input(input.input());
            }
            case UiEvent.Execution execution -> state.execution(execution.notice());
            case UiEvent.StreamStarted started -> state.transcript.requestStarted(started.request(), started.actor());
            case UiEvent.Delta delta -> state.delta(delta);
            case UiEvent.ToolPreviewStarted preview -> state.transcript.previewStarted(preview.request(), preview.actor(),
                    preview.blockIndex(), preview.callId(), preview.name(), preview.initialInput(), preview.identity());
            case UiEvent.ToolPreviewDelta preview -> state.transcript.previewDelta(preview.request(), preview.blockIndex(),
                    preview.fragment());
            case UiEvent.ToolPreviewClosed preview -> state.transcript.previewClosed(preview.request(), preview.blockIndex());
            case UiEvent.StreamEnd end -> state.end(end);
            case UiEvent.Message message -> state.message(message);
            case UiEvent.ApprovalRequested request -> {
                if (state.closing) approvals.answer(request.id(), ApprovalAnswer.DENY);
                else {
                    // 审批优先于搜索/菜单：关闭局部搜索，避免按键继续被 Overlay 消费。
                    if (searchOpen) { search.close(); searchOpen = false; }
                    if (state.approvals.isEmpty()) {
                        var snapshot = new UiEvent.ApprovalRequested(request.id(), request.actor(), request.tool(),
                                request.arguments(), request.reason(), request.suggestion(), request.revision());
                        state.approvals.addLast(snapshot);
                        state.approval.show(snapshot, state.inputSequence, state.terminalEpoch);
                    } else state.approvals.addLast(request);
                }
            }
            case UiEvent.ApprovalRemoved removed -> removeApproval(removed.id());
            case UiEvent.Branch branch -> state.branch = branch.branch();
            case UiEvent.ClipboardText text -> {
                if (text.text().isEmpty()) return true;
                // 用户显式 Ctrl+V 是插入，不是替换整份草稿；保留光标/撤销边界。
                editor.insert(text.text(), true);
            }
            case UiEvent.ExternalEditorResult text -> editor.replaceText(text.text());
            case UiEvent.Repaint ignored -> { /* Application 已消费并请求重绘。 */ }
            case UiEvent.Flash flash -> state.flash(flash.text(), flash.durationMillis(), flash.error());
            case UiEvent.TerminalFailure failure -> { state.hint = failure.message(); close(); }
            case UiEvent.Stopped stopped -> { return false; }
        }
        return true;
    }

    /** 从当前会话读取已发布统计/权限；不读盘、不等模型，也不访问可变 history。 */
    public boolean refreshSession() {
        boolean changed = false;
        var permission = host.permissionSnapshot();
        if (permission != null) {
            String profile = permission.profile().value();
            changed = !profile.equals(state.permissionProfile); state.permissionProfile = profile;
        }
        var next = host.display();
        if (next != null && (!next.id().equals(state.sessionId) || !next.equals(state.session))) {
            state.session = next; state.model = next.model(); state.modelInfo = next.metadata().describe();
            changed = true;
        }
        var servers = host.mcpServers();
        if (!servers.equals(state.mcpServers)) { state.mcpServers = servers; changed = true; }
        return changed;
    }

    /** 处理一个已解码输入。先全局退出/暂退，再按审批/菜单/编辑器/视口优先级路由。 */
    private void input(InputEvent event) {
        if (state.closing) return;
        switch (event.kind()) {
            case RESIZE -> { invalidateActions(); editor.setGeneration(state.generation); return; }
            case EOF -> { close(); return; }
            case FAILURE -> { state.transcript.addNotice("System", "Terminal", event.text(), true); close(); return; }
            case REPLY -> { return; }
            case MOUSE -> { mouse(event); return; }
            default -> { }
        }
        if (event.kind() != InputEvent.Kind.KEY && event.kind() != InputEvent.Kind.TEXT
                && event.kind() != InputEvent.Kind.PASTE) return;
        if (event.actionableKey()) {
            KeyChord key = event.key();
            // 选择器的 Ctrl+C 是取消选择，不是全局清草稿；与 KeyBindings 的 select.cancel 对齐。
            if (state.selector != null && key.key().equals("c") && key.has(KeyChord.Modifier.CTRL)) {
                cancelSelector(); return;
            }
            // 全局会话动作：关闭/退出/暂退/剪贴板/展开。审批期间也不允许 Ctrl+C 清掉参数草稿。
            if (matches("app.exit", event) && state.approvals.isEmpty() && state.selector == null && editor.text().isEmpty()) { close(); return; }
            if (key.key().equals("c") && key.has(KeyChord.Modifier.CTRL)) { ctrlC(event); return; }
            if (matches("app.suspend", event)) { terminal.suspend(); return; }
            if (matches("app.editor.external", event)) {
                terminal.openExternalEditor(editor.expandedText(), value -> {
                    events.accept(new UiEvent.Message("System", "Editor", "External draft applied", false));
                    // 结果必须回到 UI 线程；Application 在事件循环中调用这里。
                    pendingExternalEditor = value;
                }, message -> events.accept(new UiEvent.Message("System", "Editor", message, true)));
                return;
            }
            if (matches("app.clipboard.pasteImage", event)) { terminal.pasteClipboard(editor); return; }
            if (matches("app.message.copy", event)) { copyLastReply(); return; }
            if (matches("app.tools.expand", event) && state.approvals.isEmpty()) {
                state.toolsExpanded = !state.toolsExpanded;
                state.transcript.setToolsExpanded(state.toolsExpanded);
                welcomeExpanded = !welcomeExpanded;
                return;
            }
            if (matches("app.thinking.toggle", event) && state.approvals.isEmpty()) {
                state.thinkingVisible = !state.thinkingVisible; return;
            }
            if (matches("app.thinking.cycle", event) && state.approvals.isEmpty()) { cycleThinking(); return; }
            if (matches("app.session.new", event) && state.approvals.isEmpty()) { resume(null); return; }
            if (matches("app.session.resume", event) && state.approvals.isEmpty()) { openSessionList(); return; }
            if (matches("app.permission.menu", event) && state.approvals.isEmpty()) {
                if (host.hasCurrent()) {
                    state.selectorKind = "Permission";
                    state.selector = PermissionMenu.choices(host.current().permissionState().snapshot().profile());
                } else state.hint = "Open a session first";
                return;
            }
            if (matches("tui.altScreen.halfPageUp", event)) { chat.scrollLines(-Math.max(1, viewportHeight / 2)); return; }
            if (matches("tui.altScreen.halfPageDown", event)) { chat.scrollLines(Math.max(1, viewportHeight / 2)); return; }
            if (matches("tui.altScreen.lineUp", event)) { chat.scrollLines(-1); return; }
            if (matches("tui.altScreen.lineDown", event)) { chat.scrollLines(1); return; }
            if (key.key().equals("f") && key.has(KeyChord.Modifier.CTRL) && state.approvals.isEmpty() && state.selector == null) {
                openSearch(); return;
            }
        }
        // 搜索 Overlay 优先于编辑器；它消费文字/Esc/Enter。
        if (searchOpen) {
            if (search.handle(event)) updateSearchOpen();
            return;
        }
        if (!state.approvals.isEmpty()) { approvalInput(event); return; }
        if (state.selector != null) { selectorInput(event); return; }
        // 全屏 PageUp/PageDown 在编辑器为空时滚动转录；有草稿时仍由编辑器翻页。
        if (event.actionableKey() && (event.key().key().equals("pageup") || event.key().key().equals("pagedown"))
                && editor.text().isEmpty()) {
            chat.page(event.key().key().equals("pageup") ? -1 : 1); return;
        }
        if (event.actionableKey() && (matches("tui.altScreen.top", event) || matches("tui.altScreen.bottom", event))) {
            if (matches("tui.altScreen.top", event)) chat.top(); else chat.bottom();
            return;
        }
        if (editor.handle(event)) return;
        // 未消费的 Esc 是本地关闭提示，不是 Agent 取消。
        if (event.actionableKey() && event.key().key().equals("escape")) {
            state.hint = UiMessages.NO_AGENT_CANCEL;
        }
    }

    private String pendingExternalEditor;
    private final com.jadecode.tui.overlay.SearchOverlay search = new com.jadecode.tui.overlay.SearchOverlay();

    /** 由 Application 在每个事件循环调用；外部编辑器结果只在 UI 线程采用。 */
    public void applyPendingExternalEditor() {
        if (pendingExternalEditor == null) return;
        editor.replaceText(pendingExternalEditor);
        pendingExternalEditor = null;
    }

    /** @return 搜索 Overlay，Application 用于合成。 */
    public com.jadecode.tui.overlay.SearchOverlay searchOverlay() { return search; }

    private void openSearch() {
        search.open(chat); searchOpen = true;
        state.hint = "Search · Enter next · Shift+Enter previous · Esc close";
    }

    private void updateSearchOpen() { searchOpen = search.visible(); if (!searchOpen) state.hint = ""; }

    private void ctrlC(InputEvent event) {
        long now = System.nanoTime();
        if (ctrlC != 0 && now - ctrlC < 500_000_000L) { close(); return; }
        ctrlC = now;
        if (state.approvals.isEmpty() && state.selector == null) editor.clear();
        state.hint = "Press Ctrl+C again within 500ms to exit; Esc does not cancel the agent";
    }

    /**
     * 全屏鼠标：滚轮滚动；审批按钮只在显式按下且证据齐全时答复；
     * 其余按下/拖动/释放走屏幕级选区，单击合成编辑器光标/链接动作。
     * regular 不进入这里，保留终端原生选择。
     */
    private void mouse(InputEvent event) {
        if (!fullscreen) return;
        InputEvent.Mouse mouse = event.mouse();
        int row = mouse.y(), column = mouse.x();
        if (mouse.wheel()) {
            chat.wheel(mouse.wheelDirection(), mouse.alt() ? 3 : 1);
            return;
        }
        PresentedFrame frame = state.presented;
        if (frame == null || lastFrame == null) return;
        ActionToken hit = lastFrame.actionAt(column, row);
        // 审批优先：按下按钮必须满足显式命中/水位/已呈现；拖动与释放绝不答复。
        if (state.approval.active()) {
            if (mouse.action() == InputEvent.Mouse.Action.PRESS && hit != null && hit.isApproval()
                    && state.approval.canAnswer(event.sequence(), state.terminalEpoch, frame, hit.kind())) {
                approvals.answer(state.approval.request().id(),
                        hit.kind() == ActionToken.Kind.DENY ? ApprovalAnswer.DENY
                                : hit.kind() == ActionToken.Kind.ALLOW_SIMILAR ? ApprovalAnswer.ALLOW_SIMILAR
                                : ApprovalAnswer.ALLOW_ONCE);
                removeApproval(state.approval.request().id());
            }
            return;
        }
        switch (mouse.action()) {
            case PRESS -> {
                long now = System.nanoTime();
                clickCount = now - lastClickNanos < 400_000_000L ? Math.min(3, clickCount + 1) : 1;
                lastClickNanos = now;
                selectionDragged = false;
                selectionPressActive = true;
                screenSelection.start(new SelectionModel.Position(Math.max(0, row), Math.max(0, column)), clickCount);
            }
            case DRAG -> {
                if (!selectionPressActive) return;
                screenSelection.drag(new SelectionModel.Position(Math.max(0, row), Math.max(0, column)));
                selectionDragged = true;
                autoScrollSelection(row);
            }
            case RELEASE -> {
                // 释放必须属于一次真实按下；重复释放或没有按下的释放不得复制/点击。
                if (!selectionPressActive) return;
                selectionPressActive = false;
                boolean dragged = selectionDragged;
                selectionDragged = false;
                if (!dragged) {
                    LinkTarget link = linkAt(row, column);
                    if (link != null) { screenSelection.clear(); terminalsOpen(link); return; }
                    if (editorClick(row, column)) { screenSelection.clear(); return; }
                    screenSelection.clear();
                    return;
                }
                screenSelection.finish();
                // 一次拖选只复制一次；选区保留，重复释放不再触发。
                String text = screenSelection.text(lastFrame.lines());
                if (!text.isBlank() && chat.copyOnSelect()) terminal.copy(text, false);
            }
            default -> { }
        }
    }

    /** 拖出视口边缘时自动滚动聊天；焦点夹回可见边缘，避免坐标越界。 */
    private void autoScrollSelection(int row) {
        if (row < viewportTop) {
            chat.scrollLines(-2);
            screenSelection.drag(new SelectionModel.Position(viewportTop, 0));
        } else if (row >= viewportTop + viewportHeight) {
            chat.scrollLines(2);
            screenSelection.drag(new SelectionModel.Position(Math.max(viewportTop, viewportTop + viewportHeight - 1),
                    lastFrame.lines().isEmpty() ? 0
                            : Math.max(0, lastFrame.lines().get(Math.min(lastFrame.lines().size() - 1,
                            viewportTop + viewportHeight - 1)).width() - 1)));
        }
    }

    /** @return 帧内该位置是否带 OSC8 链接。 */
    private LinkTarget linkAt(int row, int column) {
        if (lastFrame == null || row < 0 || row >= lastFrame.lines().size()) return null;
        int used = 0;
        for (Span span : lastFrame.lines().get(row).spans()) {
            int width = com.jadecode.tui.text.DisplayWidth.width(span.text());
            if (column >= used && column < used + width) return span.link();
            used += width;
        }
        return null;
    }

    /** 单击编辑器：候选区选中对应项，内容区把光标放到对应字素；边框不处理。 */
    private boolean editorClick(int row, int column) {
        if (state.selector != null || state.editor == null) return false;
        int editorRow = row - dockTop;
        int candidateRow = editorRow - state.editor.completionStartRow();
        if (candidateRow >= 0 && candidateRow < state.editor.completionRows())
            return state.editor.clickCompletion(candidateRow);
        int contentRow = editorRow - 1;
        if (contentRow < 0 || contentRow >= state.editor.visibleRows()) return false;
        state.editor.caretForClick(contentRow, column, columns);
        return true;
    }

    private void terminalsOpen(LinkTarget link) {
        // 打开链接是外部副作用；只接受用户明确点击，并交给 Application 的受控端口。
        terminal.openLink(link.uri());
    }

    private void cycleThinking() {
        if (state.session == null || state.session.thinkingChoices().size() < 2) {
            state.hint = UiMessages.NO_THINKING; return;
        }
        var choices = state.session.thinkingChoices();
        int index = choices.indexOf(state.session.thinking());
        var next = choices.get((index + 1) % choices.size());
        saveLocal("Thinking", id -> host.thinking(id, next), "Thinking level: " + next.value());
    }

    private void copyLastReply() {
        String text = state.transcript.lastReplyText();
        if (text.isBlank()) { state.hint = UiMessages.COPY_EMPTY; return; }
        terminal.copy(text, true);
    }

    /** Ctrl+X/菜单复制：屏幕级选区优先，否则聊天选区，最后最近助手回复。 */
    public void copySelectionOrLastReply() {
        String text = selectionActive() ? selectedText()
                : chat != null && chat.selection().active() ? chat.selection().text(chat.document())
                : state.transcript.lastReplyText();
        if (text.isBlank()) { state.hint = UiMessages.COPY_EMPTY; return; }
        terminal.copy(text, true);
    }

    /** 审批输入：阅读键不答复；左右/Tab 改选择；Enter 需证据与显式选择。 */
    private void approvalInput(InputEvent event) {
        if (event.kind() != InputEvent.Kind.KEY || !event.actionableKey()) return;
        KeyChord key = event.key();
        var approval = state.approval;
        switch (key.key()) {
            case "up" -> { approval.scroll(-1); return; }
            case "down" -> { approval.scroll(1); return; }
            case "pageup" -> { approval.scroll(-Math.max(1, approval.pageSize())); return; }
            case "pagedown" -> { approval.scroll(Math.max(1, approval.pageSize())); return; }
            case "home" -> { approval.scroll(-approval.totalLines()); return; }
            case "end" -> { approval.scroll(approval.totalLines()); return; }
            default -> { }
        }
        if (key.key().equals("left") || key.key().equals("right") || key.key().equals("tab")) {
            boolean backward = key.key().equals("left") || key.has(KeyChord.Modifier.SHIFT);
            approval.move(backward ? -1 : 1, !approval.request().suggestion().isBlank());
            return;
        }
        if (key.key().equals("enter")) {
            var answer = approval.selectedAnswer();
            if (answer == null) return;
            ActionToken.Kind kind = answer == ApprovalAnswer.DENY ? ActionToken.Kind.DENY
                    : answer == ApprovalAnswer.ALLOW_SIMILAR ? ActionToken.Kind.ALLOW_SIMILAR : ActionToken.Kind.APPROVE;
            if (!approval.canAnswer(event.sequence(), state.terminalEpoch, state.presented, kind)) return;
            approvals.answer(approval.request().id(), answer);
            removeApproval(approval.request().id());
            return;
        }
        if (key.key().equals("escape")) { state.hint = "Esc does not answer an approval; use an explicit option"; }
        // 普通文字/粘贴/空 Enter 不批准也不拒绝。
    }

    private void removeApproval(String id) {
        boolean first = !state.approvals.isEmpty() && state.approvals.getFirst().id().equals(id);
        state.approvals.removeIf(request -> request.id().equals(id));
        if (first) {
            state.approval.clear();
            if (!state.approvals.isEmpty()) {
                var next = state.approvals.getFirst();
                state.approval.show(next, state.inputSequence, state.terminalEpoch);
            } else state.hint = "";
        }
    }

    /** 选择器输入：Esc 回滚；上下移动；输入文字过滤；Enter 分派。 */
    private void selectorInput(InputEvent event) {
        if (event.kind() == InputEvent.Kind.TEXT) {
            state.selector.filter(state.selector.filter() + event.text()); return;
        }
        if (event.kind() == InputEvent.Kind.PASTE) return;
        if (event.kind() != InputEvent.Kind.KEY || !event.actionableKey()) return;
        KeyChord key = event.key();
        switch (key.key()) {
            case "escape" -> { cancelSelector(); return; }
            case "up" -> { state.selector.move(-1); previewSelector(); return; }
            case "down" -> { state.selector.move(1); previewSelector(); return; }
            case "pageup" -> { state.selector.page(-1, 5); return; }
            case "pagedown" -> { state.selector.page(1, 5); return; }
            case "backspace" -> {
                String filter = state.selector.filter();
                if (!filter.isEmpty()) state.selector.filter(DisplayWidth.take(filter, DisplayWidth.width(filter) - 1));
                return;
            }
            case "enter" -> { chooseSelector(); return; }
            default -> { }
        }
        if (key.key().equals("n") && key.has(KeyChord.Modifier.CTRL) && state.selectorKind.equals("Resume")) {
            state.sessionPicker.toggleNamedOnly(); state.selector = state.sessionPicker.choices(); return;
        }
        if (key.key().equals("p") && key.has(KeyChord.Modifier.CTRL) && state.selectorKind.equals("Resume")) {
            state.sessionPicker.togglePath(); state.selector = state.sessionPicker.choices(); return;
        }
        if (key.key().equals("s") && key.has(KeyChord.Modifier.CTRL) && state.selectorKind.equals("Resume")) {
            state.sessionPicker.toggleSort(); state.selector = state.sessionPicker.choices(); return;
        }
    }

    private void previewSelector() {
        if (!state.selectorKind.equals("Theme")) return;
        String selected = state.selector.selected();
        if (selected == null) return;
        String error = themes.preview(selected);
        if (!error.isEmpty()) state.hint = error;
    }

    private void cancelSelector() {
        if (state.selectorKind.equals("Theme")) {
            themes.cancelPreview();
            if (originalTheme != null) themes.reload(originalTheme);
        }
        String kind = state.selectorKind;
        state.selector = null; state.sessionPicker = null; state.selectorKind = ""; state.hint = "";
        if (kind.equals("Resume") && !host.hasCurrent()) close();
    }

    private void chooseSelector() {
        String selectedId = state.selector.selected();
        if (selectedId == null) return;
        String kind = state.selectorKind;
        switch (kind) {
            case "Resume" -> {
                String id = state.sessionPicker.id(selectedId);
                state.selector = null; state.sessionPicker = null; state.selectorKind = "";
                if (id != null) resume(id);
            }
            case "Theme" -> {
                state.selector = null; state.selectorKind = "";
                themes.applyPreview();
                preferences.saveLater(Map.of("theme", selectedId), failed -> {
                    if (failed == null) events.accept(new UiEvent.Message("System", "Theme", "Theme saved", false));
                    else events.accept(new UiEvent.Message("System", "Warning", UiMessages.NOT_SAVED + failed.getMessage(), true));
                });
            }
            case "Thinking" -> {
                state.selector = null; state.selectorKind = "";
                try { changeThinking(com.jadecode.llm.ThinkingSettings.parse(selectedId)); }
                catch (IllegalArgumentException invalid) { state.hint = invalid.getMessage(); }
            }
            case "Permission" -> {
                state.selector = null; state.selectorKind = "";
                choosePermission(PermissionProfile.parse(selectedId));
            }
            case "PermissionConfirm" -> {
                state.selector = null; state.selectorKind = "";
                if (selectedId.equals("confirm")) applyPermission(PermissionProfile.FULL_ACCESS);
                else state.hint = "Permission unchanged";
            }
            case "Settings" -> {
                if (selectedId.equals("theme")) {
                    originalTheme = themes.preview().name();
                    state.selectorKind = "Theme";
                    var items = new java.util.ArrayList<SelectList.Item>();
                    for (String choice : settings.themeChoices())
                        items.add(new SelectList.Item(choice, choice, ""));
                    state.selector = new SelectList(items, true);
                    return;
                }
                if (selectedId.equals("mode")) {
                    String mode = settings.current().mode().equals("fullscreen") ? "regular" : "fullscreen";
                    settings.reload(SettingsMenu.apply(settings.current(), Map.of("mode", mode)));
                    state.selector = settings.list();
                    state.selector.selectId("mode");
                    terminal.setMode(mode);
                    preferences.saveLater(Map.of("mode", mode), failed -> {
                        if (failed == null) events.accept(new UiEvent.Message("System", "Settings", "TUI mode: " + mode, false));
                        else events.accept(new UiEvent.Message("System", "Warning", UiMessages.NOT_SAVED + failed.getMessage(), true));
                    });
                    return;
                }
                Map<String, Object> patch = settings.cycle(selectedId);
                if (patch.isEmpty()) return;
                applySettingsPatch(patch);
                state.selector = settings.list();
                state.selector.selectId(selectedId);
            }
            default -> { }
        }
    }

    private void applySettingsPatch(Map<String, Object> patch) {
        var updated = settings.current();
        if (patch.containsKey("thinkingVisible")) state.thinkingVisible = updated.thinkingVisible();
        if (patch.containsKey("toolsExpanded")) { state.toolsExpanded = updated.toolsExpanded();
            state.transcript.setToolsExpanded(state.toolsExpanded); }
        if (patch.containsKey("fullscreenScrollbar")) {
            try { chat.setScrollbar(ChatViewport.Scrollbar.valueOf(updated.fullscreenScrollbar().toUpperCase(java.util.Locale.ROOT))); }
            catch (IllegalArgumentException ignored) { }
        }
        if (patch.containsKey("fullscreenCopyOnSelect")) chat.setCopyOnSelect(updated.fullscreenCopyOnSelect());
        if (patch.containsKey("fullscreenWheelScrollLines")) chat.wheelLines(
                updated.fullscreenWheelScrollLines() == 0 ? 3 : updated.fullscreenWheelScrollLines());
        if (patch.containsKey("autocompleteMaxVisible")) editor.setCompletionMaxVisible(updated.autocompleteMaxVisible());
        preferences.saveLater(patch, failed -> {
            if (failed == null) events.accept(new UiEvent.Message("System", "Settings", "Setting saved", false));
            else events.accept(new UiEvent.Message("System", "Warning", UiMessages.NOT_SAVED + failed.getMessage(), true));
        });
    }

    private void changeThinking(com.jadecode.llm.ThinkingSettings value) {
        saveLocal("Thinking", id -> host.thinking(id, value), "Thinking level: " + value.value());
    }

    /** 权限：Full Access 需显式确认；其余档位直接切换，真实 Gate 规则不变。 */
    private void choosePermission(PermissionProfile profile) {
        if (!host.hasCurrent()) { state.hint = "Open a session first"; return; }
        if (profile == PermissionProfile.FULL_ACCESS
                && host.current().permissionState().snapshot().profile() != profile) {
            state.selectorKind = "PermissionConfirm"; state.selector = PermissionMenu.confirm();
            state.hint = "Full Access skips routine prompts; explicit denies remain in force";
        } else applyPermission(profile);
    }

    private void applyPermission(PermissionProfile profile) {
        var permissions = host.current().permissionState();
        boolean save = savePermissionDefault;
        savePermissionDefault = false;
        state.hint = "";
        localWrites = localWrites.handle((value, failure) -> null).thenRunAsync(() -> {
            permissions.select(profile);
            events.accept(new UiEvent.Message("System", "Permission", "Permission: " + profile.label(), false));
            if (save) {
                try {
                    preferences.savePermission(profile);
                    events.accept(new UiEvent.Message("System", "Permission", "Default permission saved: " + profile.label(), false));
                } catch (java.io.IOException failed) {
                    events.accept(new UiEvent.Message("System", "Warning",
                            "Active for this run, but saving the default failed: " + failed.getMessage(), true));
                }
            }
        }, command -> Thread.ofVirtual().name("jade-permission").start(command));
    }

    @FunctionalInterface private interface LocalAction { void run(String expectedId) throws Exception; }

    private void saveLocal(String label, LocalAction action, String success) {
        if (!host.hasCurrent() || state.loadingResume) { state.hint = "Open a session first"; return; }
        long generation = state.generation;
        String id = state.sessionId;
        localWrites = localWrites.handle((value, failure) -> null).thenRunAsync(() -> {
            try { action.run(id); events.accept(new UiEvent.Scoped(generation, new UiEvent.Message("System", label, success, false))); }
            catch (Exception failure) { events.accept(new UiEvent.Scoped(generation, new UiEvent.Message("System", "Error", failure.getMessage(), true))); }
        }, command -> Thread.ofVirtual().name("jade-session-setting").start(command));
    }

    /** @return 编辑器提交意图；返回 true 才清空草稿。 */
    public boolean submit(String expanded) {
        String text = expanded == null ? "" : expanded;
        String trimmed = text.strip();
        if (trimmed.isEmpty()) return false;
        if (trimmed.startsWith("/")) return command(trimmed);
        if (state.loadingResume) { state.hint = "Session is opening; draft kept"; return false; }
        if (!host.hasCurrent()) { state.hint = "Open or create a session first"; return false; }
        state.transcript.addUser(text);
        state.hint = "";
        long generation = state.generation;
        host.current().submitUser(text, approvals).whenComplete((answer, failure) -> {
            if (failure instanceof java.util.concurrent.RejectedExecutionException)
                events.accept(new UiEvent.Scoped(generation, new UiEvent.Message("System", "Rejected", failure.getMessage(), true)));
        });
        return true;
    }

    /** 本地命令；未知 slash 保留输入并返回 false。 */
    private boolean command(String text) {
        int space = text.indexOf(' ');
        String name = space < 0 ? text : text.substring(0, space);
        String arguments = space < 0 ? "" : text.substring(space + 1).strip();
        CommandCatalog.Command command = catalog.find(name);
        if (command == null) {
            state.hint = "Unknown command: " + name + " · /hotkeys";
            return false;
        }
        switch (name) {
            case "/quit" -> { close(); return true; }
            case "/new" -> { resume(null); return true; }
            case "/resume" -> {
                if (arguments.isEmpty()) openSessionList(); else resume(arguments); return true;
            }
            case "/name" -> {
                if (arguments.isEmpty()) { editor.replaceText("/name "); state.hint = "Type a title after /name"; return false; }
                saveLocal("Rename", id -> host.rename(id, arguments), "Session renamed: " + arguments); return true;
            }
            case "/settings" -> {
                state.selectorKind = "Settings"; state.selector = settings.list();
                state.hint = "Enter change · Esc return to draft"; return true;
            }
            case "/session" -> { addNotice("Session", "Session", sessionInfo.render(state, renderContext(), columns)); return true; }
            case "/hotkeys" -> { addNotice("Hotkeys", "Hotkeys", hotkeys.render(keys, catalog.helpLines(),
                    renderContext(), columns)); return true; }
            case "/copy" -> { copyLastReply(); return true; }
            case "/model" -> {
                refreshSession();
                String detail = state.modelInfo == null || state.modelInfo.isBlank() ? state.model : state.modelInfo;
                state.transcript.addNotice("System", "Model", (detail == null ? "" : detail)
                        + (detail == null || detail.isBlank() ? "" : "\n") + UiMessages.NO_MODEL_SWITCH, false);
                return true;
            }
            case "/thinking" -> {
                if (arguments.isEmpty()) {
                    if (state.session == null || state.session.thinkingChoices().size() < 2) {
                        state.hint = UiMessages.NO_THINKING; return true;
                    }
                    var items = new java.util.ArrayList<SelectList.Item>();
                    for (var thinking : state.session.thinkingChoices())
                        items.add(new SelectList.Item(thinking.value(), thinking.value(), ""));
                    state.selector = new SelectList(items, true); state.selectorKind = "Thinking";
                } else {
                    try { changeThinking(com.jadecode.llm.ThinkingSettings.parse(arguments)); }
                    catch (IllegalArgumentException invalid) { state.hint = invalid.getMessage(); }
                }
                return true;
            }
            case "/permission" -> {
                savePermissionDefault = arguments.startsWith("default ");
                String value = savePermissionDefault ? arguments.substring(8).strip() : arguments;
                if (value.isEmpty()) {
                    if (!host.hasCurrent()) { state.hint = "Open a session first"; return true; }
                    state.selectorKind = "Permission";
                    state.selector = PermissionMenu.choices(host.current().permissionState().snapshot().profile());
                } else {
                    try { choosePermission(PermissionProfile.parse(value)); }
                    catch (IllegalArgumentException invalid) { state.hint = invalid.getMessage(); }
                }
                return true;
            }
            case "/mcp" -> { addNotice("MCP", "MCP", mcpView.render(state.mcpServers, renderContext(), columns)); return true; }
            default -> { state.hint = "Unknown command: " + name; return false; }
        }
    }

    private com.jadecode.tui.core.RenderContext renderContext() {
        return new com.jadecode.tui.core.RenderContext(Math.max(20, columns), Math.max(10, rows),
                themes.preview(), com.jadecode.tui.terminal.TerminalCapabilities.detect(java.util.Map.of()),
                System.nanoTime(), state.transcript.revision());
    }

    private void addNotice(String actor, String title, List<StyledLine> lines) {
        String text = lines.stream().map(StyledLine::text).collect(java.util.stream.Collectors.joining("\n"));
        state.transcript.addNotice(actor, title, text, false);
    }

    /** 打开会话清单；只读清单，不解析每份日志。 */
    private void openSessionList() {
        if (state.loadingResume) { state.hint = "Session is switching"; return; }
        state.loadingResume = true; state.hint = UiMessages.SESSION_LIST;
        Thread.ofVirtual().name("jade-session-list").start(() -> {
            try { events.accept(new UiEvent.SessionList(host.list())); }
            catch (Exception failure) { events.accept(new UiEvent.ResumeFailed(failure.getMessage())); }
        });
    }

    /** 异步打开会话；加载/关闭不占 UI 线程，只有 SessionOpened 回来后才清理旧卡片。 */
    public void resume(String id) {
        if (id != null && id.isEmpty()) { openSessionList(); return; }
        if (state.loadingResume) { state.hint = "Session is switching"; return; }
        state.loadingResume = true; state.hint = id == null ? "Creating session…" : UiMessages.SESSION_OPENING;
        Thread.ofVirtual().name("jade-session-open").start(() -> {
            try {
                String target = id;
                if (target != null) {
                    boolean uuid;
                    try { com.jadecode.session.history.SessionMetadata.requireId(target); uuid = true; }
                    catch (IllegalArgumentException invalid) { uuid = false; }
                    if (!uuid) {
                        var matches = host.find(target);
                        if (matches.isEmpty()) throw new java.io.IOException("No session matches that name");
                        if (matches.size() > 1) { events.accept(new UiEvent.SessionList(matches)); return; }
                        target = matches.getFirst().id();
                    }
                }
                events.accept(new UiEvent.SessionOpened(host.open(target)));
            } catch (Exception failure) { events.accept(new UiEvent.ResumeFailed(failure.getMessage())); }
        });
    }

    /** 已提交本地写入先完成，再关闭业务；审批先拒绝。 */
    private void close() {
        if (state.closing) return;
        state.closing = true; approvals.close();
        localWrites.whenComplete((value, failure) -> shutdown.run());
    }

    private boolean matches(String action, InputEvent event) { return keys.matches(action, event); }
}
